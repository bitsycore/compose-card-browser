package com.bitsycore.cardbrowser.providers.tcgdex

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A set is asked for in its own product line's locale, not in the user's.
 *
 * ## The fault
 *
 * Reported 2026-09-13: every Japanese, Taiwanese and Chinese Pokémon set opened onto nothing -- no
 * cards, no images -- while its row in the set list showed a card count. Measured against the live
 * API the same day:
 *
 * ```
 *   GET /v2/ja/sets/SV1a   200, 103 cards
 *   GET /v2/en/sets/SV1a   404
 *   GET /v2/fr/sets/SV1a   404
 *   GET /v2/en/sets/SC1D   404
 * ```
 *
 * A TCGdex set id exists only inside the locales of the line that publishes it, and the adapter
 * resolved its locale from the user's browsing language alone. So anyone not browsing in Japanese,
 * Korean or Chinese could list 265 sets and open none of them.
 *
 * The mock answers exactly that way: 404 for a set outside the requested locale's line. A version
 * that ignores the line fails here with an empty page rather than by asking the wrong URL, which
 * is the same thing the user saw.
 */
class TcgdexSetLocaleTest {

	private val mProvider = ProviderId("tcgdex")

	/** Every locale this asks about, recorded in order, so the test can name what was requested. */
	private val mAsked = mutableListOf<String>()

	/**
	 * TCGdex, cut down to two sets: `sv08` in the western locales and `SV1a` in the Japanese ones.
	 *
	 * The catalogue endpoint answers per locale, which is what the adapter builds its line table
	 * from, and the set endpoint 404s for an id the locale does not carry -- the behaviour that
	 * made this a bug rather than a wrong translation.
	 */
	private fun provider(): TcgdexProvider {
		val vWestern = setOf("en", "fr", "de", "es", "it", "pt", "ru")
		val vJapan = setOf("ja", "ko")
		val vClient = HttpClient(
			MockEngine { vRequest ->
				val vSegments = vRequest.url.segments.filter { it.isNotBlank() }
				val vLocale = vSegments.getOrNull(1).orEmpty()
				val vIsSetList = vSegments.getOrNull(2) == "sets" && vSegments.size == 3
				val vSetId = if (vSegments.getOrNull(2) == "sets") vSegments.getOrNull(3) else null
				val vHolds = when (vLocale) {
					in vWestern -> setOf("sv08")
					in vJapan -> setOf("SV1a")
					else -> emptySet()
				}
				when {
					vRequest.method.value == "POST" -> respond(
						"""{"data":{"sets":[]}}""",
						HttpStatusCode.OK,
						headersOf(HttpHeadersContentType, APPLICATION_JSON),
					)

					vIsSetList -> respond(
						vHolds.joinToString(",", "[", "]") { """{"id":"$it","name":"$it"}""" },
						HttpStatusCode.OK,
						headersOf(HttpHeadersContentType, APPLICATION_JSON),
					)

					vSetId != null -> {
						mAsked += "$vLocale/$vSetId"
						if (vSetId in vHolds) {
							respond(
								"""{"id":"$vSetId","name":"$vSetId","cards":[
									{"id":"$vSetId-001","localId":"001","name":"One",
									 "image":"https://assets.tcgdex.net/$vLocale/x/$vSetId/001"}
								]}""".trimIndent(),
								HttpStatusCode.OK,
								headersOf(HttpHeadersContentType, APPLICATION_JSON),
							)
						} else {
							respond("{}", HttpStatusCode.NotFound)
						}
					}

					else -> respond("[]", HttpStatusCode.OK, headersOf(HttpHeadersContentType, APPLICATION_JSON))
				}
			},
		) {
			install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
		}
		return TcgdexProvider(vClient)
	}

	@Test
	fun `a japanese set browsed in english is fetched in japanese`() = runTest {
		val vProvider = provider()
		// The set list first, because that is what tells the adapter which line `SV1a` belongs to
		// -- exactly as the app does it, and the catalogue is memoised afterwards.
		vProvider.listSets(CardLanguage.ENGLISH)

		val vPage = vProvider.listCards(
			CardPageRequest(
				setId = SourceId(mProvider, "SV1a"),
				page = 1,
				pageSize = 100,
				language = CardLanguage.ENGLISH,
			),
		)

		assertEquals(listOf("ja/SV1a"), mAsked, "the locale has to come from the set, not the user")
		assertEquals(1, vPage.cards.size, "a Japanese set browsed in English served no cards")
		// And it says so. The card is Japanese because Japanese is what answered, which is the
		// difference between a record that is right and one that is merely populated.
		assertTrue(
			CardLanguage.JAPANESE in vPage.cards.single().languages.confirmed,
			"the record should be stamped with the language that was served",
		)
	}

	@Test
	fun `an international set browsed in english is still fetched in english`() = runTest {
		val vProvider = provider()
		vProvider.listSets(CardLanguage.ENGLISH)

		vProvider.listCards(
			CardPageRequest(
				setId = SourceId(mProvider, "sv08"),
				page = 1,
				pageSize = 100,
				language = CardLanguage.ENGLISH,
			),
		)

		assertEquals(listOf("en/sv08"), mAsked, "the requested language wins when its line has it")
	}

	@Test
	fun `a japanese set browsed in korean is fetched in korean`() = runTest {
		val vProvider = provider()
		vProvider.listSets(CardLanguage.ENGLISH)

		// Korea prints the Japan line, so Korean is one of that line's languages and the request
		// stands. Falling back to the line's first language regardless would answer in Japanese.
		vProvider.listCards(
			CardPageRequest(
				setId = SourceId(mProvider, "SV1a"),
				page = 1,
				pageSize = 100,
				language = CardLanguage.KOREAN,
			),
		)

		assertEquals(listOf("ko/SV1a"), mAsked)
	}

	private companion object {

		const val HttpHeadersContentType = "Content-Type"
		const val APPLICATION_JSON = "application/json"
	}
}
