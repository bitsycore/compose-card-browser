package com.bitsycore.cardbrowser.data

import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.data.cache.CacheEnvelope
import com.bitsycore.cardbrowser.data.cache.CacheKey
import com.bitsycore.cardbrowser.data.cache.CacheScope
import com.bitsycore.cardbrowser.data.cache.Completeness
import com.bitsycore.cardbrowser.data.cache.MetadataCache
import com.bitsycore.cardbrowser.core.model.ProviderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okio.FileSystem
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * What opening the storage screen costs, against a real filesystem.
 *
 * Written to answer "why is this screen so slow to open". Measured on 2026-09-11 with 1000 pinned
 * records, which is about what a Magic import leaves:
 *
 * ```
 *   four separate calls    (sizeInBytes + entryCount + pinnedBytes + pinnedEntries)
 *   one snapshot()
 * ```
 *
 * The numbers the run prints go in the commit message rather than here, so this comment cannot
 * drift from them. The shape of the problem is the part worth recording: each of those four calls
 * listed the metadata directory and stat-ed every file in it, and the directory holds three files
 * per cached set -- the record, its pin marker and its card-count marker. The storage screen made
 * all four calls and then walked the image cache as well.
 */
class StorageScreenCostBench {

	@Test
	@Ignore("A measurement tool, not a check. Remove to re-run.")
	fun `time the storage screen's reads against a real filesystem`() = runBlocking {
		val vRoot = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "cardbrowser-storage-bench"
		FileSystem.SYSTEM.deleteRecursively(vRoot, mustExist = false)
		val vStorage = AppStorage(FileSystem.SYSTEM, vRoot, vRoot / "prefs").also { it.prepare() }
		val vCache = MetadataCache(
			mStorage = vStorage,
			mJson = Json,
			mIoDispatcher = Dispatchers.IO,
			mClock = { 0L },
		)

		val vPayload = "x".repeat(20_000)
		for (vIndex in 0 until 1000) {
			val vKey = CacheKey.of("set", vIndex.toString())
			vCache.pin(vKey, "magic\tscryfall:s$vIndex\ten")
			vCache.write(
				key = vKey,
				envelope = CacheEnvelope(
					schemaVersion = CacheEnvelope.CURRENT_SCHEMA_VERSION,
					provider = ProviderId("scryfall"),
					language = null,
					scope = CacheScope.CompleteSet("scryfall:s$vIndex"),
					fetchedAtEpochMillis = 0L,
					completeness = Completeness.COMPLETE,
					payload = vPayload,
				),
				serializer = CacheEnvelope.serializer(String.serializer()),
			)
			vCache.recordCardCount(vKey, 250)
		}

		// Warm, so the figures are about the walks rather than about the first touch of a cold
		// directory. Both paths are measured after the same warm-up, for the same reason.
		vCache.snapshot()

		val vFourStart = System.nanoTime()
		vCache.sizeInBytes()
		vCache.entryCount()
		vCache.pinnedBytes()
		vCache.pinnedEntries()
		val vFourMillis = (System.nanoTime() - vFourStart) / 1_000_000.0

		val vOneStart = System.nanoTime()
		vCache.snapshot()
		val vOneMillis = (System.nanoTime() - vOneStart) / 1_000_000.0

		println("four calls: $vFourMillis ms")
		println("snapshot(): $vOneMillis ms")
		FileSystem.SYSTEM.deleteRecursively(vRoot, mustExist = false)
	}
}
