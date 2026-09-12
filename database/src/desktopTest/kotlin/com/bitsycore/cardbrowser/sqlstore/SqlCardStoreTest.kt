package com.bitsycore.cardbrowser.sqlstore

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

		val vPinned = mStore.pinnedSets().single()
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
		assertEquals(0..4, vFacets.costRange, "the costs that are actually present")
	}

	@Test
	fun `a game with nothing stored offers no filters rather than empty ones`() {
		val vFacets = mStore.facetsForGame("a-game-with-no-cards")

		assertTrue(vFacets.cardTypes.isEmpty())
		assertTrue(vFacets.rarities.isEmpty())
		assertNull(vFacets.costRange, "no cost range is not a range of zero")
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
			text = LocalizedText(language, "Card $number"),
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
