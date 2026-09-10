package com.bitsycore.cardbrowser.providers.ygoprodeck

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.data.net.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which languages a Yu-Gi-Oh! set is really published in, rather than which ones the source can
 * serve in general.
 *
 * ## The fault
 *
 * Reported 2026-09-10: the set list offered French for every set, so Beyond the Brave opened onto
 * an empty grid and Magnificent Maestros onto four of its twenty-four cards. Measured against the
 * live API the same day, both being late-2026 releases:
 *
 * ```
 *   cardset=Beyond the Brave                  8 cards
 *   cardset=Beyond the Brave&language=fr      400 "No card matching your query"
 *   cardset=Magnificent Maestros             24 cards
 *   cardset=Magnificent Maestros&language=fr  4 cards
 * ```
 *
 * `cardsets.php` says nothing about languages, so every set arrived claiming none, and the layer
 * above then fell back to all seven this source can serve. That fallback is right for a source
 * that translates everything and wrong for one that translates as it goes -- and the only way to
 * tell the two apart is to ask, which is what [CardProvider.confirmLanguages] is for.
 *
 * The grid already refuses to open a set in a language with no cards; it just had nothing to go on
 * for this source, because the override did not exist.
 */
class YgoprodeckLanguagesTest {

	private val mSetId = SourceId(ProviderId("ygoprodeck"), "Beyond the Brave")

	/**
	 * Answers by language, the way the real API does.
	 *
	 * @param translated the languages that have cards. English is selected by *omitting* the
	 *   parameter, which is the quirk this has to reproduce faithfully or the probe would test
	 *   nothing
	 */
	private fun providerOf(
		translated: Set<CardLanguage>,
		onRequest: (String?) -> Unit = {},
	): YgoprodeckProvider {
		val vClient = HttpClient(
			MockEngine { vRequest ->
				val vLanguage = vRequest.url.parameters["language"]
				onRequest(vLanguage)
				val vHas = if (vLanguage == null) {
					CardLanguage.ENGLISH in translated
				} else {
					translated.any { it.code == vLanguage }
				}
				if (vHas) {
					respond(
						content = ONE_CARD,
						status = HttpStatusCode.OK,
						headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
					)
				} else {
					// The real shape of "nothing matched": a 400 with an error body, not an empty
					// list. Treating it as a failure would leave every language unconfirmed.
					respond(
						content = NO_MATCH,
						status = HttpStatusCode.BadRequest,
						headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
					)
				}
			},
		) {
			expectSuccess = true
			install(ContentNegotiation) { json(HttpClientFactory.json) }
		}
		return YgoprodeckProvider(vClient)
	}

	@Test
	fun `a set with no translations confirms English alone`() = runTest {
		val vProvider = providerOf(translated = setOf(CardLanguage.ENGLISH))

		val vConfirmed = vProvider.confirmLanguages(mSetId, ALL_SEVEN)

		assertEquals(setOf(CardLanguage.ENGLISH), vConfirmed)
	}

	@Test
	fun `a partly translated set keeps only what it has`() = runTest {
		val vProvider = providerOf(
			translated = setOf(CardLanguage.ENGLISH, CardLanguage.FRENCH, CardLanguage.GERMAN),
		)

		assertEquals(
			setOf(CardLanguage.ENGLISH, CardLanguage.FRENCH, CardLanguage.GERMAN),
			vProvider.confirmLanguages(mSetId, ALL_SEVEN),
		)
	}

	@Test
	fun `English is probed by omitting the parameter -- sending it is an error`() = runTest {
		// `language=en` is not a valid value: English is the default and is selected by leaving the
		// parameter off. A probe that sent it would report English absent for every set in the game.
		val vSeen = mutableListOf<String?>()
		providerOf(translated = ALL_SEVEN, onRequest = { vSeen += it })
			.confirmLanguages(mSetId, ALL_SEVEN)

		assertTrue(null in vSeen, "English must be probed with no language parameter")
		assertTrue("en" !in vSeen, "Got $vSeen")
	}

	@Test
	fun `a probe that fails keeps its language`() = runTest {
		// A language is dropped on evidence that the set is not published in it. A server error is
		// not that evidence, and dropping on it would hide editions whenever the API wobbled.
		val vClient = HttpClient(
			MockEngine {
				respond(
					content = "upstream is unwell",
					status = HttpStatusCode.InternalServerError,
					headers = headersOf("Content-Type", ContentType.Text.Plain.toString()),
				)
			},
		) {
			expectSuccess = true
			install(ContentNegotiation) { json(HttpClientFactory.json) }
		}

		assertEquals(ALL_SEVEN, YgoprodeckProvider(vClient).confirmLanguages(mSetId, ALL_SEVEN))
	}

	private companion object {

		val ALL_SEVEN = setOf(
			CardLanguage.FRENCH,
			CardLanguage.JAPANESE,
			CardLanguage.ENGLISH,
			CardLanguage.KOREAN,
			CardLanguage.GERMAN,
			CardLanguage.ITALIAN,
			CardLanguage.PORTUGUESE,
		)

		/** Trimmed from a real `num=1` response: only what the probe reads matters. */
		const val ONE_CARD = """
			{"data":[{"id":46986414,"name":"Dark Magician","type":"Normal Monster",
			"card_images":[{"id":46986414,"image_url":"https://images.ygoprodeck.com/images/cards/46986414.jpg"}]}]}
		"""

		/** The real 400 body, which this API uses to mean "nothing matched". */
		const val NO_MATCH = """{"error":"No card matching your query was found in the database."}"""
	}
}
