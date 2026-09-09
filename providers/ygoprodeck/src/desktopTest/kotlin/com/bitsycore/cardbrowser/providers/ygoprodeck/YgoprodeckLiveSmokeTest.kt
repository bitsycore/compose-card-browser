package com.bitsycore.cardbrowser.providers.ygoprodeck

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import com.bitsycore.cardbrowser.core.provider.CardSearchRequest
import com.bitsycore.cardbrowser.data.net.HttpClientFactory
import io.ktor.client.request.head
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Talks to the real YGOPRODeck API.
 *
 * **Not part of the ordinary test run.** Run it deliberately:
 *
 * ```
 * ./gradlew :providers:ygoprodeck:liveProviderTest
 * ```
 */
class YgoprodeckLiveSmokeTest {

	private fun provider() = YgoprodeckProvider(HttpClientFactory.create())

	private val mMetalRaiders = SourceId(YgoprodeckProvider.PROVIDER_ID, "Metal Raiders")

	@Test
	fun `the set list loads with counts and dates`() = runBlocking<Unit> {
		val vSets = provider().listSets(Game.YU_GI_OH)

		assertTrue(vSets.size > 500, "Expected a large catalogue, got ${vSets.size}")

		val vMetalRaiders = vSets.firstOrNull { it.name == "Metal Raiders" }
		assertNotNull(vMetalRaiders, "Metal Raiders is missing from the live catalogue")
		// The set id is the *name*, because that is the only thing `cardinfo.php` filters on.
		assertEquals("Metal Raiders", vMetalRaiders.id.local)
		assertTrue((vMetalRaiders.cardCount ?: 0) > 100)
		assertNotNull(vMetalRaiders.releaseDate)
	}

	@Test
	fun `a set pages with the provider's own total`() = runBlocking<Unit> {
		val vFirst = provider().listCards(CardPageRequest(setId = mMetalRaiders, pageSize = 100))

		assertEquals(100, vFirst.cards.size)
		assertNotNull(vFirst.totalCount, "The meta block's total_rows is what proves completeness")
		assertTrue(vFirst.hasMore, "144 cards do not fit in one page of 100")

		val vSecond = provider().listCards(
			CardPageRequest(setId = mMetalRaiders, page = 2, pageSize = 100),
		)
		assertTrue(vSecond.cards.isNotEmpty())
		assertTrue(!vSecond.hasMore, "The second page should exhaust the set")
		// Real paging, not the same page twice.
		assertTrue(
			vFirst.cards.map { it.id }.intersect(vSecond.cards.map { it.id }.toSet()).isEmpty(),
			"Pages must not overlap",
		)
	}

	@Test
	fun `collector number and rarity come from this set's own printing`() = runBlocking<Unit> {
		val vPage = provider().listCards(
			CardPageRequest(setId = mMetalRaiders, pageSize = 100, language = CardLanguage.ENGLISH),
		)

		val vCard = vPage.cards.first { it.displayName == "7 Colored Fish" }
		// A card carries every set it has ever appeared in -- Gold Series, Speed Duel, Starter
		// Deck: Joey and more for this one. The number shown must be the Metal Raiders printing's.
		assertEquals("Metal Raiders", vCard.setName)
		assertTrue(
			vCard.providerRawCollectorNumber.startsWith("MRD"),
			"Expected an MRD printing, got ${vCard.providerRawCollectorNumber}",
		)
		assertNotNull(vCard.classification.rarity)
	}

	@Test
	fun `every non-English language the adapter declares returns translated text`() = runBlocking<Unit> {
		// Worth measuring rather than reading off the docs: the endpoint's own error message lists
		// only `fr`, `de`, `it` and `pt`, and yet `ja` and `ko` both answer with translated names.
		// The message is out of date, and this test is what the capability list actually rests on.
		val vProvider = provider()
		val vEnglish = vProvider.listCards(
			CardPageRequest(setId = mMetalRaiders, pageSize = 5, language = CardLanguage.ENGLISH),
		)

		for (vLanguage in vProvider.capabilities.data.languages - CardLanguage.ENGLISH) {
			val vPage = vProvider.listCards(
				CardPageRequest(setId = mMetalRaiders, pageSize = 5, language = vLanguage),
			)
			assertTrue(vPage.cards.isNotEmpty(), "${vLanguage.code} returned nothing")
			assertEquals(vLanguage, vPage.cards.first().text.language)
			// A locale that quietly fell back to English would be indistinguishable from a working
			// one without this.
			assertTrue(
				vPage.cards.map { it.displayName } != vEnglish.cards.take(vPage.cards.size).map { it.displayName },
				"${vLanguage.code} returned the English names",
			)
		}
	}

	@Test
	fun `English must not send a language parameter`() = runBlocking<Unit> {
		// `language=en` is not a valid value -- English is selected by omitting the parameter, and
		// sending it produces an error rather than English.
		val vPage = provider().listCards(
			CardPageRequest(setId = mMetalRaiders, pageSize = 5, language = CardLanguage.ENGLISH),
		)

		assertTrue(vPage.cards.isNotEmpty(), "English should work, which means no parameter was sent")
	}

	@Test
	fun `omitting a language gets the app's top preference -- not English`() = runBlocking<Unit> {
		// `resolveLanguage(null)` walks `CardLanguage.PREFERENCE_ORDER` and takes the first the
		// provider carries, which for a source serving all four is French. This is the intended
		// behaviour and it is easy to mistake for a bug -- a test written without an explicit
		// language gets French names back and looks broken.
		val vPage = provider().listCards(CardPageRequest(setId = mMetalRaiders, pageSize = 5))

		assertTrue(vPage.cards.isNotEmpty())
		assertEquals(CardLanguage.FRENCH, vPage.cards.first().text.language)
	}

	@Test
	fun `a search matching nothing is an empty result rather than a failure`() = runBlocking<Unit> {
		// The API answers 400 with an error body, not an empty list. Left unhandled that would put
		// an error screen over a search that simply found nothing.
		val vPage = provider().searchAllSets(
			CardSearchRequest(game = Game.YU_GI_OH, text = "zzzzz no such card zzzzz"),
		)

		assertTrue(vPage.cards.isEmpty())
		assertTrue(!vPage.hasMore)
	}

	@Test
	fun `cross-set search finds a card and gives it a small image`() = runBlocking<Unit> {
		val vPage = provider().searchAllSets(
			CardSearchRequest(
				game = Game.YU_GI_OH,
				text = "Dark Magician",
				language = CardLanguage.ENGLISH,
				pageSize = 10,
			),
		)

		assertTrue(vPage.cards.isNotEmpty())
		assertTrue(
			vPage.cards.all { it.displayName.contains("Dark Magician", ignoreCase = true) },
			"Every result should actually match",
		)
		val vArtwork = vPage.cards.first().artwork
		assertTrue(
			vArtwork.thumbnailUrl?.contains("cards_small") == true,
			"Grid art should use the small variant, got ${vArtwork.thumbnailUrl}",
		)
	}
	@Test
	fun `sets that publish box art carry it as a symbol`() = runBlocking<Unit> {
		val vClient = HttpClientFactory.create()
		val vSets = YgoprodeckProvider(vClient).listSets(Game.YU_GI_OH)

		val vWith = vSets.filter { it.symbol != null }
		assertTrue(vWith.isNotEmpty(), "Some sets should publish box art")
		assertTrue(vWith.none { it.symbol!!.isMonochrome }, "These are full-colour JPEGs")

		val vResponse: HttpResponse = vClient.head(vWith.first().symbol!!.url)
		assertEquals(HttpStatusCode.OK, vResponse.status, "Set image is not loading")
	}
}
