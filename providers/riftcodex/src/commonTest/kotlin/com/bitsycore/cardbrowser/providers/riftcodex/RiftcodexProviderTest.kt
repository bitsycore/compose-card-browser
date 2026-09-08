package com.bitsycore.cardbrowser.providers.riftcodex

import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.Availability
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.ExternalIdKey
import com.bitsycore.cardbrowser.core.model.Finish
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardFilterField
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.data.net.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Riftcodex adapter, against captured responses.
 *
 * Deterministic: no network, no clock, no sleeping. The live-provider check is separate -- see
 * `RiftcodexLiveSmokeTest`.
 */
class RiftcodexProviderTest {

	/** A client that answers every request from [handler]. */
	private fun clientOf(
		handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
	) = HttpClient(MockEngine { vRequest -> handler(vRequest) }) {
		expectSuccess = true
		install(ContentNegotiation) { json(HttpClientFactory.json) }
	}

	private fun jsonClient(body: String) = clientOf { vRequest ->
		respond(
			content = body,
			status = HttpStatusCode.OK,
			headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
		)
	}

	// ============
	//  Sets

	@Test
	fun `sets map with dates, counts and both shapes of cardmarket id`() = runTest {
		val vProvider = RiftcodexProvider(jsonClient(RiftcodexFixtures.SETS))

		val vSets = vProvider.listSets(Game.RIFTBOUND)

		assertEquals(4, vSets.size)

		val vOrigins = vSets.first { it.code == "OGN" }
		assertEquals("Origins", vOrigins.name)
		assertEquals(352, vOrigins.cardCount)
		assertEquals(2025, vOrigins.releaseDate?.year)
		assertEquals(10, vOrigins.releaseDate?.month?.ordinal?.plus(1))
		// A single string.
		assertEquals(listOf("6286"), vOrigins.externalIds[ExternalIdKey.CARDMARKET_EXPANSION])

		// An array. Both shapes really occur, and neither may fail the whole list.
		val vPromos = vSets.first { it.code == "OPP" }
		assertEquals(listOf("6322", "6483"), vPromos.externalIds[ExternalIdKey.CARDMARKET_EXPANSION])

		// Nulls simply mean absent.
		val vVendetta = vSets.first { it.code == "VEN" }
		assertNull(vVendetta.externalIds[ExternalIdKey.CARDMARKET_EXPANSION])
		assertNull(vVendetta.externalIds[ExternalIdKey.TCGPLAYER])
	}

	@Test
	fun `a set's id is its game code, which is what the cards endpoint needs`() = runTest {
		// Regression. The id used to be Riftcodex's own record id, and `/cards?set_id=<record id>`
		// answers 200 with an empty page -- so every set browsed that way looked like a set with no
		// cards in it, and the empty result was then cached.
		val vProvider = RiftcodexProvider(jsonClient(RiftcodexFixtures.SETS))

		val vOrigins = vProvider.listSets(Game.RIFTBOUND).first { it.code == "OGN" }

		assertEquals("OGN", vOrigins.id.local)
		assertEquals("riftcodex:OGN", vOrigins.id.qualified)
		// The record id is kept rather than discarded.
		assertEquals(
			listOf("69bc5bf6e195be3e561d1eb1"),
			vOrigins.externalIds[ExternalIdKey.PROVIDER_RECORD],
		)
	}

	@Test
	fun `a card's set id matches the set list's set id`() = runTest {
		// The two must agree or the detail screen cannot find its set, and the cache keys diverge.
		val vSetProvider = RiftcodexProvider(jsonClient(RiftcodexFixtures.SETS))
		val vCardProvider = RiftcodexProvider(
			jsonClient("""{"items":[${RiftcodexFixtures.CARD_ORDINARY}],"total":1,"page":1,"size":100,"pages":1}"""),
		)

		val vOrigins = vSetProvider.listSets(Game.RIFTBOUND).first { it.code == "OGN" }
		val vCard = vCardProvider.listCards(request()).cards.single()

		assertEquals(vOrigins.id.provider, vCard.setId.provider)
		// The fixture card is from OGS, so compare the shape rather than the value.
		assertEquals(vCard.setCode, vCard.setId.local)
	}

	@Test
	fun `set ids are qualified with the provider`() = runTest {
		val vProvider = RiftcodexProvider(jsonClient(RiftcodexFixtures.SETS))

		val vSets = vProvider.listSets(Game.RIFTBOUND)

		assertTrue(vSets.all { it.id.provider == RiftcodexProvider.PROVIDER_ID })
		assertTrue(vSets.all { it.id.qualified.startsWith("riftcodex:") })
	}

	@Test
	fun `asking Riftcodex for another game is a programming error`() = runTest {
		val vProvider = RiftcodexProvider(jsonClient(RiftcodexFixtures.SETS))

		assertFailsWith<IllegalArgumentException> { vProvider.listSets(Game.MAGIC) }
	}

	// ============
	//  Cards

	@Test
	fun `an ordinary card maps every field it has`() = runTest {
		val vProvider = RiftcodexProvider(
			jsonClient("""{"items":[${RiftcodexFixtures.CARD_ORDINARY}],"total":1,"page":1,"size":100,"pages":1}"""),
		)

		val vCard = vProvider.listCards(request()).cards.single()

		assertEquals("Annie - Fiery", vCard.displayName)
		assertEquals("001", vCard.collectorNumber)
		assertEquals("1", vCard.providerRawCollectorNumber)
		assertEquals("Epic", vCard.classification.rarity)
		assertEquals("Unit", vCard.classification.type)
		assertEquals("Champion", vCard.classification.supertype)
		assertEquals(listOf("Fury"), vCard.classification.domains)
		assertEquals(5, vCard.attributes.energy)
		assertEquals(4, vCard.attributes.might)
		assertEquals(1, vCard.attributes.power)
		assertEquals("Polar Engine Studio", vCard.artwork.artist)
		assertEquals("Your spells and abilities deal 1 Bonus Damage.", vCard.text.rules)
		assertEquals("I never play with matches.", vCard.text.flavour)
		assertEquals(listOf("653136"), vCard.externalIds[ExternalIdKey.TCGPLAYER])
	}

	@Test
	fun `the thumbnail is the CDN image narrowed by a width parameter`() = runTest {
		val vProvider = RiftcodexProvider(
			jsonClient("""{"items":[${RiftcodexFixtures.CARD_ORDINARY}],"total":1,"page":1,"size":100,"pages":1}"""),
		)

		val vCard = vProvider.listCards(request()).cards.single()

		assertTrue(vCard.artwork.imageUrl.contains("cmsassets.rgpub.io"))
		assertTrue(vCard.artwork.thumbnailUrl!!.contains("w=320"))
		// The format is pinned rather than negotiated. Left to itself the CDN answers some assets
		// with AVIF, which Skia cannot decode -- a valid 200 that caches and then fails forever.
		assertTrue(vCard.artwork.thumbnailUrl!!.contains("fm=webp"))
		// The original must keep its own query string rather than being rebuilt.
		assertTrue(vCard.artwork.thumbnailUrl!!.contains("accountingTag=RB"))
	}

	@Test
	fun `an image URL from an unknown host gets no invented thumbnail`() {
		assertNull(RiftcodexMapper.thumbnailUrl("https://example.com/card.png"))
	}

	@Test
	fun `two printings sharing a collector number stay distinct`() = runTest {
		// The real Origins 299 pair. If either the id or the collector number collapsed, the grid
		// would drop a card and the cache would overwrite one with the other.
		val vProvider = RiftcodexProvider(jsonClient(RiftcodexFixtures.CARDS_SHARED_COLLECTOR_NUMBER))

		val vCards = vProvider.listCards(request()).cards

		assertEquals(2, vCards.size)
		assertNotEquals(vCards[0].id, vCards[1].id)
		assertEquals("299", vCards[0].collectorNumber)
		assertEquals("299*", vCards[1].collectorNumber)
		// Both report the same bare integer, which is exactly why it cannot be the identity.
		assertEquals("299", vCards[0].providerRawCollectorNumber)
		assertEquals("299", vCards[1].providerRawCollectorNumber)
	}

	@Test
	fun `variant flags become artwork treatments`() = runTest {
		val vProvider = RiftcodexProvider(jsonClient(RiftcodexFixtures.CARDS_SHARED_COLLECTOR_NUMBER))

		val vCards = vProvider.listCards(request()).cards

		assertEquals(ArtworkTreatment.OVERNUMBERED, vCards[0].artwork.treatment)
		assertEquals(ArtworkTreatment.SIGNATURE, vCards[1].artwork.treatment)
	}

	@Test
	fun `distinct printings carry distinct artwork ids so the grid gives each its own tile`() = runTest {
		val vProvider = RiftcodexProvider(jsonClient(RiftcodexFixtures.CARDS_SHARED_COLLECTOR_NUMBER))

		val vCards = vProvider.listCards(request()).cards

		assertNotEquals(vCards[0].artwork.id, vCards[1].artwork.id)
		assertNotEquals(vCards[0].artwork.imageUrl, vCards[1].artwork.imageUrl)
	}

	@Test
	fun `a sparse record with an unknown field still maps`() = runTest {
		val vProvider = RiftcodexProvider(
			jsonClient("""{"items":[${RiftcodexFixtures.CARD_SPARSE}],"total":1,"page":1,"size":100,"pages":1}"""),
		)

		val vCard = vProvider.listCards(request()).cards.single()

		assertEquals("Mystery Card", vCard.displayName)
		// No riftbound_id and no collector_number: the id is the last resort rather than a crash.
		assertEquals("sparse-1", vCard.collectorNumber)
		assertNull(vCard.attributes.energy)
		assertNull(vCard.text.rules)
		assertNull(vCard.text.flavour)
		assertNull(vCard.artwork.artist)
		assertNull(vCard.artwork.thumbnailUrl)
		assertEquals("", vCard.artwork.imageUrl)
		// A lowercase set code is normalised, so it matches the set list.
		assertEquals("OGN", vCard.setCode)
	}

	// ============
	//  Coverage claims

	@Test
	fun `every card reports English confirmed and the other three unknown`() = runTest {
		val vProvider = RiftcodexProvider(
			jsonClient("""{"items":[${RiftcodexFixtures.CARD_ORDINARY}],"total":1,"page":1,"size":100,"pages":1}"""),
		)

		val vCard = vProvider.listCards(request()).cards.single()

		assertEquals(Availability.AVAILABLE, vCard.languages.availabilityOf(CardLanguage.ENGLISH))
		listOf(CardLanguage.FRENCH, CardLanguage.JAPANESE, CardLanguage.KOREAN).forEach { vLanguage ->
			assertEquals(
				Availability.UNKNOWN,
				vCard.languages.availabilityOf(vLanguage),
				"$vLanguage must be unknown, not unavailable: Riftcodex has no language field",
			)
		}
	}

	@Test
	fun `no finish is ever claimed`() = runTest {
		val vProvider = RiftcodexProvider(
			jsonClient("""{"items":[${RiftcodexFixtures.CARD_ORDINARY}],"total":1,"page":1,"size":100,"pages":1}"""),
		)

		val vCard = vProvider.listCards(request()).cards.single()

		assertTrue(vCard.finishes.isUnstated)
		Finish.entries.forEach {
			assertEquals(Availability.UNKNOWN, vCard.finishes.availabilityOf(it))
		}
	}

	@Test
	fun `no card identity is invented`() = runTest {
		val vProvider = RiftcodexProvider(jsonClient(RiftcodexFixtures.CARDS_SHARED_COLLECTOR_NUMBER))

		// Two records for what a player calls one card, and the adapter still links neither,
		// because the provider does not.
		vProvider.listCards(request()).cards.forEach { assertNull(it.identity) }
	}

	@Test
	fun `no cardmarket product id is ever produced`() = runTest {
		val vProvider = RiftcodexProvider(
			jsonClient("""{"items":[${RiftcodexFixtures.CARD_ORDINARY}],"total":1,"page":1,"size":100,"pages":1}"""),
		)

		val vCard = vProvider.listCards(request()).cards.single()

		assertNull(vCard.externalIds[ExternalIdKey.CARDMARKET_PRODUCT])
	}

	@Test
	fun `declared capabilities match what the adapter actually does`() {
		val vCapabilities = RiftcodexProvider(jsonClient("{}")).capabilities

		assertEquals(setOf(Game.RIFTBOUND), vCapabilities.games)
		assertEquals(100, vCapabilities.maxPageSize)
		// Text is the only remote filter; the rest need the whole set.
		assertEquals(setOf(CardFilterField.TEXT), vCapabilities.filtering.remote)
		assertTrue(CardFilterField.RARITY in vCapabilities.filtering.localOnly)
		// Neither is offered at all, which is what keeps them off the filter sheet.
		assertFalse(CardFilterField.FINISH in vCapabilities.filtering.supported)
		assertFalse(CardFilterField.LANGUAGE in vCapabilities.filtering.supported)
		assertFalse(vCapabilities.data.finishes)
		assertFalse(vCapabilities.data.cardIdentity)
		assertFalse(vCapabilities.data.cardmarketProductMapping)
		assertEquals(setOf(CardLanguage.ENGLISH), vCapabilities.data.languages)
	}

	@Test
	fun `a rarity filter is reported as needing the complete set`() {
		val vCapabilities = RiftcodexProvider(jsonClient("{}")).capabilities

		assertTrue(
			vCapabilities.filtering.requiresCompleteSet(CardQuery(rarities = setOf("Epic"))),
		)
		// Text alone does not: the server can do that one.
		assertFalse(vCapabilities.filtering.requiresCompleteSet(CardQuery(text = "annie")))
	}

	// ============
	//  Paging and requests

	@Test
	fun `hasMore comes from the server's page count, not from how full the page was`() = runTest {
		val vFirst = RiftcodexProvider(jsonClient(RiftcodexFixtures.PAGE_ONE_OF_TWO))
			.listCards(request(page = 1))
		val vSecond = RiftcodexProvider(jsonClient(RiftcodexFixtures.PAGE_TWO_OF_TWO))
			.listCards(request(page = 2))

		// One item on a page of 100 would look like the end by any other measure.
		assertTrue(vFirst.hasMore)
		assertEquals(150, vFirst.totalCount)
		assertFalse(vSecond.hasMore)
	}

	@Test
	fun `a page size above the server cap is clamped before the request goes out`() = runTest {
		var vSentSize: String? = null
		val vClient = clientOf { vRequest ->
			vSentSize = vRequest.url.parameters["size"]
			respond(
				RiftcodexFixtures.PAGE_TWO_OF_TWO,
				HttpStatusCode.OK,
				headersOf("Content-Type", ContentType.Application.Json.toString()),
			)
		}

		RiftcodexProvider(vClient).listCards(request(pageSize = 400))

		// The server answers 422 for anything above 100; clamping means it never sees one.
		assertEquals("100", vSentSize)
	}

	@Test
	fun `a text query goes to the search endpoint and an empty one does not`() = runTest {
		val vPaths = mutableListOf<String>()
		val vClient = clientOf { vRequest ->
			vPaths += vRequest.url.encodedPath
			respond(
				RiftcodexFixtures.PAGE_TWO_OF_TWO,
				HttpStatusCode.OK,
				headersOf("Content-Type", ContentType.Application.Json.toString()),
			)
		}
		val vProvider = RiftcodexProvider(vClient)

		vProvider.listCards(request(query = CardQuery(text = "annie")))
		vProvider.listCards(request(query = CardQuery()))
		vProvider.listCards(request(query = CardQuery(text = "   ")))

		assertEquals("/cards/search", vPaths[0])
		assertEquals("/cards", vPaths[1])
		// Blank is not a search.
		assertEquals("/cards", vPaths[2])
	}

	@Test
	fun `the set code and sort are sent as the API names them`() = runTest {
		var vRequestUrl: io.ktor.http.Url? = null
		val vClient = clientOf { vRequest ->
			vRequestUrl = vRequest.url
			respond(
				RiftcodexFixtures.PAGE_TWO_OF_TWO,
				HttpStatusCode.OK,
				headersOf("Content-Type", ContentType.Application.Json.toString()),
			)
		}

		RiftcodexProvider(vClient).listCards(request())

		assertEquals("OGN", vRequestUrl?.parameters?.get("set_id"))
		assertEquals("collector_number", vRequestUrl?.parameters?.get("sort"))
		assertEquals("1", vRequestUrl?.parameters?.get("dir"))
	}

	// ============
	//  Errors

	@Test
	fun `a 404 on detail is an answer of null, not a failure`() = runTest {
		val vClient = clientOf { respondError(HttpStatusCode.NotFound) }

		val vResult = RiftcodexProvider(vClient).cardDetail(SourceId(RiftcodexProvider.PROVIDER_ID, "nope"))

		assertNull(vResult)
	}

	@Test
	fun `a 422 becomes a non-transient BadRequest`() = runTest {
		val vClient = clientOf {
			respond(
				RiftcodexFixtures.PAGE_SIZE_REJECTED,
				HttpStatusCode.UnprocessableEntity,
				headersOf("Content-Type", ContentType.Application.Json.toString()),
			)
		}

		val vError = assertFailsWith<ProviderError.BadRequest> {
			RiftcodexProvider(vClient).listCards(request())
		}

		assertEquals(422, vError.status)
		// Retrying an argument the server rejected is wasted traffic.
		assertFalse(vError.isTransient)
	}

	@Test
	fun `a 500 becomes a transient ServerError`() = runTest {
		val vClient = clientOf { respondError(HttpStatusCode.InternalServerError) }

		val vError = assertFailsWith<ProviderError.ServerError> {
			RiftcodexProvider(vClient).listSets(Game.RIFTBOUND)
		}

		assertTrue(vError.isTransient)
	}

	@Test
	fun `a 429 carries its Retry-After and is transient`() = runTest {
		val vClient = clientOf {
			respond(
				content = "",
				status = HttpStatusCode.TooManyRequests,
				headers = headersOf("Retry-After", "42"),
			)
		}

		val vError = assertFailsWith<ProviderError.RateLimited> {
			RiftcodexProvider(vClient).listSets(Game.RIFTBOUND)
		}

		assertEquals(42, vError.retryAfterSeconds)
		assertTrue(vError.isTransient)
	}

	@Test
	fun `unreadable JSON becomes a non-transient MalformedResponse`() = runTest {
		val vClient = clientOf {
			respond(
				"{ this is not json",
				HttpStatusCode.OK,
				headersOf("Content-Type", ContentType.Application.Json.toString()),
			)
		}

		val vError = assertFailsWith<ProviderError.MalformedResponse> {
			RiftcodexProvider(vClient).listSets(Game.RIFTBOUND)
		}

		assertFalse(vError.isTransient)
	}

	@Test
	fun `cancellation propagates as cancellation, never as a ProviderError`() = runTest {
		// The behaviour that keeps a superseded set selection from painting an error over the
		// screen the user just opened.
		val vGate = CompletableDeferred<Unit>()
		val vClient = clientOf {
			vGate.await()
			respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
		}
		val vProvider = RiftcodexProvider(vClient)

		val vJob = async { vProvider.listSets(Game.RIFTBOUND) }
		vJob.cancel()

		assertFailsWith<CancellationException> { vJob.await() }
	}

	private fun request(
		page: Int = 1,
		pageSize: Int = 100,
		query: CardQuery = CardQuery(),
	) = CardPageRequest(
		setId = SourceId(RiftcodexProvider.PROVIDER_ID, "OGN"),
		query = query,
		page = page,
		pageSize = pageSize,
	)
}
