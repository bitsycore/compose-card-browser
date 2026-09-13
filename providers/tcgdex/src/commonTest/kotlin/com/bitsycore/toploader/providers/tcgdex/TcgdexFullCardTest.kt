package com.bitsycore.toploader.providers.tcgdex

import com.bitsycore.toploader.core.model.CardLanguage
import com.bitsycore.toploader.core.model.ProviderId
import com.bitsycore.toploader.core.model.SourceId
import com.bitsycore.toploader.core.provider.CardPageRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A set's cards arrive with their rarity, category and type -- which the set endpoint does not give.
 *
 * ## The fault
 *
 * `GET /{lang}/sets/{id}` returns **brief** cards: `{id, localId, name, image}` and nothing else.
 * Every Pokémon card in the grid therefore had no rarity, no category and no type, so the filter
 * sheet drew none of those three sections -- they are built from the facets of what is on screen,
 * and what was on screen knew nothing. Reported 2026-09-13 as "no proper filter per rarity, colour,
 * type".
 *
 * One GraphQL query fills them in, and its shape was measured the same day: the root `cards`
 * resolver returns full cards, its `id` filter matches on a prefix, and a card id is
 * `{setId}-{localId}` -- so `id: "sv08-"` answered all 252 cards of Surging Sparks in one request.
 *
 * What this pins is the merge, which is the part with a choice in it: names and pictures stay the
 * locale's, and only the fields the brief cannot carry come from the other request.
 */
class TcgdexFullCardTest {

	private val mProvider = ProviderId("tcgdex")

	private var mGraphQlCalls = 0

	/**
	 * TCGdex with one set, answering the way the real one does.
	 *
	 * The REST set gives two brief cards with French names; the GraphQL query gives the same two
	 * with English names and the facts. If the merge took the whole GraphQL card the names would
	 * come back English, which is what the assertions below are really watching for.
	 */
	private fun provider(): TcgdexProvider {
		val vClient = HttpClient(
			MockEngine { vRequest ->
				val vSegments = vRequest.url.segments.filter { it.isNotBlank() }
				when {
					vSegments.lastOrNull() == "graphql" -> {
						val vBody = vRequest.body.toByteReadPacketText()
						if ("cards(" in vBody) {
							mGraphQlCalls++
							respond(
								"""{"data":{"cards":[
									{"id":"sv08-001","localId":"001","name":"Exeggcute",
									 "rarity":"Common","category":"Pokemon","types":["Grass"]},
									{"id":"sv08-004","localId":"004","name":"Durant ex",
									 "rarity":"Double rare","category":"Pokemon","types":["Grass"],
									 "hp":190}
								]}}""".trimIndent(),
								HttpStatusCode.OK,
								headersOf("Content-Type", "application/json"),
							)
						} else {
							respond(
								"""{"data":{"sets":[]}}""",
								HttpStatusCode.OK,
								headersOf("Content-Type", "application/json"),
							)
						}
					}

					vSegments.getOrNull(2) == "sets" && vSegments.size == 4 -> respond(
						"""{"id":"sv08","name":"Étincelles Déferlantes","cards":[
							{"id":"sv08-001","localId":"001","name":"Nœunœuf",
							 "image":"https://assets.tcgdex.net/fr/sv/sv08/001"},
							{"id":"sv08-004","localId":"004","name":"Fourmidable ex",
							 "image":"https://assets.tcgdex.net/fr/sv/sv08/004"},
							{"id":"sv08-009","localId":"009","name":"Inconnu",
							 "image":"https://assets.tcgdex.net/fr/sv/sv08/009"}
						]}""".trimIndent(),
						HttpStatusCode.OK,
						headersOf("Content-Type", "application/json"),
					)

					else -> respond(
						"""[{"id":"sv08","name":"Étincelles Déferlantes"}]""",
						HttpStatusCode.OK,
						headersOf("Content-Type", "application/json"),
					)
				}
			},
		) {
			install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
		}
		return TcgdexProvider(vClient)
	}

	@Test
	fun `a card carries its rarity and its type -- and keeps the locale's name`() = runTest {
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(mProvider, "sv08"),
				page = 1,
				pageSize = 1000,
				language = CardLanguage.FRENCH,
			),
		)

		val vDurant = vPage.cards.single { it.collectorNumber == "004" }
		assertEquals("Double rare", vDurant.classification.rarity)
		assertEquals("Pokemon", vDurant.classification.type)
		assertEquals(listOf("grass"), vDurant.classification.domains)
		assertEquals(190, vDurant.attributes.primary)
		// The name is the one the French endpoint served, not the English one the facts came with.
		assertEquals("Fourmidable ex", vDurant.text.name)
		assertTrue(
			vDurant.artwork.imageUrl?.contains("/fr/") == true,
			"the picture is the locale's too: ${vDurant.artwork.imageUrl}",
		)
	}

	@Test
	fun `a card the second request did not cover keeps what the first one knew`() = runTest {
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(mProvider, "sv08"),
				page = 1,
				pageSize = 1000,
				language = CardLanguage.FRENCH,
			),
		)

		// Unknown, not absent. A card the enrichment missed is still a card, with the name and the
		// picture the set endpoint gave it -- which is exactly what every card had before.
		val vUnknown = vPage.cards.single { it.collectorNumber == "009" }
		assertEquals("Inconnu", vUnknown.text.name)
		assertEquals(null, vUnknown.classification.rarity)
		assertEquals(3, vPage.cards.size, "no card may be dropped by the merge")
	}

	@Test
	fun `the whole set costs one extra request`() = runTest {
		provider().listCards(
			CardPageRequest(
				setId = SourceId(mProvider, "sv08"),
				page = 1,
				pageSize = 1000,
				language = CardLanguage.FRENCH,
			),
		)

		// The point of the GraphQL query. One per set, not one per card -- 252 requests for Surging
		// Sparks is what the obvious version costs.
		assertEquals(1, mGraphQlCalls)
	}
}

/** The request body as text, so the mock can tell the two GraphQL queries apart. */
private suspend fun io.ktor.http.content.OutgoingContent.toByteReadPacketText(): String =
	when (this) {
		is io.ktor.http.content.TextContent -> text
		is io.ktor.http.content.OutgoingContent.ByteArrayContent -> bytes().decodeToString()
		is io.ktor.http.content.OutgoingContent.ReadChannelContent ->
			readFrom().readRemaining().readString()
		else -> ""
	}
