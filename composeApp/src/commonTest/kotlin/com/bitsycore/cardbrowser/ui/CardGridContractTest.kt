package com.bitsycore.cardbrowser.ui

import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.model.GameVocabulary
import com.bitsycore.cardbrowser.core.provider.CardSortField
import com.bitsycore.cardbrowser.core.provider.CardFilterField
import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.data.repository.DataOrigin
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
			game = com.bitsycore.cardbrowser.core.model.Game.RIFTBOUND,
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
				game = Game.RIFTBOUND,
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
			Intent.CapabilitiesResolved(supportedFilters = emptySet(), game = Game.MAGIC),
		)

		assertEquals(Game.MAGIC, vState.game)
		assertEquals("Colour", GameVocabulary.of(vState.game).domain)
		assertEquals("Mana value", GameVocabulary.of(vState.game).energy)
	}

	@Test
	fun `a game with no single cost number is not offered a cost sort`() {
		// Pokémon costs are per attack, so there is no one number to sort on. Offering the option
		// would produce a sort that leaves every card in place and looks broken.
		val vPokemon = CardGridContract.sortOptions(Game.POKEMON).map { it.first }
		val vMagic = CardGridContract.sortOptions(Game.MAGIC).map { it.first }

		assertFalse(CardSortField.ENERGY_COST in vPokemon)
		assertTrue(CardSortField.ENERGY_COST in vMagic)
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
}
