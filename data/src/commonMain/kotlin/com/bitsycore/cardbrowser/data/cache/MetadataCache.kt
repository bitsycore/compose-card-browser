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
 * A bounded, least-recently-used, on-disk cache of the *small* provider metadata.
 *
 * One file per record. That is a deliberate trade: the failure this cache has to survive is a
 * process dying mid-write, which per-file atomic replacement handles without a schema or a
 * migration, and the blast radius of a damaged file is one record.
 *
 * ## What is in here, and what is not
 *
 * Set lists, card detail, search pages and per-set language probes. Each is a few kilobytes, each
 * is re-fetchable in one request, and none of them is ever kept on purpose.
 *
 * **Complete sets are not here.** They live in [SetRecordStore], because they are the only records
 * that are large, the only ones a user downloads deliberately, and the only ones anything wants to
 * search across. Everything this class used to carry for their sake -- pin markers, count sidecars,
 * a budget that had to exclude pinned bytes -- went with them, and that is most of why this file is
 * half the size it was.
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
				// Kept, deliberately. A malformed record is deleted just above -- it can never be
				// read and holding it wastes the budget -- but an I/O failure means "not readable
				// *now*": too many open files, a locked file, an OS cache purge racing the read,
				// iOS data protection on a locked device. Deleting on that destroyed the only copy
				// of a record at the exact moment there was no network to fetch it again.
				null
			}
		}

	// ============
	//  Writing

	/**
	 * Writes [envelope] at [key], atomically, and sweeps only when the cache is over its ceiling.
	 *
	 * A write that fails is swallowed rather than thrown: failing to cache is not failing to browse,
	 * and a full disk should not take down a screen that already has its data.
	 *
	 * ## Why the total is tracked rather than measured
	 *
	 * This used to end in an unconditional [trimLocked], which made a bulk import quadratic.
	 * Measured on 2026-09-10 by `CacheWriteCostBench`, 1000 records of 300 KB against a real
	 * filesystem: 4.3 ms for the first writes, 31.4 ms by the five hundredth, 66.6 ms by the
	 * thousandth, **33.1 s in total** -- against **1.4 s** flat with the sweep taken out. The
	 * sweep was 96% of the time and none of it was writing.
	 *
	 * A sweep lists the whole directory and stats every file. Per write, against a directory that
	 * is growing, that is O(n^2). So the total is kept in memory and adjusted as records are
	 * written, and the directory is walked only when that total says the ceiling has actually been
	 * breached. The ceiling still holds exactly, because a sweep recomputes the real total from
	 * disk whenever it runs.
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
				// What this key already occupied, so replacing a record is not counted twice.
				val vPrevious = mFileSystem.metadataOrNull(vPath)?.size ?: 0L
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
				val vWritten = mFileSystem.metadataOrNull(vPath)?.size ?: 0L
				// Unknown until something measures it once: a fresh process inherits a directory
				// it has never looked at, and assuming zero would let the cache grow past its
				// ceiling until the first eviction.
				val vTotal = (mTotalBytes ?: measuredBytes()) - vPrevious + vWritten
				mTotalBytes = vTotal
				if (vTotal > mMaxBytes()) trimLocked()
			}
		}
	}

	/**
	 * Whether a record exists at [key], without reading or parsing it.
	 *
	 * A file-existence check and nothing more, for callers that want to know a probe has been run
	 * without paying to deserialise its answer.
	 */
	suspend fun exists(key: CacheKey): Boolean = withContext(mIoDispatcher) {
		mFileSystem.exists(pathFor(key))
	}

	/** Removes one record. Absent is success. */
	suspend fun remove(key: CacheKey) {
		withContext(mIoDispatcher) {
			mWriteLock.withLock {
				val vPath = pathFor(key)
				val vSize = mFileSystem.metadataOrNull(vPath)?.size ?: 0L
				deleteQuietly(vPath)
				mAccessTimes.remove(key.fileName)
				mTotalBytes = mTotalBytes?.let { (it - vSize).coerceAtLeast(0L) }
			}
		}
	}

	// ============
	//  Housekeeping

	/**
	 * Deletes every metadata record.
	 *
	 * Preferences are untouched: they live under a different root entirely, which is why this can be
	 * wired straight to a "clear cache" button without qualification. Downloaded sets are untouched
	 * too, for a better reason -- they are not in here at all.
	 */
	suspend fun clear() {
		withContext(mIoDispatcher) {
			mWriteLock.withLock {
				entriesOnDisk().forEach { deleteQuietly(it.path) }
				mAccessTimes.clear()
				mTotalBytes = 0L
			}
		}
	}

	/** Evicts least-recently-used records until the cache fits its ceiling. */
	suspend fun trim() {
		withContext(mIoDispatcher) { mWriteLock.withLock { trimLocked() } }
	}

	/**
	 * What this cache holds, in one directory walk.
	 *
	 * One call because the storage screen needs both figures and a second walk for the second one
	 * is the sort of thing that made that screen take seconds to open.
	 */
	suspend fun snapshot(): CacheSnapshot = withContext(mIoDispatcher) {
		val vRecords = entriesOnDisk().filterNot { isTemp(it.path.name) }
		CacheSnapshot(
			totalBytes = vRecords.sumOf { it.sizeBytes },
			entryCount = vRecords.size,
		)
	}

	/**
	 * [trim]'s body, for callers that already hold [mWriteLock].
	 *
	 * Also the only place [mTotalBytes] is re-established from disk, which is why a caller that
	 * has lost track of it can simply run this.
	 */
	private fun trimLocked() {
		val vEntries = entriesOnDisk().toMutableList()

		// Stray temp files are the debris of an interrupted write. They are never valid records, so
		// they go first and do not count toward the budget.
		vEntries.removeAll { vEntry ->
			if (isTemp(vEntry.path.name)) {
				deleteQuietly(vEntry.path)
				true
			} else {
				false
			}
		}

		val vCeiling = mMaxBytes()
		var vTotal = vEntries.sumOf { it.sizeBytes }
		mTotalBytes = vTotal
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
		mTotalBytes = vTotal
	}

	// ============
	//  Internals

	/**
	 * When each record was last read or written.
	 *
	 * Kept in memory rather than read from file metadata because several platforms do not update
	 * access time on read, which would turn "least recently used" into "least recently written" and
	 * evict exactly the record the app keeps coming back to. The cost is that the ordering restarts
	 * with the process -- which is survivable here and was not for downloaded sets, and is one of
	 * the reasons those are a table with an `accessed_at` column instead.
	 */
	private val mAccessTimes = mutableMapOf<String, Long>()

	/**
	 * What the records currently occupy, or `null` when nothing has measured it yet.
	 *
	 * In memory and per process, like [mAccessTimes], and for the same reason: the alternative is
	 * a directory walk, and that walk is the cost this exists to avoid. Seeded by the first write
	 * that needs it and re-established exactly by every sweep.
	 */
	private var mTotalBytes: Long? = null

	/** The real figure, from disk. One directory walk. */
	private fun measuredBytes(): Long =
		entriesOnDisk().filterNot { isTemp(it.path.name) }.sumOf { it.sizeBytes }

	/** A half-written temporary is debris, not a record. */
	private fun isTemp(name: String): Boolean = name.endsWith(TEMP_SUFFIX)

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
		 * 64 MB.
		 *
		 * It was 512 MB, and before that 1 GB, because complete sets lived in here and one bulk
		 * import of Magic is larger than either. They do not any more -- [SetRecordStore] holds
		 * them and carries the user's ceiling -- so what is left is set lists, card detail, search
		 * pages and language probes, all of which are kilobytes apiece. Ten games' full catalogues
		 * come to a few megabytes.
		 *
		 * Fixed rather than configurable on purpose: one user-facing storage limit that means one
		 * thing is better than two that each govern half of something.
		 */
		const val DEFAULT_MAX_BYTES: Long = 64L * 1024 * 1024

		const val TEMP_SUFFIX = ".tmp"
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

/** One reading of the metadata cache, taken in a single pass. */
data class CacheSnapshot(
	val totalBytes: Long,
	val entryCount: Int,
)
