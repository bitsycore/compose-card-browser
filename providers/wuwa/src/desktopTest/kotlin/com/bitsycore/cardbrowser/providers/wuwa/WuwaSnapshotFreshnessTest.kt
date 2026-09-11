package com.bitsycore.cardbrowser.providers.wuwa

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.data.net.HttpClientFactory
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Asks UCP whether the bundled snapshot is still the whole game.
 *
 * The adapter serves a bundled `wuwa-cards.json` asset and makes no requests of its own, which buys
 * an offline cold start at the cost of going stale when a set is released. This is the check that
 * makes the staleness visible rather than something a user finds. It is the *only* thing in this
 * module that talks to UCP.
 *
 * **Not part of the ordinary test run.** Run it deliberately:
 *
 * ```
 * ./gradlew :providers:wuwa:liveProviderTest
 * ```
 *
 * A failure here is not a bug in the adapter. It means the catalogue moved and the snapshot needs
 * regenerating:
 *
 * ```
 * python providers/wuwa/tools/scrape_wuwa.py --refresh
 * ```
 */
class WuwaSnapshotFreshnessTest {

	/**
	 * One locale's card list, straight from the endpoint the scraper uses.
	 *
	 * Through [HttpClientFactory], and that is the fix rather than a tidy-up. This file used to
	 * build a bare `HttpClient(Java)` -- the only test in the repository that did -- so its
	 * requests to a volunteer-run API carried Ktor's default User-Agent instead of the honest,
	 * contactable one the factory exists to guarantee, and had no timeout, no retry and no
	 * throttle. It also built and closed a client *per request*, so eleven requests meant eleven
	 * connection pools. One client, held for the class.
	 */
	private suspend fun listFor(locale: String): WireList = run {
		mClient
			.get(WuwaProvider.API_BASE_URL) {
				url { appendPathSegments("api", "web", "card", "list") }
				parameter("page", 1)
				parameter("size", 500)
				header("x-lang", locale)
				header("Accept", "application/json")
			}
			.body<WireEnvelope>()
			.also { assertEquals(1, it.code, "UCP answered application code ${it.code}: ${it.msg}") }
			.data
	}

	@Test
	fun `each locale still holds exactly what the snapshot recorded`() = runBlocking {
		// The provenance block states what each catalogue answered on the day it was scraped. A
		// changed total is the signal, and it is a far cheaper one than diffing 357 records.
		val vRecorded = WuwaCatalogue.snapshot().source.locales
		assertTrue(vRecorded.isNotEmpty(), "the snapshot records no locale totals")

		val vDrift = mutableListOf<String>()
		for ((vTag, vExpected) in vRecorded) {
			val vLocale = LOCALES.getValue(vTag)
			val vLive = listFor(vLocale).total
			if (vLive != vExpected) vDrift += "$vTag ($vLocale): snapshot $vExpected, live $vLive"
		}

		assertTrue(
			vDrift.isEmpty(),
			"The bundled catalogue is out of date. Regenerate it with " +
				"`python providers/wuwa/tools/scrape_wuwa.py --refresh`.\n  " + vDrift.joinToString("\n  "),
		)
	}

	@Test
	fun `every printed code in the live catalogue is one the snapshot knows`() = runBlocking {
		// Totals can coincide while the contents change -- a card withdrawn and another added. This
		// is the cheap second opinion, and it needs one request rather than 123.
		val vLive = listFor(LOCALES.getValue("ja")).list.map { it.code }.toSet()
		val vBundled = WuwaCatalogue.snapshot().cards.map { it.code }.toSet()

		val vMissing = vLive - vBundled
		assertTrue(vMissing.isEmpty(), "codes UCP serves that the snapshot lacks: $vMissing")
	}

	@Test
	fun `the locales the adapter offers are still the ones that answer with cards`() = runBlocking {
		// `en-us`, `zh-tw` and bare `ko` are all accepted and all answer 200 with an empty list,
		// which is why the adapter declares three languages rather than six. If one of them ever
		// starts serving cards, this is what notices.
		for (vLanguage in WuwaProvider().capabilities.data.languages) {
			val vTag = requireNotNull(TAGS[vLanguage]) { "no locale mapped for $vLanguage" }
			assertTrue(listFor(vTag).total > 0, "$vTag stopped answering with cards")
		}
		for (vDead in listOf("en-us", "zh-tw", "ko", "kr")) {
			assertEquals(0, listFor(vDead).total, "$vDead now serves cards and could be offered")
		}
	}

	@Test
	fun `the bundled image URLs still resolve`() = runBlocking {
		// The images are the one thing not bundled, so a moved CDN path is a screen of grey
		// placeholders. Three cards rather than 128: this is a smoke check on the bucket.
		val vProvider = WuwaProvider()
		val vSample = vProvider
			.listCards(
				com.bitsycore.cardbrowser.core.provider.CardPageRequest(
					setId = com.bitsycore.cardbrowser.core.model.SourceId(WuwaProvider.PROVIDER_ID, "SD01"),
					language = CardLanguage.JAPANESE,
				),
			)
			.cards
			.take(3)
		assertTrue(vSample.isNotEmpty())

		for (vCard in vSample) {
			val vResponse: HttpResponse = mClient.head(vCard.artwork.imageUrl)
			assertEquals(
				HttpStatusCode.OK,
				vResponse.status,
				"${vCard.collectorNumber} art is gone: ${vCard.artwork.imageUrl}",
			)
		}
	}

	@Test
	fun `the CDN still resizes -- so a thumbnail is really a thumbnail`() = runBlocking {
		// The adapter asks the bucket for a 320 px rendition rather than shipping a second file --
		// see `WuwaCatalogue.THUMBNAIL_PARAMS`. If Tencent COS ever stops honouring `imageMogr2`
		// the request still succeeds and returns the full asset, so the app would quietly fetch
		// 180 KB a card for a grid tile and nothing would look wrong.
		//
		// Measured 2026-09-11 across five random cards: 168-188 KB down to 22-43 KB.
		val vCard = WuwaCatalogue
			.printings(CardLanguage.JAPANESE, WuwaProvider.PROVIDER_ID)
			.first { it.artwork.thumbnailUrl != null }
		val vThumbnail = assertNotNull(vCard.artwork.thumbnailUrl)
		assertTrue(
			vThumbnail.endsWith(WuwaCatalogue.THUMBNAIL_PARAMS),
			"the adapter stopped asking for a resize: $vThumbnail",
		)

		val vFullBytes = mClient.get(vCard.artwork.imageUrl).readRawBytes().size
		val vThumbBytes = mClient.get(vThumbnail).readRawBytes().size

		assertTrue(vFullBytes > 100_000, "the full asset looks wrong at $vFullBytes bytes")
		assertTrue(
			vThumbBytes < vFullBytes / 2,
			"the CDN is no longer resizing: $vThumbBytes against $vFullBytes",
		)
	}

	// ==================
	// MARK: Wire types
	// ==================

	@Serializable
	private data class WireEnvelope(
		val code: Int = 0,
		val msg: String? = null,
		val data: WireList = WireList(),
	)

	@Serializable
	private data class WireList(
		val list: List<WireCard> = emptyList(),
		val total: Int = 0,
	)

	@Serializable
	private data class WireCard(
		val id: Long = 0,
		val code: String = "",
		val name: String = "",
		@SerialName("card_type")
		val cardType: String? = null,
	)

	private companion object {

		/** One per JVM, with the app's own User-Agent, timeout and retry policy. */
		val mClient by lazy { HttpClientFactory.create() }

		/** Snapshot locale tag to the `x-lang` value UCP wants. The full tag; a bare one answers empty. */
		val LOCALES = mapOf("ja" to "ja-jp", "zh-cn" to "zh-cn", "ko" to "ko-kr")

		val TAGS = mapOf(
			CardLanguage.JAPANESE to "ja-jp",
			CardLanguage.SIMPLIFIED_CHINESE to "zh-cn",
			CardLanguage.KOREAN to "ko-kr",
		)
	}
}
