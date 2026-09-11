package com.bitsycore.cardbrowser.data

import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.data.cache.CacheEnvelope
import com.bitsycore.cardbrowser.data.cache.CacheKey
import com.bitsycore.cardbrowser.data.cache.CacheScope
import com.bitsycore.cardbrowser.data.cache.Completeness
import com.bitsycore.cardbrowser.data.cache.MetadataCache
import com.bitsycore.cardbrowser.core.model.ProviderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A deliberately downloaded set is not evicted to make room for a browsed one.
 *
 * The gap this closes: eviction was one rule -- least recently used until the total fits -- with no
 * idea that some records exist because the user asked for them offline. A downloaded set is large
 * and therefore the first thing worth reclaiming, so the feature was undermined by the cache it
 * depended on. Worse, the access times live in memory, so after a relaunch every record sorts
 * equally old and the order collapses to however the filesystem lists.
 */
class MetadataCachePinTest {

	private val mJson = Json { ignoreUnknownKeys = true }

	private var mNow = 1_000L

	/**
	 * A cache with a ceiling small enough that two records cannot both fit.
	 *
	 * Sizes here are chosen against a measured envelope overhead of about **218 bytes** per record,
	 * so a 300-character payload lands at roughly 518 bytes on disk. That matters: a ceiling below
	 * one whole record evicts everything and proves nothing about the order.
	 */
	private fun cache(maxBytes: Long) = MetadataCache(
		mStorage = AppStorage(FakeFileSystem(), "/cache".toPath(), "/prefs".toPath()).also { it.prepare() },
		mJson = mJson,
		mIoDispatcher = Dispatchers.Unconfined,
		mMaxBytes = { maxBytes },
		mClock = { mNow },
	)

	private fun envelope(payload: String) = CacheEnvelope(
		schemaVersion = CacheEnvelope.CURRENT_SCHEMA_VERSION,
		provider = ProviderId("test"),
		language = null,
		scope = CacheScope.CompleteSet("test:OGN"),
		fetchedAtEpochMillis = mNow,
		completeness = Completeness.COMPLETE,
		payload = payload,
	)

	private suspend fun MetadataCache.put(key: CacheKey, payload: String) =
		write(key, envelope(payload), CacheEnvelope.serializer(String.serializer()))

	private suspend fun MetadataCache.get(key: CacheKey) =
		read(key, CacheEnvelope.serializer(String.serializer()))

	@Test
	fun `a pinned record survives an eviction that removes an unpinned one`() = runTest {
		// Two unpinned records are enough to breach a 600-byte ceiling on their own, which is what
		// makes this an eviction at all: the pinned record is outside the budget, so it can neither
		// cause the sweep nor be taken by it.
		// ~518 bytes each: two browses breach a 900-byte ceiling, one fits inside it.
		val vCache = cache(maxBytes = 900)
		val vDownloaded = CacheKey.of("downloaded")
		val vOldBrowse = CacheKey.of("browsed-old")
		val vNewBrowse = CacheKey.of("browsed-new")

		vCache.pin(vDownloaded)
		vCache.put(vDownloaded, "x".repeat(300))
		mNow += 1_000
		vCache.put(vOldBrowse, "y".repeat(300))
		mNow += 1_000
		vCache.put(vNewBrowse, "z".repeat(300))

		assertNotNull(vCache.get(vDownloaded), "The downloaded set must survive")
		assertNull(vCache.get(vOldBrowse), "The least recently used browse is the evictable one")
		assertNotNull(vCache.get(vNewBrowse), "The newest browse should still fit")
	}

	@Test
	fun `pinned records do not spend the browsing budget`() = runTest {
		// The rule this file is really about, and it used to be the other way round. Pinned bytes
		// counted toward the ceiling while being exempt from eviction, so one bulk import -- which
		// pins everything it writes -- could exceed the limit on its own. From then on every write
		// swept, and every browsed record was evicted immediately however small it was: the cache
		// thrashed and re-fetched sets it had just stored.
		//
		// A ceiling the user sets is a statement about how much space *browsing* may take. What a
		// download occupies is theirs to delete, not the cache's to reclaim.
		val vCache = cache(maxBytes = 900)

		for (vIndex in 0 until 8) {
			val vKey = CacheKey.of("imported-$vIndex")
			vCache.pin(vKey)
			vCache.put(vKey, "x".repeat(400))
		}
		assertTrue(vCache.sizeInBytes() > 900, "the import should be well past the ceiling")

		mNow += 1_000
		// ~418 bytes, comfortably inside the ceiling on its own.
		vCache.put(CacheKey.of("browsed"), "y".repeat(200))

		assertNotNull(
			vCache.get(CacheKey.of("browsed")),
			"a small browse was evicted by an import that the ceiling cannot reclaim anyway",
		)
		for (vIndex in 0 until 8) {
			assertNotNull(vCache.get(CacheKey.of("imported-$vIndex")), "imported-$vIndex must survive")
		}
	}

	@Test
	fun `without a pin the least recently used one goes -- the behaviour that was already there`() =
		runTest {
			val vCache = cache(maxBytes = 600)
			val vFirst = CacheKey.of("first")
			val vSecond = CacheKey.of("second")

			vCache.put(vFirst, "x".repeat(300))
			mNow += 1_000
			vCache.put(vSecond, "y".repeat(300))

			assertNull(vCache.get(vFirst))
			assertNotNull(vCache.get(vSecond))
		}

	@Test
	fun `the ceiling gives way rather than the pinned data`() = runTest {
		// Deleting what the user explicitly kept, in order to honour a number they set to bound
		// incidental browsing, is the wrong trade. The cache goes over instead, and says so.
		//
		// Pinned *before* writing, which is the order the download queue uses and the only one
		// that works: a write runs a trim, so a record big enough to breach the ceiling can be
		// evicted by the very write that stored it, and a pin applied afterwards would be
		// protecting something already gone. The marker does not need its record to exist yet.
		val vCache = cache(maxBytes = 100)
		val vOne = CacheKey.of("one")
		val vTwo = CacheKey.of("two")

		vCache.pin(vOne)
		vCache.put(vOne, "x".repeat(300))
		vCache.pin(vTwo)
		vCache.put(vTwo, "y".repeat(300))

		assertNotNull(vCache.get(vOne))
		assertNotNull(vCache.get(vTwo))
		assertTrue(vCache.sizeInBytes() > 100, "Got ${vCache.sizeInBytes()}")
		assertTrue(vCache.pinnedBytes() > 100, "Pinned bytes should be reported, got ${vCache.pinnedBytes()}")
	}

	@Test
	fun `unpinning returns a record to ordinary eviction`() = runTest {
		val vCache = cache(maxBytes = 600)
		val vDownloaded = CacheKey.of("downloaded")

		vCache.pin(vDownloaded)
		vCache.put(vDownloaded, "x".repeat(300))
		assertTrue(vCache.isPinned(vDownloaded))

		vCache.unpin(vDownloaded)
		assertFalse(vCache.isPinned(vDownloaded))

		mNow += 1_000
		vCache.put(CacheKey.of("browsed"), "y".repeat(300))

		assertNull(vCache.get(vDownloaded), "Once unpinned it evicts like anything else")
	}

	@Test
	fun `clearing browsing data keeps what was downloaded`() = runTest {
		// "Clear cached data" must not mean "throw away the catalogue you spent twenty minutes
		// importing". The two live in the same directory and only the pin tells them apart.
		val vCache = cache(maxBytes = Long.MAX_VALUE)
		val vKept = CacheKey.of("downloaded")
		vCache.pin(vKept)
		vCache.put(vKept, "x".repeat(300))
		vCache.put(CacheKey.of("browsed-a"), "y".repeat(300))
		vCache.put(CacheKey.of("browsed-b"), "z".repeat(300))

		val vRemoved = vCache.clearUnpinned()

		assertEquals(2, vRemoved)
		assertNotNull(vCache.get(vKept), "a downloaded set must survive clearing browsing data")
		assertNull(vCache.get(CacheKey.of("browsed-a")))
		assertNull(vCache.get(CacheKey.of("browsed-b")))
	}

	@Test
	fun `pinned bytes are reported apart from the rest`() = runTest {
		// What the storage screen shows, and what the limit in settings does not govern.
		val vCache = cache(maxBytes = Long.MAX_VALUE)
		val vKept = CacheKey.of("downloaded")
		vCache.pin(vKept)
		vCache.put(vKept, "x".repeat(300))
		vCache.put(CacheKey.of("browsed"), "y".repeat(300))

		val vTotal = vCache.sizeInBytes()
		val vPinned = vCache.pinnedBytes()

		assertTrue(vPinned > 0, "the downloaded record should be counted as kept")
		assertTrue(vPinned < vTotal, "kept is a part of the total, not all of it")
		// And the part the ceiling governs is the remainder, which is what the screen calls
		// browsing data.
		assertTrue(vTotal - vPinned > 0)
	}

	@Test
	fun `the ceiling still bites once browsing alone exceeds it`() = runTest {
		// The other side of the rule above: taking pinned bytes out of the budget must not take
		// the budget away. A pile of imported records sits outside it; browsing that breaches it
		// on its own is still swept, least recently used first.
		val vCache = cache(maxBytes = 900)

		for (vIndex in 0 until 4) {
			val vKey = CacheKey.of("pinned-$vIndex")
			vCache.pin(vKey)
			vCache.put(vKey, "x".repeat(300))
		}
		assertTrue(vCache.sizeInBytes() > 900, "the pinned records should be past the ceiling")

		mNow += 1_000
		vCache.put(CacheKey.of("browsed-old"), "y".repeat(300))
		mNow += 1_000
		vCache.put(CacheKey.of("browsed-new"), "z".repeat(300))

		assertNull(vCache.get(CacheKey.of("browsed-old")), "the oldest browse is the one that goes")
		assertNotNull(vCache.get(CacheKey.of("browsed-new")))
		for (vIndex in 0 until 4) {
			assertNotNull(vCache.get(CacheKey.of("pinned-$vIndex")), "pinned-$vIndex must survive")
		}
	}

	@Test
	fun `a marker is not itself an entry`() = runTest {
		// It is a zero-byte sibling of the record. Counting it as an evictable entry would let a
		// pin delete itself, which would be a quiet way of un-protecting everything.
		val vCache = cache(maxBytes = 10_000)
		val vKey = CacheKey.of("only")

		vCache.pin(vKey)
		vCache.put(vKey, "x".repeat(100))
		vCache.trim()

		assertTrue(vCache.isPinned(vKey), "The marker must survive a trim")
		assertEquals(1, vCache.entryCount(), "The marker must not count as a record")
	}
}
