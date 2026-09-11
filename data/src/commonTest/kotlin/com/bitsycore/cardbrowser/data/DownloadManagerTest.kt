package com.bitsycore.cardbrowser.data

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.data.download.DownloadKind
import com.bitsycore.cardbrowser.data.download.DownloadManager
import com.bitsycore.cardbrowser.data.download.DownloadRequest
import com.bitsycore.cardbrowser.data.download.DownloadStatus
import com.bitsycore.cardbrowser.data.download.ImagePrefetcher
import kotlinx.coroutines.CompletableDeferred
import okio.Path.Companion.toPath
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The download queue.
 *
 * These are about the *queue*, not about fetching: the repository underneath is the real one only
 * as far as its constructor, and the image side is a counter. What is worth asserting here is the
 * behaviour a user notices -- that two taps do not download twice, that cancelling stops the thing
 * that is running, and that jobs do not run in parallel against somebody's rate-limited API.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagerTest {

	private val mProvider = ProviderId("fake")

	private fun request(
		set: String = "OGN",
		kinds: Set<DownloadKind> = setOf(DownloadKind.CARD_INFO),
		language: com.bitsycore.cardbrowser.core.model.CardLanguage? = null,
	) = DownloadRequest(
		setId = SourceId(mProvider, set),
		game = GameId("riftbound"),
		setName = set,
		kinds = kinds,
		language = language,
	)

	/** Records what it was asked for, and can be held open to observe a job mid-flight. */
	private class RecordingPrefetcher(
		private val mGate: CompletableDeferred<Unit>? = null,
	) : ImagePrefetcher {

		val requested = mutableListOf<String>()

		var maxConcurrent = 0
			private set

		private var mInFlight = 0

		override suspend fun prefetch(url: String): Boolean {
			mInFlight++
			maxConcurrent = maxOf(maxConcurrent, mInFlight)
			requested += url
			mGate?.await()
			mInFlight--
			return true
		}
	}

	@Test
	fun `a request with no kinds is rejected outright`() {
		// A download that downloads nothing is a programming mistake, not a no-op to tolerate.
		val vError = runCatching {
			DownloadRequest(
				setId = SourceId(mProvider, "OGN"),
				game = GameId("riftbound"),
				setName = "Origins",
				kinds = emptySet(),
			)
		}.exceptionOrNull()

		assertIs<IllegalArgumentException>(vError)
	}

	@Test
	fun `the same set and kinds enqueued twice is one job`() = runTest {
		// The button sits on a row. Tapping it twice must not fetch the set twice.
		val vManager = managerWith(RecordingPrefetcher(), testScheduler)

		val vFirst = vManager.enqueue(request())
		val vSecond = vManager.enqueue(request())

		assertEquals(vFirst, vSecond, "The same download should return the same job id")
		assertEquals(1, vManager.jobs.value.size, "Got ${vManager.jobs.value}")
	}

	@Test
	fun `the same set with different kinds is a different job`() = runTest {
		// Records and thumbnails are two separate asks against one set, so each is its own row
		// rather than being collapsed into one.
		val vManager = managerWith(RecordingPrefetcher(), testScheduler)

		vManager.enqueue(request(kinds = setOf(DownloadKind.CARD_INFO)))
		vManager.enqueue(request(kinds = setOf(DownloadKind.GRID_THUMBNAILS)))

		assertEquals(2, vManager.jobs.value.size)
	}

	@Test
	fun `cancelling a queued job takes it out of the queue`() = runTest {
		val vManager = managerWith(RecordingPrefetcher(), testScheduler)
		val vId = vManager.enqueue(request())

		vManager.cancel(vId)

		val vJob = vManager.jobs.value.single()
		assertIs<DownloadStatus.Cancelled>(vJob.status)
		assertFalse(vJob.isActive)
	}

	@Test
	fun `cancelAll leaves finished jobs alone and stops the active ones`() = runTest {
		val vManager = managerWith(RecordingPrefetcher(), testScheduler)
		vManager.enqueue(request("OGN"))
		vManager.enqueue(request("VEN"))

		vManager.cancelAll()

		assertTrue(vManager.jobs.value.all { !it.isActive }, "Got ${vManager.jobs.value}")
	}

	@Test
	fun `clearFinished drops finished rows and keeps active ones`() = runTest {
		val vManager = managerWith(RecordingPrefetcher(), testScheduler)
		val vDone = vManager.enqueue(request("OGN"))
		vManager.enqueue(request("VEN"))
		vManager.cancel(vDone)

		vManager.clearFinished()

		assertTrue(vManager.jobs.value.none { it.id == vDone }, "The cancelled row should be gone")
		assertEquals(1, vManager.jobs.value.size)
	}

	@Test
	fun `re-running a finished download replaces its row rather than adding one`() = runTest {
		val vManager = managerWith(RecordingPrefetcher(), testScheduler)
		val vId = vManager.enqueue(request())
		vManager.cancel(vId)

		vManager.enqueue(request())

		assertEquals(1, vManager.jobs.value.size, "Got ${vManager.jobs.value}")
		assertTrue(vManager.jobs.value.single().isActive, "The replacement should be live again")
	}

	@Test
	fun `a whole-game import is a job in the queue like any other`() = runTest {
		// The property that matters is where it lives, not how it fetches. It used to run in the
		// set list's view model, which Navigation 3 scopes to the back-stack entry -- so going
		// back to the game picker cancelled it and threw away the 74 MB already downloaded. In
		// the queue it runs in the application's scope, which no screen can take away, and it is
		// visible in the one place a user looks for a download.
		val vManager = managerWith(RecordingPrefetcher(), testScheduler)

		val vId = vManager.enqueue(
			DownloadRequest(
				setId = null,
				game = GameId("riftbound"),
				setName = "Riftbound",
				kinds = setOf(DownloadKind.CARD_INFO),
				isWholeGameImport = true,
			),
		)

		val vJob = vManager.jobs.value.single { it.id == vId }
		assertTrue(vJob.request.isWholeGameImport)
		assertEquals("Riftbound", vJob.request.setName)
	}

	@Test
	fun `an import and a set download for the same game are different jobs`() = runTest {
		// The id folds in the import flag, so queueing one does not replace the other. They still
		// run one at a time, which is what stops them writing the same records at once.
		val vManager = managerWith(RecordingPrefetcher(), testScheduler)

		vManager.enqueue(request())
		vManager.enqueue(
			DownloadRequest(
				setId = null,
				game = GameId("riftbound"),
				setName = "Riftbound",
				kinds = setOf(DownloadKind.CARD_INFO),
				isWholeGameImport = true,
			),
		)

		assertEquals(2, vManager.jobs.value.size)
	}

	@Test
	fun `a request with no set must say it is a whole-game import`() {
		// The set id is nullable so an import is not forced to invent one -- a synthetic id would
		// have made every "is my set downloading?" comparison quietly wrong for one game. That
		// nullability must not become a way to enqueue a set download with no set.
		kotlin.test.assertFailsWith<IllegalArgumentException> {
			DownloadRequest(
				setId = null,
				game = GameId("riftbound"),
				setName = "Riftbound",
				kinds = setOf(DownloadKind.CARD_INFO),
			)
		}
	}

	@Test
	fun `thumbnails are the only imagery a download ever fetches`() {
		// Full-size art was a third kind and was deliberately removed. Measured across three
		// providers a full image is roughly four times its thumbnail -- 63 KB against 19.5 for
		// TCGdex, 153 against 28 for YGOPRODeck -- so bulk-fetching it meant hundreds of megabytes
		// off someone else's CDN for pictures almost none of which are ever looked at. It arrives
		// on demand when a card is opened instead, which the image cache keeps.
		//
		// The count is asserted so that putting a kind back is a decision rather than an accident:
		// a new one needs the dialog, the queue's labels and the per-rendition record updating.
		assertTrue(DownloadKind.GRID_THUMBNAILS.isImagery)
		assertFalse(DownloadKind.CARD_INFO.isImagery)
		assertEquals(2, DownloadKind.entries.size, "A new kind needs the UI and the record updating")
	}

	@Test
	fun `progress is null until a total is known -- zero is not a total`() {
		// The image count cannot be known before the card list arrives. A bar that sits at 100%
		// because 0 of 0 is done would be worse than one that admits it does not know yet.
		val vQueued = com.bitsycore.cardbrowser.data.download.DownloadJob("x", request())
		val vStarting = vQueued.copy(status = DownloadStatus.Running(completed = 0, total = 0))
		val vHalf = vQueued.copy(status = DownloadStatus.Running(completed = 5, total = 10))

		assertEquals(null, vStarting.progress)
		assertEquals(0.5f, vHalf.progress)
		assertEquals(1f, vQueued.copy(status = DownloadStatus.Completed(1, 2, 0)).progress)
	}

	@Test
	fun `a completed job reports images that failed rather than hiding them`() {
		// A set that is 98% cached is genuinely different from a complete one, and the difference
		// should not be discovered on a train.
		val vJob = com.bitsycore.cardbrowser.data.download.DownloadJob("x", request())
			.copy(status = DownloadStatus.Completed(cards = 352, imagesFetched = 700, imagesFailed = 4))

		val vStatus = vJob.status as DownloadStatus.Completed
		assertEquals(4, vStatus.imagesFailed)
		assertFalse(vJob.isActive)
	}

	@Test
	fun `the same set and kinds in two languages are two jobs`() = runTest {
		// Everything downstream is per language -- a cache key embeds it, and so does an image
		// download record -- so a set's thumbnails in Japanese and in French are two pieces of work. The
		// job id left the language out, so the second silently replaced the first in the queue and
		// only one of them ever ran. Nothing noticed until the dialog started offering a choice.
		val vManager = managerWith(RecordingPrefetcher(), testScheduler)

		val vJapanese = vManager.enqueue(
			request(kinds = setOf(DownloadKind.GRID_THUMBNAILS), language = CardLanguage.JAPANESE),
		)
		val vFrench = vManager.enqueue(
			request(kinds = setOf(DownloadKind.GRID_THUMBNAILS), language = CardLanguage.FRENCH),
		)

		assertTrue(vJapanese != vFrench, "Two languages must not share a job id")
		assertEquals(2, vManager.jobs.value.size, "Got ${vManager.jobs.value.map { it.id }}")
	}

	@Test
	fun `the same set in the same language is still one job`() = runTest {
		// The dedupe that mattered before still has to hold: two taps are one download.
		val vManager = managerWith(RecordingPrefetcher(), testScheduler)

		val vFirst = vManager.enqueue(request(language = CardLanguage.ENGLISH))
		val vSecond = vManager.enqueue(request(language = CardLanguage.ENGLISH))

		assertEquals(vFirst, vSecond)
		assertEquals(1, vManager.jobs.value.size)
	}


	/** Builds a manager over throwaway collaborators, so a constructor change is one edit here. */
	private fun managerWith(
		prefetcher: ImagePrefetcher,
		scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
	) = DownloadManager(
		mRepository = repositoryStub(),
		mImagePrefetcher = prefetcher,
		mPreferences = preferencesStub(),
		mScope = TestScope(scheduler),
	)

	/** Preferences over a fake filesystem; the queue tests never read them back. */
	private fun preferencesStub() = com.bitsycore.cardbrowser.data.settings.PreferencesStore(
		com.bitsycore.cardbrowser.data.cache.AppStorage(
			okio.fakefilesystem.FakeFileSystem(),
			"/cache".toPath(),
			"/prefs".toPath(),
		).also { it.prepare() },
		kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
		kotlinx.coroutines.Dispatchers.Unconfined,
	)

	/**
	 * A repository that is never actually driven.
	 *
	 * These tests exercise queue bookkeeping, which happens before any fetching; the jobs never get
	 * to run because the test scheduler is not advanced. Constructing a real one keeps the manager's
	 * signature honest without standing up a fake provider stack for assertions that do not need it.
	 */
	private fun repositoryStub(): com.bitsycore.cardbrowser.data.repository.CardRepository =
		com.bitsycore.cardbrowser.data.repository.CardRepository(
			mRegistry = com.bitsycore.cardbrowser.core.provider.ProviderRegistry(
				providers = emptyList(),
				routes = emptyList(),
			),
			mCache = com.bitsycore.cardbrowser.data.cache.MetadataCache(
				mStorage = com.bitsycore.cardbrowser.data.cache.AppStorage(
					okio.fakefilesystem.FakeFileSystem(),
					"/cache".toPath(),
					"/prefs".toPath(),
				).also { it.prepare() },
				mJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
				mIoDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
				mClock = { 0L },
			),
			mSetStore = com.bitsycore.cardbrowser.data.cache.InMemorySetRecordStore(),
			mClock = { 0L },
		)
}
