package com.bitsycore.cardbrowser.providers.altered

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
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
 * Talks to the real Altered mirror.
 *
 * **Not part of the ordinary test run.** Run it deliberately:
 *
 * ```
 * ./gradlew :providers:altered:liveProviderTest
 * ```
 *
 * This one carries an extra burden the others do not: the mirror exists *because* the official API
 * is gone, so one check here confirms the images actually load. A mirror that stopped serving art
 * would leave a browsable set of blank tiles, which is the failure mode worth catching early.
 */
class AlteredLiveSmokeTest {

	private fun provider() = AlteredProvider(HttpClientFactory.create())

	@Test
	fun `the set index loads with localised names`() = runBlocking {
		val vFrench = provider().listSets(Game.ALTERED, CardLanguage.FRENCH)
		val vEnglish = provider().listSets(Game.ALTERED, CardLanguage.ENGLISH)

		assertTrue(vFrench.size >= 20, "Expected ~20 sets, got ${vFrench.size}")
		assertEquals(vFrench.size, vEnglish.size, "Both locales should list the same sets")

		val vAlize = vFrench.firstOrNull { it.id.local == "ALIZE" }
		assertNotNull(vAlize, "ALIZE is missing from the index")
		assertEquals("TBF", vAlize.code)
		// The French name really is French, which is the point of routing this game at all.
		assertEquals("Épreuve du froid", vAlize.name)
		// Deliberately absent -- `createdAt` is not a release date. See `AlteredMapper.toSet`.
		assertEquals(null, vAlize.releaseDate)
	}

	@Test
	fun `a set arrives complete in one file`() = runBlocking {
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(AlteredProvider.PROVIDER_ID, "ALIZE"),
				language = CardLanguage.FRENCH,
			),
		)

		assertTrue(vPage.cards.size > 300, "ALIZE should be ~327 cards, got ${vPage.cards.size}")
		assertTrue(!vPage.hasMore)
		assertEquals(vPage.cards.size, vPage.totalCount)
	}

	@Test
	fun `two print runs of one card stay two cards`() = runBlocking {
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(AlteredProvider.PROVIDER_ID, "ALIZE"),
				language = CardLanguage.FRENCH,
			),
		)

		// The reason the id is the reference and not the collector number: ALIZE contains both
		// ALT_ALIZE_A_AX_35_C and ALT_ALIZE_B_AX_35_C, and both state collector number
		// TBF-005-C-FR. Keying on the collector number would silently drop one of every such pair.
		val vIds = vPage.cards.map { it.id.qualified }
		assertEquals(vIds.size, vIds.distinct().size, "Ids must be unique")

		val vCollectorNumbers = vPage.cards.map { it.collectorNumber }
		assertTrue(
			vCollectorNumbers.distinct().size < vCollectorNumbers.size,
			"Collector numbers are expected to repeat in this set; if they no longer do, the " +
				"reason the id is the reference has changed and the comment should be revisited",
		)
	}

	@Test
	fun `French is French, in text and in the image path`() = runBlocking {
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(AlteredProvider.PROVIDER_ID, "ALIZE"),
				language = CardLanguage.FRENCH,
			),
		)

		val vCard = vPage.cards.first()
		assertEquals(CardLanguage.FRENCH, vCard.text.language)
		assertTrue(
			vCard.artwork.imageUrl.contains("/IMAGES/fr/"),
			"French request produced ${vCard.artwork.imageUrl}",
		)
		// No thumbnail variant exists, and the adapter must not pretend one does by pointing it at
		// the full-size file. This is the stated limitation for this provider.
		assertEquals(null, vCard.artwork.thumbnailUrl)
	}

	@Test
	fun `the mirrored images actually load`() = runBlocking {
		val vClient = HttpClientFactory.create()
		val vPage = AlteredProvider(vClient).listCards(
			CardPageRequest(
				setId = SourceId(AlteredProvider.PROVIDER_ID, "ALIZE"),
				language = CardLanguage.FRENCH,
			),
		)

		// The official bucket answers 403 for every one of these; the mirror is the only thing
		// making this game browsable at all, so this asserts it directly.
		val vResponse: HttpResponse = vClient.head(vPage.cards.first().artwork.imageUrl)
		assertEquals(HttpStatusCode.OK, vResponse.status, "Mirrored card art is not loading")
	}

	@Test
	fun `hand cost and faction are mapped from the elements bag`() = runBlocking {
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(AlteredProvider.PROVIDER_ID, "ALIZE"),
				language = CardLanguage.FRENCH,
			),
		)

		assertTrue(
			vPage.cards.count { it.attributes.energy != null } > vPage.cards.size / 2,
			"Most cards should have a hand cost",
		)
		assertTrue(
			vPage.cards.count { it.classification.domains.isNotEmpty() } > vPage.cards.size / 2,
			"Most cards should have a faction",
		)
		assertTrue(
			vPage.cards.mapNotNull { it.classification.rarity }.toSet().isNotEmpty(),
			"Rarity should be populated",
		)
	}
}
