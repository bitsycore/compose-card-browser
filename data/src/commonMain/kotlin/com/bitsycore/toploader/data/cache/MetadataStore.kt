package com.bitsycore.toploader.data.cache

import com.bitsycore.toploader.sqlstore.SqlCardStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

/**
 * The small provider records that are not cards: set lists, card detail, search pages and per-set
 * language probes.
 *
 * ## Why this is a store and not a cache any more
 *
 * It used to be one JSON file per record under a cache directory, with its own atomic-replace, its
 * own least-recently-used eviction and its own byte budget -- a whole storage subsystem for a few
 * kinds of small record, sitting beside the database that already held the big ones. Card data was
 * therefore in two places, with two ways of going missing and two sets of numbers on the storage
 * screen that had to be added up to mean anything.
 *
 * It is one table now. Nothing evicts it and no limit governs it: every record in here is a few
 * kilobytes, ten games' full catalogues come to a handful of megabytes, and what actually consumes
 * a device is downloaded sets -- which are rows in the same database and carry the user's ceiling.
 *
 * ## What survived the move
 *
 * - **A corrupt or incompatible record reads as a miss, never as a crash.** Unparseable JSON or a
 *   record written by an older [CacheEnvelope.CURRENT_SCHEMA_VERSION] is deleted and reported
 *   absent, so the app re-fetches instead of failing.
 * - **A write that fails is swallowed.** Failing to remember is not failing to browse.
 *
 * What did not survive is the interrupted-write problem, because a transaction either lands or it
 * does not.
 */
interface MetadataStore {

	/**
	 * Reads the record at [key], or `null` when there is nothing usable there.
	 *
	 * "Nothing usable" covers absent, unparseable and schema-incompatible. A caller cannot tell
	 * them apart and does not need to: all three mean "fetch it".
	 */
	suspend fun <T> read(key: CacheKey, serializer: KSerializer<CacheEnvelope<T>>): CacheEnvelope<T>?

	suspend fun <T> write(key: CacheKey, envelope: CacheEnvelope<T>, serializer: KSerializer<CacheEnvelope<T>>)

	/** Whether a record is held, without deserialising it. */
	suspend fun exists(key: CacheKey): Boolean

	/** Removes one record. Absent is success. */
	suspend fun remove(key: CacheKey)

	/** Forgets every record. Downloaded sets and preferences are untouched. */
	suspend fun clear()

	/** Total payload bytes and record count, in one pass. */
	suspend fun snapshot(): CacheSnapshot
}

/** [MetadataStore] over the `metadata` table. */
class SqlMetadataStore(
	private val mStore: SqlCardStore,
	private val mJson: Json,
	private val mIoDispatcher: CoroutineDispatcher,
) : MetadataStore {

	override suspend fun <T> read(
		key: CacheKey,
		serializer: KSerializer<CacheEnvelope<T>>,
	): CacheEnvelope<T>? = withContext(mIoDispatcher) {
		val vPayload = mStore.readMetadata(key.storageKey) ?: return@withContext null
		try {
			val vEnvelope = mJson.decodeFromString(serializer, vPayload)
			if (!vEnvelope.isSchemaCompatible) {
				// Written by a build that meant something else by these fields.
				mStore.removeMetadata(key.storageKey)
				return@withContext null
			}
			vEnvelope
		} catch (vSerialization: SerializationException) {
			mStore.removeMetadata(key.storageKey)
			null
		}
	}

	override suspend fun <T> write(
		key: CacheKey,
		envelope: CacheEnvelope<T>,
		serializer: KSerializer<CacheEnvelope<T>>,
	) {
		withContext(mIoDispatcher) {
			runCatching {
				mStore.writeMetadata(key.storageKey, mJson.encodeToString(serializer, envelope))
			}
		}
	}

	override suspend fun exists(key: CacheKey): Boolean =
		withContext(mIoDispatcher) { mStore.hasMetadata(key.storageKey) }

	override suspend fun remove(key: CacheKey) {
		withContext(mIoDispatcher) { mStore.removeMetadata(key.storageKey) }
	}

	override suspend fun clear() {
		withContext(mIoDispatcher) { mStore.clearMetadata() }
	}

	override suspend fun snapshot(): CacheSnapshot = withContext(mIoDispatcher) {
		val (vBytes, vCount) = mStore.metadataSnapshot()
		CacheSnapshot(totalBytes = vBytes, entryCount = vCount)
	}
}

/**
 * A record's identity.
 *
 * [storageKey] is a hash rather than the readable value. That was forced when these were files --
 * a key carries provider ids and a query fingerprint, and would have blown past filename limits --
 * and it is kept now because the readable form is still useful in a test and a stack trace, while
 * a fixed-width primary key is the better column.
 */
data class CacheKey(val value: String) {

	/** A stable, fixed-width key derived from [value]. */
	val storageKey: String get() = value.encodeUtf8().sha256().hex()

	companion object {

		/** Builds a key from parts, joined so two different part lists cannot collide. */
		fun of(vararg parts: String): CacheKey = CacheKey(parts.joinToString("|"))
	}
}

/** One reading of the metadata table. */
data class CacheSnapshot(
	val totalBytes: Long,
	val entryCount: Int,
)
