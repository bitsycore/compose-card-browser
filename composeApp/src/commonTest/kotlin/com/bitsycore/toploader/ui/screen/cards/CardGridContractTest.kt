package com.bitsycore.toploader.ui.screen.cards

import com.bitsycore.toploader.core.model.CardLanguage
import com.bitsycore.toploader.core.provider.CardFilterField
import com.bitsycore.toploader.core.provider.CardQuery
import com.bitsycore.toploader.core.provider.CardSortField
import com.bitsycore.toploader.core.provider.ProviderError
import com.bitsycore.toploader.data.repository.DataOrigin
import com.bitsycore.toploader.games.magic.MagicGame
import com.bitsycore.toploader.games.pokemon.PokemonGame
import com.bitsycore.toploader.games.riftbound.RiftboundGame
import com.bitsycore.toploader.ui.screen.cards.CardGridContract
import com.bitsycore.toploader.ui.preview.PreviewData
import com.bitsycore.toploader.ui.screen.cards.CardGridContract.Intent
import com.bitsycore.toploader.ui.screen.cards.CardGridContract.UiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The card grid's reducer.
 *
 * A pure function, so the awkward cases -- a slow response landing after the user changed their
 * mind, a refresh failing over data already on screen -- are tested directly rather than by
 * orchestrating coroutines and hoping the race reproduces.
 */
class CardGridContractTest {

	@Test
	fun `opening a card closes the filter sheet`() {
		val vOpen = reduce(UiState(), Intent.FilterSheetToggled(true))
		assertTrue(vOpen.isFilterSheetOpen)

		// The grid is still tappable behind the sheet, so this is reachable -- and it leaves two
		// things on screen that both answer Back.
		val vAfter = reduce(vOpen, Intent.CardOpened(PreviewData.CARDS.first()))

		assertFalse(vAfter.isFilterSheetOpen)
	}

	@Test
	fun `a game-wide search offers the filters its source supports`() {
		// The whole global search used to come up with a sort order and nothing else: capabilities
		// were resolved from the provider a *set id* names, and this branch has no set. The sheet
		// draws only what its source supports, so an empty set of supported filters is an empty
		// sheet however many cards are on screen.
		val vState = reduce(
			UiState(),
			Intent.GameSelected(MagicGame),
			Intent.CapabilitiesResolved(
				supportedFilters = setOf(CardFilterField.RARITY, CardFilterField.DOMAIN),
				game = MagicGame,
				languages = setOf(CardLanguage.ENGLISH),
				language = CardLanguage.ENGLISH,
			),
		)

		assertEquals(setOf(CardFilterField.RARITY, CardFilterField.DOMAIN), vState.supportedFilters)
		assertEquals(MagicGame, vState.game)
	}

	@Test
	fun `a game-wide search says how much of the game it could see`() {
		// The app asks no source to search its own catalogue -- that path was implemented five
		// times and called from nowhere, and was deleted. So an empty result here means "not in
		// what you have downloaded" and never "no such card", and the screen has to say which.
		val vState = reduce(
			UiState(),
			Intent.GameSelected(MagicGame),
			Intent.SearchCoverage(searched = 12, known = 988, isTruncated = false),
		)

		assertEquals(
			"Searched the 12 sets you have downloaded, of 988.",
			vState.coverageNotice,
		)
	}

	@Test
	fun `a truncated search says it is showing the first of them`() {
		val vState = reduce(
			UiState(),
			Intent.GameSelected(MagicGame),
			loaded(1, List(200) { card("c$it") }),
			Intent.SearchCoverage(searched = 988, known = 988, isTruncated = true),
		)

		// 200 is the store's row limit. Printing it as a total would be the "227 of 358" mistake
		// again, in the other direction.
		assertEquals(
			"Searched the 988 sets you have downloaded, of 988. Showing the first 200.",
			vState.coverageNotice,
		)
	}

	@Test
	fun `a set browse says nothing about downloaded set counts`() {
		val vState = reduce(UiState(), Intent.SetSelected("scryfall:set:vow", "Crimson Vow", "VOW"))

		assertNull(vState.coverageNotice, "there is no catalogue question when one set is open")
	}

	// ==================
	// MARK: The browse key
	// ==================

	@Test
	fun `a set browse is keyed on the set`() {
		val vState = reduce(UiState(), Intent.SetSelected("scryfall:set:vow", "Crimson Vow", "VOW"))

		assertFalse(vState.isStoredBrowse, "opening a set reads that set, not the store")
		// The same string the detail screen falls back to, so opening a card from a set does not
		// have to be told anything: the key and the card's set are one value.
		assertEquals("scryfall:set:vow", vState.browseKey)
	}

	@Test
	fun `one set picked in the search's filter reads that set rather than the store`() {
		// The report: picking a single set in the global search's filter found nothing, while
		// opening the same set from the list worked. A store search answers with what is on disk,
		// and the set had not been downloaded -- so the filter was quietly a different question
		// from the tap. One set is one set, wherever it was named.
		val vSearch = reduce(UiState(), Intent.GameSelected(MagicGame))
		assertTrue(vSearch.isStoredBrowse, "no set named: the store is all there is")

		val vOneSet = reduce(vSearch, Intent.SetFilterChanged(setOf("scryfall:set:vow")))

		assertFalse(vOneSet.isStoredBrowse, "one set is a set browse, and reads its provider")
		assertEquals("scryfall:set:vow", vOneSet.browseKey)

		// Two is a search again: no source answers "every card in these two sets".
		assertTrue(
			reduce(vOneSet, Intent.SetFilterChanged(setOf("scryfall:set:vow", "scryfall:set:mid")))
				.isStoredBrowse,
		)
	}

	@Test
	fun `a game-wide search is keyed on the sets it searched`() {
		// What the set list's search button opens: this screen with no set ticked.
		val vState = reduce(UiState(), Intent.GameSelected(MagicGame))

		assertTrue(vState.isStoredBrowse, "with no set ticked there is no set to read")
		assertEquals("stored:", vState.browseKey)
	}

	@Test
	fun `widening a set browse changes the key -- and narrowing back restores it`() {
		val vOpened = reduce(UiState(), Intent.SetSelected("scryfall:set:vow", "Crimson Vow", "VOW"))

		// Two sets ticked is a search across both, and the key has to say which two -- a card
		// opened from it swipes those results, not one set's.
		val vWidened = reduce(
			vOpened,
			Intent.SetFilterChanged(setOf("scryfall:set:vow", "scryfall:set:mid")),
		)
		assertTrue(vWidened.isStoredBrowse)
		assertEquals("stored:scryfall:set:mid,scryfall:set:vow", vWidened.browseKey)

		// Sorted, so the same pair ticked in the other order is the same list and not a second one.
		val vOtherOrder = reduce(
			vOpened,
			Intent.SetFilterChanged(setOf("scryfall:set:mid", "scryfall:set:vow")),
		)
		assertEquals(vWidened.browseKey, vOtherOrder.browseKey)

		assertEquals(
			vOpened.browseKey,
			reduce(vWidened, Intent.SetFilterChanged(setOf("scryfall:set:vow"))).browseKey,
		)
	}

	private fun reduce(state: UiState, vararg intents: Intent): UiState =
		intents.fold(state) { vState, vIntent -> CardGridContract.reduce(vState, vIntent) }

	private fun loaded(
		generation: Int,
		cards: List<com.bitsycore.toploader.core.model.CardPrinting>,
		complete: Boolean = true,
		error: ProviderError? = null,
		origin: DataOrigin = DataOrigin.NETWORK,
		knownSetSize: Int? = null,
	) = Intent.Loaded(
		generation = generation,
		cards = cards,
		isCompleteSet = complete,
		cachedCardCount = cards.size,
		knownSetSize = knownSetSize,
		origin = origin,
		isStale = false,
		error = error,
		isFinal = true,
	)

	// ============
	//  When the source has no pictures

	@Test
	fun `a set the source has no pictures of says so once`() {
		// The reported confusion: TCGdex has no scans for the Japanese MEGA sets, so every tile
		// drew a broken-image mark and the screen said nothing. A reader cannot tell that from an
		// app that is failing to load, and assumed the second.
		val vState = UiState(
			cards = listOf(card("001"), card("002")).map { it.withoutArtwork() },
			isLoading = false,
		)

		assertEquals("This source has no card images for this set.", vState.artworkNotice)
	}

	@Test
	fun `one card without a picture is not worth a sentence`() {
		// Ordinary: TCGdex's own `ja/sv8` has 32 of 138 without art. The tile says "No image" and
		// that is the whole story -- a banner for it would cry wolf on most sets.
		val vState = UiState(
			cards = listOf(card("001").withoutArtwork(), card("002")),
			isLoading = false,
		)

		assertNull(vState.artworkNotice)
	}

	@Test
	fun `an empty grid says nothing about pictures`() {
		// Nothing loaded is not evidence about what the source holds.
		assertNull(UiState(cards = emptyList(), isLoading = false).artworkNotice)
	}

	@Test
	fun `the picture notice and the coverage notice are both said`() {
		// Two different facts, and a `when` chain would have picked one. A partly downloaded set
		// with no scans has to say both.
		val vState = UiState(
			cards = listOf(card("001")).map { it.withoutArtwork() },
			isLoading = false,
			isCompleteSet = false,
			cachedCardCount = 1,
			knownSetSize = 116,
		)

		assertNotNull(vState.artworkNotice)
		assertNotNull(vState.coverageNotice)
	}

	/** The same card with every rendition stripped, as a source with no scan of it returns. */
	private fun com.bitsycore.toploader.core.model.CardPrinting.withoutArtwork() =
		copy(artwork = artwork.copy(imageUrl = "", thumbnailUrl = null, displayUrl = null))

	private fun card(number: String, rarity: String = "Common") =
		com.bitsycore.toploader.core.model.CardPrinting(
			id = com.bitsycore.toploader.core.model.SourceId(
				com.bitsycore.toploader.core.model.ProviderId("riftcodex"),
				"card-$number",
			),
			game = RiftboundGame.id,
			setId = com.bitsycore.toploader.core.model.SourceId(
				com.bitsycore.toploader.core.model.ProviderId("riftcodex"),
				"OGN",
			),
			setCode = "OGN",
			setName = "Origins",
			collectorNumber = number,
			providerRawCollectorNumber = number,
			identity = null,
			text = com.bitsycore.toploader.core.model.LocalizedText(
				com.bitsycore.toploader.core.model.CardLanguage.ENGLISH,
				"Card $number",
			),
			artwork = com.bitsycore.toploader.core.model.Artwork(
				id = com.bitsycore.toploader.core.model.SourceId(
					com.bitsycore.toploader.core.model.ProviderId("riftcodex"),
					"card-$number",
				),
				imageUrl = "https://example.test/$number.png",
				thumbnailUrl = null,
				artist = null,
				treatment = com.bitsycore.toploader.core.model.ArtworkTreatment.STANDARD,
				language = com.bitsycore.toploader.core.model.CardLanguage.ENGLISH,
			),
			classification = com.bitsycore.toploader.core.model.CardClassification(rarity = rarity),
		)

	// ============
	//  Stale response suppression

	@Test
	fun `a response from a superseded generation is discarded`() {
		// The user asked for Fury, then changed to Order. The Fury response arrives late.
		var vState = reduce(
			UiState(),
			Intent.QueryChanged(CardQuery(domains = setOf("Fury"))),
		)
		val vFuryGeneration = vState.requestGeneration

		vState = reduce(vState, Intent.QueryChanged(CardQuery(domains = setOf("Order"))))

		// Late arrival, tagged with the older generation.
		val vAfter = reduce(vState, loaded(vFuryGeneration, listOf(card("1"), card("2"))))

		assertTrue(vAfter.cards.isEmpty(), "a superseded response must not populate the grid")
		assertEquals(setOf("Order"), vAfter.query.domains)
		assertTrue(vAfter.isLoading, "the current load is still outstanding")
	}

	@Test
	fun `the current generation's response is applied`() {
		var vState = reduce(UiState(), Intent.QueryChanged(CardQuery(domains = setOf("Order"))))

		vState = reduce(vState, loaded(vState.requestGeneration, listOf(card("1"))))

		assertEquals(1, vState.cards.size)
		assertFalse(vState.isLoading)
	}

	@Test
	fun `rapid filter changes leave only the last one applied`() {
		// Five changes in quick succession, then all five responses arrive out of order.
		var vState = UiState()
		val vGenerations = mutableListOf<Int>()
		listOf("Fury", "Order", "Chaos", "Calm", "Mind").forEach { vDomain ->
			vState = reduce(vState, Intent.QueryChanged(CardQuery(domains = setOf(vDomain))))
			vGenerations += vState.requestGeneration
		}

		// Deliberately shuffled, with the newest arriving in the middle.
		listOf(2, 0, 4, 1, 3).forEach { vIndex ->
			vState = reduce(
				vState,
				loaded(vGenerations[vIndex], listOf(card("${vIndex + 1}"))),
			)
		}

		// Only the response for the fifth query counts, whenever it happened to land.
		assertEquals(setOf("Mind"), vState.query.domains)
		assertEquals(listOf("5"), vState.cards.map { it.collectorNumber })
	}

	@Test
	fun `clearing filters supersedes anything in flight`() {
		var vState = reduce(UiState(), Intent.QueryChanged(CardQuery(rarities = setOf("Epic"))))
		val vFiltered = vState.requestGeneration

		vState = reduce(vState, Intent.ClearFilters)
		vState = reduce(vState, loaded(vFiltered, listOf(card("1"))))

		assertTrue(vState.cards.isEmpty(), "the cleared grid must not be repopulated by the old load")
		assertEquals(0, vState.activeFilterCount)
	}

	@Test
	fun `clearing filters keeps the chosen sort`() {
		var vState = reduce(
			UiState(),
			Intent.QueryChanged(
				CardQuery(
					rarities = setOf("Epic"),
					sortBy = com.bitsycore.toploader.core.provider.CardSortField.NAME,
					sortDirection = com.bitsycore.toploader.core.provider.SortDirection.DESCENDING,
				),
			),
		)

		vState = reduce(vState, Intent.ClearFilters)

		assertEquals(com.bitsycore.toploader.core.provider.CardSortField.NAME, vState.query.sortBy)
		assertEquals(
			com.bitsycore.toploader.core.provider.SortDirection.DESCENDING,
			vState.query.sortDirection,
		)
		assertTrue(vState.query.domains.isEmpty())
	}

	// ============
	//  Recoverable errors

	@Test
	fun `a failed refresh keeps the cards already on screen`() {
		var vState = reduce(UiState(), Intent.Load)
		vState = reduce(vState, loaded(vState.requestGeneration, listOf(card("1"), card("2"))))

		vState = reduce(vState, Intent.Load)
		vState = reduce(
			vState,
			Intent.Loaded(
				generation = vState.requestGeneration,
				cards = listOf(card("1"), card("2")),
				isCompleteSet = true,
				cachedCardCount = 2,
				knownSetSize = 2,
				origin = DataOrigin.CACHE,
				isStale = true,
				error = ProviderError.Offline(),
				isFinal = true,
			),
		)

		assertEquals(2, vState.cards.size, "a failed refresh must not blank the grid")
		assertNotNull(vState.error)
		assertTrue(vState.noticeIsRetryable)
		assertEquals("Showing saved cards. Refresh failed.", vState.coverageNotice)
	}

	@Test
	fun `starting a load clears the previous error`() {
		var vState = reduce(UiState(), Intent.Load)
		vState = reduce(vState, loaded(vState.requestGeneration, emptyList(), error = ProviderError.Offline()))
		assertNotNull(vState.error)

		vState = reduce(vState, Intent.Load)

		assertNull(vState.error)
		assertTrue(vState.isLoading)
	}

	// ============
	//  Honest coverage wording

	@Test
	fun `a partial set says how much of it is present`() {
		var vState = reduce(UiState(), Intent.Load)
		vState = reduce(
			vState,
			Intent.Loaded(
				generation = vState.requestGeneration,
				cards = List(200) { card("$it") },
				isCompleteSet = false,
				cachedCardCount = 200,
				knownSetSize = 352,
				origin = DataOrigin.NETWORK,
				isStale = false,
				error = null,
				isFinal = true,
			),
		)

		assertEquals("Partial set: 200 of 352 cards downloaded.", vState.coverageNotice)
	}

	// ============
	//  The count in the bar

	@Test
	fun `a complete set counts the cards it shows and not the provider's records`() {
		// Riftbound's Vendetta: 358 records, one per variant, collapsing to 227 distinct cards. The
		// bar used to compare those two numbers and read "227 of 358" over a set that was fully
		// downloaded -- which is what a download that gave up two thirds of the way looks like.
		var vState = reduce(UiState(), Intent.Load)
		vState = reduce(
			vState,
			loaded(vState.requestGeneration, List(227) { card("$it") }, knownSetSize = 358),
		)

		assertEquals("227 cards", vState.countLabel)
		assertNull(vState.coverageNotice)
	}

	@Test
	fun `a filtered set counts against what is held`() {
		var vState = reduce(UiState(), Intent.QueryChanged(CardQuery(rarities = setOf("Epic"))))
		vState = reduce(
			vState,
			Intent.Loaded(
				generation = vState.requestGeneration,
				cards = List(12) { card("$it", "Epic") },
				isCompleteSet = true,
				cachedCardCount = 227,
				knownSetSize = 358,
				origin = DataOrigin.NETWORK,
				isStale = false,
				error = null,
				isFinal = true,
			),
		)

		assertEquals("12 of 227", vState.countLabel)
	}

	@Test
	fun `filtering a partial set says the results are not the whole set`() {
		var vState = reduce(UiState(), Intent.QueryChanged(CardQuery(rarities = setOf("Epic"))))
		vState = reduce(
			vState,
			Intent.Loaded(
				generation = vState.requestGeneration,
				cards = List(4) { card("$it", "Epic") },
				isCompleteSet = false,
				cachedCardCount = 200,
				knownSetSize = 352,
				origin = DataOrigin.NETWORK,
				isStale = false,
				error = null,
				isFinal = true,
			),
		)

		assertEquals(
			"Filtered from 200 of 352 downloaded cards — not the whole set.",
			vState.coverageNotice,
		)
	}

	@Test
	fun `a complete set says nothing at all`() {
		var vState = reduce(UiState(), Intent.Load)
		vState = reduce(
			vState,
			loaded(vState.requestGeneration, List(352) { card("$it") }, knownSetSize = 352),
		)

		assertNull(vState.coverageNotice, "a complete, fresh set needs no caveat")
	}

	// ============
	//  Filter offering

	@Test
	fun `the count of active filters drives the badge`() {
		val vState = reduce(
			UiState(),
			Intent.QueryChanged(
				CardQuery(text = "annie", rarities = setOf("Epic"), domains = setOf("Fury")),
			),
		)

		assertEquals(3, vState.activeFilterCount)
	}

	@Test
	fun `unsupported filters are recorded so the sheet can leave them out`() {
		val vState = reduce(
			UiState(),
			Intent.CapabilitiesResolved(
				supportedFilters = setOf(CardFilterField.TEXT, CardFilterField.RARITY),
				game = RiftboundGame,
				languages = setOf(CardLanguage.ENGLISH),
				language = CardLanguage.ENGLISH,
			),
		)

		assertTrue(CardFilterField.RARITY in vState.supportedFilters)
		assertFalse(CardFilterField.FINISH in vState.supportedFilters)
		assertFalse(CardFilterField.LANGUAGE in vState.supportedFilters)
	}

	@Test
	fun `resolving capabilities also records the game -- so the sheet speaks its vocabulary`() {
		// The filter sheet says "Colour" for Magic and "Domain" for Riftbound, and this is where
		// it learns which. A grid that never resolved its game would silently use Riftbound's
		// words for every game.
		val vState = reduce(
			UiState(),
			Intent.CapabilitiesResolved(
				supportedFilters = emptySet(),
				game = MagicGame,
				languages = setOf(CardLanguage.ENGLISH, CardLanguage.FRENCH),
				language = CardLanguage.FRENCH,
			),
		)

		assertEquals(MagicGame, vState.game)
		// The words come from the game module now, not from a table in :core.
		assertEquals("Colour", vState.game?.vocabulary?.domain)
		assertEquals("Mana value", vState.game?.vocabulary?.cost)
	}

	@Test
	fun `a game with no single cost number is not offered a cost sort`() {
		// Pokémon costs are per attack, so there is no one number to sort on. Offering the option
		// would produce a sort that leaves every card in place and looks broken.
		val vPokemon = CardGridContract.sortOptions(PokemonGame).map { it.first }
		val vMagic = CardGridContract.sortOptions(MagicGame).map { it.first }

		assertFalse(CardSortField.COST in vPokemon)
		assertTrue(CardSortField.COST in vMagic)
	}

	@Test
	fun `scroll position is remembered so a trip into detail returns to the same place`() {
		val vState = reduce(UiState(), Intent.ScrollPositionChanged(42))

		assertEquals(42, vState.firstVisibleIndex)
	}

	@Test
	fun `an empty result after filtering is distinguished from a failed load`() {
		var vState = reduce(UiState(), Intent.QueryChanged(CardQuery(rarities = setOf("Nonexistent"))))
		vState = reduce(
			vState,
			Intent.Loaded(
				generation = vState.requestGeneration,
				cards = emptyList(),
				isCompleteSet = true,
				cachedCardCount = 352,
				knownSetSize = 352,
				origin = DataOrigin.NETWORK,
				isStale = false,
				error = null,
				isFinal = true,
			),
		)

		assertTrue(vState.isEmptyAfterFilter)
		assertNull(vState.error)
		assertFalse(vState.isInitialLoad)
	}

	// ============
	//  Changing the edition on screen

	@Test
	fun `a claim opens the control -- it does not fill the menu`() {
		// The reported behaviour: opening the language list on a Magic set showed a long list
		// which then shrank. It was the source's *claim* being drawn as though it were the set's
		// editions, replaced by the confirmed list when the probe landed.
		val vClaimed = reduce(
			UiState(),
			Intent.CapabilitiesResolved(
				supportedFilters = emptySet(),
				game = MagicGame,
				languages = setOf(CardLanguage.ENGLISH, CardLanguage.JAPANESE, CardLanguage.FRENCH),
				language = CardLanguage.FRENCH,
			),
		)

		// Worth offering to look, because the source says there may be more than one.
		assertTrue(vClaimed.hasLanguageChoice)
		// But the only edition anything is known about is the one on screen.
		assertEquals(listOf(CardLanguage.FRENCH), vClaimed.languageOptions)
	}

	@Test
	fun `a single-language source gets no control at all`() {
		// A menu holding one already-selected item is furniture. Riftcodex describes English and
		// nothing else, so the control does not appear for Riftbound.
		val vOne = reduce(
			UiState(),
			Intent.CapabilitiesResolved(
				supportedFilters = emptySet(),
				game = RiftboundGame,
				languages = setOf(CardLanguage.ENGLISH),
				language = CardLanguage.ENGLISH,
			),
		)

		assertFalse(vOne.hasLanguageChoice)
	}

	@Test
	fun `what is confirmed is what the menu lists -- in preference order`() {
		val vOpened = reduce(
			UiState(language = CardLanguage.FRENCH),
			Intent.LanguageOptionsRequested,
		)
		assertTrue(vOpened.isConfirmingLanguages, "the menu says it is still asking")

		val vResolved = reduce(
			vOpened,
			Intent.LanguageOptionsResolved(
				setOf(CardLanguage.ENGLISH, CardLanguage.JAPANESE, CardLanguage.FRENCH),
			),
		)

		assertFalse(vResolved.isConfirmingLanguages)
		assertEquals(
			listOf(CardLanguage.FRENCH, CardLanguage.JAPANESE, CardLanguage.ENGLISH),
			vResolved.languageOptions,
		)
	}

	@Test
	fun `a probe that answered nothing is a failure -- not a set with one language`() {
		// An empty answer used to leave the state untouched, so the menu simply looked short. The
		// two are opposite facts and the menu now says which one it is.
		val vFailed = reduce(
			reduce(UiState(language = CardLanguage.ENGLISH), Intent.LanguageOptionsRequested),
			Intent.LanguageOptionsResolved(emptySet()),
		)

		assertFalse(vFailed.isConfirmingLanguages)
		assertTrue(vFailed.languageCheckFailed)
		assertEquals(listOf(CardLanguage.ENGLISH), vFailed.languageOptions, "what is on screen stays")
	}

	@Test
	fun `switching language keeps the old cards on screen while the new ones load`() {
		// Blanking the grid would make a switch that turns out to be impossible look like one that
		// destroyed the set.
		val vLoaded = reduce(
			UiState(),
			Intent.CapabilitiesResolved(
				supportedFilters = emptySet(),
				game = MagicGame,
				languages = setOf(CardLanguage.ENGLISH, CardLanguage.JAPANESE),
				language = CardLanguage.ENGLISH,
			),
			loaded(generation = 0, cards = listOf(card("1"), card("2"))),
		)

		val vSwitching = reduce(vLoaded, Intent.LanguageSelected(CardLanguage.JAPANESE))

		assertEquals(CardLanguage.JAPANESE, vSwitching.language)
		assertEquals(2, vSwitching.cards.size, "the current edition stays visible while loading")
		assertTrue(vSwitching.isChangingLanguage)
		assertTrue(vSwitching.isLoading)
	}

	@Test
	fun `a language with nothing for this set is put back`() {
		// The real case: TCGdex keys each locale by its own set ids, so there is no Korean edition
		// of an English Pokemon set to fetch. Leaving the user on an empty grid in a language they
		// cannot browse is worse than not having offered the switch.
		val vLoaded = reduce(
			UiState(),
			Intent.CapabilitiesResolved(
				supportedFilters = emptySet(),
				game = PokemonGame,
				languages = setOf(CardLanguage.ENGLISH, CardLanguage.KOREAN),
				language = CardLanguage.ENGLISH,
			),
			loaded(generation = 0, cards = listOf(card("1"))),
		)

		val vReverted = reduce(
			vLoaded,
			Intent.LanguageSelected(CardLanguage.KOREAN),
			Intent.LanguageUnavailable,
		)

		assertEquals(CardLanguage.ENGLISH, vReverted.language, "the previous edition must come back")
		assertFalse(vReverted.isChangingLanguage)
		assertFalse(vReverted.isLoading)
		assertEquals(1, vReverted.cards.size)
	}

	@Test
	fun `picking the language already showing does nothing at all`() {
		val vLoaded = reduce(
			UiState(),
			Intent.CapabilitiesResolved(
				supportedFilters = emptySet(),
				game = MagicGame,
				languages = setOf(CardLanguage.ENGLISH, CardLanguage.JAPANESE),
				language = CardLanguage.ENGLISH,
			),
		)

		val vAgain = reduce(vLoaded, Intent.LanguageSelected(CardLanguage.ENGLISH))

		// No reload, so no generation bump: a request for what is already on screen is waste.
		assertEquals(vLoaded.requestGeneration, vAgain.requestGeneration)
		assertFalse(vAgain.isChangingLanguage)
	}
}
