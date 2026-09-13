package com.bitsycore.cardbrowser.data.cache

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okio.IOException
import okio.Path

/**
 * What is on disk, and how to empty the parts that can be emptied.
 *
 * Card data and images are reported and cleared separately because only one of them is a cache.
 * Card data is small, it is what makes offline browsing work, and nothing evicts it -- a set is
 * kept until somebody asks for it to go. Images are most of the bytes, have a ceiling, and are the
 * first thing worth dropping.
 *
 * Preferences are never touched by anything here. They live under a different root, which is what
 * makes "clear cache" a safe button rather than one that also forgets which set you were reading.
 */
class CacheManager(
	private val mStorage: AppStorage,
	private val mMetadataStore: MetadataStore,
	/**
	 * Where the bytes actually are.
	 *
	 * Reported together with the metadata table as one card-data figure, because that is what a
	 * user has: they did not choose to put set lists in one table and cards in another.
	 */
	private val mSetStore: SetRecordStore,
	private val mIoDispatcher: CoroutineDispatcher,
	private val mImageCacheMaxBytes: () -> Long = { DEFAULT_IMAGE_CACHE_MAX_BYTES },
) {

	/**
	 * Everything the storage screen shows, in one pass.
	 *
	 * The image directory is a real walk -- thousands of files after a full browse of Magic -- and
	 * the card-data figures are counts out of the database, so the walk is started first and the
	 * queries run beside it.
	 */
	suspend fun report(): StorageReport = withContext(mIoDispatcher) {
		coroutineScope {
			// The image directory is the only walk left, and it is the expensive one -- thousands
			// of files after a full browse of Magic. Started first so the two counts overlap.
			val vImages = async { directorySize(mStorage.imageCacheDir) }
			val vMetadata = mMetadataStore.snapshot()
			// Counts, not a directory walk. This is what the migration bought the storage screen:
			// measured on 2026-09-11, the same figures took 2456 ms out of the file cache and
			// 14.4 ms out of the store.
			val vSets = mSetStore.snapshot()
			StorageReport(
				usage = CacheUsage(
					metadataBytes = vMetadata.totalBytes + vSets.unpinnedBytes + vSets.pinnedBytes,
					// Split, because the two answer different questions: what you asked for, and
					// what browsing left behind. Only the second is offered for clearing.
					metadataKeptBytes = vSets.pinnedBytes,
					imageBytes = vImages.await(),
					imageLimitBytes = mImageCacheMaxBytes(),
				),
			)
		}
	}

	/** What is on disk, without the rest of the report. */
	suspend fun usage(): CacheUsage = report().usage

	/** Empties every card record, downloads included. Preferences are untouched. */
	suspend fun clearMetadata() {
		mMetadataStore.clear()
		mSetStore.clear()
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
	/** Of [metadataBytes], the part that was downloaded rather than merely browsed. */
	val metadataKeptBytes: Long = 0L,
	val imageBytes: Long,
	val imageLimitBytes: Long,
) {

	/** What browsing left behind: the part "Clear browsed sets" removes. */
	val metadataBrowsingBytes: Long get() = (metadataBytes - metadataKeptBytes).coerceAtLeast(0L)
}
