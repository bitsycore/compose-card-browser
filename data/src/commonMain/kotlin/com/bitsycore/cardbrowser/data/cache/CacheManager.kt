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
	private val mImageCacheMaxBytes: Long = DEFAULT_IMAGE_CACHE_MAX_BYTES,
) {

	/** Current usage of both caches, and their ceilings. */
	suspend fun usage(): CacheUsage = withContext(mIoDispatcher) {
		CacheUsage(
			metadataBytes = mMetadataCache.sizeInBytes(),
			metadataEntries = mMetadataCache.entryCount(),
			metadataLimitBytes = MetadataCache.DEFAULT_MAX_BYTES,
			imageBytes = directorySize(mStorage.imageCacheDir),
			imageLimitBytes = mImageCacheMaxBytes,
		)
	}

	/** Empties the metadata cache. Cards must be refetched; preferences are untouched. */
	suspend fun clearMetadata() {
		mMetadataCache.clear()
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

	/** Empties both. */
	suspend fun clearAll() {
		clearMetadata()
		clearImages()
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
		 * 128 MB of images.
		 *
		 * Sized from the real data: a Riftbound thumbnail at `w=320` is roughly 300 KB and a full
		 * card image around 1.1 MB, so this holds a few complete sets browsed as thumbnails plus
		 * the cards actually opened. Deliberately modest, and a constructor argument so it is one
		 * edit to change.
		 */
		const val DEFAULT_IMAGE_CACHE_MAX_BYTES: Long = 128L * 1024 * 1024
	}
}

/** A reading of both caches, for display. */
data class CacheUsage(
	val metadataBytes: Long,
	val metadataEntries: Int,
	val metadataLimitBytes: Long,
	val imageBytes: Long,
	val imageLimitBytes: Long,
) {

	val totalBytes: Long get() = metadataBytes + imageBytes
}
