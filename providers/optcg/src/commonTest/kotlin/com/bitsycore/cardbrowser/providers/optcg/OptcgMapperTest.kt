package com.bitsycore.cardbrowser.providers.optcg

import com.bitsycore.cardbrowser.core.model.Availability
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.game.RarityLadder
import com.bitsycore.cardbrowser.games.onepiece.OnePieceGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The OPTCG mapping, against DTOs shaped like real responses. Deterministic and offline. */
class OptcgMapperTest {

	private val mProvider = ProviderId("optcg")

	private fun card(
		cardSetId: String = "OP01-077",
		rarity: String? = "UC",
		colour: String? = "Blue",
		cost: String? = "1",
		power: String? = "2000",
		subTypes: String? = "Thriller Bark Pirates",
	) = OptcgCardDto(
		cardName = "Perona",
		cardText = "[On Play] Look at 5 cards from the top of your deck.",
		setName = "Romance Dawn",
		setId = "OP-01",
		cardSetId = cardSetId,
		rarity = rarity,
		cardColor = colour,
		cardType = "Character",
		cardCost = cost,
		cardPower = power,
		subTypes = subTypes,
		attribute = "Special",
		counterAmount = 1000,
		cardImage = "https://optcgapi.com/media/static/Card_Images/OP01-077.jpg",
	)

	@Test
	fun `a set states neither a date nor a count -- and neither is invented`() {
		val vSet = OptcgMapper.toSet(OptcgSetDto(setName = "Romance Dawn", setId = "OP-01"), mProvider)

		assertNotNull(vSet)
		assertEquals("OP-01", vSet.id.local)
		assertEquals(OnePieceGame.id, vSet.game)
		assertNull(vSet.releaseDate)
		assertNull(vSet.cardCount)
	}

	@Test
	fun `the collector number is the part after the hyphen`() {
		val vCard = OptcgMapper.toPrinting(card(), mProvider, null)

		assertNotNull(vCard)
		assertEquals("077", vCard.collectorNumber)
		// The printed identifier is kept whole, because that is what a user reads off the card.
		assertEquals("OP01-077", vCard.providerRawCollectorNumber)
	}

	@Test
	fun `rarity codes are expanded to what the ladder is written in`() {
		// The ladder in `RarityLadder` uses words. An unexpanded "UC" would rank as unrecognised
		// and sort every uncommon to the end of the set.
		assertEquals("Common", OptcgMapper.expandRarity("C"))
		assertEquals("Uncommon", OptcgMapper.expandRarity("UC"))
		assertEquals("Secret Rare", OptcgMapper.expandRarity("SEC"))
		assertTrue(RarityLadder.rankOf(OnePieceGame.rarityLadder, "Uncommon") < RarityLadder.rankOf(OnePieceGame.rarityLadder, "Rare"))
	}

	@Test
	fun `Leader and Promo are expanded but stay off the ladder`() {
		// Neither is a power tier -- a Leader is a card role and a Promo is a distribution -- so
		// they are named but rank last rather than being slotted in among the rarities.
		assertEquals("Leader", OptcgMapper.expandRarity("L"))
		assertEquals(Int.MAX_VALUE, RarityLadder.rankOf(OnePieceGame.rarityLadder, "Leader"))
		assertEquals(Int.MAX_VALUE, RarityLadder.rankOf(OnePieceGame.rarityLadder, "Promo"))
	}

	@Test
	fun `an unrecognised rarity code is passed through rather than guessed at`() {
		assertEquals("ZZ", OptcgMapper.expandRarity("ZZ"))
		assertNull(OptcgMapper.expandRarity(null))
		assertNull(OptcgMapper.expandRarity(""))
	}

	@Test
	fun `no language is confirmed -- because the API states none`() {
		val vCard = OptcgMapper.toPrinting(card(), mProvider, null)

		assertNotNull(vCard)
		assertTrue(vCard.languages.isUnstated)
		// Japanese printings of this game certainly exist; this source not carrying them is not
		// evidence against them.
		assertEquals(Availability.UNKNOWN, vCard.languages.availabilityOf(CardLanguage.JAPANESE))
		assertEquals(CardLanguage.ENGLISH, vCard.text.language)
		assertTrue(!vCard.text.isProviderStated, "The language is inferred, not stated")
	}

	@Test
	fun `a dual-colour card becomes two domains`() {
		val vCard = OptcgMapper.toPrinting(card(colour = "Red/Green"), mProvider, null)

		assertNotNull(vCard)
		// Lower-cased onto the keys `OnePieceGame` declares, which is what makes a Red filter match
		// a Red/Green leader.
		assertEquals(listOf("red", "green"), vCard.classification.domains)
	}

	@Test
	fun `a dual colour stated with a space is split too`() {
		// What the live API actually sends: OP-01 reports `Blue Purple` and `Green Red` as single
		// space-separated strings, not slash-separated ones. Kept whole, a Blue/Purple leader did
		// not match the Blue filter and added a "Blue Purple" chip of its own to the sheet.
		val vCard = OptcgMapper.toPrinting(card(colour = "Blue Purple"), mProvider, null)

		assertNotNull(vCard)
		assertEquals(listOf("blue", "purple"), vCard.classification.domains)
	}

	@Test
	fun `a Leader has no cost and that is left null`() {
		val vCard = OptcgMapper.toPrinting(card(cost = "", rarity = "L"), mProvider, null)

		assertNotNull(vCard)
		assertNull(vCard.attributes.cost)
	}

	@Test
	fun `the grid and the detail screen share one image -- since no small variant exists`() {
		val vCard = OptcgMapper.toPrinting(card(), mProvider, null)

		assertNotNull(vCard)
		// Null rather than the same URL under a different name, so `CardImage` knows it is loading
		// a full-size file rather than a thumbnail.
		assertNull(vCard.artwork.thumbnailUrl)
		assertEquals(vCard.artwork.imageUrl, vCard.artwork.displayUrl)
	}

	@Test
	fun `prices are deliberately not mapped anywhere`() {
		val vCard = OptcgMapper.toPrinting(card(), mProvider, null)

		assertNotNull(vCard)
		// They carry no currency and no source, and this is a browser rather than a price guide.
		assertTrue(vCard.tags.none { it.contains("0.55") || it.contains("0.73") })
		assertTrue(vCard.externalIds.isEmpty())
	}
}
