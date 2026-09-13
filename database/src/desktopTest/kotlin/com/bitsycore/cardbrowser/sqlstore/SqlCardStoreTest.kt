package com.bitsycore.cardbrowser.sqlstore

import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.filter.CardFilterEngine
import com.bitsycore.cardbrowser.core.model.Artwork
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardAttributes
import com.bitsycore.cardbrowser.core.model.CardClassification
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.FinishCoverage
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.model.LanguageCoverage
import com.bitsycore.cardbrowser.core.model.LocalizedText
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store's behaviour, and specifically the three things the spike deliberately did not model.
 *
 * Eviction, the pin budget, and identity. The first two are what the file cache spent three
 * separate bug-fixes learning, and a database that reimplements them differently would relearn
 * them the same way -- so each rule that was a bug there is a test here.
 */
class SqlCardStoreTest {

	private val mDriver = DesktopDriverFactory().create(null)

	private val mStore = SqlCardStore(mDriver)

	@AfterTest
	fun closeDriver() = mDriver.close()

	// ==================
	// MARK: Identity
	// ==================

	@Test
	fun `several values on one axis are an OR`() {
		// The card grid's chips have always been an OR; the store's filter took one value per axis
		// and the search screen was stuck with it. Two rarities must bring back both, and the
		// third must stay out -- "any of these" is a different query from "all of these" and from
		// "the last one you tapped".
		write("s", null, "Set", false, 1L, listOf(
			card(1, rarity = "Common"),
			card(2, rarity = "Rare"),
			card(3, rarity = "Epic"),
		))

		val vHits = mStore.search(game = "test", rarities = setOf("Common", "Epic"))

		assertEquals(
			listOf("Common", "Epic"),
			vHits.mapNotNull { it.classification.rarity }.sorted(),
		)
	}

	@Test
	fun `several domains are an OR across a card's own list`() {
		// Domains are stored as one delimited string per card, so this is the axis SQL cannot do in
		// a single predicate -- the store runs a pass per chosen domain and merges. What matters is
		// that a card with *either* comes back exactly once.
		write("s", null, "Set", false, 1L, listOf(
			card(1, domains = listOf("fury")),
			card(2, domains = listOf("calm", "fury")),
			card(3, domains = listOf("order")),
		))

		val vHits = mStore.search(game = "test", domains = setOf("fury", "calm"))

		assertEquals(2, vHits.size, "the card with both domains must not be returned twice")
		assertTrue(vHits.none { it.classification.domains == listOf("order") })
	}

	@Test
	fun `language is part of a set's identity -- not a column to collapse`() {
		// `(provider, set, language)` is the key everywhere in this app: cache, downloads, pins,
		// image records. A schema that treated language as an attribute of one cached set would
		// make an English import overwrite a French one.
		write("s", CardLanguage.ENGLISH, "Set", false, 1L, listOf(card(1, CardLanguage.ENGLISH)))
		write("s", CardLanguage.FRENCH, "Set", false, 1L, listOf(card(2, CardLanguage.FRENCH)))

		assertEquals(2, mStore.storageSnapshot().sets)
		assertEquals(1, mStore.readSet("p", "s", CardLanguage.ENGLISH).size)
		assertEquals(1, mStore.readSet("p", "s", CardLanguage.FRENCH).size)
		assertEquals(setOf("en", "fr"), mStore.languagesHeld("p", "s").toSet())
	}

	@Test
	fun `rewriting a set replaces it rather than merging into it`() {
		// A refetch that returns fewer cards must not leave the dropped ones behind. That is how
		// a set ends up holding printings the source no longer serves, with nothing on screen to
		// say where they came from.
		write("s", null, "Set", false, 1L, (1..5).map { card(it) })
		write("s", null, "Set", false, 2L, (1..2).map { card(it) })

		assertEquals(2, mStore.readSet("p", "s", null).size)
		assertEquals(2, mStore.cardCount("p", "s", null))
	}

	@Test
	fun `a card count is known without reading the cards`() {
		write("s", null, "Set", false, 1L, (1..7).map { card(it) })

		assertEquals(7, mStore.cardCount("p", "s", null))
		assertNull(mStore.cardCount("p", "absent", null), "unknown is not zero")
	}

	// ==================
	// MARK: Eviction and the budget
	// ==================

	@Test
	fun `eviction takes the least recently used -- not the least recently written`() {
		// The property the file cache could not keep: it held access times in memory, so after a
		// relaunch every record sorted equally old and the order collapsed to whatever the
		// filesystem listed. A column survives the process.
		write("old", null, "Old", false, 1L, (1..20).map { card(it) })
		write("new", null, "New", false, 2L, (1..20).map { card(it) })
		// "old" is written first but used last, so "new" is the one that should go.
		mStore.touch("p", "old", null, at = 99L)

		mStore.trim(ceilingBytes = mStore.unpinnedBytes() / 2)

		assertTrue(mStore.hasSet("p", "old", null), "the recently used set must survive")
		assertFalse(mStore.hasSet("p", "new", null))
	}

	@Test
	fun `a pinned set is outside the budget, not merely skipped`() {
		// The distinction that mattered: counting pinned bytes made one import exceed any sane
		// ceiling, after which every write evicted browsing records that together came nowhere
		// near it. The cache thrashed and re-fetched sets it had just cached.
		write("downloaded", null, "Downloaded", true, 1L, (1..50).map { card(it) })

		assertEquals(0L, mStore.unpinnedBytes(), "a pinned set contributes nothing to the budget")
		assertTrue(mStore.pinnedBytes() > 0L, "and is reported separately")
		assertEquals(0, mStore.trim(ceilingBytes = 1L), "so a tiny ceiling evicts nothing")
		assertTrue(mStore.hasSet("p", "downloaded", null))
	}

	@Test
	fun `the ceiling gives way rather than the pinned data`() {
		write("kept", null, "Kept", true, 1L, (1..50).map { card(it) })
		write("browsed", null, "Browsed", false, 2L, (1..50).map { card(it) })

		mStore.trim(ceilingBytes = 1L)

		assertTrue(mStore.hasSet("p", "kept", null), "a download is never evicted for a limit")
		assertFalse(mStore.hasSet("p", "browsed", null))
	}

	@Test
	fun `unpinning returns a set to ordinary eviction`() {
		write("s", null, "Set", true, 1L, (1..50).map { card(it) })
		assertEquals(0L, mStore.unpinnedBytes())

		mStore.setPinned("p", "s", null, "test", "Set", isPinned = false)

		assertTrue(mStore.unpinnedBytes() > 0L, "the bytes rejoin the budget")
		mStore.trim(ceilingBytes = 1L)
		assertFalse(mStore.hasSet("p", "s", null))
	}

	@Test
	fun `evicting a set takes its cards with it`() {
		// Otherwise the rows outlive the set that owned them and every cross-set search answers
		// from cards the app would say it does not have.
		write("s", null, "Set", false, 1L, (1..30).map { card(it) })

		mStore.trim(ceilingBytes = 0L)

		assertEquals(0, mStore.storageSnapshot().printings, "orphaned rows would be invisible")
	}

	@Test
	fun `a pinned set can still be named after everything else is gone`() {
		// The storage screen has to list what a download left. The file cache learned this the
		// hard way: its pin markers were zero bytes, so clearing the cache deleted the only index
		// from a hashed filename back to a set, and downloaded sets vanished from the screen
		// while the download dialog went on correctly reporting them as held.
		write("s", CardLanguage.JAPANESE, "Base Set", true, 1L, (1..3).map { card(it) })
		mStore.trim(ceilingBytes = 0L)

		val vPinned = mStore.storedSets().single()
		assertEquals("Base Set", vPinned.label)
		assertEquals(3, vPinned.cardCount)
		assertEquals("ja", vPinned.language)
	}

	@Test
	fun `a pin lands before the set does and survives the write`() {
		// The order a download uses, and it is deliberate: a set large enough to breach the ceiling
		// would otherwise be a candidate for the eviction its own write triggers. `setPinned` was an
		// UPDATE, so pinning a set that was not yet cached did nothing at all -- every download came
		// out unpinned and evictable, and the flag only ever appeared to work because the tests
		// wrote the set first.
		mStore.setPinned("p", "s", CardLanguage.ENGLISH, "test", "Base Set", isPinned = true)
		assertTrue(mStore.isPinned("p", "s", CardLanguage.ENGLISH), "the pin must exist on its own")

		write("s", CardLanguage.ENGLISH, "Base Set", false, 1L, (1..40).map { card(it) })

		assertTrue(mStore.isPinned("p", "s", CardLanguage.ENGLISH), "and the write must not clear it")
		assertEquals(0L, mStore.unpinnedBytes(), "so the download is outside the budget")
	}

	// ==================
	// MARK: Search
	// ==================

	@Test
	fun `search narrows on every axis and excludes as well as includes`() {
		write("s", null, "Set", false, 1L, (1..40).map { card(it) })

		// The capability the file cache does not have at all: "does not contain" has no
		// equivalent in a name match over whatever happens to be loaded.
		val vAll = mStore.search(game = "test", text = "card")
		val vExcluded = mStore.search(game = "test", text = "card", excludeText = "card 1")
		assertTrue(vExcluded.size < vAll.size)
		assertTrue(vExcluded.none { it.displayName.contains("Card 1") })

		val vCheap = mStore.search(game = "test", maxCost = 2)
		assertTrue(vCheap.isNotEmpty())
		assertTrue(vCheap.all { (it.attributes.cost ?: 99) <= 2 })
	}

	@Test
	fun `the advanced search narrows on every axis at once`() {
		// The query the whole feature rests on. Each predicate is NULL-guarded so one statement
		// serves any combination of chips, and the combination is what is worth asserting: a
		// filter that works alone and not with its neighbours is the usual way this breaks.
		write("s", null, "Set", false, 1L, (1..40).map { card(it) })

		val vHits = mStore.search(
			game = "test",
			text = "card",
			excludeText = "card 3",
			cardTypes = setOf("Unit"),
			rarities = setOf("Common"),
			maxCost = 3,
		)

		assertTrue(vHits.isNotEmpty(), "every axis at once matched nothing at all")
		assertTrue(vHits.all { it.classification.type == "Unit" })
		assertTrue(vHits.all { it.classification.rarity == "Common" })
		assertTrue(vHits.all { (it.attributes.cost ?: 99) <= 3 })
		assertTrue(vHits.none { it.displayName.contains("Card 3") }, "the exclusion was ignored")
	}

	@Test
	fun `the filter lists offer only what the game actually has`() {
		// Read from the rows rather than from a game profile, so the search cannot offer a rarity
		// that would match nothing on this device.
		write("s", null, "Set", false, 1L, (1..10).map { card(it) })

		val vFacets = mStore.facetsForGame("test")

		assertEquals(listOf("Unit"), vFacets.cardTypes)
		assertEquals(listOf("Common"), vFacets.rarities)
		assertEquals(listOf("fury"), vFacets.domains, "domains are stored joined and split back")
		assertEquals(0..4, vFacets.costRange, "the lowest and the highest present")
		assertEquals(listOf(0, 1, 2, 3, 4), vFacets.costs)
	}

	@Test
	fun `the filter values are remembered and re-read when the rows change`() {
		// Computing them is five DISTINCT scans plus an unindexable LIKE per treatment. For Magic
		// that is several passes over 110,000 payloads, and it used to run every time the search
		// screen opened.
		write("s", null, "Set", false, 1L, (1..10).map { card(it) })
		val vFirst = mStore.facetsForGame("test")

		// A second read must not recompute. Proven by writing straight into the cache row: a
		// recompute would overwrite this and the assertion would see the real value back.
		mStore.rememberFacetsForTest("test", rarities = listOf("Sentinel"))
		assertEquals(listOf("Sentinel"), mStore.facetsForGame("test").rarities)

		// Downloading another set changes the row count, which is the signal to recompute.
		write("s2", null, "Set 2", false, 1L, listOf(card(11, rarity = "Epic")))
		assertEquals(listOf("Common", "Epic"), mStore.facetsForGame("test").rarities)
		assertEquals(vFirst.cardTypes, mStore.facetsForGame("test").cardTypes)
	}

	@Test
	fun `one absurd cost does not become a million filter values`() {
		// Magic's Gleemax has a mana value of 1,000,000. The filter sheet used to build its cost
		// chips by expanding MIN..MAX, so one such card turned a sixteen-value axis into a million
		// and the sheet ran out of memory opening. The distinct values are the answer to the same
		// question and there are three of them here.
		write("s", null, "Set", false, 1L, listOf(card(1, cost = 0), card(2, cost = 3), card(3, cost = 1_000_000)))

		val vFacets = mStore.facetsForGame("test")

		assertEquals(listOf(0, 3, 1_000_000), vFacets.costs)
		// The range is still the range: it is a different fact and something may still want it.
		assertEquals(0..1_000_000, vFacets.costRange)
	}

	@Test
	fun `a game with nothing stored offers no filters rather than empty ones`() {
		val vFacets = mStore.facetsForGame("a-game-with-no-cards")

		assertTrue(vFacets.cardTypes.isEmpty())
		assertTrue(vFacets.rarities.isEmpty())
		assertNull(vFacets.costRange, "no cost range is not a range of zero")
		assertTrue(vFacets.costs.isEmpty())
	}

	@Test
	fun `a cost filter does not sweep up cards whose cost is unknown`() {
		// `Availability`'s third state, in schema form. A source that publishes no cost is not a
		// source publishing zero, and "cost under 4" must not quietly include everything it could
		// not measure.
		write("s", null, "Set", false, 1L, listOf(card(1, cost = null), card(2, cost = 1)))

		val vHits = mStore.search(game = "test", maxCost = 4)

		assertEquals(1, vHits.size, "the unknown-cost card must not be counted as cheap")
		assertEquals(1, vHits.single().attributes.cost)
	}

	/**
	 * A set written for one game, since every test here is about one.
	 *
	 * Positional on purpose: the point of each test is the argument it varies, and named arguments
	 * for the five it does not would bury that.
	 */
	@Test
	fun `chosen costs are matched exactly, not as the span between them`() {
		// The reported-shaped bug: the filter sheet offers values, and the search collapsed them to
		// `min..max`. Choosing 1 and 5 then returned 2, 3 and 4 as well -- while the same chips
		// inside a set matched exactly, so one control meant two things.
		write("s", null, "Set", true, 1L, (1..6).map { card(it, cost = it) })

		val vHits = mStore.search(game = "test", costs = setOf(1, 5))

		assertEquals(listOf(1, 5), vHits.mapNotNull { it.attributes.cost }.sorted())
	}

	@Test
	fun `one chosen cost is an equality`() {
		write("s", null, "Set", true, 1L, (1..4).map { card(it, cost = it) })

		assertEquals(listOf(3), mStore.search(game = "test", costs = setOf(3)).mapNotNull { it.attributes.cost })
	}

	@Test
	fun `a card with no cost matches nothing once a cost is chosen`() {
		// `cost IS NOT NULL` in the statement. A Leader with no cost is not "cost 0".
		write("s", null, "Set", true, 1L, listOf(card(1, cost = 2), card(2, cost = null)))

		assertEquals(1, mStore.search(game = "test", costs = setOf(2)).size)
		assertEquals(2, mStore.search(game = "test").size, "and is still there when no cost is asked for")
	}

	@Test
	fun `the store and the filter engine answer the same chips the same way`() {
		// The guard that actually matters, and the one that was missing.
		//
		// The same cost chips are applied by two different things: `CardFilterEngine` inside a set,
		// this statement across a game. They disagreed -- the engine matched exact values, the
		// search matched the span between them -- and nothing compared them, so the screen said two
		// things depending on which path you arrived by. Both are real here: real SQLite, and the
		// real engine over the same cards.
		val vCards = (1..6).map { card(it, cost = it) }
		write("s", null, "Set", true, 1L, vCards)

		for (vChosen in listOf(setOf(1, 5), setOf(3), setOf(2, 3, 4), setOf(6), emptySet())) {
			val vFromStore = mStore.search(game = "test", costs = vChosen)
				.mapNotNull { it.attributes.cost }
				.sorted()
			val vFromEngine = CardFilterEngine
				.apply(vCards, CardQuery(costs = vChosen), listOf("Common"))
				.mapNotNull { it.attributes.cost }
				.sorted()

			assertEquals(vFromEngine, vFromStore, "the two disagree on $vChosen")
		}
	}

	@Test
	fun `a collector number finds its card across a game`() {
		// It found one inside a set and nothing across a game, because the engine matched the name
		// or the number and this statement matched only the name. An empty result is a claim that
		// there are none, so the two had to be made to agree.
		write("s", null, "Set", true, 1L, (1..3).map { card(it) })

		assertEquals(1, mStore.search(game = "test", text = "2").size)
	}

	@Test
	fun `a collector number matches from the start -- not anywhere`() {
		// Typing 1 should offer 1, 10 and 100 -- not every card with a 1 somewhere in its number.
		//
		// Named without digits on purpose. "Card 21" contains a 1, so the *name* half matches it and
		// the prefix rule cannot be seen -- which is what the first version of this test tripped on.
		write("s", null, "Set", true, 1L, listOf(
			card(1, name = "Alpha"),
			card(10, name = "Beta"),
			card(100, name = "Gamma"),
			card(21, name = "Delta"),
		))

		val vHits = mStore.search(game = "test", text = "1")
			.map { it.collectorNumber }
			.sorted()

		assertEquals(listOf("1", "10", "100"), vHits, "21 contains a 1 but does not start with one")
	}

	@Test
	fun `the store and the engine answer the same text the same way`() {
		// The cost axis had exactly this bug and now has exactly this test. Real SQLite, the real
		// engine, the same cards, the same needles.
		val vCards = listOf(card(1), card(10), card(100), card(21))
		write("s", null, "Set", true, 1L, vCards)

		for (vNeedle in listOf("1", "10", "2", "card", "card 1", "zzz", "")) {
			val vFromStore = mStore.search(game = "test", text = vNeedle.takeIf { it.isNotEmpty() })
				.map { it.id.local }
				.sorted()
			val vFromEngine = CardFilterEngine
				.apply(vCards, CardQuery(text = vNeedle.takeIf { it.isNotEmpty() }), listOf("Common"))
				.map { it.id.local }
				.sorted()

			assertEquals(vFromEngine, vFromStore, "the two disagree on \"$vNeedle\"")
		}
	}

	// ==================
	// MARK: Removing part of a game
	// ==================

	@Test
	fun `deleting one edition leaves the other languages alone`() {
		// The storage screen offers a set in one language. The neighbouring editions are separate
		// rows and must survive, and so must their cards -- a delete that took the printings of
		// every edition sharing a set id would empty a set the screen still lists.
		write("s1", CardLanguage.ENGLISH, "Set One", true, 1L, listOf(card(1), card(2)))
		write("s1", CardLanguage.FRENCH, "Set One", true, 1L, listOf(card(1), card(2)))
		write("s2", CardLanguage.ENGLISH, "Set Two", true, 1L, listOf(card(3)))

		assertTrue(mStore.deleteDownloadedSet("p", "s1", "fr"))

		assertEquals(
			listOf("s1/en", "s2/en"),
			mStore.storedSetsForGame("test").map { "${it.setId}/${it.language}" }.sorted(),
		)
		assertEquals(2, mStore.readSet("p", "s1", CardLanguage.ENGLISH).size)
		assertTrue(
			mStore.readSet("p", "s1", CardLanguage.FRENCH).isEmpty(),
			"the French printings went with the French record",
		)
	}

	@Test
	fun `a browsed set is listed beside a downloaded one and says which it is`() {
		// The storage screen shows both now: nothing evicts either, and both cost the same bytes.
		// `pinned` is carried so a row can say where a set came from, not so one can be hidden.
		write("s1", CardLanguage.ENGLISH, "Downloaded", true, 1L, listOf(card(1)))
		write("s2", CardLanguage.ENGLISH, "Browsed", false, 1L, listOf(card(2)))

		val vHeld = mStore.storedSetsForGame("test").associateBy { it.label }

		assertEquals(setOf("Downloaded", "Browsed"), vHeld.keys)
		assertTrue(vHeld.getValue("Downloaded").isPinned)
		assertFalse(vHeld.getValue("Browsed").isPinned)
	}

	@Test
	fun `deleting a game takes its browsed sets too`() {
		// Leaving them would have the game reappear on the storage screen at a fraction of its
		// size immediately after being deleted, which reads as a delete that did not work.
		write("s1", CardLanguage.ENGLISH, "Downloaded", true, 1L, listOf(card(1)))
		write("s2", CardLanguage.ENGLISH, "Browsed", false, 1L, listOf(card(2)))

		mStore.deleteDownloadedGame("test")

		assertTrue(mStore.storedSetsForGame("test").isEmpty())
		assertTrue(mStore.search(game = "test").isEmpty(), "and their cards went with them")
	}

	@Test
	fun `deleting an edition that is not there says so`() {
		write("s1", CardLanguage.ENGLISH, "Set One", true, 1L, listOf(card(1)))

		assertFalse(mStore.deleteDownloadedSet("p", "s1", "ja"))
		assertFalse(mStore.deleteDownloadedSet("p", "nope", "en"))
		assertEquals(1, mStore.storedSetsForGame("test").size)
	}

	@Test
	fun `a set stored under no language is listed and deletable`() {
		// OPTCG states no language at all, so its rows are written under "-". That is a real
		// edition the screen has to be able to name and remove, not a null to be skipped.
		write("s1", null, "Set One", true, 1L, listOf(card(1)))

		val vHeld = mStore.storedSetsForGame("test")
		assertEquals(listOf("-"), vHeld.map { it.language })

		assertTrue(mStore.deleteDownloadedSet("p", "s1", "-"))
		assertTrue(mStore.storedSetsForGame("test").isEmpty())
	}

	private fun write(
		setId: String,
		language: CardLanguage?,
		label: String,
		pinned: Boolean,
		at: Long,
		cards: List<CardPrinting>,
	) = mStore.writeSet(
		"p", setId, language, "test", label,
		// Read rather than passed, exactly as `SqlSetRecordStore.write` does: a write must not
		// clear a pin that arrived before it.
		pinned || mStore.isPinned("p", setId, language),
		at, cards, isComplete = true,
	)

	private fun card(
		number: Int,
		language: CardLanguage = CardLanguage.ENGLISH,
		cost: Int? = number % 5,
		rarity: String = "Common",
		domains: List<String> = listOf("Fury"),
		/** Overridable so a text test can use a name with no digits in it. */
		name: String = "Card $number",
	): CardPrinting {
		val vProvider = ProviderId("p")
		return CardPrinting(
			id = SourceId(vProvider, "c$number-${language.code}"),
			game = GameId("test"),
			setId = SourceId(vProvider, "s"),
			identity = null,
			setCode = "S",
			setName = "Set",
			collectorNumber = number.toString(),
			providerRawCollectorNumber = number.toString(),
			text = LocalizedText(language, name),
			artwork = Artwork(
				id = SourceId(vProvider, "a$number"),
				imageUrl = "https://example.test/$number.png",
				thumbnailUrl = null,
				artist = null,
				treatment = ArtworkTreatment.STANDARD,
				language = language,
			),
			attributes = CardAttributes(cost = cost),
			classification = CardClassification(type = "Unit", rarity = rarity, domains = domains),
			languages = LanguageCoverage(confirmed = setOf(language)),
			finishes = FinishCoverage(),
		)
	}
}
