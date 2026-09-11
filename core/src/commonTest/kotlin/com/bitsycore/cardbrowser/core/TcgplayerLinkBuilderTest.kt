package com.bitsycore.cardbrowser.core

import com.bitsycore.cardbrowser.core.model.ExternalIdKey
import com.bitsycore.cardbrowser.core.tcgplayer.TcgplayerLinkBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Links out to TCGplayer.
 *
 * Four of the eight sources publish a TCGplayer product id and the app has been storing all of
 * them under [ExternalIdKey.TCGPLAYER] without reading any -- Scryfall's `tcgplayer_id`, TCGdex's
 * `thirdParty.tcgplayer`, Riftcodex's `tcgplayer_id`, and TCGCSV's `productId`, which is
 * TCGplayer's own id by definition.
 *
 * These assert the two things that can actually be got wrong: the URL shape, and refusing to
 * invent one. Whether the shape *resolves* is not asserted here and cannot be -- TCGplayer answers
 * 200 with an identical page shell for a real id and a nonsense one, because the page is built
 * client-side. See `TcgplayerLinkBuilder` for what is and is not known about that.
 */
class TcgplayerLinkBuilderTest {

	@Test
	fun `a published id becomes a product link`() {
		val vLink = assertNotNull(
			TcgplayerLinkBuilder.linkFor(
				TestCards.printing(externalIds = mapOf(ExternalIdKey.TCGPLAYER to listOf("559497"))),
			),
		)

		assertEquals("https://www.tcgplayer.com/product/559497", vLink.url)
		assertEquals("559497", vLink.productId)
	}

	@Test
	fun `no id means no button`() {
		// Not a search, not a home page. Without an id there is nothing precise to open, and a
		// button that lands somewhere vaguely related is the thing this codebase treats as a lie.
		assertNull(TcgplayerLinkBuilder.linkFor(TestCards.printing()))
	}

	@Test
	fun `a blank id is the same as none`() {
		assertNull(
			TcgplayerLinkBuilder.linkFor(
				TestCards.printing(externalIds = mapOf(ExternalIdKey.TCGPLAYER to listOf("   "))),
			),
		)
	}

	@Test
	fun `a non-numeric id is refused rather than pasted into a URL`() {
		// Every source publishes a number. Something else means the field changed shape upstream,
		// and `/product/Origins-Charm` would be a link to nowhere dressed as a link to a card.
		assertNull(
			TcgplayerLinkBuilder.linkFor(
				TestCards.printing(
					externalIds = mapOf(ExternalIdKey.TCGPLAYER to listOf("Origins-Charm")),
				),
			),
		)
	}

	@Test
	fun `the first id wins when a source lists several`() {
		// TCGdex publishes one per variant. They are all the same card, so the first is as good
		// an answer as any and picking one beats offering none.
		val vLink = assertNotNull(
			TcgplayerLinkBuilder.linkFor(
				TestCards.printing(
					externalIds = mapOf(ExternalIdKey.TCGPLAYER to listOf("111", "222")),
				),
			),
		)

		assertEquals("https://www.tcgplayer.com/product/111", vLink.url)
	}
}
