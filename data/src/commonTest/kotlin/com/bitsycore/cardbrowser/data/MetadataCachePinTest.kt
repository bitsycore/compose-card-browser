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

	/** A cache with a ceiling small enough that two records cannot both fit. */
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
		val vCache = cache(maxBytes = 600)
		val vDownloaded = CacheKey.of("downloaded")
		val vBrowsed = CacheKey.of("browsed")

		vCache.pin(vDownloaded)
		vCache.put(vDownloaded, "x".repeat(300))
		mNow += 1_000
		// Written later, so it is the *more* recently used of the two -- and still the one that
		// goes, because the other was asked for.
		vCache.put(vBrowsed, "y".repeat(300))

		assertNotNull(vCache.get(vDownloaded), "The downloaded set must survive")
		assertNull(vCache.get(vBrowsed), "The browsed set is the evictable one")
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
