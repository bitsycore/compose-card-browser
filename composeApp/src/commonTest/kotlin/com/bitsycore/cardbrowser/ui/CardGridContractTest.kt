package com.bitsycore.cardbrowser.ui

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.provider.CardFilterField
import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.provider.CardSortField
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.data.repository.DataOrigin
import com.bitsycore.cardbrowser.games.magic.MagicGame
import com.bitsycore.cardbrowser.games.pokemon.PokemonGame
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import com.bitsycore.cardbrowser.ui.cards.CardGridContract
import com.bitsycore.cardbrowser.ui.cards.CardGridContract.Intent
import com.bitsycore.cardbrowser.ui.cards.CardGridContract.UiState
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

	private fun reduce(state: UiState, vararg intents: Intent): UiState =
		intents.fold(state) { vState, vIntent -> CardGridContract.reduce(vState, vIntent) }

	private fun loaded(
		generation: Int,
		cards: List<com.bitsycore.cardbrowser.core.model.CardPrinting>,
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

	private fun card(number: String, rarity: String = "Common") =
		com.bitsycore.cardbrowser.core.model.CardPrinting(
			id = com.bitsycore.cardbrowser.core.model.SourceId(
				com.bitsycore.cardbrowser.core.model.ProviderId("riftcodex"),
				"card-$number",
			),
			game = RiftboundGame.id,
			setId = com.bitsycore.cardbrowser.core.model.SourceId(
				com.bitsycore.cardbrowser.core.model.ProviderId("riftcodex"),
				"OGN",
			),
			setCode = "OGN",
			setName = "Origins",
			collectorNumber = number,
			providerRawCollectorNumber = number,
			identity = null,
			text = com.bitsycore.cardbrowser.core.model.LocalizedText(
				com.bitsycore.cardbrowser.core.model.CardLanguage.ENGLISH,
				"Card $number",
			),
			artwork = com.bitsycore.cardbrowser.core.model.Artwork(
				id = com.bitsycore.cardbrowser.core.model.SourceId(
					com.bitsycore.cardbrowser.core.model.ProviderId("riftcodex"),
					"card-$number",
				),
				imageUrl = "https://example.test/$number.png",
				thumbnailUrl = null,
				artist = null,
				treatment = com.bitsycore.cardbrowser.core.model.ArtworkTreatment.STANDARD,
				language = com.bitsycore.cardbrowser.core.model.CardLanguage.ENGLISH,
			),
			classification = com.bitsycore.cardbrowser.core.model.CardClassification(rarity = rarity),
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
					sortBy = com.bitsycore.cardbrowser.core.provider.CardSortField.NAME,
					sortDirection = com.bitsycore.cardbrowser.core.provider.SortDirection.DESCENDING,
				),
			),
		)

		vState = reduce(vState, Intent.ClearFilters)

		assertEquals(com.bitsycore.cardbrowser.core.provider.CardSortField.NAME, vState.query.sortBy)
		assertEquals(
			com.bitsycore.cardbrowser.core.provider.SortDirection.DESCENDING,
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
	fun `only a real choice of language is offered`() {
		// A menu holding one already-selected item is furniture. Riftcodex describes English and
		// nothing else, so the control does not appear for Riftbound at all.
		val vOne = reduce(
			UiState(),
			Intent.CapabilitiesResolved(
				supportedFilters = emptySet(),
				game = RiftboundGame,
				languages = setOf(CardLanguage.ENGLISH),
				language = CardLanguage.ENGLISH,
			),
		)
		assertTrue(vOne.languageOptions.isEmpty())

		val vMany = reduce(
			UiState(),
			Intent.CapabilitiesResolved(
				supportedFilters = emptySet(),
				game = MagicGame,
				languages = setOf(CardLanguage.ENGLISH, CardLanguage.JAPANESE, CardLanguage.FRENCH),
				language = CardLanguage.FRENCH,
			),
		)
		// In the app's preference order, not the set's iteration order.
		assertEquals(
			listOf(CardLanguage.FRENCH, CardLanguage.JAPANESE, CardLanguage.ENGLISH),
			vMany.languageOptions,
		)
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
