package com.bitsycore.cardbrowser.providers.tcgdex

import com.bitsycore.cardbrowser.core.model.Availability
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.ExternalIdKey
import com.bitsycore.cardbrowser.core.model.Finish
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

/**
 * The TCGdex mapping, against DTOs shaped like real responses.
 *
 * Deterministic and offline. `TcgdexLiveSmokeTest` covers whether the API still sends this shape;
 * this covers whether the shape is read correctly once it arrives.
 */
class TcgdexMapperTest {

	private val mProvider = ProviderId("tcgdex")

	private val mSet = TcgdexMapper.toSet(
		TcgdexSetDto(
			id = "swsh3",
			name = "Darkness Ablaze",
			releaseDate = "2020-08-14",
			cardCount = TcgdexCardCountDto(total = 201, official = 189),
			abbreviation = TcgdexAbbreviationDto(official = "DAA"),
		),
		mProvider,
	)!!

	// ============
	//  Sets

	@Test
	fun `the catalogue takes its release date from the separate query`() {
		val vSet = TcgdexMapper.toSet(
			dto = TcgdexSetBriefDto(id = "base1", name = "Base Set", cardCount = TcgdexCardCountDto(total = 102)),
			provider = mProvider,
			releaseDates = mapOf("base1" to LocalDate(1999, 1, 9)),
		)

		assertNotNull(vSet)
		assertEquals(LocalDate(1999, 1, 9), vSet.releaseDate)
		assertEquals(102, vSet.cardCount)
	}

	@Test
	fun `a set the date query never saw keeps a null date rather than a wrong one`() {
		// The GraphQL schema is English-only, so a locale-exclusive set has no date to find. It
		// sorts last, which is honest; giving it another set's date would not be.
		val vSet = TcgdexMapper.toSet(
			dto = TcgdexSetBriefDto(id = "sv10.5", name = "Locale Only"),
			provider = mProvider,
			releaseDates = mapOf("base1" to LocalDate(1999, 1, 9)),
		)

		assertNotNull(vSet)
		assertNull(vSet.releaseDate)
	}

	@Test
	fun `the card count includes secret rares -- since the grid shows them`() {
		// `official` is 189 and `total` is 201. Showing 189 beside a grid of 201 tiles reads as a
		// bug in the app rather than as two different counts.
		assertEquals(201, mSet.cardCount)
	}

	@Test
	fun `a set logo gets the extension appended and is not treated as monochrome`() {
		// Like card art, the URL is a base. The bare form returns an HTML error page, not an image.
		// The `symbol` field TCGdex also advertises is deliberately unused: its CDN 404s every form
		// of it, checked across several sets with .png, .webp and .jpg.
		val vSet = TcgdexMapper.toSet(
			dto = TcgdexSetBriefDto(
				id = "swsh3",
				name = "Darkness Ablaze",
				logo = "https://assets.tcgdex.net/en/swsh/swsh3/logo",
				symbol = "https://assets.tcgdex.net/univ/swsh/swsh3/symbol",
			),
			provider = mProvider,
			releaseDates = emptyMap(),
		)

		assertNotNull(vSet)
		assertEquals("https://assets.tcgdex.net/en/swsh/swsh3/logo.webp", vSet.symbol?.url)
		// Full-colour wordmarks; recolouring one would flatten it.
		assertEquals(false, vSet.symbol?.isMonochrome)
	}

	@Test
	fun `the 61 sets with no logo get no symbol`() {
		val vSet = TcgdexMapper.toSet(
			dto = TcgdexSetBriefDto(id = "B2A", name = "Paldean Wonders", logo = null),
			provider = mProvider,
			releaseDates = emptyMap(),
		)

		assertNotNull(vSet)
		assertNull(vSet.symbol)
	}

	// ============
	//  Cards

	@Test
	fun `images get the WebP suffixes rather than the raw base`() {
		val vCard = TcgdexMapper.toPrinting(
			dto = TcgdexCardBriefDto(
				id = "swsh3-1",
				localId = "1",
				name = "Butterfree V",
				image = "https://assets.tcgdex.net/en/swsh/swsh3/1",
			),
			provider = mProvider,
			set = mSet,
			language = CardLanguage.ENGLISH,
		)

		assertNotNull(vCard)
		assertEquals("https://assets.tcgdex.net/en/swsh/swsh3/1/low.webp", vCard.artwork.thumbnailUrl)
		assertEquals("https://assets.tcgdex.net/en/swsh/swsh3/1/high.webp", vCard.artwork.displayUrl)
	}

	@Test
	fun `a card with no scan gets a placeholder rather than a broken URL`() {
		// TCGdex has records for cards it has no image of. A URL built from a null base would be
		// the string "null/low.webp", which fails forever and gets cached as a failure.
		val vCard = TcgdexMapper.toPrinting(
			dto = TcgdexCardBriefDto(id = "2024sv-1", localId = "1", name = "No Art", image = null),
			provider = mProvider,
			set = mSet,
			language = CardLanguage.ENGLISH,
		)

		assertNotNull(vCard)
		assertEquals("", vCard.artwork.imageUrl)
		assertNull(vCard.artwork.thumbnailUrl)
	}

	@Test
	fun `the requested language is confirmed and no other is denied`() {
		val vCard = TcgdexMapper.toPrinting(
			dto = TcgdexCardBriefDto(id = "swsh3-1", localId = "1", name = "Papilusion V"),
			provider = mProvider,
			set = mSet,
			language = CardLanguage.FRENCH,
		)

		assertNotNull(vCard)
		assertEquals(Availability.AVAILABLE, vCard.languages.availabilityOf(CardLanguage.FRENCH))
		// The Korean catalogue not listing this card says nothing about Korean printings existing.
		assertEquals(Availability.UNKNOWN, vCard.languages.availabilityOf(CardLanguage.KOREAN))
		assertTrue(vCard.languages.absent.isEmpty(), "Nothing may be marked absent")
	}

	@Test
	fun `variant booleans populate both sides of the finish coverage`() {
		// The only provider here that can. `normal: false` is a statement, not a silence.
		val vCard = TcgdexMapper.toPrinting(
			dto = TcgdexCardDto(
				id = "swsh3-1",
				localId = "1",
				name = "Butterfree V",
				variants = TcgdexVariantsDto(normal = false, holo = true, reverse = false),
			),
			provider = mProvider,
			set = mSet,
			language = CardLanguage.ENGLISH,
		)

		assertNotNull(vCard)
		assertEquals(Availability.UNAVAILABLE, vCard.finishes.availabilityOf(Finish.NON_FOIL))
		assertEquals(Availability.AVAILABLE, vCard.finishes.availabilityOf(Finish.FOIL))
		// TCGdex has no textured-foil concept, so its silence about one is not a denial.
		assertEquals(Availability.UNKNOWN, vCard.finishes.availabilityOf(Finish.TEXTURED))
	}

	@Test
	fun `a card with no variants block leaves finishes unstated`() {
		val vCard = TcgdexMapper.toPrinting(
			dto = TcgdexCardDto(id = "swsh3-2", localId = "2", name = "Something", variants = null),
			provider = mProvider,
			set = mSet,
			language = CardLanguage.ENGLISH,
		)

		assertNotNull(vCard)
		assertTrue(vCard.finishes.isUnstated)
	}

	@Test
	fun `Cardmarket product ids are carried across -- de-duplicated`() {
		val vCard = TcgdexMapper.toPrinting(
			dto = TcgdexCardDto(
				id = "swsh3-1",
				localId = "1",
				name = "Butterfree V",
				variantsDetailed = listOf(
					TcgdexVariantDetailDto(type = "holo", thirdParty = TcgdexThirdPartyDto(cardmarket = 482879)),
					TcgdexVariantDetailDto(type = "reverse", thirdParty = TcgdexThirdPartyDto(cardmarket = 482879)),
				),
			),
			provider = mProvider,
			set = mSet,
			language = CardLanguage.ENGLISH,
		)

		assertNotNull(vCard)
		assertEquals(listOf("482879"), vCard.externalIds[ExternalIdKey.CARDMARKET_PRODUCT])
	}

	@Test
	fun `Pokemon gets no energy cost -- because a card has no single one`() {
		// Cost is per attack. Filling this from the first attack would produce a sortable number
		// that means nothing.
		val vCard = TcgdexMapper.toPrinting(
			dto = TcgdexCardDto(id = "swsh3-1", localId = "1", name = "Butterfree V", hp = 190),
			provider = mProvider,
			set = mSet,
			language = CardLanguage.ENGLISH,
		)

		assertNotNull(vCard)
		assertNull(vCard.attributes.cost)
		assertEquals(190, vCard.attributes.primary)
	}

	@Test
	fun `a collector number missing from the record falls back to the id's tail`() {
		val vCard = TcgdexMapper.toPrinting(
			dto = TcgdexCardBriefDto(id = "swsh3-42", localId = null, name = "Numberless"),
			provider = mProvider,
			set = mSet,
			language = CardLanguage.ENGLISH,
		)

		assertNotNull(vCard)
		assertEquals("42", vCard.collectorNumber)
	}

	@Test
	fun `a search result derives its set from the card id when no set is supplied`() {
		val vCard = TcgdexMapper.toPrinting(
			dto = TcgdexCardDto(id = "gym2-2", localId = "2", name = "Blaine's Charizard"),
			provider = mProvider,
			set = null,
			language = CardLanguage.ENGLISH,
		)

		assertNotNull(vCard)
		assertEquals(SourceId(mProvider, "gym2"), vCard.setId)
	}
}
