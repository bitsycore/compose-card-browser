package com.bitsycore.cardbrowser.data.cache

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okio.IOException
import okio.Path

/**
 * What the caches are using, and how to empty them.
 *
 * Backs the settings screen's cache section. Metadata and images are reported and cleared
 * separately because they behave differently: metadata is small, cheap to refetch and what makes
 * offline browsing work, while images are most of the bytes and the first thing worth dropping.
 *
 * Preferences are never touched by anything here. They live under a different root, which is what
 * makes "clear cache" a safe button rather than one that also forgets which set you were reading.
 */
class CacheManager(
	private val mStorage: AppStorage,
	private val mMetadataCache: MetadataCache,
	/**
	 * Where the bytes actually are.
	 *
	 * The two halves are reported as one "card data" figure because that is what a user has: they
	 * did not choose to put set lists in one place and sets in another. The split is this app's,
	 * and the storage screen should not make it the user's problem.
	 */
	private val mSetStore: SetRecordStore,
	private val mIoDispatcher: CoroutineDispatcher,
	private val mMetadataLimitBytes: () -> Long = { MetadataCache.DEFAULT_MAX_BYTES },
	private val mImageCacheMaxBytes: () -> Long = { DEFAULT_IMAGE_CACHE_MAX_BYTES },
) {

	/**
	 * Current usage of both caches, their ceilings, and what is pinned -- in one pass over each.
	 *
	 * One call because the storage screen needs all of it at once and the two halves are both
	 * directory walks. They run concurrently: the image cache after a full browse of Magic is
	 * thousands of files and has nothing to do with the metadata directory, so waiting for one
	 * before starting the other was pure latency.
	 */
	suspend fun report(): StorageReport = withContext(mIoDispatcher) {
		coroutineScope {
			// The image directory is the only walk left, and it is the expensive one -- thousands
			// of files after a full browse of Magic. Started first so the two counts overlap.
			val vImages = async { directorySize(mStorage.imageCacheDir) }
			val vMetadata = mMetadataCache.snapshot()
			// Counts, not a directory walk. This is what the migration bought the storage screen:
			// measured on 2026-09-11, the same figures took 2456 ms out of the file cache and
			// 14.4 ms out of the store.
			val vSets = mSetStore.snapshot()
			StorageReport(
				usage = CacheUsage(
					metadataBytes = vMetadata.totalBytes + vSets.unpinnedBytes + vSets.pinnedBytes,
					metadataEntries = vMetadata.entryCount + vSets.sets,
					metadataLimitBytes = mMetadataLimitBytes(),
					// Split, because the limit governs only one of the two. Downloaded sets sit
					// outside it: the ceiling cannot reclaim them, so counting them against it was
					// a number that could only ever be exceeded. See `SqlCardStore.trim`.
					metadataKeptBytes = vSets.pinnedBytes,
					imageBytes = vImages.await(),
					imageLimitBytes = mImageCacheMaxBytes(),
				),
			)
		}
	}

	/** Current usage of both caches, and their ceilings. */
	suspend fun usage(): CacheUsage = report().usage

	/** Empties every card record, downloads included. Preferences are untouched. */
	suspend fun clearMetadata() {
		mMetadataCache.clear()
		mSetStore.clear()
	}

	/**
	 * Empties only the part of the metadata cache that browsing filled.
	 *
	 * Downloaded sets and imported catalogues stay. They are not cache in the sense the word is
	 * usually meant -- nothing evicts them and nothing re-fetches them by itself -- so sweeping
	 * them away under a button labelled "clear cached data" would throw away a twenty-minute
	 * import on a tap meant to reclaim a few megabytes.
	 */
	suspend fun clearBrowsingMetadata(): Int {
		// Everything in the metadata cache is browsing data now -- nothing in it is ever kept on
		// purpose -- and the sets that *are* kept are rows with a `pinned` flag rather than files
		// that had to be told apart from their neighbours.
		val vSnapshot = mMetadataCache.snapshot()
		mMetadataCache.clear()
		return vSnapshot.entryCount + mSetStore.trim(ceilingBytes = 0L)
	}

	/**
	 * Empties the image cache directory.
	 *
	 * Deletes the files rather than going through the image loader, so this works whether or not a
	 * loader has been created yet and cannot deadlock against one that is mid-write. Coil treats a
	 * missing file as a miss, so the worst outcome is a re-download.
	 */
	suspend fun clearImages() {
		withContext(mIoDispatcher) {
			try {
				if (!mStorage.fileSystem.exists(mStorage.imageCacheDir)) return@withContext
				mStorage.fileSystem.listRecursively(mStorage.imageCacheDir)
					.toList()
					.sortedByDescending { it.segments.size }
					.forEach { vPath ->
						try {
							mStorage.fileSystem.delete(vPath, mustExist = false)
						} catch (vIo: IOException) {
							// Skip whatever is locked; the rest still goes.
						}
					}
				mStorage.fileSystem.createDirectories(mStorage.imageCacheDir)
			} catch (vIo: IOException) {
				// A cache that will not clear is not worth crashing over.
			}
		}
	}

	/**
	 * Evicts metadata down to the current ceiling.
	 *
	 * Called when the ceiling is lowered, so the reported usage matches the limit immediately rather
	 * than drifting under it over the next few writes.
	 */
	suspend fun trimMetadata() {
		mMetadataCache.trim()
		mSetStore.trim(mMetadataLimitBytes())
	}

	private fun directorySize(directory: Path): Long = try {
		if (!mStorage.fileSystem.exists(directory)) {
			0L
		} else {
			mStorage.fileSystem.listRecursively(directory).sumOf { vPath ->
				mStorage.fileSystem.metadataOrNull(vPath)?.size ?: 0L
			}
		}
	} catch (vIo: IOException) {
		0L
	}

	companion object {

		/**
		 * 1 GB of images.
		 *
		 * Generous on purpose, and affordable because of what the images now cost: a WebP thumbnail
		 * is ~22 KB and a full-size card ~180 KB, so this is room for tens of thousands of cards --
		 * far more than any one game has. In practice the limit stops being the thing that evicts,
		 * which is what it is for: the LRU should be trimming what is old, not what is recent.
		 *
		 * It bounds browsing only. Downloaded art is pinned and pinned bytes are outside the
		 * budget, so this number never decides whether something the user asked for survives.
		 *
		 * A ceiling is not an allocation. Nothing is reserved; the cache only grows as cards are
		 * actually looked at, and a real browse of all 352 Origins cards came to under 4 MB.
		 *
		 * It is also the OS's to overrule. On Android and iOS this lives in the system cache
		 * directory, which the platform may purge whenever it wants the space back -- which is
		 * precisely why a disposable cache is the right place to be generous.
		 */
		const val DEFAULT_IMAGE_CACHE_MAX_BYTES: Long = 1024L * 1024 * 1024
	}
}

/**
 * One reading of everything on disk: what is used and what of it is kept.
 *
 * Taken together because the storage screen needs it together and the alternative was five
 * directory walks for one screen.
 */
data class StorageReport(val usage: CacheUsage)

/** A reading of both caches, for display. */
data class CacheUsage(
	val metadataBytes: Long,
	val metadataEntries: Int,
	val metadataLimitBytes: Long,
	/** Of [metadataBytes], the part that is kept rather than cached -- see `SqlCardStore.trim`. */
	val metadataKeptBytes: Long = 0L,
	val imageBytes: Long,
	val imageLimitBytes: Long,
) {

	/** What browsing occupies: the part the limit actually governs. */
	val metadataBrowsingBytes: Long get() = (metadataBytes - metadataKeptBytes).coerceAtLeast(0L)
}
