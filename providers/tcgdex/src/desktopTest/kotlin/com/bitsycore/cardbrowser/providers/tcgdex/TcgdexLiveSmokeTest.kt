package com.bitsycore.cardbrowser.providers.tcgdex

import com.bitsycore.cardbrowser.core.model.Availability
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import com.bitsycore.cardbrowser.core.provider.CardSearchRequest
import com.bitsycore.cardbrowser.data.net.HttpClientFactory
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Talks to the real TCGdex API.
 *
 * **Not part of the ordinary test run.** Run it deliberately:
 *
 * ```
 * ./gradlew :providers:tcgdex:liveProviderTest
 * ```
 *
 * Assertions are about shape and invariants rather than exact values, except where an anchor is
 * solid enough to pin -- Base Set really does have 102 cards and really was released in 1999.
 */
class TcgdexLiveSmokeTest {

	private fun provider() = TcgdexProvider(HttpClientFactory.create())

	@Test
	fun `the set catalogue loads and carries real release dates`() = runBlocking {
		val vSets = provider().listSets(Game.POKEMON, CardLanguage.ENGLISH)

		assertTrue(vSets.size > 100, "Expected a large catalogue, got ${vSets.size}")

		val vBase = vSets.firstOrNull { it.id.local == "base1" }
		assertNotNull(vBase, "Base Set is missing from the live catalogue")
		assertEquals("Base Set", vBase.name)
		assertEquals(102, vBase.cardCount)

		// The whole reason for the second GraphQL request. If it silently stopped working, every
		// set would come back undated and the list would order alphabetically.
		val vReleased = vBase.releaseDate
		assertNotNull(vReleased, "Release dates are missing -- the GraphQL query failed")
		assertEquals(1999, vReleased.year)
		assertTrue(
			vSets.count { it.releaseDate != null } > vSets.size / 2,
			"Most sets should have a date; only ${vSets.count { it.releaseDate != null }} do",
		)
	}

	@Test
	fun `a set arrives complete in one request`() = runBlocking {
		val vProvider = provider()
		val vPage = vProvider.listCards(
			CardPageRequest(
				setId = SourceId(TcgdexProvider.PROVIDER_ID, "swsh3"),
				language = CardLanguage.ENGLISH,
			),
		)

		// The claim the repository relies on: no paging, so `hasMore` false really means complete.
		assertTrue(vPage.cards.size > 150, "Darkness Ablaze should be ~201 cards, got ${vPage.cards.size}")
		assertEquals(vPage.cards.size, vPage.totalCount)
		assertTrue(!vPage.hasMore, "This adapter must never report a second page")

		val vCard = vPage.cards.first()
		assertTrue(vCard.artwork.thumbnailUrl?.endsWith("low.webp") == true, "Thumbnails must be WebP")
		assertTrue(vCard.artwork.displayUrl?.endsWith("high.webp") == true, "Display art must be WebP")
		assertEquals(CardLanguage.ENGLISH, vCard.text.language)
	}

	@Test
	fun `all four preferred languages return their own catalogue`() = runBlocking {
		val vProvider = provider()
		// The claim that makes TCGdex the best source in this app. If a locale silently emptied,
		// the app would show a Pokémon game with no sets rather than an error.
		for (vLanguage in listOf(
			CardLanguage.FRENCH,
			CardLanguage.JAPANESE,
			CardLanguage.ENGLISH,
			CardLanguage.KOREAN,
		)) {
			val vSets = vProvider.listSets(Game.POKEMON, vLanguage)
			assertTrue(vSets.size > 50, "${vLanguage.code} returned only ${vSets.size} sets")
		}
	}

	@Test
	fun `a French card is French, text and image alike`() = runBlocking {
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(TcgdexProvider.PROVIDER_ID, "swsh3"),
				language = CardLanguage.FRENCH,
			),
		)

		val vCard = vPage.cards.first()
		assertEquals(CardLanguage.FRENCH, vCard.text.language)
		assertEquals(Availability.AVAILABLE, vCard.languages.availabilityOf(CardLanguage.FRENCH))
		// Never claimed as absent: TCGdex not listing a Korean record says nothing about whether a
		// Korean printing exists.
		assertEquals(Availability.UNKNOWN, vCard.languages.availabilityOf(CardLanguage.KOREAN))
		// The image comes from the French endpoint, so it is a French scan.
		assertTrue(
			vCard.artwork.displayUrl?.contains("/fr/") == true,
			"French request returned a non-French image: ${vCard.artwork.displayUrl}",
		)
	}

	@Test
	fun `finishes are stated on both sides, not merely left unknown`() = runBlocking {
		val vCard = provider().cardDetail(
			SourceId(TcgdexProvider.PROVIDER_ID, "swsh3-1"),
			CardLanguage.ENGLISH,
		)

		assertNotNull(vCard, "swsh3-1 (Butterfree V) should exist")
		// TCGdex says `holo: true, normal: false`, which is a real statement rather than a silence.
		// This is the only provider in the app that can populate `absent` from booleans.
		assertTrue(!vCard.finishes.isUnstated, "Finish coverage should be stated for a Pokémon card")
		assertTrue(
			vCard.finishes.confirmed.isNotEmpty() || vCard.finishes.absent.isNotEmpty(),
			"At least one side of the finish coverage should be populated",
		)
	}

	@Test
	fun `a Cardmarket product id really is attached to a printing`() = runBlocking {
		val vCard = provider().cardDetail(
			SourceId(TcgdexProvider.PROVIDER_ID, "swsh3-1"),
			CardLanguage.ENGLISH,
		)

		assertNotNull(vCard)
		val vProduct = vCard.externalIds[com.bitsycore.cardbrowser.core.model.ExternalIdKey.CARDMARKET_PRODUCT]
		assertTrue(!vProduct.isNullOrEmpty(), "Expected a Cardmarket product id on this card")
		assertTrue(vProduct.first().toLongOrNull() != null, "Product id should be numeric")
	}

	@Test
	fun `cross-set search finds a card across eras`() = runBlocking {
		val vPage = provider().searchAllSets(
			CardSearchRequest(
				game = Game.POKEMON,
				text = "Charizard",
				language = CardLanguage.ENGLISH,
				pageSize = 20,
			),
		)

		assertTrue(vPage.cards.isNotEmpty(), "Charizard should match something")
		assertTrue(
			vPage.cards.all { it.displayName.contains("Charizard", ignoreCase = true) },
			"Every result should actually match the search term",
		)
		// The point of a cross-set search: results from more than one set.
		assertTrue(
			vPage.cards.map { it.setId }.distinct().size > 1,
			"Results should span several sets",
		)
	}
}
