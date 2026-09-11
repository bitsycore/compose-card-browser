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
				// Kept, deliberately. A malformed record is deleted just above -- it can never be
				// read and holding it wastes the budget -- but an I/O failure means "not readable
				// *now*": too many open files, a locked file, an OS cache purge racing the read,
				// iOS data protection on a locked device. Deleting on that destroyed the only copy
				// of a set at the exact moment there was no network to fetch it again, which made
				// this the one unrecoverable path in a cache where every other failure costs a
				// re-download.
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
	 * A sweep lists the whole directory and stats every file, and it did so twice -- once directly
	 * and once inside the old `pinnedNames`. Per write, against a directory that is growing, that
	 * is O(n^2): importing Magic paid roughly two million stat calls to evict nothing, because
	 * everything an import writes is pinned and pinned records are never evicted.
	 *
	 * So the total is kept in memory and adjusted as records are written, and the directory is
	 * walked only when that total says the ceiling has actually been breached -- which during an
	 * import is never, and in ordinary browsing is rare. The ceiling still holds exactly, because
	 * a sweep recomputes the real total from disk whenever it runs.
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
				// A pinned record is outside the budget entirely, so writing one moves the total
				// not at all -- see [trimLocked]. One `exists` call, which `isWorthSweeping`
				// below was already making.
				val vIsPinned = mFileSystem.exists(markerFor(key))
				// Unknown until something measures it once: a fresh process inherits a directory
				// it has never looked at, and assuming zero would let the cache grow past its
				// ceiling until the first eviction.
				val vTotal = if (vIsPinned) {
					mTotalBytes ?: measuredBytes()
				} else {
					(mTotalBytes ?: measuredBytes()) - vPrevious + vWritten
				}
				mTotalBytes = vTotal
				if (vTotal > mMaxBytes() && isWorthSweeping(key)) trimLocked()
			}
		}
	}

	/**
	 * Whether a record exists at [key], without reading or parsing it.
	 *
	 * A file-existence check and nothing more. It exists because the set list wants to mark which
	 * sets are saved on the device, and answering that with [read] would deserialise every cached
	 * set on every launch -- for Magic's 988 sets that is a megabyte of JSON parsed to render a
	 * list of icons.
	 *
	 * "Exists" means saved, not complete: a partially fetched set has a file too. The set list says
	 * "saved", which is exactly what this reports.
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

	/** Total bytes the metadata cache currently occupies. */
	suspend fun sizeInBytes(): Long = withContext(mIoDispatcher) {
		recordsOnDisk().sumOf { it.sizeBytes }
	}

	/** How many records are held. */
	suspend fun entryCount(): Int = withContext(mIoDispatcher) { recordsOnDisk().size }

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
				mTotalBytes = 0L
			}
		}
	}

	// ==================
	// MARK: Pinning
	// ==================

	/**
	 * Marks a record as deliberately downloaded, so eviction leaves it alone.
	 *
	 * ## Why this exists
	 *
	 * Without it the download feature is a promise the cache does not keep. Everything in here was
	 * evicted by one rule -- least recently used, until the total fits -- which makes no distinction
	 * between a set the user idly tapped and one they explicitly downloaded to have on a flight.
	 * The second is exactly the thing that must survive, and it was the likeliest to go: a
	 * downloaded set is big, and browsing anything else pushed the total over the ceiling.
	 *
	 * It was worse than that in practice. [mAccessTimes] lives in memory, so after a relaunch every
	 * record sorts equally old and the eviction order collapses to whatever order the filesystem
	 * lists in. A set downloaded last week and a set opened yesterday were indistinguishable.
	 *
	 * ## Why a marker file
	 *
	 * Trimming must decide what to keep without reading anything: it works from a directory listing
	 * and file sizes, and parsing every record to look for a flag inside it would turn a cheap
	 * sweep into a full deserialisation of the cache. A zero-byte sibling is visible in the same
	 * listing, costs a directory entry, and -- unlike anything held in memory -- survives a restart,
	 * which is the whole point.
	 */
	suspend fun pin(key: CacheKey, label: String = "") {
		withContext(mIoDispatcher) {
			mWriteLock.withLock {
				try {
					mFileSystem.createDirectories(mDirectory)
					// The marker carries *what* it pins, not merely that something is pinned.
					//
					// It used to be zero bytes, which made a kept record anonymous: the only way
					// back from a hashed filename to a set was the game's set list, and that is
					// ordinary browsing data. Clearing the cache therefore deleted the index, and
					// the storage screen stopped being able to name records that were still on
					// disk -- they vanished from the screen while the download dialog went on
					// correctly reporting them as downloaded.
					//
					// An opaque string, because this layer names no games. The caller decides what
					// it means; see `CardRepository.pinLabel`.
					mFileSystem.sink(markerFor(key)).buffer().use { vSink ->
						if (label.isNotEmpty()) vSink.writeUtf8(label)
					}
					// Those bytes just left the budget. The running total is now an overestimate,
					// and re-measuring is cheaper than being wrong in the direction of evicting
					// things needlessly.
					mTotalBytes = null
				} catch (vIo: IOException) {
					// A pin that cannot be written is not worth failing a download over. The set is
					// still cached; it is merely evictable, which is where it started.
				}
			}
		}
	}

	/** Removes the mark, returning the record to ordinary eviction. */
	suspend fun unpin(key: CacheKey) {
		withContext(mIoDispatcher) {
			mWriteLock.withLock {
				deleteQuietly(markerFor(key))
				// Something just became evictable, so a sweep that previously found nothing may
				// now find this. Without the reset it would stay deferred until an unpinned
				// record happened to be written.
				mSweepFoundNothing = false
				// And those bytes just entered the budget, which the running total does not know.
				mTotalBytes = null
			}
		}
	}

	/** Whether [key] is protected from eviction. */
	suspend fun isPinned(key: CacheKey): Boolean = withContext(mIoDispatcher) {
		mFileSystem.exists(markerFor(key))
	}

	/**
	 * What the pinned records occupy.
	 *
	 * Reported separately because it is the part of the cache the ceiling cannot reclaim, and a
	 * settings screen that shows a limit should be able to say how much of it is spoken for.
	 */
	/**
	 * Deletes every record the ceiling could have reclaimed, and keeps the pinned ones.
	 *
	 * What "clear the cache" should mean once some of the cache is there on purpose. [clear] takes
	 * everything, including a catalogue that took twenty minutes to import -- fine as a reset, and
	 * not what a user means by freeing up browsing data.
	 *
	 * @return how many records went
	 */
	suspend fun clearUnpinned(): Int = withContext(mIoDispatcher) {
		mWriteLock.withLock {
			val vAll = entriesOnDisk()
			val vPinned = pinnedNamesIn(vAll)
			var vRemoved = 0
			for (vEntry in vAll) {
				if (isMarker(vEntry.path.name)) continue
				if (vEntry.path.name in vPinned) continue
				deleteQuietly(vEntry.path)
				mAccessTimes.remove(vEntry.path.name)
				vRemoved++
			}
			// Nothing evictable is left, so the budget is empty whatever it was before.
			mTotalBytes = 0L
			mSweepFoundNothing = false
			vRemoved
		}
	}

	suspend fun pinnedBytes(): Long = withContext(mIoDispatcher) {
		val vAll = entriesOnDisk()
		val vPinned = pinnedNamesIn(vAll)
		vAll.filterNot { isMarker(it.path.name) }
			.filter { it.path.name in vPinned }
			.sumOf { it.sizeBytes }
	}

	// ==================
	// MARK: Counting
	// ==================

	/**
	 * Remembers how many cards a record turned out to hold.
	 *
	 * ## Why the count is stored rather than derived
	 *
	 * The set list wants to say how many cards a set has *in the language it will open in*, and no
	 * source answers that. A set's stated size is one number for the set -- YGOPRODeck's
	 * `num_of_cards`, Scryfall's `card_count` -- and it counts the English printing. Ask the same
	 * source for the same set in French and it serves only the cards that have been translated:
	 * measured 2026-09-10, Magnificent Maestros is 24 cards and 4 of them in French, and Beyond the
	 * Brave is 8 and none. Printing "24 cards" above a grid of 4 is the app stating something it
	 * does not know.
	 *
	 * What it *does* know is what a source actually served, once it has served it. That is a
	 * measurement, and this is where it is kept.
	 *
	 * ## Why a sibling file
	 *
	 * The same reason as a pin: the set list runs this per set, and for Magic that is 988 of them.
	 * Reading the count out of the record would mean deserialising every cached set to render a
	 * list of subtitles. A few bytes next to the record is one small read.
	 *
	 * Only ever written for a record known to be complete. A partial fetch's size is not the set's
	 * size, and recording it would replace an over-count with an under-count.
	 */
	suspend fun recordCardCount(key: CacheKey, count: Int) {
		withContext(mIoDispatcher) {
			mWriteLock.withLock {
				try {
					mFileSystem.createDirectories(mDirectory)
					mFileSystem.sink(countFor(key)).buffer().use { it.writeUtf8(count.toString()) }
				} catch (vIo: IOException) {
					// A count that cannot be written costs a subtitle, not a screen.
				}
			}
		}
	}

	/**
	 * How many cards [key] held when it was last written, or `null` if that was never recorded.
	 *
	 * `null` is not zero and the difference matters: "never counted" means fall back to whatever the
	 * source states, while zero means a source was asked and answered with nothing.
	 */
	suspend fun cardCount(key: CacheKey): Int? = withContext(mIoDispatcher) {
		val vPath = countFor(key)
		try {
			if (!mFileSystem.exists(vPath)) return@withContext null
			mFileSystem.source(vPath).buffer().use { it.readUtf8() }.trim().toIntOrNull()
		} catch (vIo: IOException) {
			null
		}
	}

	/** Evicts least-recently-used records until the cache fits its ceiling. */
	suspend fun trim() {
		withContext(mIoDispatcher) { mWriteLock.withLock { trimLocked() } }
	}

	/**
	 * [trim]'s body, for callers that already hold [mWriteLock].
	 *
	 * Also the only place [mTotalBytes] is re-established from disk, which is why a caller that
	 * has lost track of it can simply run this.
	 */
	private fun trimLocked() {
		val vAll = entriesOnDisk()
		val vEntries = vAll.toMutableList()

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

		// Marker files are bookkeeping, not records. They are a few bytes at most, but they must not
		// be treated as evictable entries or a pin would delete itself.
		// From the listing already taken. This used to walk the directory a second time, which
		// doubled the cost of the one operation here that was already the expensive one.
		val vPinnedNames = pinnedNamesIn(vAll)
		vEntries.removeAll { it.path.name.endsWith(PIN_SUFFIX) || it.path.name.endsWith(COUNT_SUFFIX) }

		// Pinned records are removed from the budget, not merely skipped when evicting.
		//
		// They cannot be reclaimed -- a deliberately downloaded set is never deleted to honour a
		// number the user set to bound *incidental* browsing -- so counting them was a ceiling
		// measured against bytes it had no power over. One bulk import is enough to exceed the
		// limit on its own, and from then on every write swept and evicted browsing records that
		// together came nowhere near it: the cache thrashed, and re-fetched sets that had just
		// been cached.
		//
		// So the ceiling now means what a user would take it to mean: how much space browsing is
		// allowed to take. What downloads occupy is reported by [pinnedBytes] and managed by
		// deleting them, which is a decision rather than an eviction.
		vEntries.removeAll { it.path.name in vPinnedNames }

		val vCeiling = mMaxBytes()
		var vTotal = vEntries.sumOf { it.sizeBytes }
		mTotalBytes = vTotal
		if (vTotal <= vCeiling) {
			mSweepFoundNothing = false
			return
		}

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
		// Whether the sweep achieved anything decides whether the next one is worth running.
		mSweepFoundNothing = vTotal > vCeiling
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

	/**
	 * What the records currently occupy, or `null` when nothing has measured it yet.
	 *
	 * In memory and per process, like [mAccessTimes], and for the same reason: the alternative is
	 * a directory walk, and that walk is the cost this exists to avoid. Seeded by the first write
	 * that needs it and re-established exactly by every sweep, so it can drift only if something
	 * outside this class changes the directory -- and the ceiling bounds incidental browsing, it
	 * is not an accounting guarantee.
	 */
	private var mTotalBytes: Long? = null

	/** The real figure, from disk. One directory walk. */
	/**
	 * What the budget currently holds, measured from disk.
	 *
	 * Pinned records are excluded, because they are not in the budget -- see [trimLocked]. Counting
	 * them here would put them straight back into the running total the moment it had to be
	 * re-established, which is exactly when the number matters.
	 */
	private fun measuredBytes(): Long {
		val vAll = entriesOnDisk()
		val vPinned = pinnedNamesIn(vAll)
		return vAll
			.filterNot { isMarker(it.path.name) }
			.filterNot { it.path.name in vPinned }
			.sumOf { it.sizeBytes }
	}

	/**
	 * Whether a sweep could achieve anything, given what is being written.
	 *
	 * The cache is over its ceiling; the question is whether walking the directory would free
	 * anything, and the answer is knowable without walking it.
	 *
	 * A sweep that already failed means everything on disk is pinned, and pinned records are never
	 * evicted -- the ceiling gives way by design. Writing *another pinned* record cannot change
	 * that, so re-walking the directory for it is pure cost. That is the download case, and it is
	 * the one that hurt: measured after the first fix, an import still spent 30 ms a write over
	 * its last fifty, re-sweeping a cache with nothing in it to reclaim.
	 *
	 * Writing an **unpinned** record does change it -- that record is evictable, and it is exactly
	 * what the ceiling exists to evict -- so that always sweeps.
	 *
	 * This replaced a byte-margin heuristic ("wait until 8 MB more has accumulated"), which was
	 * wrong for the same reason heuristics usually are: it also deferred the sweep that should
	 * have thrown away the first unpinned record after a download. A test written for that case
	 * failed immediately, which is how this rule came to be exact rather than approximate.
	 */
	private fun isWorthSweeping(key: CacheKey): Boolean =
		!mSweepFoundNothing || !mFileSystem.exists(markerFor(key))

	/**
	 * Whether the last sweep got under the ceiling.
	 *
	 * False after a sweep that could not, which happens only when the excess is entirely pinned.
	 * Cleared by any successful sweep and by [unpin], so releasing a set takes effect on the next
	 * write rather than being suppressed by a stale reading.
	 */
	private var mSweepFoundNothing: Boolean = false

	/** Pins, counts and half-written temporaries are bookkeeping, not records. */
	private fun isMarker(name: String): Boolean =
		name.endsWith(PIN_SUFFIX) || name.endsWith(COUNT_SUFFIX) || name.endsWith(TEMP_SUFFIX)

	private fun touch(key: CacheKey) {
		mAccessTimes[key.fileName] = mClock()
	}

	private fun pathFor(key: CacheKey): Path = mDirectory / key.fileName

	private fun markerFor(key: CacheKey): Path = mDirectory / (key.fileName + PIN_SUFFIX)

	private fun countFor(key: CacheKey): Path = mDirectory / (key.fileName + COUNT_SUFFIX)

	/**
	 * Every pinned record: what it was pinned as, and what it occupies.
	 *
	 * Reads the markers rather than the records -- they are a few dozen bytes each -- so this
	 * survives the cache being cleared around it, which is the whole reason the label is there.
	 *
	 * A marker written before labels existed, or by a caller that passed none, comes back with an
	 * empty [PinnedEntry.label]. Reported rather than dropped: the bytes are real and a screen that
	 * hid them would disagree with its own total.
	 */
	suspend fun pinnedEntries(): List<PinnedEntry> = withContext(mIoDispatcher) {
		val vAll = entriesOnDisk()
		val vBySize = vAll.associate { it.path.name to it.sizeBytes }
		vAll
			.filter { it.path.name.endsWith(PIN_SUFFIX) }
			.map { vMarker ->
				val vRecord = vMarker.path.name.removeSuffix(PIN_SUFFIX)
				PinnedEntry(
					label = readMarker(vMarker.path),
					// Zero when the marker outlived its record, which a failed write can leave
					// behind. Still listed, because the pin is real and a screen offering to
					// delete it is right to.
					bytes = vBySize[vRecord] ?: 0L,
				)
			}
	}

	private fun readMarker(path: Path): String = try {
		mFileSystem.read(path) { readUtf8() }
	} catch (vIo: IOException) {
		""
	}

	/** The record names that carry a pin marker, from a listing the caller already has. */
	private fun pinnedNamesIn(entries: List<DiskEntry>): Set<String> = entries
		.asSequence()
		.map { it.path.name }
		.filter { it.endsWith(PIN_SUFFIX) }
		.map { it.removeSuffix(PIN_SUFFIX) }
		.toSet()

	/**
	 * The cached records, without the bookkeeping.
	 *
	 * A pin marker and a half-written temp file are both real files in the directory and neither is
	 * a record. Counting them makes `entryCount` report more than is held and lets a trim consider
	 * deleting a marker -- which would quietly unprotect the very thing it marks.
	 */
	private fun recordsOnDisk(): List<DiskEntry> = entriesOnDisk().filterNot { isMarker(it.path.name) }

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
		 * 256 MB, matching the image cache.
		 *
		 * The largest Riftbound set is 358 cards and its complete-set record is roughly 460 KB of
		 * JSON, so every set the game has comes to a few megabytes. This is deliberately far above
		 * that: card metadata is what makes the app work offline, it is the cheapest thing here by
		 * two orders of magnitude next to images, and evicting it to save a few megabytes would be
		 * a poor trade.
		 *
		 * It went to 1 GB when a bulk import of Magic -- larger than 256 MB on its own -- was
		 * evicted by its own ceiling the moment it finished. That is fixed properly now: an
		 * imported or downloaded record is pinned, and [trimLocked] leaves pinned bytes out of the
		 * budget entirely. So this ceiling bounds *browsing* again, which is what it was for.
		 */
		const val DEFAULT_MAX_BYTES: Long = 256L * 1024 * 1024

		/** Marks a record as deliberately downloaded. A zero-byte sibling of the record itself. */
		private const val PIN_SUFFIX: String = ".pin"

		/**
		 * Holds how many cards a record turned out to contain. A few bytes beside the record.
		 *
		 * Deliberately outlives eviction. The count is a measurement of what a source served, not
		 * a property of the cached copy, so it stays true after the cards themselves are trimmed
		 * away -- and a set list that has evicted a set can still say how big it is.
		 */
		private const val COUNT_SUFFIX: String = ".n"

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



/**
 * One pinned record, as the storage screen sees it.
 *
 * @property label whatever the caller pinned it as, or empty for a marker that carries none
 * @property bytes what the record occupies
 */
data class PinnedEntry(val label: String, val bytes: Long)
