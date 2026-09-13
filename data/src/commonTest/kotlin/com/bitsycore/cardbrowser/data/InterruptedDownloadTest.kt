package com.bitsycore.cardbrowser.data

import com.bitsycore.cardbrowser.core.model.Artwork
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardAttributes
import com.bitsycore.cardbrowser.core.model.CardClassification
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.FinishCoverage
import com.bitsycore.cardbrowser.core.model.LanguageCoverage
import com.bitsycore.cardbrowser.core.model.LocalizedText
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardPage
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import com.bitsycore.cardbrowser.core.provider.CardProvider
import com.bitsycore.cardbrowser.core.provider.CardSortField
import com.bitsycore.cardbrowser.core.provider.DataCapabilities
import com.bitsycore.cardbrowser.core.provider.FilterSupport
import com.bitsycore.cardbrowser.core.provider.ProviderCapabilities
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.core.provider.ProviderRoute
import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.data.cache.InMemoryMetadataStore
import com.bitsycore.cardbrowser.data.cache.InMemorySetRecordStore
import com.bitsycore.cardbrowser.data.download.DownloadKind
import com.bitsycore.cardbrowser.data.download.DownloadManager
import com.bitsycore.cardbrowser.data.download.DownloadRequest
import com.bitsycore.cardbrowser.data.download.DownloadStatus
import com.bitsycore.cardbrowser.data.download.ImagePrefetcher
import com.bitsycore.cardbrowser.data.repository.CardRepository
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
A download cut off part-way must say so.

The reported symptom: a phone going to sleep during a multi-set download left one set half on the
device, the queue moved on to the next set, and nothing anywhere reported a failure. The set then
sat there looking downloaded and the game could never reach 100%.

The cause was a read, not the fetch. `cards()` handles a mid-fetch failure correctly -- it keeps the
pages that arrived, writes them marked incomplete, and emits a partial snapshot. The download job
took only the card count off that snapshot, found it non-empty, and reported `Completed`.
*/
class InterruptedDownloadTest {

	private val mProviderId = ProviderId("fake")

	private val mSetId = SourceId(mProviderId, "s")

	@Test
	fun `a set cut off part-way is reported failed rather than completed`() = runTest {
		// Four cards, and the source stops answering after the first page of two.
		val vManager = managerFor(failFromPage = 2)

		vManager.enqueue(
			DownloadRequest(
				game = TestGame.id,
				setId = mSetId,
				setName = "Origins",
				kinds = setOf(DownloadKind.CARD_INFO),
				language = CardLanguage.ENGLISH,
			),
		)
		testScheduler.advanceUntilIdle()

		val vStatus = vManager.jobs.value.single().status
		assertTrue(
			vStatus is DownloadStatus.Failed,
			"a half-downloaded set reported $vStatus",
		)
		assertTrue(
			vStatus.reason.contains("2 of 4"),
			"the row should say how far it got, and said: ${vStatus.reason}",
		)
	}

	@Test
	fun `a set that arrives whole is still reported completed`() {
		// The other half of the guard: this must not start failing ordinary downloads.
		runTest {
			val vManager = managerFor(failFromPage = null)

			vManager.enqueue(
				DownloadRequest(
					game = TestGame.id,
					setId = mSetId,
					setName = "Origins",
					kinds = setOf(DownloadKind.CARD_INFO),
					language = CardLanguage.ENGLISH,
				),
			)
			testScheduler.advanceUntilIdle()

			val vStatus = vManager.jobs.value.single().status
			assertTrue(vStatus is DownloadStatus.Completed, "a whole set reported $vStatus")
			assertEquals(4, vStatus.cards)
		}
	}

	// ==================
	// MARK: Harness
	// ==================

	private fun TestScope.managerFor(failFromPage: Int?): DownloadManager {
		val vProvider = HalfwayProvider(mProviderId, failFromPage, (1..4).map { card(it) })
		val vRepository = CardRepository(
			mRegistry = ProviderRegistry(
				providers = listOf(vProvider),
				routes = listOf(ProviderRoute(TestGame.id, vProvider.id)),
			),
			mCache = InMemoryMetadataStore(),
			mSetStore = InMemorySetRecordStore(),
			mClock = { 0L },
		)
		return DownloadManager(
			mRepository = vRepository,
			mImagePrefetcher = object : ImagePrefetcher {
				override suspend fun prefetch(url: String): Boolean = true
			},
			mPreferences = PreferencesStore(
				AppStorage(FakeFileSystem(), "/cache".toPath(), "/prefs".toPath()).also { it.prepare() },
				Json { ignoreUnknownKeys = true },
				Dispatchers.Unconfined,
			),
			mScope = TestScope(testScheduler),
		)
	}

	/** Serves pages until [mFailFromPage], then refuses -- a phone losing the network mid-set. */
	private class HalfwayProvider(
		override val id: ProviderId,
		private val mFailFromPage: Int?,
		private val mCards: List<CardPrinting>,
	) : CardProvider<TestGame> {

		override val displayName = "Halfway"

		override val game: TestGame = TestGame

		override val capabilities = ProviderCapabilities(
			filtering = FilterSupport(remote = emptySet(), localOnly = emptySet()),
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
			maxPageSize = 2,
		)

		override suspend fun listSets(language: CardLanguage?): List<CardSet> = listOf(
			CardSet(SourceId(id, "s"), TestGame.id, "OGN", "Origins", mCards.size, null),
		)

		override suspend fun listCards(request: CardPageRequest): CardPage {
			if (mFailFromPage != null && request.page >= mFailFromPage) throw ProviderError.Offline()
			val vFrom = (request.page - 1) * request.pageSize
			val vItems = mCards.drop(vFrom).take(request.pageSize)
			return CardPage(
				cards = vItems,
				page = request.page,
				pageSize = request.pageSize,
				totalCount = mCards.size,
				hasMore = vFrom + vItems.size < mCards.size,
			)
		}

		override suspend fun cardDetail(id: SourceId, language: CardLanguage?): CardPrinting? =
			mCards.firstOrNull { it.id == id }
	}

	private fun card(number: Int): CardPrinting = CardPrinting(
		id = SourceId(mProviderId, "card-$number"),
		printingKey = null,
		game = TestGame.id,
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
			treatment = ArtworkTreatment.STANDARD,
			language = CardLanguage.ENGLISH,
		),
		attributes = CardAttributes(cost = number),
		classification = CardClassification(type = "Unit", rarity = "Common", domains = emptyList()),
		languages = LanguageCoverage.ENGLISH_ONLY,
		finishes = FinishCoverage(),
	)
}
