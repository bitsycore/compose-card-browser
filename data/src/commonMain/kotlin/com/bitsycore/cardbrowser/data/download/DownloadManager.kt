package com.bitsycore.cardbrowser.data.download

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.data.repository.BulkImportProgress
import com.bitsycore.cardbrowser.data.repository.CardRepository
import com.bitsycore.cardbrowser.data.settings.imageDownloadKey
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.cardbrowser.data.settings.BulkImportRecord
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
 *
 * ## Why full-size art is not in here
 *
 * It used to be, and it was removed on purpose. Bulk-fetching every card's full rendition is the
 * single heaviest thing this app could ask of a CDN it does not own and does not pay for --
 * measured across three providers a full image is roughly four times its thumbnail (63 KB against
 * 19 for TCGdex, 153 against 28 for YGOPRODeck), so downloading a game meant hundreds of megabytes
 * of pictures, almost all of which are never looked at.
 *
 * Full art is fetched **on demand** instead: opening a card loads it and the image cache keeps it,
 * so the art you actually read is on disk and the art you scrolled past never cost anyone a
 * request. Nothing was lost from the offline story that thumbnails do not already cover -- a set
 * with its records and its thumbnails browses completely offline.
 */
enum class DownloadKind {

	/** Card records: names, numbers, rarities, rules text. What makes a set browsable offline. */
	CARD_INFO,

	/**
	 * The small rendition the grid draws.
	 *
	 * The only imagery that is ever bulk-fetched. Measured across three providers a thumbnail is
	 * about a quarter of the full image -- 19 KB against 63 for TCGdex, 28 against 153 for
	 * YGOPRODeck -- so this makes a set browsable offline for a quarter of the bytes, and the
	 * expensive three quarters are left to arrive one card at a time as they are read.
	 */
	GRID_THUMBNAILS,
	;

	/** True for the kind that fetches pictures, as opposed to records. */
	val isImagery: Boolean get() = this == GRID_THUMBNAILS
}

/**
 * A request to put something on disk: one set, or a whole game's records in one file.
 *
 * @property setId the set, or `null` for a whole-game import -- which is not about a set and must
 *   not be matched against one. A row asking "is my set downloading?" compares this, and a
 *   synthetic id would have made every such comparison quietly wrong for one game
 * @property setName what the queue calls this job. The set's name, or the game's for an import
 * @property isWholeGameImport true when this is the source's bulk file rather than a set fetch.
 *   The two are queued together on purpose -- see `DownloadManager` -- because they are the same
 *   thing to the person waiting, however differently they are fetched
 */
data class DownloadRequest(
	val setId: SourceId?,
	val game: GameId,
	val setName: String,
	val kinds: Set<DownloadKind>,
	val language: CardLanguage? = null,
	val isWholeGameImport: Boolean = false,
	/**
	 * Which dump to read, by `BulkSummary.id`, or `null` for the source's cheapest.
	 *
	 * Part of the job's identity, so choosing the every-language file after taking the English
	 * one queues a second import rather than being deduplicated against the first.
	 */
	val bulkVariantId: String? = null,
) {

	init {
		require(kinds.isNotEmpty()) { "A download with nothing to download is not a download" }
		require(setId != null || isWholeGameImport) { "A set download needs a set" }
	}
}

/** Where a job has got to. */
sealed interface DownloadStatus {

	/** Accepted, waiting for the one ahead of it. */
	data object Queued : DownloadStatus

	/**
	 * Running.
	 *
	 * @property completed how many [unit] are done
	 * @property total how many there are, or zero when that is not known yet -- a real "unknown",
	 *   not a zero-length job. The image count cannot be known before the card list lands
	 * @property unit what is being counted. Stated rather than assumed: this used to carry two
	 *   bare numbers that the queue rendered as "images", which was right for a set download and
	 *   wrong for a whole-game import -- so a card-info import reported "544 of 1111 images"
	 *   while writing sets and having fetched no image at all
	 */
	data class Running(
		val completed: Int,
		val total: Int,
		val unit: ProgressUnit = ProgressUnit.IMAGES,
	) : DownloadStatus

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
 * What a running job's two numbers are counting.
 *
 * A whole-game import passes through three phases with nothing in common -- it downloads a file,
 * reads cards out of it, then writes sets -- so one label cannot describe all of them, and the
 * bar restarts at each rather than pretending they are one scale.
 */
enum class ProgressUnit {

	/** Card images, the only thing a per-set download counts once it has its card list. */
	IMAGES,

	/** Kilobytes of a bulk file. Kilobytes rather than bytes so the count fits in an `Int`. */
	KILOBYTES,

	/** Cards read out of a bulk file. */
	CARDS,

	/** Sets written to the cache from a bulk file. */
	SETS,
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


	/**
	 * A whole-game import, as a job in this queue.
	 *
	 * It used to run in the set list's view model, and Navigation 3 scopes a view model to its
	 * back-stack entry -- so going back to the game picker cancelled the import and `ScryfallBulk`
	 * deleted the 74 MB it had already fetched. The queue's scope is the application's, which is
	 * the property this needed all along.
	 *
	 * It is still not paced like the rest: one transfer replacing hundreds of requests is the
	 * opposite of the thing the one-at-a-time rule protects against. Being *in* the queue is about
	 * where the user looks for it, not about how it is fetched -- and it does mean a set fetch and
	 * an import for the same game cannot run at once, which is the right answer anyway since they
	 * would be writing the same records.
	 */
	private suspend fun runWholeGameImport(job: DownloadJob) {
		val vResult = runCatching {
			mRepository.importBulk(job.request.game, job.request.bulkVariantId) { vProgress ->
				// Bytes while downloading, then cards, then sets. Three different units for one
				// bar, which is honest about the three phases having nothing in common: the bar
				// restarts rather than pretending the download and the write are one scale.
				update(job.id) {
					when (vProgress) {
						is BulkImportProgress.Downloading -> DownloadStatus.Running(
							completed = (vProgress.bytes / 1024).toInt(),
							total = ((vProgress.total ?: 0L) / 1024).toInt(),
							unit = ProgressUnit.KILOBYTES,
						)
						is BulkImportProgress.Reading -> DownloadStatus.Running(
							completed = vProgress.cards,
							total = 0,
							unit = ProgressUnit.CARDS,
						)
						is BulkImportProgress.Writing -> DownloadStatus.Running(
							completed = vProgress.sets,
							total = vProgress.total,
							unit = ProgressUnit.SETS,
						)
					}
				}
			}
		}
		val vImport = vResult.getOrNull()
		if (vImport?.variantId != null) {
			// Written here rather than in the repository because it is a statement about what
			// this device has done, which is what preferences hold -- the repository's business
			// is the cards. Without it nothing records that an import happened, and the
			// download-all dialog goes on offering one that would fetch nothing new.
			runCatching {
				mPreferences.update { vPreferences ->
					vPreferences.copy(
						bulkImports = vPreferences.bulkImports + (
							job.request.game.value to BulkImportRecord(
								variantId = vImport.variantId,
								updatedAt = vImport.dumpUpdatedAt?.toString() ?: "-",
							)
						),
					)
				}
			}
		}
		update(job.id) {
			when {
				vResult.isFailure -> DownloadStatus.Failed(
					vResult.exceptionOrNull()?.message ?: "The import did not finish",
				)
				// Null means this game's source publishes no dump. Not a failure of the download,
				// but not a success either -- nothing was fetched, and saying "done" would be a
				// claim that the game is now on disk.
				vImport == null -> DownloadStatus.Failed("This game's source publishes no bulk file")
				else -> DownloadStatus.Completed(
					cards = vImport.cards,
					imagesFetched = 0,
					imagesFailed = 0,
				)
			}
		}
	}

	/** One job, start to finish. */
	private suspend fun run(job: DownloadJob) {
		val vRequest = job.request
		if (vRequest.isWholeGameImport) {
			runWholeGameImport(job)
			return
		}
		val vSetId = vRequest.setId ?: return
		// Marked *before* the fetch, not after it.
		//
		// Writing a record runs a trim, so a set large enough to push the cache over its ceiling
		// could be evicted by the very write that stored it -- and a pin applied afterwards would
		// be protecting something already gone. The marker is independent of the record, so it can
		// be laid down first and simply waits for it.
		val vPinsRecords = DownloadKind.CARD_INFO in vRequest.kinds
		if (vPinsRecords) {
			mRepository.setPinned(vRequest.game, vSetId, vRequest.language, isPinned = true)
		}
		try {
			// 1. The card records. Needed even for an images-only download, because the image URLs
			//    are on them -- but for images-only this is nearly always already a cache hit, so
			//    it costs nothing beyond the read.
			val vCards = mRepository
				.cards(
					setId = vSetId,
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
				// Not a failure. A set with no cards is a real thing a source can hold: a
				// marketplace catalogue files sealed product under a set name and lists no
				// singles for it at all -- 13 of the WoW TCG's 54 sets are exactly that, holding
				// a booster box or a raid deck and nothing else.
				//
				// Reporting it as an error made a whole-game download look broken, with a dozen
				// red rows for sets that had answered perfectly and simply had nothing to give.
				// The distinction the app cares about is "the fetch failed" against "the source
				// has nothing here", and only the first is worth a warning.
				if (vPinsRecords) {
					mRepository.setPinned(vRequest.game, vRequest.setId, vRequest.language, false)
				}
				update(job.id) {
					DownloadStatus.Completed(cards = 0, imagesFetched = 0, imagesFailed = 0)
				}
				return
			}

			if (vRequest.kinds.none { it.isImagery }) {
				update(job.id) {
					DownloadStatus.Completed(cards = vCards.size, imagesFetched = 0, imagesFailed = 0)
				}
				return
			}

			// 2. The thumbnails. Still keyed by kind rather than a bare list, because the record
			//    written afterwards is per rendition and a second one may well come back one day.
			//    A provider with no small rendition (One Piece, Altered) yields an empty list
			//    rather than quietly falling back to the full image, which would both record art
			//    under the heading "thumbnails" and fetch exactly the megabytes this avoids.
			val vByKind: Map<DownloadKind, List<String>> = buildMap {
				if (DownloadKind.GRID_THUMBNAILS in vRequest.kinds) {
					put(
						DownloadKind.GRID_THUMBNAILS,
						vCards.mapNotNull { it.artwork.thumbnailUrl?.ifBlank { null } }.distinct(),
					)
				}
			}
			val vUrls = vByKind.values.flatten()

			var vDone = 0
			var vFailed = 0
			update(job.id) { DownloadStatus.Running(completed = 0, total = vUrls.size) }

			// Per rendition, so what gets recorded is what actually happened to that rendition
			// rather than a share of a combined figure.
			for ((vKind, vKindUrls) in vByKind) {
				var vKindDone = 0
				for (vBatch in vKindUrls.chunked(MAX_CONCURRENT_IMAGES)) {
					currentCoroutineContext().ensureActive()
					coroutineScope {
						val vPending = vBatch.map { vUrl ->
							async { runCatching { mImagePrefetcher.prefetch(vUrl) }.getOrDefault(false) }
						}
						for (vDeferred in vPending) {
							if (vDeferred.await()) {
								vKindDone++
								vDone++
							} else {
								vFailed++
							}
						}
					}
					update(job.id) {
						DownloadStatus.Running(completed = vDone + vFailed, total = vUrls.size)
					}
				}
				// Recorded so the set list can say what came down, across restarts. A record of a
				// download, not a claim that every file is still there -- see `imageDownloads`.
				recordImages(vRequest, vKind, fetched = vKindDone, total = vKindUrls.size)
			}


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
	private suspend fun recordImages(
		request: DownloadRequest,
		kind: DownloadKind,
		fetched: Int,
		total: Int,
	) {
		if (total <= 0) return
		// Only a set download fetches images, so a whole-game import never reaches here with a
		// null set -- but the record is keyed by set id, so there would be nothing to write under.
		val vSetId = request.setId ?: return
		runCatching {
			val vKey = imageDownloadKey(vSetId.qualified, request.language, kind.name)
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

	/**
	 * What makes two requests the same job.
	 *
	 * The language is part of it, and was not. Everything downstream is per language -- a cache key
	 * embeds it, and so does an image download record -- so downloading a set's art in Japanese and
	 * then in French is two different pieces of work. Leaving the language out gave them one id, so
	 * the second silently *replaced* the first in the queue and only one of the two ever ran.
	 *
	 * It only stopped mattering because nothing offered a choice of language until now.
	 */
	private fun idOf(request: DownloadRequest): String = buildString {
		if (request.isWholeGameImport) append("bulk:${request.bulkVariantId ?: "default"}:")
		append(request.setId?.qualified ?: request.game.value)
		append('|')
		append(request.kinds.map { it.name }.sorted().joinToString(","))
		append('|')
		append(request.language?.code ?: "-")
	}

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
