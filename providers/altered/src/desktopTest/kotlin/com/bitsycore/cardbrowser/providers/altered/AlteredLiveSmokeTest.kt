package com.bitsycore.cardbrowser.providers.altered

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import com.bitsycore.cardbrowser.data.net.HttpClientFactory
import com.bitsycore.cardbrowser.games.altered.AlteredGame
import io.ktor.client.request.head
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import com.bitsycore.cardbrowser.core.provider.CardQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

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

	// ============
	//  Languages and the mirror's two hosts

	@Test
	fun `every declared language really is translated`() = runBlocking {
		// The adapter used to declare French and English only, and the mirror publishes five. Each
		// one is checked by reading the same card in it: a locale that merely *exists* and returns
		// the English strings would be a language the app claims and cannot deliver.
		val vProvider = provider()
		val vNames = mutableMapOf<CardLanguage, String>()

		for (vLanguage in vProvider.capabilities.data.languages) {
			val vPage = vProvider.listCards(
				CardPageRequest(
					setId = SourceId(AlteredProvider.PROVIDER_ID, "CORE"),
					query = CardQuery(),
					page = 1,
					pageSize = AlteredProvider.MAX_PAGE_SIZE,
					language = vLanguage,
				),
			)
			assertTrue(
				vPage.cards.size > 400,
				"$vLanguage returned ${vPage.cards.size} cards of CORE",
			)
			val vCard = assertNotNull(
				vPage.cards.firstOrNull { it.collectorNumber.isNotBlank() },
				"$vLanguage returned no usable card",
			)
			vNames[vLanguage] = vCard.text.name
			// The record has to say which language it really is, not which was asked for.
			assertEquals(vLanguage, vCard.text.language, "$vLanguage mislabelled its text")
		}

		// Five declared languages, five distinct renderings of the same set. If any locale were
		// quietly serving English, two of these would collide.
		assertEquals(
			vProvider.capabilities.data.languages.size,
			vNames.values.distinct().size,
			"two locales returned the same name, so one is not really translated: $vNames",
		)
	}

	@Test
	fun `a file the CDN refuses is still fetched`() = runBlocking {
		// jsDelivr caps a repository at 50 MB and this one is 5.2 GB, so it answers
		// `403 Package size exceeded` for files it has not taken -- measured, `CORE_ES.json` is
		// refused while EN, FR, DE and IT are served, and which is which is not predictable. Raw
		// GitHub has all five, so the adapter falls back to it.
		//
		// Spanish is therefore the case that only passes because of the fallback.
		val vSpanish = provider().listCards(
			CardPageRequest(
				setId = SourceId(AlteredProvider.PROVIDER_ID, "CORE"),
				query = CardQuery(),
				page = 1,
				pageSize = AlteredProvider.MAX_PAGE_SIZE,
				language = CardLanguage.SPANISH,
			),
		)

		assertTrue(vSpanish.cards.size > 400, "Spanish CORE did not load: ${vSpanish.cards.size}")
		assertEquals(CardLanguage.SPANISH, vSpanish.cards.first().text.language)
	}

	@Test
	fun `the set index loads with localised names`() = runBlocking {
		val vFrench = provider().listSets(CardLanguage.FRENCH)
		val vEnglish = provider().listSets(CardLanguage.ENGLISH)

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
	fun `French is French -- in text and in the image path`() = runBlocking {
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
		//
		// Several, not one, because the failure this caught is per *file*: jsDelivr caps a
		// repository at 50 MB and this one is 5.2 GB, so it refuses whichever files it has not
		// taken -- `fr/ALIZE` art and `en/CORE` art were both 403 while `fr/CORE` was fine. One
		// sample would have passed for a subset and left the rest as blank tiles.
		val vSamples = vPage.cards.take(5).map { it.artwork.imageUrl }
		assertEquals(5, vSamples.size, "not enough cards to sample")
		for (vUrl in vSamples) {
			val vResponse: HttpResponse = vClient.head(vUrl)
			assertEquals(HttpStatusCode.OK, vResponse.status, "Mirrored card art is not loading: $vUrl")
		}
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
			vPage.cards.count { it.attributes.cost != null } > vPage.cards.size / 2,
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
