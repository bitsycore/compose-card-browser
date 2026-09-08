package com.bitsycore.cardbrowser.data.cache

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import okio.IOException
import okio.Path
import okio.buffer
import okio.use

/**
 * A bounded, least-recently-used, on-disk cache of provider metadata.
 *
 * One file per record. That is a deliberate trade: a database would be faster for very large sets
 * and much harder to recover from, and the failure this cache actually has to survive is a process
 * dying mid-write, which per-file atomic replacement handles without a schema or a migration.
 *
 * ## Guarantees
 *
 * - **Interrupted writes cannot corrupt a good record.** Every write goes to a temporary file and
 *   is then atomically moved into place. A process killed mid-write leaves a stray temp file, which
 *   the next [trim] removes; the previous record is untouched.
 * - **A corrupt or incompatible record reads as a miss, never as a crash.** Unparseable JSON, a
 *   truncated file, or a record written by an older [CacheEnvelope.CURRENT_SCHEMA_VERSION] are all
 *   deleted and reported absent, so the app re-fetches instead of failing.
 * - **Disk use is bounded.** [trim] evicts least-recently-*used* entries, tracked by an access
 *   timestamp the cache maintains itself rather than by the file system's mtime, which several
 *   platforms do not update on read.
 *
 * All file work happens on [mIoDispatcher]; nothing here touches the UI thread.
 *
 * @param mMaxBytes the ceiling, read afresh on every trim rather than captured once, so changing
 *   it in settings takes effect on the next write instead of on the next launch
 */
class MetadataCache(
	private val mStorage: AppStorage,
	private val mJson: Json,
	private val mIoDispatcher: CoroutineDispatcher,
	private val mMaxBytes: () -> Long = { DEFAULT_MAX_BYTES },
	private val mClock: () -> Long,
) {

	private val mDirectory: Path get() = mStorage.metadataCacheDir
	private val mFileSystem get() = mStorage.fileSystem

	/**
	 * Serialises writes and evictions against each other.
	 *
	 * Reads are not locked: a read either sees a complete old file or a complete new one, because
	 * replacement is atomic. What must not interleave is two writers trimming at once, which would
	 * have them both delete against the same stale size total.
	 */
	private val mWriteLock = Mutex()

	// ============
	//  Reading

	/**
	 * Reads the record at [key], or `null` when there is nothing usable there.
	 *
	 * "Nothing usable" covers absent, unreadable, unparseable and schema-incompatible, and the last
	 * three delete the offending file on the way out. A caller cannot tell the cases apart and does
	 * not need to: all four mean "fetch it".
	 */
	suspend fun <T> read(key: CacheKey, serializer: KSerializer<CacheEnvelope<T>>): CacheEnvelope<T>? =
		withContext(mIoDispatcher) {
			val vPath = pathFor(key)
			try {
				if (!mFileSystem.exists(vPath)) return@withContext null
				val vText = mFileSystem.source(vPath).buffer().use { it.readUtf8() }
				val vEnvelope = mJson.decodeFromString(serializer, vText)
				if (!vEnvelope.isSchemaCompatible) {
					// Written by a build that meant something else by these fields.
					deleteQuietly(vPath)
					return@withContext null
				}
				touch(key)
				vEnvelope
			} catch (vSerialization: SerializationException) {
				// Truncated or malformed. Recovering means forgetting it.
				deleteQuietly(vPath)
				null
			} catch (vIo: IOException) {
				deleteQuietly(vPath)
				null
			}
		}

	// ============
	//  Writing

	/**
	 * Writes [envelope] at [key], atomically, then trims the cache back under its ceiling.
	 *
	 * A write that fails is swallowed rather than thrown: failing to cache is not failing to browse,
	 * and a full disk should not take down a screen that already has its data.
	 */
	suspend fun <T> write(
		key: CacheKey,
		envelope: CacheEnvelope<T>,
		serializer: KSerializer<CacheEnvelope<T>>,
	) {
		withContext(mIoDispatcher) {
			mWriteLock.withLock {
				val vPath = pathFor(key)
				val vTemp = vPath.parent!! / "${vPath.name}$TEMP_SUFFIX"
				try {
					mFileSystem.createDirectories(mDirectory)
					mFileSystem.sink(vTemp).buffer().use { vSink ->
						vSink.writeUtf8(mJson.encodeToString(serializer, envelope))
					}
					// The atomic step. Until this line the old record is still the real one.
					mFileSystem.atomicMove(vTemp, vPath)
					touch(key)
				} catch (vIo: IOException) {
					deleteQuietly(vTemp)
					return@withLock
				}
				trimLocked()
			}
		}
	}

	/** Removes one record. Absent is success. */
	suspend fun remove(key: CacheKey) {
		withContext(mIoDispatcher) {
			mWriteLock.withLock {
				deleteQuietly(pathFor(key))
				mAccessTimes.remove(key.fileName)
			}
		}
	}

	// ============
	//  Housekeeping

	/** Total bytes the metadata cache currently occupies. */
	suspend fun sizeInBytes(): Long = withContext(mIoDispatcher) {
		entriesOnDisk().sumOf { it.sizeBytes }
	}

	/** How many records are held. */
	suspend fun entryCount(): Int = withContext(mIoDispatcher) { entriesOnDisk().size }

	/**
	 * Deletes every metadata record.
	 *
	 * Preferences are untouched: they live under a different root entirely, which is why this can be
	 * wired straight to a "clear cache" button without qualification.
	 */
	suspend fun clear() {
		withContext(mIoDispatcher) {
			mWriteLock.withLock {
				entriesOnDisk().forEach { deleteQuietly(it.path) }
				mAccessTimes.clear()
			}
		}
	}

	/** Evicts least-recently-used records until the cache fits its ceiling. */
	suspend fun trim() {
		withContext(mIoDispatcher) { mWriteLock.withLock { trimLocked() } }
	}

	/** [trim]'s body, for callers that already hold [mWriteLock]. */
	private fun trimLocked() {
		val vEntries = entriesOnDisk().toMutableList()

		// Stray temp files are the debris of an interrupted write. They are never valid records, so
		// they go first and do not count toward the budget.
		vEntries.removeAll { vEntry ->
			if (vEntry.path.name.endsWith(TEMP_SUFFIX)) {
				deleteQuietly(vEntry.path)
				true
			} else {
				false
			}
		}

		val vCeiling = mMaxBytes()
		var vTotal = vEntries.sumOf { it.sizeBytes }
		if (vTotal <= vCeiling) return

		// Least recently used first. An entry the cache has never recorded an access for sorts
		// oldest, which is correct: it was written before this process started tracking.
		vEntries.sortBy { mAccessTimes[it.path.name] ?: 0L }
		for (vEntry in vEntries) {
			if (vTotal <= vCeiling) break
			deleteQuietly(vEntry.path)
			mAccessTimes.remove(vEntry.path.name)
			vTotal -= vEntry.sizeBytes
		}
	}

	// ============
	//  Internals

	/**
	 * When each record was last read or written.
	 *
	 * Kept in memory rather than read from file metadata because several platforms do not update
	 * access time on read, which would turn "least recently used" into "least recently written" and
	 * evict exactly the set the user keeps coming back to. The cost is that the ordering restarts
	 * with the process; the ceiling still holds, which is what the limit is for.
	 */
	private val mAccessTimes = mutableMapOf<String, Long>()

	private fun touch(key: CacheKey) {
		mAccessTimes[key.fileName] = mClock()
	}

	private fun pathFor(key: CacheKey): Path = mDirectory / key.fileName

	private fun entriesOnDisk(): List<DiskEntry> = try {
		if (!mFileSystem.exists(mDirectory)) {
			emptyList()
		} else {
			mFileSystem.list(mDirectory).mapNotNull { vPath ->
				val vMetadata = mFileSystem.metadataOrNull(vPath)
				if (vMetadata?.isRegularFile != true) {
					null
				} else {
					DiskEntry(vPath, vMetadata.size ?: 0L)
				}
			}
		}
	} catch (vIo: IOException) {
		emptyList()
	}

	private fun deleteQuietly(path: Path) {
		try {
			mFileSystem.delete(path, mustExist = false)
		} catch (vIo: IOException) {
			// A file we cannot delete is a file we stop counting on. Nothing useful to do here.
		}
	}

	private data class DiskEntry(val path: Path, val sizeBytes: Long)

	companion object {

		/**
		 * 256 MB.
		 *
		 * The largest Riftbound set is 358 cards and its complete-set record is roughly 460 KB of
		 * JSON, so every set the game has comes to a few megabytes. This is deliberately far above
		 * that: card metadata is what makes the app work offline, it is the cheapest thing here by
		 * two orders of magnitude next to images, and evicting it to save a few megabytes would be
		 * a poor trade. Room for a second and third game later without revisiting the number.
		 */
		const val DEFAULT_MAX_BYTES: Long = 256L * 1024 * 1024

		private const val TEMP_SUFFIX = ".tmp"
	}
}

/**
 * A cache key, and the filename it maps to.
 *
 * The filename is a hash rather than the readable key, because a key contains a query fingerprint
 * and provider ids and would otherwise blow past every platform's filename length limit and drag in
 * path separators. The readable form is kept for debugging and for tests.
 */
data class CacheKey(val value: String) {

	/** A filesystem-safe name derived from [value]. Stable across runs and platforms. */
	val fileName: String get() = "${value.encodeUtf8().sha256().hex()}.json"

	companion object {

		/** Builds a key from parts, joined so two different part lists cannot collide. */
		fun of(vararg parts: String): CacheKey = CacheKey(parts.joinToString("|"))
	}
}
