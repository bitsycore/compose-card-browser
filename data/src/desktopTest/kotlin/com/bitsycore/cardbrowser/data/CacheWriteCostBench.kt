package com.bitsycore.cardbrowser.data

import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.data.cache.CacheEnvelope
import com.bitsycore.cardbrowser.data.cache.CacheKey
import com.bitsycore.cardbrowser.data.cache.CacheScope
import com.bitsycore.cardbrowser.data.cache.Completeness
import com.bitsycore.cardbrowser.data.cache.MetadataCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okio.FileSystem
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * What a bulk import's write loop actually costs, against a real filesystem.
 *
 * Written to answer "why is saving so slow", and it did. Measured on 2026-09-10, 1000 sets of
 * 300 KB each:
 *
 * ```
 *                        before        after
 *   writes #1-50           4.3 ms       2.7 ms
 *   writes #451-500       31.4 ms       1.3 ms
 *   writes #951-1000      66.6 ms       1.4 ms
 *   total                 33.1 s        1.5 s
 * ```
 *
 * Before, the per-write cost grew with the number of files already on disk, which made the loop
 * quadratic: `write` ended in `trimLocked`, and that listed the whole directory twice -- once
 * directly and once inside `pinnedNames` -- stat-ing every file each time. The eviction sweep was
 * 96% of a bulk import's write time and none of it was the writing.
 *
 * After, the cost is flat. `MetadataCache` keeps the total in memory and sweeps only when it says
 * the ceiling is breached, with hysteresis so a cache that cannot be reclaimed -- everything an
 * import writes is pinned -- is not re-swept per write. 1.4 s is what the same run costs with the
 * sweep removed entirely, so 1.6 s is close to the floor.
 */
class CacheWriteCostBench {

	@Test
	@Ignore("A measurement tool, not a check. Remove to re-run.")
	fun `time 1000 writes against a real filesystem`() = runBlocking {
		val vRoot = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "cardbrowser-bench"
		FileSystem.SYSTEM.deleteRecursively(vRoot, mustExist = false)
		val vStorage = AppStorage(FileSystem.SYSTEM, vRoot, vRoot / "prefs").also { it.prepare() }
		val vCache = MetadataCache(
			mStorage = vStorage,
			mJson = Json,
			mIoDispatcher = Dispatchers.IO,
			mClock = { 0L },
		)
		val vPayload = "x".repeat(300_000)
		val vMarks = mutableListOf<Long>()
		for (vIndex in 0 until 1000) {
			val vStart = System.nanoTime()
			vCache.pin(CacheKey.of("set", vIndex.toString()))
			vCache.write(
				key = CacheKey.of("set", vIndex.toString()),
				envelope = CacheEnvelope(
					schemaVersion = CacheEnvelope.CURRENT_SCHEMA_VERSION,
					provider = ProviderId("bench"),
					language = null,
					scope = CacheScope.CompleteSet("bench:$vIndex"),
					fetchedAtEpochMillis = 0L,
					completeness = Completeness.COMPLETE,
					payload = vPayload,
				),
				serializer = CacheEnvelope.serializer(String.serializer()),
			)
			vMarks += System.nanoTime() - vStart
		}
		fun ms(from: Int, to: Int) = vMarks.subList(from, to).average() / 1_000_000
		println("write #1-50    avg ${"%.1f".format(ms(0, 50))} ms")
		println("write #451-500 avg ${"%.1f".format(ms(450, 500))} ms")
		println("write #951-1000 avg ${"%.1f".format(ms(950, 1000))} ms")
		println("total ${"%.1f".format(vMarks.sum() / 1_000_000.0 / 1000)} s for 1000 sets")
		FileSystem.SYSTEM.deleteRecursively(vRoot, mustExist = false)
	}
}
