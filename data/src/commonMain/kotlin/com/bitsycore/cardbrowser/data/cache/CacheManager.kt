package com.bitsycore.cardbrowser.data.cache

import kotlinx.coroutines.CoroutineDispatcher
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
	private val mIoDispatcher: CoroutineDispatcher,
	private val mMetadataLimitBytes: () -> Long = { MetadataCache.DEFAULT_MAX_BYTES },
	private val mImageCacheMaxBytes: () -> Long = { DEFAULT_IMAGE_CACHE_MAX_BYTES },
) {

	/** Current usage of both caches, and their ceilings. */
	suspend fun usage(): CacheUsage = withContext(mIoDispatcher) {
		val vTotal = mMetadataCache.sizeInBytes()
		val vKept = mMetadataCache.pinnedBytes()
		CacheUsage(
			metadataBytes = vTotal,
			metadataEntries = mMetadataCache.entryCount(),
			metadataLimitBytes = mMetadataLimitBytes(),
			// Split, because the limit governs only one of the two. Kept records -- downloads and
			// bulk imports -- sit outside it: the ceiling cannot reclaim them, so counting them
			// against it was a number that could only ever be exceeded. See `MetadataCache.trim`.
			metadataKeptBytes = vKept,
			imageBytes = directorySize(mStorage.imageCacheDir),
			imageLimitBytes = mImageCacheMaxBytes(),
		)
	}

	/** Empties the metadata cache, downloads included. Preferences are untouched. */
	suspend fun clearMetadata() {
		mMetadataCache.clear()
	}

	/**
	 * Empties only the part of the metadata cache that browsing filled.
	 *
	 * Downloaded sets and imported catalogues stay. They are not cache in the sense the word is
	 * usually meant -- nothing evicts them and nothing re-fetches them by itself -- so sweeping
	 * them away under a button labelled "clear cached data" would throw away a twenty-minute
	 * import on a tap meant to reclaim a few megabytes.
	 */
	suspend fun clearBrowsingMetadata(): Int = mMetadataCache.clearUnpinned()

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
		 * far more than any one game has. In practice the limit stops being the thing that evicts.
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

/** A reading of both caches, for display. */
data class CacheUsage(
	val metadataBytes: Long,
	val metadataEntries: Int,
	val metadataLimitBytes: Long,
	/** Of [metadataBytes], the part that is kept rather than cached -- see `MetadataCache.pin`. */
	val metadataKeptBytes: Long = 0L,
	val imageBytes: Long,
	val imageLimitBytes: Long,
) {

	/** What browsing occupies: the part the limit actually governs. */
	val metadataBrowsingBytes: Long get() = (metadataBytes - metadataKeptBytes).coerceAtLeast(0L)
}
