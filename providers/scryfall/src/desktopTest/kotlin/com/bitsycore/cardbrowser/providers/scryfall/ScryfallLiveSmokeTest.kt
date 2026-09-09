package com.bitsycore.cardbrowser.providers.scryfall

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
 * Talks to the real Scryfall API.
 *
 * **Not part of the ordinary test run.** Run it deliberately:
 *
 * ```
 * ./gradlew :providers:scryfall:liveProviderTest
 * ```
 */
class ScryfallLiveSmokeTest {

	private fun provider() = ScryfallProvider(HttpClientFactory.create())

	@Test
	fun `the set catalogue loads and excludes digital-only sets`() = runBlocking {
		val vSets = provider().listSets(Game.MAGIC)

		assertTrue(vSets.size > 500, "Expected a large catalogue, got ${vSets.size}")

		val vBloomburrow = vSets.firstOrNull { it.id.local == "blb" }
		assertNotNull(vBloomburrow, "Bloomburrow is missing from the live catalogue")
		assertEquals("Bloomburrow", vBloomburrow.name)
		val vReleased = vBloomburrow.releaseDate
		assertNotNull(vReleased)
		assertEquals(2024, vReleased.year)
	}

	@Test
	fun `a set pages with a real total and a real hasMore`() = runBlocking {
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(ScryfallProvider.PROVIDER_ID, "blb"),
				language = CardLanguage.ENGLISH,
			),
		)

		assertTrue(vPage.cards.isNotEmpty())
		assertNotNull(vPage.totalCount, "Scryfall states a total; losing it would break completeness")
		// Bloomburrow has ~400 printings and the page size is 175, so there must be more.
		assertTrue(vPage.hasMore, "A 400-card set should not fit in one 175-card page")
	}

	@Test
	fun `oracle_id gives the only real card identity in this app`() = runBlocking {
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(ScryfallProvider.PROVIDER_ID, "blb"),
				language = CardLanguage.ENGLISH,
			),
		)

		val vWithIdentity = vPage.cards.count { it.identity != null }
		assertTrue(
			vWithIdentity > vPage.cards.size / 2,
			"Most printings should carry an oracle_id; only $vWithIdentity of ${vPage.cards.size} do",
		)
	}

	@Test
	fun `French printings come back French -- with printed names`() = runBlocking {
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(ScryfallProvider.PROVIDER_ID, "blb"),
				language = CardLanguage.FRENCH,
			),
		)

		assertTrue(vPage.cards.isNotEmpty(), "Bloomburrow should have French printings")
		val vCard = vPage.cards.first()
		assertEquals(CardLanguage.FRENCH, vCard.text.language)
		// `printed_name` is the localised one. If the mapper preferred `name`, every French card
		// would show an English title and the whole language feature would be cosmetic.
		assertTrue(
			vPage.cards.any { it.displayName != it.identity?.name },
			"At least one French card should show a printed name differing from the English one",
		)
	}

	@Test
	fun `a language with no printings falls back to English rather than erroring`() = runBlocking {
		// Scryfall answers a search that matches nothing with 404, not an empty list. Without the
		// fallback in `listCards`, asking for a language a set was never printed in would show an
		// error over a set that is perfectly browsable.
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(ScryfallProvider.PROVIDER_ID, "blb"),
				language = CardLanguage.KOREAN,
			),
		)

		assertTrue(vPage.cards.isNotEmpty(), "Should have fallen back rather than returning nothing")
		// And the fallback is labelled honestly: these are English records and say so.
		assertTrue(
			vPage.cards.all { it.text.language == CardLanguage.KOREAN || it.text.language == CardLanguage.ENGLISH },
			"Fallback records must state the language they actually are",
		)
	}

	@Test
	fun `finishes are exhaustive -- so absence is a real statement`() = runBlocking {
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(ScryfallProvider.PROVIDER_ID, "blb"),
				language = CardLanguage.ENGLISH,
			),
		)

		val vCard = vPage.cards.first { !it.finishes.isUnstated }
		assertTrue(vCard.finishes.confirmed.isNotEmpty())
		// The property that justifies populating `absent` here and nowhere else.
		assertTrue(
			vCard.finishes.confirmed.intersect(vCard.finishes.absent).isEmpty(),
			"A finish cannot be both confirmed and absent",
		)
	}

	@Test
	fun `cross-set search spans printings from many sets`() = runBlocking {
		val vPage = provider().searchAllSets(
			CardSearchRequest(
				game = Game.MAGIC,
				text = "Lightning Bolt",
				language = CardLanguage.ENGLISH,
			),
		)

		assertTrue(vPage.cards.isNotEmpty())
		assertTrue(
			vPage.cards.map { it.setId }.distinct().size > 1,
			"Lightning Bolt has been printed in many sets",
		)
	}

	@Test
	fun `images are the WebP variants`() = runBlocking {
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(ScryfallProvider.PROVIDER_ID, "blb"),
				language = CardLanguage.ENGLISH,
			),
		)

		val vArtwork = vPage.cards.first { it.artwork.thumbnailUrl != null }.artwork
		assertTrue(
			vArtwork.thumbnailUrl?.contains(".webp") == true,
			"Grid art should be WebP, got ${vArtwork.thumbnailUrl}",
		)
		assertTrue(
			vArtwork.displayUrl?.contains(".webp") == true,
			"Display art should be WebP, got ${vArtwork.displayUrl}",
		)
	}
	@Test
	fun `every paper set carries a set symbol that actually loads`() = runBlocking<Unit> {
		val vClient = HttpClientFactory.create()
		val vSets = ScryfallProvider(vClient).listSets(Game.MAGIC)

		val vWithSymbol = vSets.count { it.symbol != null }
		assertEquals(vSets.size, vWithSymbol, "Every paper set should publish an icon")
		assertTrue(vSets.all { it.symbol?.isMonochrome == true })

		// The symbols are SVG and nothing else, so the app needs an SVG decoder registered or
		// every Magic set silently falls back to its code.
		val vUrl = vSets.first { it.symbol != null }.symbol!!.url
		// Scryfall appends a cache-busting query, so the extension is not at the end of the string.
		assertTrue(vUrl.substringBefore('?').endsWith(".svg"), "Expected an SVG, got $vUrl")
		val vResponse: HttpResponse = vClient.head(vUrl)
		assertEquals(HttpStatusCode.OK, vResponse.status, "Set symbol is not loading")
	}
}
