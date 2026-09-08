package com.bitsycore.cardbrowser.data

import com.bitsycore.cardbrowser.core.model.Artwork
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardAttributes
import com.bitsycore.cardbrowser.core.model.CardClassification
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.FinishCoverage
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.model.LanguageCoverage
import com.bitsycore.cardbrowser.core.model.LocalizedText
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardFilterField
import com.bitsycore.cardbrowser.core.provider.CardPage
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import com.bitsycore.cardbrowser.core.provider.CardProvider
import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.provider.CardSortField
import com.bitsycore.cardbrowser.core.provider.DataCapabilities
import com.bitsycore.cardbrowser.core.provider.FilterSupport
import com.bitsycore.cardbrowser.core.provider.ProviderCapabilities
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.core.provider.ProviderRoute
import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.data.cache.MetadataCache
import com.bitsycore.cardbrowser.data.repository.CardRepository
import com.bitsycore.cardbrowser.data.repository.DataOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The repository's routing, completeness and fallback rules.
 *
 * The provider is a fake whose behaviour each test dictates, so failures, partial pages and offline
 * conditions are reproduced exactly rather than waited for.
 */
class CardRepositoryTest {

	private val mProviderId = ProviderId("fake")
	private val mSetId = SourceId(mProviderId, "OGN")
	private var mNow = 10_000L

	// ============
	//  Fake provider

	/**
	 * A provider that answers from a script.
	 *
	 * @param pages the pages of the set, in order
	 * @param failFromPage the 1-based page at which every call starts failing
	 */
	private class FakeProvider(
		override val id: ProviderId,
		private val mPages: List<List<CardPrinting>>,
		private val mFailFromPage: Int? = null,
		private val mSets: List<CardSet> = emptyList(),
		private val mSetsError: ProviderError? = null,
		private val mRemoteFilters: Set<CardFilterField> = setOf(CardFilterField.TEXT),
		private val mDelayByPage: Map<Int, Long> = emptyMap(),
		private val mMaxPageSize: Int = 2,
	) : CardProvider {

		var listCardsCallCount = 0
			private set

		/** Every page size asked for, in order, so the first-paint request can be asserted on. */
		val requestedPageSizes = mutableListOf<Int>()

		override val displayName = "Fake"

		override val capabilities = ProviderCapabilities(
			games = setOf(Game.RIFTBOUND),
			filtering = FilterSupport(
				remote = mRemoteFilters,
				localOnly = setOf(CardFilterField.RARITY, CardFilterField.DOMAIN),
			),
			sorting = setOf(CardSortField.COLLECTOR_NUMBER),
			data = DataCapabilities(
				languages = setOf(CardLanguage.ENGLISH),
				localizedText = false,
				localizedImages = false,
				cardIdentity = false,
				artworkVariants = true,
				finishes = false,
				cardmarketProductMapping = false,
			),
			attribution = null,
			maxPageSize = mMaxPageSize,
		)

		override suspend fun listSets(game: Game): List<CardSet> {
			mSetsError?.let { throw it }
			return mSets
		}

		override suspend fun listCards(request: CardPageRequest): CardPage {
			listCardsCallCount++
			requestedPageSizes += request.pageSize
			mDelayByPage[request.page]?.let { kotlinx.coroutines.delay(it) }
			if (mFailFromPage != null && request.page >= mFailFromPage) {
				throw ProviderError.Offline()
			}
			// Paged over the flat list so any requested page size behaves sensibly, including the
			// small first-paint request.
			val vAll = mPages.flatten()
			val vFrom = (request.page - 1) * request.pageSize
			val vItems = vAll.drop(vFrom).take(request.pageSize)
			return CardPage(
				cards = vItems,
				page = request.page,
				pageSize = request.pageSize,
				totalCount = vAll.size,
				hasMore = vFrom + vItems.size < vAll.size,
			)
		}

		override suspend fun cardDetail(id: SourceId): CardPrinting? =
			mPages.flatten().firstOrNull { it.id == id }
	}

	private fun card(
		number: Int,
		rarity: String = "Common",
		printingKey: String? = null,
		treatment: ArtworkTreatment = ArtworkTreatment.STANDARD,
		idSuffix: String = "",
	): CardPrinting = CardPrinting(
		id = SourceId(mProviderId, "card-$number$idSuffix"),
		printingKey = printingKey,
		game = Game.RIFTBOUND,
		setId = mSetId,
		setCode = "OGN",
		setName = "Origins",
		collectorNumber = number.toString(),
		providerRawCollectorNumber = number.toString(),
		identity = null,
		text = LocalizedText(CardLanguage.ENGLISH, "Card $number"),
		artwork = Artwork(
			id = SourceId(mProviderId, "card-$number"),
			imageUrl = "https://example.test/$number.png",
			thumbnailUrl = null,
			artist = null,
			treatment = treatment,
			language = CardLanguage.ENGLISH,
		),
		attributes = CardAttributes(energy = number),
		classification = CardClassification(type = "Unit", rarity = rarity, domains = listOf("Fury")),
		languages = LanguageCoverage.ENGLISH_ONLY,
		finishes = FinishCoverage(),
	)

	private fun repositoryFor(
		provider: CardProvider,
		fileSystem: FakeFileSystem = FakeFileSystem(),
	): CardRepository {
		val vStorage = AppStorage(fileSystem, "/cache".toPath(), "/prefs".toPath()).also { it.prepare() }
		val vCache = MetadataCache(
			mStorage = vStorage,
			mJson = Json { ignoreUnknownKeys = true },
			mIoDispatcher = Dispatchers.Unconfined,
			mClock = { mNow },
		)
		return CardRepository(
			mRegistry = ProviderRegistry(
				providers = listOf(provider),
				routes = listOf(ProviderRoute(Game.RIFTBOUND, provider.id)),
			),
			mCache = vCache,
			mClock = { mNow },
		)
	}

	// ============
	//  Sets

	@Test
	fun `sets come back newest first with undated ones last`() = runTest {
		val vProvider = FakeProvider(
			id = mProviderId,
			mPages = emptyList(),
			mSets = listOf(
				CardSet(SourceId(mProviderId, "a"), Game.RIFTBOUND, "OGN", "Origins", 352, LocalDate(2025, 10, 31)),
				CardSet(SourceId(mProviderId, "b"), Game.RIFTBOUND, "VEN", "Vendetta", 358, LocalDate(2026, 7, 31)),
				CardSet(SourceId(mProviderId, "c"), Game.RIFTBOUND, "PR", "Promos", 13, null),
			),
		)

		val vResult = repositoryFor(vProvider).setList(Game.RIFTBOUND).toList().last()

		assertEquals(listOf("VEN", "OGN", "PR"), vResult.value?.map { it.code })
	}

	@Test
	fun `a set list survives a restart and is served without a request`() = runTest {
		val vFileSystem = FakeFileSystem()
		val vSets = listOf(
			CardSet(SourceId(mProviderId, "a"), Game.RIFTBOUND, "OGN", "Origins", 352, LocalDate(2025, 10, 31)),
		)
		repositoryFor(FakeProvider(mProviderId, emptyList(), mSets = vSets), vFileSystem)
			.setList(Game.RIFTBOUND).toList()

		// A brand new repository over the same disk, and a provider that would throw if asked.
		val vOffline = FakeProvider(mProviderId, emptyList(), mSetsError = ProviderError.Offline())
		val vResult = repositoryFor(vOffline, vFileSystem).setList(Game.RIFTBOUND).toList()

		assertEquals(1, vResult.size, "a fresh cached list should not trigger a network call")
		assertEquals(DataOrigin.CACHE, vResult.single().origin)
		assertEquals("Origins", vResult.single().value?.single()?.name)
		assertNull(vResult.single().error)
	}

	@Test
	fun `a failed refresh keeps the cached set list and reports the error beside it`() = runTest {
		val vFileSystem = FakeFileSystem()
		val vSets = listOf(
			CardSet(SourceId(mProviderId, "a"), Game.RIFTBOUND, "OGN", "Origins", 352, LocalDate(2025, 10, 31)),
		)
		repositoryFor(FakeProvider(mProviderId, emptyList(), mSets = vSets), vFileSystem)
			.setList(Game.RIFTBOUND).toList()

		// Time moves past the TTL, so a refresh is attempted -- and fails.
		mNow += CardRepository.DEFAULT_SET_LIST_TTL_MILLIS + 1
		val vOffline = FakeProvider(mProviderId, emptyList(), mSetsError = ProviderError.Offline())
		val vResult = repositoryFor(vOffline, vFileSystem).setList(Game.RIFTBOUND).toList()

		val vFinal = vResult.last()
		assertNotNull(vFinal.value, "a failed refresh must not erase valid cached data")
		assertEquals("Origins", vFinal.value?.single()?.name)
		assertIs<ProviderError.Offline>(vFinal.error)
		assertTrue(vFinal.hasRecoverableError)
	}

	@Test
	fun `a first load with no cache and no network reports the failure with no value`() = runTest {
		val vOffline = FakeProvider(mProviderId, emptyList(), mSetsError = ProviderError.Offline())

		val vResult = repositoryFor(vOffline).setList(Game.RIFTBOUND).toList().last()

		assertNull(vResult.value)
		assertIs<ProviderError.Offline>(vResult.error)
		assertEquals(DataOrigin.NONE, vResult.origin)
	}

	@Test
	fun `a game with no route fails rather than silently using another provider`() = runTest {
		val vRegistry = ProviderRegistry(
			providers = listOf(FakeProvider(mProviderId, emptyList())),
			routes = listOf(ProviderRoute(Game.RIFTBOUND, mProviderId)),
		)
		val vStorage = AppStorage(FakeFileSystem(), "/cache".toPath(), "/prefs".toPath()).also { it.prepare() }
		val vRepository = CardRepository(
			mRegistry = vRegistry,
			mCache = MetadataCache(vStorage, Json {}, Dispatchers.Unconfined, mClock = { mNow }),
			mClock = { mNow },
		)

		val vResult = vRepository.setList(Game.POKEMON).toList().last()

		assertNull(vResult.value)
		assertNotNull(vResult.error)
	}

	// ============
	//  Complete sets and partial coverage

	@Test
	fun `every page is fetched and the set is reported complete`() = runTest {
		// maxPageSize is 2 on the fake, so five cards is three pages.
		val vProvider = FakeProvider(
			id = mProviderId,
			mPages = listOf(listOf(card(1), card(2)), listOf(card(3), card(4)), listOf(card(5))),
		)

		val vResult = repositoryFor(vProvider)
			.cards(mSetId, Game.RIFTBOUND, CardQuery(), knownSetSize = 5)
			.toList().last()

		val vCards = assertNotNull(vResult.value)
		assertEquals(5, vCards.cards.size)
		assertTrue(vCards.isCompleteSet)
		assertFalse(vCards.isPartial)
		assertEquals(3, vProvider.listCardsCallCount)
	}

	@Test
	fun `a set that fails partway through is kept and marked partial`() = runTest {
		// Page 3 fails. Pages 1 and 2 are real cards the user can still browse, and saying so is
		// strictly better than showing nothing -- but it must not be called a complete set.
		val vProvider = FakeProvider(
			id = mProviderId,
			mPages = listOf(listOf(card(1), card(2)), listOf(card(3), card(4)), listOf(card(5))),
			mFailFromPage = 3,
		)

		val vResult = repositoryFor(vProvider)
			.cards(mSetId, Game.RIFTBOUND, CardQuery(), knownSetSize = 5)
			.toList().last()

		val vCards = assertNotNull(vResult.value)
		assertEquals(4, vCards.cards.size)
		assertFalse(vCards.isCompleteSet, "four of five cards is not a complete set")
		assertTrue(vCards.isPartial)
		assertEquals(5, vCards.knownSetSize)
		assertEquals(4, vCards.cachedCardCount)
	}

	@Test
	fun `a filter over a partial set reports itself as filtered from part of the set`() = runTest {
		val vProvider = FakeProvider(
			id = mProviderId,
			mPages = listOf(
				listOf(card(1, "Epic"), card(2, "Common")),
				listOf(card(3, "Epic"), card(4, "Common")),
				listOf(card(5, "Epic")),
			),
			mFailFromPage = 3,
		)

		val vResult = repositoryFor(vProvider)
			.cards(mSetId, Game.RIFTBOUND, CardQuery(rarities = setOf("Epic")), knownSetSize = 5)
			.toList().last()

		val vCards = assertNotNull(vResult.value)
		// Two epics found, but there is a third on the page that failed.
		assertEquals(2, vCards.cards.size)
		assertFalse(vCards.isCompleteSet, "a filtered partial set must never claim completeness")
		assertTrue(vCards.isPartial)
	}

	@Test
	fun `a failure on the very first page has nothing partial to offer`() = runTest {
		val vProvider = FakeProvider(mProviderId, mPages = listOf(listOf(card(1))), mFailFromPage = 1)

		val vResult = repositoryFor(vProvider)
			.cards(mSetId, Game.RIFTBOUND, CardQuery())
			.toList().last()

		assertNull(vResult.value)
		assertIs<ProviderError.Offline>(vResult.error)
	}

	@Test
	fun `a locally filtered query never runs against a single page`() = runTest {
		// Rarity is localOnly on the fake. The repository must fetch all three pages before
		// answering, or it would be filtering page one and calling that the result.
		val vProvider = FakeProvider(
			id = mProviderId,
			mPages = listOf(
				listOf(card(1, "Common"), card(2, "Common")),
				listOf(card(3, "Common"), card(4, "Common")),
				listOf(card(5, "Epic")),
			),
		)

		val vResult = repositoryFor(vProvider)
			.cards(mSetId, Game.RIFTBOUND, CardQuery(rarities = setOf("Epic")))
			.toList().last()

		// The only Epic is on the last page.
		assertEquals(listOf("5"), vResult.value?.cards?.map { it.collectorNumber })
		assertEquals(3, vProvider.listCardsCallCount)
	}

	@Test
	fun `a set that comes back empty despite a non-zero total is not called complete`() = runTest {
		// Regression. A wrong `set_id` produced 200 OK, zero cards and `hasMore` false, which paged
		// "successfully" to nothing and was then cached as a complete set -- so the empty set was
		// served from disk on every later launch. The provider's own total is the cross-check.
		val vProvider = object : CardProvider by FakeProvider(mProviderId, emptyList()) {
			override suspend fun listCards(request: CardPageRequest) = CardPage(
				cards = emptyList(),
				page = 1,
				pageSize = 100,
				totalCount = 352,
				hasMore = false,
			)
		}

		val vResult = repositoryFor(vProvider)
			.cards(mSetId, Game.RIFTBOUND, CardQuery(), knownSetSize = 352)
			.toList().last()

		val vCards = assertNotNull(vResult.value)
		assertTrue(vCards.cards.isEmpty())
		assertFalse(vCards.isCompleteSet, "zero cards against a total of 352 is not a complete set")
	}

	@Test
	fun `the first page is emitted before the rest are fetched`() = runTest {
		// The whole point of the change: Origins is four pages and roughly seven seconds of round
		// trips, and the user should not watch a spinner for all of it. The first emission carries
		// page one, marked partial; the last carries everything, marked complete.
		val vProvider = FakeProvider(
			id = mProviderId,
			mPages = listOf(listOf(card(1), card(2)), listOf(card(3), card(4)), listOf(card(5))),
		)

		val vEmissions = repositoryFor(vProvider)
			.cards(mSetId, Game.RIFTBOUND, CardQuery(), knownSetSize = 5)
			.toList()

		assertTrue(vEmissions.size >= 2, "expected at least a partial paint then the complete set")

		val vFirst = assertNotNull(vEmissions.first().value)
		assertTrue(vFirst.cards.isNotEmpty(), "something should be drawn before the set finishes")
		assertFalse(vFirst.isCompleteSet, "the first paint must not claim to be the set")
		assertEquals(5, vFirst.knownSetSize)

		val vLast = assertNotNull(vEmissions.last().value)
		assertEquals(5, vLast.cards.size)
		assertTrue(vLast.isCompleteSet)
	}

	@Test
	fun `a single-page set emits once and is complete`() = runTest {
		// Nothing to be progressive about, so no partial emission to flicker through.
		val vProvider = FakeProvider(mProviderId, mPages = listOf(listOf(card(1), card(2))))

		val vEmissions = repositoryFor(vProvider)
			.cards(mSetId, Game.RIFTBOUND, CardQuery(), knownSetSize = 2)
			.toList()

		assertEquals(1, vEmissions.size)
		assertTrue(assertNotNull(vEmissions.single().value).isCompleteSet)
	}

	@Test
	fun `pages are reassembled in page order, not in the order they answer`() = runTest {
		// They are now fetched concurrently, so completion order is not request order. Collector
		// numbers must still come back 1..5 or the cached set depends on network timing.
		val vProvider = FakeProvider(
			id = mProviderId,
			mPages = listOf(listOf(card(1), card(2)), listOf(card(3), card(4)), listOf(card(5))),
			mDelayByPage = mapOf(2 to 50L, 3 to 1L),
		)

		val vResult = repositoryFor(vProvider)
			.cards(mSetId, Game.RIFTBOUND, CardQuery())
			.toList().last()

		assertEquals(
			listOf("1", "2", "3", "4", "5"),
			assertNotNull(vResult.value).cards.map { it.collectorNumber },
		)
	}

	@Test
	fun `the first request is small, so something is drawn before a full page transfers`() = runTest {
		// This API's transfer time tracks payload and varies wildly -- a 100-card page was measured
		// between 1.6 s and 11.8 s, a 24-card one at about a second. The first request is therefore
		// deliberately small and thrown away; the real pagination follows at full page size.
		val vProvider = FakeProvider(
			id = mProviderId,
			mPages = listOf((1..30).map { card(it) }),
			mMaxPageSize = 100,
		)

		val vEmissions = repositoryFor(vProvider)
			.cards(mSetId, Game.RIFTBOUND, CardQuery(), knownSetSize = 30)
			.toList()

		assertEquals(
			listOf(24, 100),
			vProvider.requestedPageSizes,
			"a small first paint, then the provider's full page size",
		)
		// And the small one really did reach the screen ahead of the rest.
		assertEquals(24, assertNotNull(vEmissions.first().value).cards.size)
		assertFalse(assertNotNull(vEmissions.first().value).isCompleteSet)
		assertEquals(30, assertNotNull(vEmissions.last().value).cards.size)
		assertTrue(assertNotNull(vEmissions.last().value).isCompleteSet)
	}

	@Test
	fun `a provider with small pages of its own is not asked twice`() = runTest {
		// maxPageSize is 2 here, so a 24-card "preview" would be nonsense: the extra round trip
		// would cost time and save nothing.
		val vProvider = FakeProvider(
			id = mProviderId,
			mPages = listOf(listOf(card(1), card(2)), listOf(card(3))),
		)

		repositoryFor(vProvider).cards(mSetId, Game.RIFTBOUND, CardQuery()).toList()

		assertTrue(
			vProvider.requestedPageSizes.all { it == 2 },
			"every request should use the provider's own page size, was ${vProvider.requestedPageSizes}",
		)
	}

	// ============
	//  Duplicate records

	@Test
	fun `records the provider sent twice for one printing collapse into one`() = runTest {
		// Riftcodex's Vendetta really does this: 358 records for 227 distinct riftbound_ids, each
		// copy under its own database id, so nothing downstream could tell them apart.
		val vProvider = FakeProvider(
			id = mProviderId,
			mPages = listOf(
				listOf(
					card(1, printingKey = "ven-001", idSuffix = "-a"),
					card(1, printingKey = "ven-001", idSuffix = "-b"),
				),
				listOf(card(2, printingKey = "ven-002")),
			),
		)

		val vResult = repositoryFor(vProvider)
			.cards(mSetId, Game.RIFTBOUND, CardQuery())
			.toList().last()

		val vCards = assertNotNull(vResult.value)
		assertEquals(2, vCards.cards.size, "the duplicated printing should appear once")
		assertEquals(listOf("1", "2"), vCards.cards.map { it.collectorNumber })
	}

	@Test
	fun `a provider that declares no printing key is never de-duplicated`() = runTest {
		// Two genuinely different printings that happen to share a name and number -- Origins 299
		// is exactly this. Collapsing them would lose a card.
		val vProvider = FakeProvider(
			id = mProviderId,
			mPages = listOf(listOf(card(299, idSuffix = "-over"), card(299, idSuffix = "-sig"))),
		)

		val vResult = repositoryFor(vProvider)
			.cards(mSetId, Game.RIFTBOUND, CardQuery())
			.toList().last()

		assertEquals(2, assertNotNull(vResult.value).cards.size)
	}

	@Test
	fun `when copies disagree the one asserting a treatment wins`() = runTest {
		// Vendetta ships `ven-019a` twice: once flagged alternate art and named as such, once with
		// the flag false and the plain name. False is indistinguishable from unset, so the copy
		// making a positive claim is the better record.
		val vProvider = FakeProvider(
			id = mProviderId,
			mPages = listOf(
				listOf(
					card(19, printingKey = "ven-019a", idSuffix = "-plain"),
					card(
						19,
						printingKey = "ven-019a",
						treatment = ArtworkTreatment.ALTERNATE_ART,
						idSuffix = "-alt",
					),
				),
			),
		)

		val vResult = repositoryFor(vProvider)
			.cards(mSetId, Game.RIFTBOUND, CardQuery())
			.toList().last()

		val vCard = assertNotNull(vResult.value).cards.single()
		assertEquals(ArtworkTreatment.ALTERNATE_ART, vCard.artwork.treatment)
	}

	// ============
	//  Cache-first behaviour

	@Test
	fun `cards are served from cache first and then refreshed`() = runTest {
		val vFileSystem = FakeFileSystem()
		val vPages = listOf(listOf(card(1), card(2)), listOf(card(3)))
		repositoryFor(FakeProvider(mProviderId, vPages), vFileSystem)
			.cards(mSetId, Game.RIFTBOUND, CardQuery()).toList()

		// Past the TTL, so a refresh runs -- but the cached copy must be emitted first.
		mNow += CardRepository.DEFAULT_CARDS_TTL_MILLIS + 1
		val vEmissions = repositoryFor(FakeProvider(mProviderId, vPages), vFileSystem)
			.cards(mSetId, Game.RIFTBOUND, CardQuery()).toList()

		assertEquals(2, vEmissions.size)
		assertEquals(DataOrigin.CACHE, vEmissions[0].origin)
		assertTrue(vEmissions[0].isStale)
		assertEquals(3, vEmissions[0].value?.cards?.size)
		assertEquals(DataOrigin.NETWORK, vEmissions[1].origin)
	}

	@Test
	fun `a fresh complete set answers a new filter with no request at all`() = runTest {
		val vFileSystem = FakeFileSystem()
		val vPages = listOf(listOf(card(1, "Epic"), card(2, "Common")), listOf(card(3, "Epic")))
		repositoryFor(FakeProvider(mProviderId, vPages), vFileSystem)
			.cards(mSetId, Game.RIFTBOUND, CardQuery()).toList()

		// Same clock, so the cache is still fresh. Filtering must be instant and offline.
		val vProvider = FakeProvider(mProviderId, vPages)
		val vResult = repositoryFor(vProvider, vFileSystem)
			.cards(mSetId, Game.RIFTBOUND, CardQuery(rarities = setOf("Epic")))
			.toList()

		assertEquals(1, vResult.size)
		assertEquals(0, vProvider.listCardsCallCount, "a fresh complete set needs no request")
		assertEquals(listOf("1", "3"), vResult.single().value?.cards?.map { it.collectorNumber })
	}

	@Test
	fun `cards remain browsable offline after a restart`() = runTest {
		val vFileSystem = FakeFileSystem()
		val vPages = listOf(listOf(card(1), card(2)), listOf(card(3)))
		repositoryFor(FakeProvider(mProviderId, vPages), vFileSystem)
			.cards(mSetId, Game.RIFTBOUND, CardQuery()).toList()

		// Later, past the TTL, with no network at all.
		mNow += CardRepository.DEFAULT_CARDS_TTL_MILLIS + 1
		val vOffline = FakeProvider(mProviderId, emptyList(), mFailFromPage = 1)
		val vEmissions = repositoryFor(vOffline, vFileSystem)
			.cards(mSetId, Game.RIFTBOUND, CardQuery()).toList()

		val vFinal = vEmissions.last()
		assertEquals(3, vFinal.value?.cards?.size, "cached cards must survive a failed refresh")
		assertIs<ProviderError.Offline>(vFinal.error)
	}

	@Test
	fun `detail is answered from the cached set without a request`() = runTest {
		val vFileSystem = FakeFileSystem()
		val vPages = listOf(listOf(card(1), card(2)), listOf(card(3)))
		repositoryFor(FakeProvider(mProviderId, vPages), vFileSystem)
			.cards(mSetId, Game.RIFTBOUND, CardQuery()).toList()

		val vProvider = FakeProvider(mProviderId, vPages)
		val vDetail = repositoryFor(vProvider, vFileSystem)
			.cardDetail(SourceId(mProviderId, "card-2"), Game.RIFTBOUND, mSetId)

		assertEquals("Card 2", vDetail.value?.displayName)
		assertEquals(DataOrigin.CACHE, vDetail.origin)
	}

	@Test
	fun `facets come only from a complete set`() = runTest {
		val vFileSystem = FakeFileSystem()
		val vPages = listOf(
			listOf(card(1, "Epic"), card(2, "Common")),
			listOf(card(3, "Legendary")),
		)

		// A partial fetch must offer no facets: a rarity hiding on an unfetched page would be
		// missing from the filter sheet with no way for the user to tell.
		repositoryFor(FakeProvider(mProviderId, vPages, mFailFromPage = 2), vFileSystem)
			.cards(mSetId, Game.RIFTBOUND, CardQuery()).toList()
		assertTrue(
			repositoryFor(FakeProvider(mProviderId, vPages), vFileSystem)
				.facetsFor(mSetId, Game.RIFTBOUND).isEmpty,
		)

		// Once complete, every rarity in the set is offered.
		val vCompleteFileSystem = FakeFileSystem()
		repositoryFor(FakeProvider(mProviderId, vPages), vCompleteFileSystem)
			.cards(mSetId, Game.RIFTBOUND, CardQuery()).toList()
		val vFacets = repositoryFor(FakeProvider(mProviderId, vPages), vCompleteFileSystem)
			.facetsFor(mSetId, Game.RIFTBOUND)

		assertEquals(listOf("Common", "Epic", "Legendary"), vFacets.rarities)
	}
}
