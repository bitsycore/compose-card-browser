package com.bitsycore.cardbrowser.data

import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.data.net.OkioHttpCacheStorage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cache.storage.CachedResponseData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The on-disk HTTP response cache, and its wiring into the client.
 *
 * Worth testing rather than trusting, because a response cache that silently stores nothing is
 * indistinguishable from not having one -- the app still works, just slower and heavier, and no
 * screen ever says so. The last test is the one that matters most: it proves a second request is
 * answered without reaching the engine at all.
 */
class HttpCacheStorageTest {

	private fun storage(
		fileSystem: FakeFileSystem = FakeFileSystem(),
		maxBytes: Long = OkioHttpCacheStorage.DEFAULT_MAX_BYTES,
	): Pair<OkioHttpCacheStorage, AppStorage> {
		val vAppStorage = AppStorage(
			fileSystem = fileSystem,
			cacheRoot = "/cache".toPath(),
			preferencesRoot = "/prefs".toPath(),
		)
		vAppStorage.prepare()
		return OkioHttpCacheStorage(vAppStorage, maxBytes) to vAppStorage
	}

	private fun response(
		url: String = "https://example.test/sets",
		body: String = """{"data":[1,2,3]}""",
		etag: String = "\"abc\"",
	) = CachedResponseData(
		url = Url(url),
		statusCode = HttpStatusCode.OK,
		requestTime = GMTDate(1_000L),
		responseTime = GMTDate(2_000L),
		version = HttpProtocolVersion.HTTP_1_1,
		expires = GMTDate(3_000L),
		headers = headersOf(HttpHeaders.ETag, etag),
		varyKeys = emptyMap(),
		body = body.encodeToByteArray(),
	)

	// ============
	//  Round trip

	@Test
	fun `a stored response comes back whole`() = runTest {
		val (vStorage, _) = storage()
		val vData = response()

		vStorage.store(vData.url, vData)
		val vFound = assertNotNull(vStorage.find(vData.url, emptyMap()))

		// The body is what makes the entry useful at all -- it is what gets served when the origin
		// answers 304 -- so it has to survive byte for byte.
		assertEquals(vData.body.decodeToString(), vFound.body.decodeToString())
		// And the validator, without which the revalidation cannot even be attempted.
		assertEquals("\"abc\"", vFound.headers[HttpHeaders.ETag])
		assertEquals(HttpStatusCode.OK, vFound.statusCode)
		assertEquals(vData.url.toString(), vFound.url.toString())
		assertEquals(2_000L, vFound.responseTime.timestamp)
	}

	@Test
	fun `a body containing newlines survives the header split`() = runTest {
		// The format is one line of JSON, a newline, then the raw body -- so a body full of
		// newlines is the case that would break a naive split on every occurrence.
		val (vStorage, _) = storage()
		val vData = response(body = "line one\nline two\n\nline four")

		vStorage.store(vData.url, vData)

		assertEquals(
			"line one\nline two\n\nline four",
			assertNotNull(vStorage.find(vData.url, emptyMap())).body.decodeToString(),
		)
	}

	@Test
	fun `two URLs do not share an entry`() = runTest {
		val (vStorage, _) = storage()
		val vFirst = response(url = "https://example.test/a", body = "first")
		val vSecond = response(url = "https://example.test/b", body = "second")

		vStorage.store(vFirst.url, vFirst)
		vStorage.store(vSecond.url, vSecond)

		assertEquals("first", assertNotNull(vStorage.find(vFirst.url, emptyMap())).body.decodeToString())
		assertEquals("second", assertNotNull(vStorage.find(vSecond.url, emptyMap())).body.decodeToString())
	}

	@Test
	fun `an empty body is not stored at all`() = runTest {
		// There is no point filing a 304's own empty body: the entry exists to *supply* a body.
		val (vStorage, _) = storage()
		val vData = response(body = "")

		vStorage.store(vData.url, vData)

		assertNull(vStorage.find(vData.url, emptyMap()))
	}

	@Test
	fun `a missing entry is a miss rather than a failure`() = runTest {
		val (vStorage, _) = storage()

		assertNull(vStorage.find(Url("https://example.test/never-stored"), emptyMap()))
	}

	// ============
	//  Damage and budget

	@Test
	fun `a truncated file is discarded rather than half-read`() = runTest {
		val vFileSystem = FakeFileSystem()
		val (vStorage, vAppStorage) = storage(vFileSystem)
		val vData = response()
		vStorage.store(vData.url, vData)

		// Cut the file off inside the header, as a process killed mid-write would.
		val vFile = vFileSystem.list(vAppStorage.cacheRoot / OkioHttpCacheStorage.HTTP_DIR).single()
		vFileSystem.write(vFile) { writeUtf8("""{"url":"https://exa""") }

		assertNull(vStorage.find(vData.url, emptyMap()))
		// And it is gone, rather than being re-read and re-rejected forever.
		assertTrue(vFileSystem.list(vAppStorage.cacheRoot / OkioHttpCacheStorage.HTTP_DIR).isEmpty())
	}

	@Test
	fun `the budget is enforced`() = runTest {
		// 2 KB of ceiling against four 1 KB bodies: something has to go, and the total has to end
		// up under the limit rather than merely being trimmed a bit.
		val vFileSystem = FakeFileSystem()
		val (vStorage, vAppStorage) = storage(vFileSystem, maxBytes = 2_048)
		val vBody = "x".repeat(1_024)

		for (vIndex in 1..4) {
			val vData = response(url = "https://example.test/$vIndex", body = vBody)
			vStorage.store(vData.url, vData)
		}

		val vDirectory = vAppStorage.cacheRoot / OkioHttpCacheStorage.HTTP_DIR
		val vTotal = vFileSystem.list(vDirectory)
			.sumOf { vFileSystem.metadata(it).size ?: 0L }
		assertTrue(vTotal <= 2_048, "cache is $vTotal bytes against a 2048 ceiling")
	}

	// ============
	//  The point of the whole thing

	@Test
	fun `a second request inside max-age never reaches the network`() = runTest {
		// The reason any of this exists. YGOPRODeck sends `max-age=3600`, so the second call within
		// the hour should be answered from disk without a request -- and if the wiring is wrong,
		// this is the test that notices rather than a user wondering why it feels slow.
		val (vStorage, _) = storage()
		var vEngineCalls = 0
		val vEngine = MockEngine {
			vEngineCalls++
			respond(
				content = """{"data":"payload"}""",
				headers = io.ktor.http.headers {
					append(HttpHeaders.CacheControl, "public, max-age=3600")
					append(HttpHeaders.ETag, "\"v1\"")
				},
			)
		}
		// The factory picks its own engine per platform, so the plugin stack is driven through a
		// client on the mock transport instead: same `HttpCache` configuration, controllable
		// engine, and a call count that can be asserted on.
		val vMockClient = io.ktor.client.HttpClient(vEngine) {
			install(io.ktor.client.plugins.cache.HttpCache) { publicStorage(vStorage) }
		}

		val vFirst = vMockClient.get("https://example.test/cardsets").bodyAsText()
		val vSecond = vMockClient.get("https://example.test/cardsets").bodyAsText()

		assertEquals("""{"data":"payload"}""", vFirst)
		assertEquals(vFirst, vSecond, "the cached body must match the original")
		assertEquals(1, vEngineCalls, "the second request should have been served from the cache")

		vMockClient.close()
	}
}
