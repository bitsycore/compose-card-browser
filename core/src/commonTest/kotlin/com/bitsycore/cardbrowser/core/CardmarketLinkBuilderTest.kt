package com.bitsycore.cardbrowser.core

import com.bitsycore.cardbrowser.core.cardmarket.CardmarketLink
import com.bitsycore.cardbrowser.core.cardmarket.CardmarketLinkBuilder
import com.bitsycore.cardbrowser.core.model.ExternalIdKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cardmarket URL construction.
 *
 * Every expectation here is anchored to a URL shape observed on the live site. Nothing asserts a
 * parameter that has not been seen working, which is the whole policy for this file.
 */
class CardmarketLinkBuilderTest {

	@Test
	fun `a card in a single-word set gets a scoped search using the provider's expansion id`() {
		// The verified shape:
		// /en/Riftbound/Products/Singles/Origins?searchMode=v2&idCategory=1655&idExpansion=6286...
		val vLink = CardmarketLinkBuilder.linkFor(
			game = TestGame,
			printing = TestCards.printing(name = "Kai'Sa - Survivor"),
			set = TestCards.ORIGINS,
		)

		val vSearch = assertIs<CardmarketLink.CardSearch>(vLink)
		assertTrue(vSearch.url.startsWith("https://www.cardmarket.com/en/Riftbound/Products/Singles/Origins?"))
		assertTrue(vSearch.url.contains("searchMode=v2"))
		assertTrue(vSearch.url.contains("idCategory=1655"))
		// 6286 is Origins' real `cardmarket_id` from the Riftcodex set record, and the same value
		// the observed Cardmarket URL carried. The two sources lining up is the point.
		assertTrue(vSearch.url.contains("idExpansion=6286"))
		assertTrue(vSearch.url.contains("idRarity=0"))
		assertTrue(vSearch.url.contains("perSite=30"))
	}

	@Test
	fun `the hyphen separator is dropped and the apostrophe is percent-encoded`() {
		// Riftcodex writes `Kai'Sa - Survivor`; the working Cardmarket search was `Kai%27sa+Survivor`.
		val vLink = CardmarketLinkBuilder.linkFor(
			game = TestGame,
			printing = TestCards.printing(name = "Kai'Sa - Survivor"),
			set = TestCards.ORIGINS,
		)

		val vSearch = assertIs<CardmarketLink.CardSearch>(vLink)
		assertEquals("Kai'Sa Survivor", vSearch.terms)
		assertTrue(vSearch.url.contains("searchString=Kai%27Sa+Survivor"))
	}

	@Test
	fun `a provider annotation in parentheses is not sent to the search`() {
		// "(Signature)" is Riftcodex's own label, not part of the printed name; searching for it
		// returns nothing.
		val vLink = CardmarketLinkBuilder.linkFor(
			game = TestGame,
			printing = TestCards.printing(name = "Volibear - Relentless Storm (Signature)"),
			set = TestCards.ORIGINS,
		)

		val vSearch = assertIs<CardmarketLink.CardSearch>(vLink)
		assertEquals("Volibear Relentless Storm", vSearch.terms)
	}

	@Test
	fun `an accented name is encoded from its UTF-8 bytes`() {
		val vEncoded = CardmarketLinkBuilder.formEncode("Épée de Fureur")

		assertEquals("%C3%89p%C3%A9e+de+Fureur", vEncoded)
	}

	@Test
	fun `a multi-word set name yields no expansion slug and falls back to the game page`() {
		// "Origins: Proving Grounds" could be hyphenated, truncated or abbreviated on Cardmarket's
		// side. Guessing would ship a 404, so the builder declines.
		assertNull(CardmarketLinkBuilder.expansionSlug(TestCards.PROVING_GROUNDS))

		val vLink = CardmarketLinkBuilder.linkFor(
			game = TestGame,
			printing = TestCards.printing(),
			set = TestCards.PROVING_GROUNDS,
		)

		val vHome = assertIs<CardmarketLink.GameHome>(vLink)
		assertEquals("https://www.cardmarket.com/en/Riftbound", vHome.url)
	}

	@Test
	fun `a set with no Cardmarket id still gets a filled-in search`() {
		// Vendetta is real and has `cardmarket_id: null`. Dropping the search over that sent people
		// to an unfiltered listing of 358 cards to find the one they were already looking at. The
		// path already names the expansion, so the scope survives without `idExpansion`.
		val vVendetta = TestCards.ORIGINS.copy(name = "Vendetta", externalIds = emptyMap())

		val vLink = CardmarketLinkBuilder.linkFor(
			game = TestGame,
			printing = TestCards.printing(name = "Renekton, Rage Fueled"),
			set = vVendetta,
		)

		val vSearch = assertIs<CardmarketLink.CardSearch>(vLink)
		assertTrue(
			vSearch.url.startsWith("https://www.cardmarket.com/en/Riftbound/Products/Singles/Vendetta?"),
		)
		assertTrue(vSearch.url.contains("searchString=Renekton%2C+Rage+Fueled"), vSearch.url)
		// The one parameter that genuinely could not be known is simply absent.
		assertFalse(vSearch.url.contains("idExpansion"), vSearch.url)
	}

	@Test
	fun `a card with no usable name falls back to the expansion listing`() {
		val vLink = CardmarketLinkBuilder.linkFor(
			game = TestGame,
			printing = TestCards.printing(name = "   "),
			set = TestCards.ORIGINS,
		)

		val vSingles = assertIs<CardmarketLink.ExpansionSingles>(vLink)
		assertEquals("https://www.cardmarket.com/en/Riftbound/Products/Singles/Origins", vSingles.url)
	}

	@Test
	fun `a provider-supplied product path wins over any search`() {
		val vLink = CardmarketLinkBuilder.linkFor(
			game = TestGame,
			printing = TestCards.printing(
				externalIds = mapOf(ExternalIdKey.CARDMARKET_PRODUCT to listOf("Origins/KaiSa-Survivor-V1-Epic")),
			),
			set = TestCards.ORIGINS,
		)

		val vProduct = assertIs<CardmarketLink.Product>(vLink)
		assertEquals(
			"https://www.cardmarket.com/en/Riftbound/Products/Singles/Origins/KaiSa-Survivor-V1-Epic",
			vProduct.url,
		)
	}

	@Test
	fun `a game with no checked slug produces no link at all`() {
		// A guessed game segment is a dead button, so these produce nothing rather than something
		// that looks plausible.

		val vCard = TestCards.printing().copy(game = TestGameWithoutMarketplace.id)
		assertNull(
			CardmarketLinkBuilder.linkFor(vCard, TestCards.ORIGINS, TestGameWithoutMarketplace),
		)
		assertNull(CardmarketLinkBuilder.expansionLink(TestCards.ORIGINS, TestGameWithoutMarketplace))
	}

	@Test
	fun `no buying preference is ever added to a URL`() {
		// Seller country, minimum condition, language and finish presets are all unverified, and
		// buying preferences are a separate concern from browsing ones. None may appear.
		val vLink = CardmarketLinkBuilder.linkFor(TestCards.printing(), TestCards.ORIGINS, TestGame)!!

		listOf("idLanguage", "minCondition", "sellerCountry", "isFoil", "sellerType").forEach { vParam ->
			assertFalse(vLink.url.contains(vParam), "URL must not carry an unverified $vParam")
		}
	}

	@Test
	fun `the expansion page is distinct from the singles listing`() {
		assertEquals(
			"https://www.cardmarket.com/en/Riftbound/Expansions/Origins",
			CardmarketLinkBuilder.expansionLink(TestCards.ORIGINS, TestGame),
		)
	}
}
