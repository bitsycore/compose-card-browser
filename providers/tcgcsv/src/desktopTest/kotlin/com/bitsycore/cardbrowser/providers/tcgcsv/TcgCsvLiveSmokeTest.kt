package com.bitsycore.cardbrowser.providers.tcgcsv

import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.data.net.HttpClientFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Talks to the real TCGCSV service.
 *
 * **Not part of the ordinary test run.** Run it deliberately:
 *
 * ```
 * ./gradlew :providers:tcgcsv:liveProviderTest
 * ```
 *
 * These assert the *shape* the adapter depends on rather than exact counts, because the catalogue
 * grows: Cyberpunk gains groups as its release approaches and Lorcana gains a set a quarter. Where
 * a number is asserted it is a floor, not an equality.
 */
class TcgCsvLiveSmokeTest {

	private fun lorcana() = LorcanaTcgCsvProvider(HttpClientFactory.create())

	private fun cyberpunk() = CyberpunkTcgCsvProvider(HttpClientFactory.create())

	private fun wow() = WowTcgCsvProvider(HttpClientFactory.create())

	@Test
	fun `each category serves a set list`() = runBlocking {
		// Measured on 2026-09-09: 20, 9 and 54. Floors, because all three can grow.
		assertTrue(lorcana().listSets().size >= 20, "Lorcana's catalogue shrank")
		assertTrue(cyberpunk().listSets().size >= 9, "Cyberpunk's catalogue shrank")
		assertTrue(wow().listSets().size >= 54, "The WoW catalogue shrank")
	}

	@Test
	fun `a Lorcana set arrives complete in one request -- and sealed product is filtered out`() =
		runBlocking {
			val vProvider = lorcana()
			val vSets = vProvider.listSets()
			val vSet = assertNotNull(vSets.firstOrNull { it.name == "Attack of the Vine!" })

			val vPage = vProvider.listCards(CardPageRequest(setId = vSet.id, pageSize = 1000))

			// 270 products in that group, 207 of them cards. If the filter broke, this would be the
			// larger number and the grid would show pictures of booster boxes.
			assertTrue(vPage.cards.size in 150..269, "Got ${vPage.cards.size}")
			assertFalse(vPage.hasMore, "The whole group arrives at once")
			assertEquals(
				vPage.cards.size,
				vPage.cards.map { it.id }.toSet().size,
				"Duplicate ids would crash the grid rather than degrade",
			)
			assertTrue(vPage.cards.all { it.artwork.imageUrl.endsWith("_in_1000x1000.jpg") })
			assertTrue(vPage.cards.any { it.classification.domains.isNotEmpty() }, "No inks mapped")
		}

	@Test
	fun `a WoW set carries a rarity and a picture and nothing else`() = runBlocking {
		val vProvider = wow()
		val vPage = vProvider.listCards(
			CardPageRequest(setId = SourceId(WowTcgCsvProvider.PROVIDER_ID, "1100"), pageSize = 1000),
		)

		assertTrue(vPage.cards.size > 100, "Got ${vPage.cards.size}")
		val vCard = vPage.cards.first()
		assertNotNull(vCard.classification.rarity, "Rarity is the only game field this source has")
		assertTrue(
			vCard.classification.rarity in WowTcgGameRarities,
			"An unexpanded letter reached the model: ${vCard.classification.rarity}",
		)
		// The limits of the source, asserted so a future change to the mapper cannot quietly start
		// claiming things the catalogue does not carry.
		assertEquals("", vCard.collectorNumber)
		assertNull(vCard.text.rules)
		assertNull(vCard.artwork.thumbnailUrl, "There is only one rendition of a WoW image")
	}

	@Test
	fun `a card can be fetched on its own -- through the group its id carries`() = runBlocking {
		val vProvider = cyberpunk()
		val vSets = vProvider.listSets()
		val vFirst = vProvider
			.listCards(CardPageRequest(setId = vSets.first().id, pageSize = 1000))
			.cards
			.first()

		val vDetail = vProvider.cardDetail(vFirst.id)

		assertEquals(vFirst.id, assertNotNull(vDetail).id)
		assertEquals(vFirst.displayName, vDetail.displayName)
	}

	private companion object {

		/** What `TcgCsvMapping.WOW_TCG` expands the catalogue's single letters into. */
		val WowTcgGameRarities = setOf("Common", "Uncommon", "Rare", "Epic", "Legendary")
	}
}
