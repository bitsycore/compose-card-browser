package com.bitsycore.cardbrowser.providers.optcg

import com.bitsycore.cardbrowser.core.model.Availability
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import com.bitsycore.cardbrowser.core.provider.CardSearchRequest
import com.bitsycore.cardbrowser.data.net.HttpClientFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Talks to the real OPTCG API.
 *
 * **Not part of the ordinary test run.** Run it deliberately:
 *
 * ```
 * ./gradlew :providers:optcg:liveProviderTest
 * ```
 */
class OptcgLiveSmokeTest {

	private fun provider() = OptcgProvider(HttpClientFactory.create())

	@Test
	fun `the set list loads and contains Romance Dawn`() = runBlocking {
		val vSets = provider().listSets()

		assertTrue(vSets.size > 5, "Expected the expansion list, got ${vSets.size}")

		val vFirst = vSets.firstOrNull { it.id.local == "OP-01" }
		assertNotNull(vFirst, "OP-01 is missing from the live set list")
		assertEquals("Romance Dawn", vFirst.name)
		// Neither is stated by this API, and the adapter must keep saying so rather than
		// backfilling something plausible.
		assertEquals(null, vFirst.releaseDate)
		assertEquals(null, vFirst.cardCount)
	}

	@Test
	fun `a set arrives complete in one request`() = runBlocking {
		val vPage = provider().listCards(
			CardPageRequest(setId = SourceId(OptcgProvider.PROVIDER_ID, "OP-01")),
		)

		assertTrue(vPage.cards.size > 100, "OP-01 should be ~154 cards, got ${vPage.cards.size}")
		assertTrue(!vPage.hasMore, "This adapter must never report a second page")
		assertEquals(vPage.cards.size, vPage.totalCount)

		val vCard = vPage.cards.first()
		assertEquals("OP-01", vCard.setCode)
		// "OP01-077" becomes collector number "077".
		assertTrue(vCard.collectorNumber.all { it.isDigit() }, "Got ${vCard.collectorNumber}")
		assertTrue(vCard.artwork.imageUrl.startsWith("https://"), "Every card should have art")
	}

	@Test
	fun `rarity codes are expanded so the ladder can order them`() = runBlocking {
		val vPage = provider().listCards(
			CardPageRequest(setId = SourceId(OptcgProvider.PROVIDER_ID, "OP-01")),
		)

		val vRarities = vPage.cards.mapNotNull { it.classification.rarity }.toSet()
		// The API says "C" / "UC" / "SEC"; the ladder in `RarityLadder` is written in words, and
		// these have to agree or every rarity sorts to the end as unrecognised.
		assertTrue("Common" in vRarities, "Got $vRarities")
		assertTrue("Uncommon" in vRarities, "Got $vRarities")
		assertTrue(vRarities.none { it.length <= 3 }, "An unexpanded code leaked through: $vRarities")
	}

	@Test
	fun `no language is claimed -- because the API states none`() = runBlocking {
		val vPage = provider().listCards(
			CardPageRequest(setId = SourceId(OptcgProvider.PROVIDER_ID, "OP-01")),
		)

		val vCard = vPage.cards.first()
		// The text is plainly English but the source never says so, so it is not *confirmed* --
		// and Japanese, which this game certainly has, is UNKNOWN rather than absent.
		assertTrue(vCard.languages.isUnstated, "This provider must not claim a language")
		assertEquals(Availability.UNKNOWN, vCard.languages.availabilityOf(CardLanguage.JAPANESE))
		assertEquals(Availability.UNKNOWN, vCard.languages.availabilityOf(CardLanguage.ENGLISH))
		assertTrue(!vCard.text.isProviderStated, "The language is an inference, not a statement")
	}

	@Test
	fun `a single card can be fetched by its printed code`() = runBlocking {
		val vCard = provider().cardDetail(SourceId(OptcgProvider.PROVIDER_ID, "OP01-077"))

		assertNotNull(vCard, "OP01-077 should exist")
		assertEquals("Perona", vCard.displayName)
		assertEquals("077", vCard.collectorNumber)
	}

	@Test
	fun `cross-set search spans sets and pages client-side`() = runBlocking {
		val vPage = provider().searchAllSets(
			CardSearchRequest(text = "Luffy", pageSize = 10),
		)

		assertEquals(10, vPage.cards.size, "The client-side window should cap the page")
		assertTrue(vPage.hasMore, "Luffy matches far more than ten cards")
		assertNotNull(vPage.totalCount)
		assertTrue(
			vPage.cards.all { it.displayName.contains("Luffy", ignoreCase = true) },
			"Every result should actually match",
		)
	}
}
