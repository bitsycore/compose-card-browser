package com.bitsycore.cardbrowser.providers.riftcodex

import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.Availability
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.ExternalIdKey
import com.bitsycore.cardbrowser.core.model.Finish
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardFilterField
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.data.net.HttpClientFactory
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

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
	fun `sets map with dates -- counts and both shapes of cardmarket id`() = runTest {
		val vProvider = RiftcodexProvider(jsonClient(RiftcodexFixtures.SETS))

		val vSets = vProvider.listSets()

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
	fun `a set's id is its game code -- which is what the cards endpoint needs`() = runTest {
		// Regression. The id used to be Riftcodex's own record id, and `/cards?set_id=<record id>`
		// answers 200 with an empty page -- so every set browsed that way looked like a set with no
		// cards in it, and the empty result was then cached.
		val vProvider = RiftcodexProvider(jsonClient(RiftcodexFixtures.SETS))

		val vOrigins = vProvider.listSets().first { it.code == "OGN" }

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

		val vOrigins = vSetProvider.listSets().first { it.code == "OGN" }
		val vCard = vCardProvider.listCards(request()).cards.single()

		assertEquals(vOrigins.id.provider, vCard.setId.provider)
		// The fixture card is from OGS, so compare the shape rather than the value.
		assertEquals(vCard.setCode, vCard.setId.local)
	}

	@Test
	fun `set ids are qualified with the provider`() = runTest {
		val vProvider = RiftcodexProvider(jsonClient(RiftcodexFixtures.SETS))

		val vSets = vProvider.listSets()

		assertTrue(vSets.all { it.id.provider == RiftcodexProvider.PROVIDER_ID })
		assertTrue(vSets.all { it.id.qualified.startsWith("riftcodex:") })
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
		// Lower-cased onto the key `RiftboundGame` declares, which carries its label and colour.
		assertEquals(listOf("fury"), vCard.classification.domains)
		assertEquals(5, vCard.attributes.cost)
		assertEquals(4, vCard.attributes.primary)
		assertEquals(1, vCard.attributes.secondary)
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
	fun `an image URL from an unknown host gets no invented variants`() {
		assertNull(RiftcodexMapper.thumbnailUrl("https://example.com/card.png"))
		assertNull(RiftcodexMapper.displayUrl("https://example.com/card.png"))
	}

	@Test
	fun `the display variant asks for the asset's own width -- not a bigger one`() = runTest {
		// The CDN will resize up to any width, but the source is 744 px and the upscale carries no
		// detail -- its w=1488 render is measurably less sharp than a plain Lanczos upscale of the
		// native one. Asking for more spends megabytes on interpolation.
		val vProvider = RiftcodexProvider(
			jsonClient("""{"items":[${RiftcodexFixtures.CARD_ORDINARY}],"total":1,"page":1,"size":100,"pages":1}"""),
		)

		val vDisplay = vProvider.listCards(request()).cards.single().artwork.displayUrl!!

		// 744 is what the asset's own filename says: `...-744x1039.png`.
		assertTrue(vDisplay.contains("w=744"), vDisplay)
		assertTrue(vDisplay.contains("fm=webp"), vDisplay)
		assertTrue(vDisplay.contains("q=90"), vDisplay)
	}

	@Test
	fun `the native width is read from the asset filename rather than assumed`() {
		// Riftbound cards are not all the same size: 744x1039 and 744x1040 both occur.
		assertEquals(744, RiftcodexMapper.nativeWidthOf("https://x/a-744x1040.png?y=1"))
		assertEquals(1024, RiftcodexMapper.nativeWidthOf("https://x/a-1024x768.jpg"))
		assertNull(RiftcodexMapper.nativeWidthOf("https://x/no-dimensions.png"))
	}

	@Test
	fun `a card with no dimensions in its filename still gets a display variant`() {
		// No `w` at all, which returns the original -- right, since the point is to avoid resampling.
		val vUrl = RiftcodexMapper.displayUrl(
			"https://cmsassets.rgpub.io/sanity/images/x/y/plain.png?accountingTag=RB",
		)!!

		assertFalse(vUrl.contains("w="), vUrl)
		assertTrue(vUrl.contains("fm=webp"), vUrl)
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
		assertNull(vCard.attributes.cost)
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
	fun `card identity is inferred from the name -- and scoped to one set`() = runTest {
		// This used to assert the opposite -- that no identity was ever invented -- and the change
		// is deliberate. Riftcodex states no cross-printing relationship, so the alternative was a
		// detail screen that could never list a card's other artwork for the game the app was built
		// for. The rule came from the project owner and was checked against all 400 OGN records
		// before being taken: see `RiftcodexMapper.identityOf`.
		val vProvider = RiftcodexProvider(jsonClient(RiftcodexFixtures.CARDS_SHARED_COLLECTOR_NUMBER))

		val vCards = vProvider.listCards(request()).cards

		vCards.forEach { vCard ->
			val vIdentity = assertNotNull(vIdentity(vCard), "${vCard.displayName} has no identity")
			// Set-scoped, so nothing claims a reprint in a later set is the same card -- which is
			// a relationship Riftcodex genuinely does not state.
			assertTrue(
				vIdentity.startsWith("${vCard.setCode}:"),
				"identity ${'$'}vIdentity is not scoped to a set",
			)
		}
	}

	private fun vIdentity(card: com.bitsycore.cardbrowser.core.model.CardPrinting) =
		card.identity?.id?.local

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
		val vProvider = RiftcodexProvider(jsonClient("{}"))
		val vCapabilities = vProvider.capabilities

		// The game is in the type now, not in a `Set<Game>` the capabilities carried.
		assertEquals(RiftboundGame, vProvider.game)

		assertEquals(100, vCapabilities.maxPageSize)
		// Nothing is filtered remotely, text included: `/cards/search` is not a name search --
		// see the class doc -- so every filter is honoured locally against the complete set.
		assertEquals(emptySet(), vCapabilities.filtering.remote)
		assertTrue(CardFilterField.TEXT in vCapabilities.filtering.localOnly)
		assertTrue(CardFilterField.RARITY in vCapabilities.filtering.localOnly)
		// And no cross-set search, for the same reason.
		assertFalse(vCapabilities.data.crossSetSearch)
		// Neither is offered at all, which is what keeps them off the filter sheet.
		assertFalse(CardFilterField.FINISH in vCapabilities.filtering.supported)
		assertFalse(CardFilterField.LANGUAGE in vCapabilities.filtering.supported)
		assertFalse(vCapabilities.data.finishes)
		// True, and inferred rather than stated -- the one place in the project that is allowed.
		assertTrue(vCapabilities.data.cardIdentity)
		assertFalse(vCapabilities.data.cardmarketProductMapping)
		assertEquals(setOf(CardLanguage.ENGLISH), vCapabilities.data.languages)
	}

	@Test
	fun `a rarity filter is reported as needing the complete set`() {
		val vProvider = RiftcodexProvider(jsonClient("{}"))
		val vCapabilities = vProvider.capabilities

		assertEquals(RiftboundGame, vProvider.game)

		assertTrue(
			vCapabilities.filtering.requiresCompleteSet(CardQuery(rarities = setOf("Epic"))),
		)
		// Text needs it too. The server's search endpoint answers, but not with name matches, so
		// the app matches names itself against the set it already holds.
		assertTrue(vCapabilities.filtering.requiresCompleteSet(CardQuery(text = "annie")))
	}

	// ============
	//  Paging and requests

	@Test
	fun `hasMore comes from the server's page count -- not from how full the page was`() = runTest {
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
	fun `the search endpoint is never used -- whatever the query says`() = runTest {
		// A regression guard on a deliberate decision. `/cards/search` exists, takes a `query` and
		// answers 200 -- and its matching is unusable: re-checked 2026-09-08, `query=Cull` returns
		// nothing while `query=Cull the Weak` returns "Aspirant's Climb". Wiring the search box to
		// it produced "no results" for most of what a user typed.
		//
		// So text is matched locally, and the adapter must not quietly go back to the endpoint.
		val vPaths = mutableListOf<String>()
		val vQueryParameters: MutableList<String?> = mutableListOf()
		val vClient = clientOf { vRequest ->
			vPaths += vRequest.url.encodedPath
			vQueryParameters += vRequest.url.parameters["query"]
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

		assertEquals(listOf("/cards", "/cards", "/cards"), vPaths)
		// And the text is not smuggled through as a parameter either.
		assertEquals(listOf<String?>(null, null, null), vQueryParameters)
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
	fun `a 404 on detail is an answer of null -- not a failure`() = runTest {
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
			RiftcodexProvider(vClient).listSets()
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
			RiftcodexProvider(vClient).listSets()
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
			RiftcodexProvider(vClient).listSets()
		}

		assertFalse(vError.isTransient)
	}

	@Test
	fun `cancellation propagates as cancellation -- never as a ProviderError`() = runTest {
		// The behaviour that keeps a superseded set selection from painting an error over the
		// screen the user just opened.
		val vGate = CompletableDeferred<Unit>()
		val vClient = clientOf {
			vGate.await()
			respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
		}
		val vProvider = RiftcodexProvider(vClient)

		val vJob = async { vProvider.listSets() }
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
