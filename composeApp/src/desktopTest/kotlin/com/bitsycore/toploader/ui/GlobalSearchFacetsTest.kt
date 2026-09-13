package com.bitsycore.toploader.ui

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.flow.toList
import com.bitsycore.toploader.core.model.ArtworkTreatment
import com.bitsycore.toploader.core.game.GameProfile
import com.bitsycore.toploader.core.game.GameVocabulary
import com.bitsycore.toploader.core.model.Artwork
import com.bitsycore.toploader.core.model.CardClassification
import com.bitsycore.toploader.core.model.CardPrinting
import com.bitsycore.toploader.core.model.CardSet
import com.bitsycore.toploader.core.model.LocalizedText
import com.bitsycore.toploader.core.model.ProviderId
import com.bitsycore.toploader.core.provider.CardFilterField
import com.bitsycore.toploader.core.provider.CardPage
import com.bitsycore.toploader.core.provider.CardPageRequest
import com.bitsycore.toploader.core.provider.CardProvider
import com.bitsycore.toploader.core.provider.CardSortField
import com.bitsycore.toploader.core.provider.DataCapabilities
import com.bitsycore.toploader.core.provider.FilterSupport
import com.bitsycore.toploader.core.provider.ProviderCapabilities
import com.bitsycore.toploader.core.model.CardLanguage
import com.bitsycore.toploader.core.model.GameId
import com.bitsycore.toploader.core.model.SourceId
import com.bitsycore.toploader.core.provider.CardQuery
import com.bitsycore.toploader.core.provider.ProviderRegistry
import com.bitsycore.toploader.core.provider.ProviderRoute
import com.bitsycore.toploader.data.cache.AppStorage
import com.bitsycore.toploader.data.cache.InMemorySetRecordStore
import com.bitsycore.toploader.data.cache.InMemoryMetadataStore
import com.bitsycore.toploader.data.repository.CardRepository
import com.bitsycore.toploader.data.settings.PreferencesStore
import com.bitsycore.toploader.controller.BrowseSession
import com.bitsycore.toploader.ui.screen.cards.CardGridContract
import com.bitsycore.toploader.ui.screen.cards.CardGridViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opening the global search offers the filter values the game already has stored.
 *
 * ## The fault
 *
 * Reported twice. The first time nothing resolved them at all -- the sheet draws only what its
 * source supports, and capabilities were read from the provider a *set id* names, which a
 * game-wide search has not got. The second time the facets themselves were missing: the read is
 * memoised per game, and it was memoising the empty answer you get while a download is still
 * running, so the values never arrived however long you waited.
 *
 * This drives the real view model against a real repository over an in-memory store, because both
 * faults lived between those two and neither was visible from either end alone.
 */
class GlobalSearchFacetsTest {

	/**
	 * A view model's scope is `Dispatchers.Main.immediate`, which a plain JVM test does not have.
	 *
	 * Without this every `handleIntent` is dropped and the state never moves -- which looks exactly
	 * like the bug under test and is not it. Worth the two lines to be sure the instrument works.
	 */
	@BeforeTest
	fun useTestMain() = Dispatchers.setMain(StandardTestDispatcher())

	@AfterTest
	fun releaseMain() = Dispatchers.resetMain()

	@Test
	fun `the sheet has both its values and its supported fields on arrival`() = runTest {
		val vProvider = FacetTestProvider()
		val vRegistry = ProviderRegistry(
			providers = listOf(vProvider),
			routes = listOf(ProviderRoute(FacetTestProvider.GAME.id, vProvider.id)),
		)
		val vFileSystem = FakeFileSystem()
		val vStorage = AppStorage(vFileSystem, "/cache".toPath(), "/prefs".toPath())
			.also { it.prepare() }
		val vRepository = CardRepository(
			mRegistry = vRegistry,
			mCache = InMemoryMetadataStore(),
			mSetStore = InMemorySetRecordStore(),
			mClock = { 0L },
		)

		// What "I opened one set once" leaves behind. The store is written by browsing, not only by
		// downloading -- `CardRepositoryTest` pins that half.
		vRepository.cards(
			setId = SourceId(vProvider.id, "s"),
			game = FacetTestProvider.GAME.id,
			query = CardQuery(),
			language = CardLanguage.ENGLISH,
		).toList()

		val vModel = CardGridViewModel(
			mRepository = vRepository,
			mRegistry = vRegistry,
			mPreferences = PreferencesStore(vStorage, Json { ignoreUnknownKeys = true }, Dispatchers.Unconfined),
			mSession = BrowseSession(),
		)
		// Exactly what `CardGridScreen` dispatches when the route carries a game and no set.
		vModel.dispatch(CardGridContract.Intent.GameSelected(FacetTestProvider.GAME))
		advanceUntilIdle()

		val vState = vModel.stateFlow.value
		assertTrue(vState.supportedFilters.isNotEmpty(), "the sheet draws only what the source supports")
		assertTrue(vState.facets.rarities.isNotEmpty(), "the rarities of everything stored")
		assertTrue(vState.facets.cardTypes.isNotEmpty(), "and the card types")
	}

	@Test
	fun `an empty store is re-read rather than remembered`() = runTest {
		val vProvider = FacetTestProvider()
		val vRegistry = ProviderRegistry(
			providers = listOf(vProvider),
			routes = listOf(ProviderRoute(FacetTestProvider.GAME.id, vProvider.id)),
		)
		val vFileSystem = FakeFileSystem()
		val vStorage = AppStorage(vFileSystem, "/cache".toPath(), "/prefs".toPath())
			.also { it.prepare() }
		val vRepository = CardRepository(
			mRegistry = vRegistry,
			mCache = InMemoryMetadataStore(),
			mSetStore = InMemorySetRecordStore(),
			mClock = { 0L },
		)
		val vModel = CardGridViewModel(
			mRepository = vRepository,
			mRegistry = vRegistry,
			mPreferences = PreferencesStore(vStorage, Json { ignoreUnknownKeys = true }, Dispatchers.Unconfined),
			mSession = BrowseSession(),
		)

		// Nothing stored: the first read finds nothing, which is honest and must not be cached.
		vModel.dispatch(CardGridContract.Intent.GameSelected(FacetTestProvider.GAME))
		advanceUntilIdle()
		assertTrue(vModel.stateFlow.value.facets.isEmpty)

		// The download lands. Anything that makes the screen load again must pick the values up --
		// waiting was what the user did, and it never worked.
		vRepository.cards(
			setId = SourceId(vProvider.id, "s"),
			game = FacetTestProvider.GAME.id,
			query = CardQuery(),
			language = CardLanguage.ENGLISH,
		).toList()
		vModel.dispatch(CardGridContract.Intent.QueryChanged(CardQuery(text = "a")))
		advanceUntilIdle()

		assertTrue(
			vModel.stateFlow.value.facets.rarities.isNotEmpty(),
			"an empty read must not be remembered as the answer",
		)
	}

	@Test
	fun `values that arrive after the read are picked up`() = runTest {
		// The read is memoised per game, which is right -- but "once" was once ever, so the rest of
		// an import, or a set downloaded since, stayed invisible until the screen was recreated.
		val vProvider = FacetTestProvider()
		val vRegistry = ProviderRegistry(
			providers = listOf(vProvider),
			routes = listOf(ProviderRoute(FacetTestProvider.GAME.id, vProvider.id)),
		)
		val vStorage = AppStorage(FakeFileSystem(), "/cache".toPath(), "/prefs".toPath())
			.also { it.prepare() }
		val vStore = InMemorySetRecordStore()
		val vRepository = CardRepository(
			mRegistry = vRegistry,
			mCache = InMemoryMetadataStore(),
			mSetStore = vStore,
			mClock = { 0L },
		)
		vRepository.cards(
			setId = SourceId(vProvider.id, "s"),
			game = FacetTestProvider.GAME.id,
			query = CardQuery(),
			language = CardLanguage.ENGLISH,
		).toList()

		val vModel = CardGridViewModel(
			mRepository = vRepository,
			mRegistry = vRegistry,
			mPreferences = PreferencesStore(vStorage, Json { ignoreUnknownKeys = true }, Dispatchers.Unconfined),
			mSession = BrowseSession(),
		)
		vModel.dispatch(CardGridContract.Intent.GameSelected(FacetTestProvider.GAME))
		advanceUntilIdle()
		assertTrue("Mythic" !in vModel.stateFlow.value.facets.rarities, "not stored yet")

		// A second set lands, carrying a rarity the first one did not have.
		vProvider.extraSet = true
		vRepository.cards(
			setId = SourceId(vProvider.id, "s2"),
			game = FacetTestProvider.GAME.id,
			query = CardQuery(),
			language = CardLanguage.ENGLISH,
		).toList()
		vModel.dispatch(CardGridContract.Intent.QueryChanged(CardQuery(text = "card")))
		advanceUntilIdle()

		assertTrue(
			"Mythic" in vModel.stateFlow.value.facets.rarities,
			"a result carrying an unoffered value tops the sheet up",
		)
	}
}

/** A game invented here, so this test does not lean on any shipped module's declarations. */
private object FacetTestGame : GameProfile {

	override val id: GameId = GameId("facet-test-game")

	override val displayName: String = "Facet Test"

	override val vocabulary: GameVocabulary = GameVocabulary(domain = "Domain", cost = "Energy")

	override val rarityLadder: List<String> = listOf("Common", "Rare")
}

/** One set, two cards, and enough capability to draw a filter sheet. */
private class FacetTestProvider : CardProvider<GameProfile> {

	override val id: ProviderId = ProviderId("facet-test")

	override val displayName: String = "Facet Test Source"

	override val game: GameProfile = GAME

	override val capabilities: ProviderCapabilities = ProviderCapabilities(
		filtering = FilterSupport(
			remote = emptySet(),
			localOnly = setOf(
				CardFilterField.TEXT,
				CardFilterField.RARITY,
				CardFilterField.CARD_TYPE,
				CardFilterField.DOMAIN,
			),
		),
		sorting = setOf(CardSortField.COLLECTOR_NUMBER),
		data = DataCapabilities(
			languages = setOf(CardLanguage.ENGLISH),
			localizedText = false,
			localizedImages = false,
			cardIdentity = false,
			artworkVariants = false,
			finishes = false,
			cardmarketProductMapping = false,
		),
		attribution = null,
		maxPageSize = 100,
	)

	override suspend fun listSets(language: CardLanguage?): List<CardSet> = listOf(
		CardSet(
			id = SourceId(id, "s"),
			game = GAME.id,
			code = "S",
			name = "Set",
			cardCount = 2,
			releaseDate = null,
		),
	)

	/** Flipped by a test to make a second set exist, carrying a rarity the first one does not. */
	var extraSet: Boolean = false

	override suspend fun listCards(request: CardPageRequest): CardPage {
		val vCards = if (request.setId.local == "s2") EXTRA_CARDS else CARDS
		return CardPage(
			cards = vCards,
			page = 1,
			pageSize = vCards.size,
			totalCount = vCards.size,
			hasMore = false,
		)
	}

	override suspend fun cardDetail(id: SourceId, language: CardLanguage?): CardPrinting? =
		CARDS.firstOrNull { it.id == id }

	companion object {

		val GAME: GameProfile = FacetTestGame

		private val PROVIDER = ProviderId("facet-test")

		val CARDS: List<CardPrinting> = listOf(card(1, "Common"), card(2, "Rare"))

		val EXTRA_CARDS: List<CardPrinting> = listOf(card(3, "Mythic", set = "s2"))

		private fun card(number: Int, rarity: String, set: String = "s") = CardPrinting(
			id = SourceId(PROVIDER, "c$number"),
			game = GAME.id,
			setId = SourceId(PROVIDER, set),
			setCode = "S",
			setName = "Set",
			collectorNumber = number.toString(),
			providerRawCollectorNumber = number.toString(),
			identity = null,
			text = LocalizedText(CardLanguage.ENGLISH, "Card $number"),
			artwork = Artwork(
				id = SourceId(PROVIDER, "a$number"),
				imageUrl = "https://example.test/$number.png",
				thumbnailUrl = null,
				artist = null,
				treatment = ArtworkTreatment.STANDARD,
				language = CardLanguage.ENGLISH,
			),
			classification = CardClassification(
				type = "Unit",
				supertype = null,
				rarity = rarity,
				domains = listOf("fury"),
			),
		)
	}
}
