package com.bitsycore.cardbrowser.providers.ygoprodeck

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.ExternalIdKey
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.games.yugioh.YuGiOhGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The YGOPRODeck mapping. Deterministic and offline. */
class YgoprodeckMapperTest {

	private val mProvider = ProviderId("ygoprodeck")

	private val mMetalRaiders = CardSet(
		id = SourceId(mProvider, "Metal Raiders"),
		game = YuGiOhGame.id,
		code = "MRD",
		name = "Metal Raiders",
		cardCount = 144,
		releaseDate = null,
	)

	/** Shaped like the real "7 Colored Fish", which appears in seven different sets. */
	private fun card() = YgoCardDto(
		id = 23771716,
		name = "7 Colored Fish",
		type = "Normal Monster",
		humanReadableCardType = "Normal Monster",
		desc = "A rare rainbow fish that has never been caught by mortal man.",
		race = "Fish",
		attribute = "WATER",
		atk = 1800,
		def = 800,
		level = 4,
		typeline = listOf("Fish", "Normal"),
		cardSets = listOf(
			YgoCardSetDto("Gold Series", "GLD1-EN001", "Common"),
			YgoCardSetDto("Metal Raiders", "MRD-098", "Rare"),
			YgoCardSetDto("Metal Raiders", "MRD-E098", "Common"),
			YgoCardSetDto("Starter Deck: Joey", "SDJ-008", "Common"),
		),
		cardImages = listOf(
			YgoCardImageDto(
				id = 23771716,
				imageUrl = "https://images.ygoprodeck.com/images/cards/23771716.jpg",
				imageUrlSmall = "https://images.ygoprodeck.com/images/cards_small/23771716.jpg",
			),
		),
	)

	// ============
	//  Sets

	@Test
	fun `a set is keyed by its name -- because that is what the API filters on`() {
		// `cardinfo.php` has a `cardset` parameter that takes a name and no parameter that takes a
		// code. Keying on the code would mean carrying a map purely to undo it on every request.
		val vSet = YgoprodeckMapper.toSet(
			YgoSetDto(setName = "Metal Raiders", setCode = "MRD", numOfCards = 144, tcgDate = "2002-06-26"),
			mProvider,
		)

		assertNotNull(vSet)
		assertEquals("Metal Raiders", vSet.id.local)
		assertEquals("MRD", vSet.code)
		assertEquals(2002, vSet.releaseDate?.year)
	}

	@Test
	fun `a set never released in the TCG keeps a null date`() {
		val vSet = YgoprodeckMapper.toSet(
			YgoSetDto(setName = "OCG Only", setCode = "OCG", numOfCards = 10, tcgDate = null),
			mProvider,
		)

		assertNotNull(vSet)
		assertNull(vSet.releaseDate)
	}

	@Test
	fun `a set image becomes a full-colour symbol -- and its absence becomes none`() {
		val vWith = YgoprodeckMapper.toSet(
			YgoSetDto(setName = "Metal Raiders", setCode = "MRD", numOfCards = 144,
				setImage = "https://images.ygoprodeck.com/images/sets/MRD.jpg"),
			mProvider,
		)
		val vWithout = YgoprodeckMapper.toSet(
			YgoSetDto(setName = "No Art", setCode = "NA", numOfCards = 5, setImage = null),
			mProvider,
		)

		assertNotNull(vWith)
		assertEquals("https://images.ygoprodeck.com/images/sets/MRD.jpg", vWith.symbol?.url)
		assertEquals(false, vWith.symbol?.isMonochrome)
		assertNotNull(vWithout)
		assertNull(vWithout.symbol)
	}

	// ============
	//  Cards

	@Test
	fun `the collector number comes from this set's printing -- not the first one listed`() {
		// The card's first listed appearance is Gold Series. Reading the number off that would put
		// "GLD1-EN001" on a card being browsed in Metal Raiders.
		val vCard = YgoprodeckMapper.toPrinting(card(), mProvider, mMetalRaiders, CardLanguage.ENGLISH)

		assertNotNull(vCard)
		assertEquals("MRD-098", vCard.providerRawCollectorNumber)
		assertEquals("098", vCard.collectorNumber)
		assertEquals("Rare", vCard.classification.rarity)
	}

	@Test
	fun `a regional suffix is kept whole rather than reduced to digits`() {
		// "ENC09" and "EN009" are different printings. Stripping the letters would collapse them.
		val vCard = YgoprodeckMapper.toPrinting(
			card().copy(cardSets = listOf(YgoCardSetDto("Speed Duel", "SBC1-ENC09", "Common"))),
			mProvider,
			CardSet(SourceId(mProvider, "Speed Duel"), YuGiOhGame.id, "SBC1", "Speed Duel", null, null),
			CardLanguage.ENGLISH,
		)

		assertNotNull(vCard)
		assertEquals("ENC09", vCard.collectorNumber)
	}

	@Test
	fun `a cross-set result with no set falls back to the first listed printing`() {
		// Nothing better is available: a search result does not name a set to pick from, and the
		// rarity genuinely belongs to a printing rather than to the card.
		val vCard = YgoprodeckMapper.toPrinting(card(), mProvider, set = null, language = CardLanguage.ENGLISH)

		assertNotNull(vCard)
		assertEquals("Gold Series", vCard.setName)
		assertEquals("Common", vCard.classification.rarity)
	}

	@Test
	fun `level is the cost axis and attribute is the colour axis`() {
		val vCard = YgoprodeckMapper.toPrinting(card(), mProvider, mMetalRaiders, CardLanguage.ENGLISH)

		assertNotNull(vCard)
		assertEquals(4, vCard.attributes.cost)
		assertEquals(1800, vCard.attributes.primary)
		assertEquals(800, vCard.attributes.secondary)
		// Lower-cased onto the key `YuGiOhGame` declares, which is what carries its colour.
		assertEquals(listOf("water"), vCard.classification.domains)
	}

	@Test
	fun `no identity is claimed -- since one record is one card rather than one printing`() {
		// The passcode is shared by every printing, but this adapter emits one record per card, so
		// there is no set of printings for an identity to group. Pointing a card at itself would
		// be an identity that says nothing.
		val vCard = YgoprodeckMapper.toPrinting(card(), mProvider, mMetalRaiders, CardLanguage.ENGLISH)

		assertNotNull(vCard)
		assertNull(vCard.identity)
		assertEquals(listOf("23771716"), vCard.externalIds[ExternalIdKey.PUBLISHER_CARD])
	}

	@Test
	fun `the small image variant is used for the grid`() {
		val vCard = YgoprodeckMapper.toPrinting(card(), mProvider, mMetalRaiders, CardLanguage.ENGLISH)

		assertNotNull(vCard)
		assertTrue(vCard.artwork.thumbnailUrl!!.contains("cards_small"))
		assertTrue(vCard.artwork.displayUrl!!.contains("/cards/"))
	}

	@Test
	fun `a card with no set entry at all still maps`() {
		val vCard = YgoprodeckMapper.toPrinting(
			card().copy(cardSets = emptyList()),
			mProvider,
			set = null,
			language = CardLanguage.ENGLISH,
		)

		assertNotNull(vCard)
		// Falls back to the passcode rather than producing a blank collector number.
		assertEquals("23771716", vCard.collectorNumber)
	}
}
