package com.bitsycore.cardbrowser.providers.wuwa

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
 * Talks to the real UCP card API.
 *
 * **Not part of the ordinary test run.** Run it deliberately:
 *
 * ```
 * ./gradlew :providers:wuwa:liveProviderTest
 * ```
 *
 * This is the one provider in the app with no published API and no stability promise behind it, so
 * these checks matter more here than anywhere else: they are the only warning that would come
 * before the game silently stopped working.
 */
class WuwaLiveSmokeTest {

	private fun provider() = WuwaProvider(HttpClientFactory.create())

	@Test
	fun `sets are derived from card codes`() = runBlocking<Unit> {
		val vSets = provider().listSets(Game.WUTHERING_WAVES)

		// The API has no set entity at all; these come from the prefix of each card's code.
		assertTrue(vSets.size >= 2, "Expected at least SD01 and BP01, got ${vSets.map { it.code }}")
		assertTrue(vSets.any { it.code == "SD01" }, "Got ${vSets.map { it.code }}")
		assertTrue(vSets.any { it.code == "BP01" }, "Got ${vSets.map { it.code }}")
		assertTrue(vSets.all { (it.cardCount ?: 0) > 0 }, "Every derived set should have cards")
		// Nothing states a release date, and none is invented.
		assertTrue(vSets.all { it.releaseDate == null })
		// The name is the code, because the product-name mapping could not be verified.
		assertTrue(vSets.all { it.name == it.code })
	}

	@Test
	fun `a set arrives complete in one request`() = runBlocking<Unit> {
		val vPage = provider().listCards(
			CardPageRequest(setId = SourceId(WuwaProvider.PROVIDER_ID, "SD01")),
		)

		assertTrue(vPage.cards.isNotEmpty())
		assertTrue(!vPage.hasMore)
		assertEquals(vPage.cards.size, vPage.totalCount)
		assertTrue(vPage.cards.all { it.setCode == "SD01" }, "Filtering by code prefix leaked")
		// "SD01-001" becomes collector number "001".
		assertTrue(vPage.cards.all { !it.collectorNumber.contains('-') })
	}

	@Test
	fun `Japanese is what comes back -- and it is labelled Japanese`() = runBlocking<Unit> {
		val vPage = provider().listCards(
			CardPageRequest(setId = SourceId(WuwaProvider.PROVIDER_ID, "SD01")),
		)

		val vCard = vPage.cards.first()
		assertEquals(CardLanguage.JAPANESE, vCard.text.language)
		// Requesting a language the provider does not carry must not change what it says it is.
		val vAsked = provider().listCards(
			CardPageRequest(
				setId = SourceId(WuwaProvider.PROVIDER_ID, "SD01"),
				language = CardLanguage.ENGLISH,
			),
		)
		assertEquals(CardLanguage.JAPANESE, vAsked.cards.first().text.language)
		assertTrue(vAsked.cards.isNotEmpty(), "Asking for English must not empty the set")
	}

	@Test
	fun `the detail endpoint fills in what the list omits`() = runBlocking<Unit> {
		val vList = provider().listCards(
			CardPageRequest(setId = SourceId(WuwaProvider.PROVIDER_ID, "SD01")),
		)
		val vBrief = vList.cards.first()
		// The list carries six fields; rarity is not one of them.
		assertEquals(null, vBrief.classification.rarity)

		val vDetail = provider().cardDetail(vBrief.id)
		assertNotNull(vDetail, "${vBrief.id.local} should resolve through /web/card/info")
		assertEquals(vBrief.id, vDetail.id)
		assertNotNull(vDetail.classification.rarity, "Detail should supply the rarity")
		assertTrue(!vDetail.text.rules.isNullOrBlank(), "Detail should supply the rules text")
	}

	@Test
	fun `the API's dash placeholder never becomes a filter chip`() = runBlocking<Unit> {
		val vList = provider().listCards(
			CardPageRequest(setId = SourceId(WuwaProvider.PROVIDER_ID, "SD01")),
		)
		// Several fields come back as a literal "-" when they do not apply. Shown as-is they would
		// produce a filter chip reading "-".
		val vDetails = vList.cards.take(8).mapNotNull { provider().cardDetail(it.id) }

		assertTrue(vDetails.isNotEmpty())
		assertTrue(vDetails.none { "-" in it.classification.domains }, "A dash leaked into domains")
		assertTrue(vDetails.none { "-" in it.tags }, "A dash leaked into tags")
		assertTrue(vDetails.none { it.classification.rarity == "-" })
	}

	@Test
	fun `keyword search spans sets`() = runBlocking<Unit> {
		val vPage = provider().searchAllSets(
			CardSearchRequest(game = Game.WUTHERING_WAVES, text = "漂泊者", pageSize = 20),
		)

		assertTrue(vPage.cards.isNotEmpty(), "漂泊者 should match the Rover cards")
		assertNotNull(vPage.totalCount)
		assertTrue(vPage.cards.all { it.text.language == CardLanguage.JAPANESE })
	}

	@Test
	fun `card art actually loads`() = runBlocking<Unit> {
		val vClient = HttpClientFactory.create()
		val vPage = WuwaProvider(vClient).listCards(
			CardPageRequest(setId = SourceId(WuwaProvider.PROVIDER_ID, "SD01")),
		)

		val vUrl = vPage.cards.first().artwork.imageUrl
		assertTrue(vUrl.endsWith(".webp"), "Art should already be WebP, got $vUrl")
		val vResponse: HttpResponse = vClient.head(vUrl)
		assertEquals(HttpStatusCode.OK, vResponse.status, "Card art is not loading")
	}
}
