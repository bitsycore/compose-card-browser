package com.bitsycore.toploader.ui

import com.bitsycore.toploader.core.model.CardLanguage
import com.bitsycore.toploader.core.model.CardSet
import com.bitsycore.toploader.core.model.ProviderId
import com.bitsycore.toploader.core.model.SourceId
import com.bitsycore.toploader.data.cache.AppStorage
import com.bitsycore.toploader.data.cache.InMemoryMetadataStore
import com.bitsycore.toploader.data.cache.InMemorySetRecordStore
import com.bitsycore.toploader.data.download.DownloadKind
import com.bitsycore.toploader.data.download.DownloadManager
import com.bitsycore.toploader.data.download.ImagePrefetcher
import com.bitsycore.toploader.data.net.HttpClientFactory
import com.bitsycore.toploader.data.repository.CardRepository
import com.bitsycore.toploader.data.settings.PreferencesStore
import com.bitsycore.toploader.di.providerRoutes
import com.bitsycore.toploader.core.provider.ProviderRegistry
import com.bitsycore.toploader.games.riftbound.RiftboundGame
import com.bitsycore.toploader.providers.riftcodex.RiftcodexProvider
import com.bitsycore.toploader.ui.screen.sets.SetListArgs
import com.bitsycore.toploader.ui.screen.sets.SetListContract
import com.bitsycore.toploader.ui.screen.sets.SetListViewModel
import com.bitsycore.toploader.data.repository.SetFactsWarmer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
The rules that turn "download this set" into queue jobs.

They were a lambda in `SetListScreen`'s composition until 2026-09-13 -- a page of domain logic that
no test could reach and no preview could exercise, which is exactly why it had none. It is in the
view model now, so this is the first time any of it has been asserted.
*/
@OptIn(ExperimentalCoroutinesApi::class)
class SetListDownloadTest {

	@BeforeTest
	fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

	@AfterTest
	fun tearDown() = Dispatchers.resetMain()

	@Test
	fun `records and pictures are split, and records go in every language asked for`() = runTest {
		val vModel = model()

		vModel.dispatch(
			SetListContract.Intent.DownloadRequested(
				set = set(languages = setOf(CardLanguage.ENGLISH, CardLanguage.FRENCH)),
				kinds = setOf(DownloadKind.CARD_INFO, DownloadKind.GRID_THUMBNAILS),
				infoLanguages = setOf(CardLanguage.ENGLISH, CardLanguage.FRENCH),
				artLanguages = setOf(CardLanguage.ENGLISH),
			),
		)
		testScheduler.advanceUntilIdle()

		val vJobs = vModel.downloads()
		// Two record jobs, one per language; one picture job, only where it was asked for.
		assertEquals(
			listOf(CardLanguage.ENGLISH, CardLanguage.FRENCH),
			vJobs.filter { DownloadKind.CARD_INFO in it.request.kinds }
				.mapNotNull { it.request.language }
				.sortedBy { it.code },
		)
		assertEquals(
			listOf(CardLanguage.ENGLISH),
			vJobs.filter { DownloadKind.GRID_THUMBNAILS in it.request.kinds }
				.mapNotNull { it.request.language },
		)
		assertTrue(
			vJobs.none { it.request.kinds.size > 1 },
			"records and pictures must be separate jobs, not one job carrying both",
		)
	}

	@Test
	fun `pictures are not queued in a language the set does not state`() = runTest {
		// A request per card per language is expensive, and a language the set has no cards in
		// would spend it on nothing.
		val vModel = model()

		vModel.dispatch(
			SetListContract.Intent.DownloadRequested(
				set = set(languages = setOf(CardLanguage.ENGLISH)),
				kinds = setOf(DownloadKind.GRID_THUMBNAILS),
				infoLanguages = emptySet(),
				artLanguages = setOf(CardLanguage.ENGLISH, CardLanguage.JAPANESE),
			),
		)
		testScheduler.advanceUntilIdle()

		assertEquals(
			listOf(CardLanguage.ENGLISH),
			vModel.downloads().mapNotNull { it.request.language },
		)
	}

	@Test
	fun `a job is labelled with the language the source will really answer in`() = runTest {
		// Riftcodex serves English whatever the reader prefers. A job labelled "French" for records
		// that come back English is a screen lying about what it is fetching -- the repository
		// normalises before it builds a key, so the file was always right and only the label was
		// wrong.
		val vModel = model(preferred = CardLanguage.FRENCH)

		vModel.dispatch(
			SetListContract.Intent.DownloadRequested(
				set = set(languages = emptySet()),
				kinds = setOf(DownloadKind.CARD_INFO),
				infoLanguages = emptySet(),
				artLanguages = emptySet(),
			),
		)
		testScheduler.advanceUntilIdle()

		assertEquals(
			listOf(CardLanguage.ENGLISH),
			vModel.downloads().mapNotNull { it.request.language },
			"the preference was French and the source answers only English",
		)
	}

	@Test
	fun `the queue arrives as state rather than being read from Koin`() = runTest {
		// What makes `SetListContent` previewable: it is a function of its state and nothing else.
		val vModel = model()
		testScheduler.advanceUntilIdle()
		assertTrue(vModel.stateFlow.value.downloads.isEmpty())

		vModel.dispatch(
			SetListContract.Intent.DownloadRequested(
				set = set(languages = setOf(CardLanguage.ENGLISH)),
				kinds = setOf(DownloadKind.CARD_INFO),
				infoLanguages = setOf(CardLanguage.ENGLISH),
				artLanguages = emptySet(),
			),
		)
		testScheduler.advanceUntilIdle()

		assertTrue(
			vModel.stateFlow.value.downloads.isNotEmpty(),
			"the queue must reach the screen through the state",
		)
	}

	// ==================
	// MARK: Harness
	// ==================

	private fun SetListViewModel.downloads() = stateFlow.value.downloads

	private fun set(languages: Set<CardLanguage>) = CardSet(
		id = SourceId(ProviderId("riftcodex"), "OGN"),
		game = RiftboundGame.id,
		code = "OGN",
		name = "Origins",
		cardCount = 352,
		releaseDate = null,
		languages = languages,
	)

	private suspend fun model(preferred: CardLanguage = CardLanguage.ENGLISH): SetListViewModel {
		val vClient = HttpClientFactory.create()
		val vRegistry = ProviderRegistry(
			providers = listOf(RiftcodexProvider(vClient)),
			routes = providerRoutes.filter { it.game == RiftboundGame.id },
		)
		val vPreferences = PreferencesStore(
			AppStorage(FakeFileSystem(), "/cache".toPath(), "/prefs".toPath()).also { it.prepare() },
			Json { ignoreUnknownKeys = true },
			Dispatchers.Unconfined,
		)
		vPreferences.update { it.copy(preferredLanguages = listOf(preferred)) }

		val vRepository = CardRepository(
			mRegistry = vRegistry,
			mCache = InMemoryMetadataStore(),
			mSetStore = InMemorySetRecordStore(),
			mClock = { 0L },
		)
		// Its own scope, never advanced: these tests assert what was *queued*, not what it fetched.
		// Nothing here reaches the network.
		val vDownloads = DownloadManager(
			mRepository = vRepository,
			mImagePrefetcher = object : ImagePrefetcher {
				override suspend fun prefetch(url: String): Boolean = true
			},
			mPreferences = vPreferences,
			mScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
		)
		return SetListViewModel(
			mRepository = vRepository,
			mPreferences = vPreferences,
			mRegistry = vRegistry,
			mDownloads = vDownloads,
			mWarmer = SetFactsWarmer(vRepository),
			mArgs = SetListArgs(RiftboundGame.id),
		)
	}
}
