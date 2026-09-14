package com.bitsycore.tcgexplorer.data.cache

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * [MetadataStore] in a map, for tests and previews.
 *
 * In `commonMain` beside [InMemorySetRecordStore] and for the same reason: the tests that need it
 * live in four modules, and a fake in one module's test source set is not visible from the others.
 *
 * It stores the serialised form rather than the object, so a test still exercises the round trip --
 * a record that cannot be written and read back is the bug most worth catching here.
 */
class InMemoryMetadataStore(
	private val mJson: Json = Json { ignoreUnknownKeys = true },
) : MetadataStore {

	private val mRecords = mutableMapOf<String, String>()

	override suspend fun <T> read(
		key: CacheKey,
		serializer: KSerializer<CacheEnvelope<T>>,
	): CacheEnvelope<T>? {
		val vPayload = mRecords[key.storageKey] ?: return null
		return try {
			mJson.decodeFromString(serializer, vPayload).takeIf { it.isSchemaCompatible }
				?: null.also { mRecords.remove(key.storageKey) }
		} catch (vSerialization: SerializationException) {
			mRecords.remove(key.storageKey)
			null
		}
	}

	override suspend fun <T> write(
		key: CacheKey,
		envelope: CacheEnvelope<T>,
		serializer: KSerializer<CacheEnvelope<T>>,
	) {
		mRecords[key.storageKey] = mJson.encodeToString(serializer, envelope)
	}

	override suspend fun exists(key: CacheKey): Boolean = mRecords.containsKey(key.storageKey)

	override suspend fun remove(key: CacheKey) {
		mRecords.remove(key.storageKey)
	}

	override suspend fun clear() {
		mRecords.clear()
	}

	override suspend fun snapshot(): CacheSnapshot = CacheSnapshot(
		totalBytes = mRecords.values.sumOf { it.length.toLong() },
		entryCount = mRecords.size,
	)
}
