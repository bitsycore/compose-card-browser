package com.bitsycore.cardbrowser.data.download

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.data.repository.CardRepository
import com.bitsycore.cardbrowser.data.settings.imageDownloadKey
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.cardbrowser.data.settings.ImageDownloadRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// ==================
// MARK: What a download is
// ==================

/**
 * What part of a set to fetch.
 *
 * Two separate things because they cost wildly different amounts. A set's card records are one to
 * four small JSON requests; its images are one request *per card* against a CDN, which for a
 * 400-card Magic set is 400 of them and tens of megabytes. Someone who wants a set browsable on a
 * train does not necessarily want to spend that, so it is a choice rather than a bundle.
 */
enum class DownloadKind {

	/** Card records: names, numbers, rarities, rules text. What makes a set browsable offline. */
	CARD_INFO,

	/** Every card's grid thumbnail *and* its full-size art, so the detail screen works offline too. */
	CARD_IMAGES,
}

/** A request to put a set on disk. */
data class DownloadRequest(
	val setId: SourceId,
	val game: GameId,
	val setName: String,
	val kinds: Set<DownloadKind>,
	val language: CardLanguage? = null,
) {

	init {
		require(kinds.isNotEmpty()) { "A download with nothing to download is not a download" }
	}
}

/** Where a job has got to. */
sealed interface DownloadStatus {

	/** Accepted, waiting for the one ahead of it. */
	data object Queued : DownloadStatus

	/**
	 * Running.
	 *
	 * @property completed units finished, where a unit is a card record batch or one image
	 * @property total units known so far. Zero until the card list arrives, because the image
	 *   count is not knowable before then -- this is a real "unknown", not a zero-length job
	 */
	data class Running(val completed: Int, val total: Int) : DownloadStatus

	/**
	 * Finished.
	 *
	 * @property imagesFailed images that did not come down. Reported rather than swallowed: a set
	 *   that is 98% cached is genuinely different from one that is complete, and the user should
	 *   not discover the gap on a train
	 */
	data class Completed(val cards: Int, val imagesFetched: Int, val imagesFailed: Int) : DownloadStatus

	/** Gave up. Only a failure that stopped the whole job lands here. */
	data class Failed(val reason: String) : DownloadStatus

	/** Cancelled by the user. Whatever had already been written stays written. */
	data object Cancelled : DownloadStatus
}

/**
 * One queued or finished download.
 *
 * @property id stable for the life of the job, so the UI can key a list on it
 */
data class DownloadJob(
	val id: String,
	val request: DownloadRequest,
	val status: DownloadStatus = DownloadStatus.Queued,
) {

	val isActive: Boolean
		get() = status is DownloadStatus.Queued || status is DownloadStatus.Running

	/** 0f..1f, or `null` when the total is not known yet. */
	val progress: Float?
		get() = when (val vStatus = status) {
			is DownloadStatus.Running ->
				if (vStatus.total <= 0) null else vStatus.completed.toFloat() / vStatus.total
			is DownloadStatus.Completed -> 1f
			else -> null
		}
}

// ==================
// MARK: Fetching images
// ==================

/**
 * Pulls one image into whatever cache the app draws from.
 *
 * An interface because the image cache is Coil's, and Coil lives in the UI module -- `:data` has no
 * Compose in it and is not about to gain any for this. The app binds the real one; a test binds a
 * counter.
 */
interface ImagePrefetcher {

	/** Fetches [url] into the disk cache. Returns false if it could not, without throwing. */
	suspend fun prefetch(url: String): Boolean
}

// ==================
// MARK: The manager
// ==================

/**
 * A queue for putting sets on disk deliberately, rather than as a side effect of browsing.
 *
 * ## One job at a time, on purpose
 *
 * Every provider here is a free, often volunteer-run API, and several ask callers to pace
 * themselves -- Scryfall wants 50-100 ms between requests and returns 429 when pushed, as this
 * project's own live tests demonstrate. The provider HTTP clients already pace *within* a host, but
 * that guarantee is per client and says nothing about how many jobs are running.
 *
 * So jobs run strictly one after another. Downloading three sets does not triple the request rate;
 * it takes three times as long, which is the correct trade for someone else's server. Images inside
 * a single job go [MAX_CONCURRENT_IMAGES] at a time, because those hit a CDN rather than the API and
 * a CDN is what CDNs are for.
 *
 * ## The queue does not survive a restart
 *
 * Deliberate, and a contained decision. What the queue *produces* is durable -- card records go to
 * the metadata cache and images to the image cache, both of which outlive the process -- so a
 * download interrupted half way leaves half a set genuinely on disk and resuming it re-fetches only
 * what is missing. Persisting the pending list as well would mean a disk write per progress tick to
 * answer a question ("what was I downloading when I force-quit?") nobody has asked yet. Swapping
 * this for a persisted queue touches this class and nothing else.
 *
 * @param mScope the scope the worker runs in. Application-lifetime: a download should survive the
 *   screen that started it being closed
 */
class DownloadManager(
	private val mRepository: CardRepository,
	private val mImagePrefetcher: ImagePrefetcher,
	private val mPreferences: PreferencesStore,
	private val mScope: CoroutineScope,
) {

	private val mJobs = MutableStateFlow<List<DownloadJob>>(emptyList())

	/** Every job this session, newest last. Finished ones stay until cleared. */
	val jobs: StateFlow<List<DownloadJob>> = mJobs.asStateFlow()

	/** Guards the decision to start the worker, so two enqueues cannot start two of them. */
	private val mLock = Mutex()

	private var mWorker: Job? = null

	/** The job the worker is on, so cancelling that one interrupts rather than just dequeuing it. */
	private var mRunningId: String? = null

	private var mRunningWork: Job? = null

	// ============
	//  Queueing

	/**
	 * Adds a download, or returns the existing one when the same set and kinds are already queued.
	 *
	 * De-duplicated because the button is on a row a user can tap twice, and two identical jobs
	 * would fetch everything twice for no benefit.
	 */
	fun enqueue(request: DownloadRequest): String {
		val vId = idOf(request)
		var vAdded = false
		mJobs.update { vCurrent ->
			if (vCurrent.any { it.id == vId && it.isActive }) {
				vCurrent
			} else {
				vAdded = true
				// A finished job for the same set is replaced rather than appended, so re-running a
				// download does not grow the list with duplicates of one row.
				vCurrent.filterNot { it.id == vId } + DownloadJob(id = vId, request = request)
			}
		}
		if (vAdded) startWorkerIfIdle()
		return vId
	}

	/** Cancels a job, whether it is waiting or running. Anything already written stays. */
	fun cancel(jobId: String) {
		mJobs.update { vCurrent ->
			vCurrent.map {
				if (it.id == jobId && it.isActive) it.copy(status = DownloadStatus.Cancelled) else it
			}
		}
		if (mRunningId == jobId) mRunningWork?.cancel()
	}

	/** Cancels everything outstanding. */
	fun cancelAll() {
		mJobs.update { vCurrent ->
			vCurrent.map { if (it.isActive) it.copy(status = DownloadStatus.Cancelled) else it }
		}
		mRunningWork?.cancel()
	}

	/** Drops finished rows from the list. Running ones are left alone. */
	fun clearFinished() {
		mJobs.update { vCurrent -> vCurrent.filter { it.isActive } }
	}

	// ============
	//  The worker

	private fun startWorkerIfIdle() {
		mScope.launch {
			mLock.withLock {
				if (mWorker?.isActive == true) return@withLock
				mWorker = mScope.launch { drain() }
			}
		}
	}

	/** Runs queued jobs until none is left. One at a time -- see the class doc. */
	private suspend fun drain() {
		while (true) {
			val vNext = mJobs.value.firstOrNull { it.status is DownloadStatus.Queued } ?: return
			mRunningId = vNext.id
			update(vNext.id) { DownloadStatus.Running(completed = 0, total = 0) }

			val vWork = mScope.launch { run(vNext) }
			mRunningWork = vWork
			vWork.join()
			mRunningWork = null
			mRunningId = null

			// A cancelled job has already had its status set by `cancel`; leave it.
			if (mJobs.value.firstOrNull { it.id == vNext.id }?.status is DownloadStatus.Running) {
				update(vNext.id) { DownloadStatus.Failed("Stopped unexpectedly") }
			}
		}
	}

	/** One job, start to finish. */
	private suspend fun run(job: DownloadJob) {
		val vRequest = job.request
		try {
			// 1. The card records. Needed even for an images-only download, because the image URLs
			//    are on them -- but for images-only this is nearly always already a cache hit, so
			//    it costs nothing beyond the read.
			val vCards = mRepository
				.cards(
					setId = vRequest.setId,
					game = vRequest.game,
					query = CardQuery(),
					language = vRequest.language,
				)
				.toList()
				.lastOrNull()
				?.value
				?.cards
				.orEmpty()

			currentCoroutineContext().ensureActive()

			if (vCards.isEmpty()) {
				update(job.id) { DownloadStatus.Failed("No cards came back for this set") }
				return
			}

			if (DownloadKind.CARD_IMAGES !in vRequest.kinds) {
				update(job.id) {
					DownloadStatus.Completed(cards = vCards.size, imagesFetched = 0, imagesFailed = 0)
				}
				return
			}

			// 2. The art. Both variants, because a set that is browsable offline but cannot open a
			//    card is only half downloaded.
			val vUrls = vCards
				.flatMap { vCard ->
					listOfNotNull(
						vCard.artwork.thumbnailUrl,
						vCard.artwork.displayUrl ?: vCard.artwork.imageUrl.ifBlank { null },
					)
				}
				.filter { it.isNotBlank() }
				.distinct()

			var vDone = 0
			var vFailed = 0
			update(job.id) { DownloadStatus.Running(completed = 0, total = vUrls.size) }

			for (vBatch in vUrls.chunked(MAX_CONCURRENT_IMAGES)) {
				currentCoroutineContext().ensureActive()
				coroutineScope {
					val vPending = vBatch.map { vUrl ->
						async { runCatching { mImagePrefetcher.prefetch(vUrl) }.getOrDefault(false) }
					}
					for (vDeferred in vPending) {
						if (vDeferred.await()) vDone++ else vFailed++
					}
				}
				update(job.id) { DownloadStatus.Running(completed = vDone + vFailed, total = vUrls.size) }
			}

			// Recorded so the set list can say what came down, across restarts. A record of a
			// download, not a claim that every file is still there -- see `imageDownloads`.
			recordImages(vRequest, fetched = vDone, total = vUrls.size)

			update(job.id) {
				DownloadStatus.Completed(
					cards = vCards.size,
					imagesFetched = vDone,
					imagesFailed = vFailed,
				)
			}
		} catch (vError: CancellationException) {
			// `cancel` has already marked it; rethrow so the scope unwinds properly.
			throw vError
		} catch (vError: ProviderError) {
			update(job.id) { DownloadStatus.Failed(vError.message ?: "The provider refused") }
		} catch (vError: Exception) {
			update(job.id) { DownloadStatus.Failed(vError.message ?: "Download failed") }
		}
	}

	/**
	 * Stores what an image download fetched.
	 *
	 * Written even when some failed, because a partial result is exactly what the set list wants to
	 * be able to show honestly. Failures here are swallowed: a download that worked should not be
	 * reported as failed because a preferences write did not.
	 */
	private suspend fun recordImages(request: DownloadRequest, fetched: Int, total: Int) {
		if (total <= 0) return
		runCatching {
			val vKey = imageDownloadKey(request.setId.qualified, request.language)
			mPreferences.update { vPreferences ->
				vPreferences.copy(
					imageDownloads = vPreferences.imageDownloads +
						(vKey to ImageDownloadRecord(fetched = fetched, total = total)),
				)
			}
		}
	}

	/** Rewrites one job's status, leaving a job the user cancelled meanwhile alone. */
	private fun update(jobId: String, next: (DownloadJob) -> DownloadStatus) {
		mJobs.update { vCurrent ->
			vCurrent.map { vJob ->
				when {
					vJob.id != jobId -> vJob
					vJob.status is DownloadStatus.Cancelled -> vJob
					else -> vJob.copy(status = next(vJob))
				}
			}
		}
	}

	private fun idOf(request: DownloadRequest): String =
		request.setId.qualified + "|" + request.kinds.map { it.name }.sorted().joinToString(",")

	companion object {

		/**
		 * How many images may be in flight within one job.
		 *
		 * These go to a CDN rather than to the provider's API, so the polite ceiling is far higher
		 * than for card data -- but not unbounded: four matches what the repository already uses
		 * for pages, and a phone on cellular does not benefit from forty open sockets.
		 */
		const val MAX_CONCURRENT_IMAGES: Int = 4
	}
}
