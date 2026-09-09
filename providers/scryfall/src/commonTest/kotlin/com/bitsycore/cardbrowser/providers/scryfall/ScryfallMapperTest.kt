package com.bitsycore.cardbrowser.providers.scryfall

import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.Availability
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardOrientation
import com.bitsycore.cardbrowser.core.model.Finish
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Scryfall mapping, against DTOs shaped like real responses. Deterministic and offline. */
class ScryfallMapperTest {

	private val mProvider = ProviderId("scryfall")

	private fun card(
		id: String = "b576a1ea-bd12-4f4f-be50-9ab23316a773",
		name: String = "Banishing Light",
		printedName: String? = null,
		finishes: List<String> = listOf("nonfoil", "foil"),
		colors: List<String> = listOf("W"),
		imageUris: ScryfallImageUrisDto? = ScryfallImageUrisDto(
			small = "https://cards.scryfall.io/small/x.jpg",
			normal = "https://cards.scryfall.io/normal/x.jpg",
			large = "https://cards.scryfall.io/large/x.jpg",
			thumb = "https://cards.scryfall.io/thumb/x.webp",
			grid = "https://cards.scryfall.io/grid/x.webp",
			display = "https://cards.scryfall.io/display/x.webp",
		),
		faces: List<ScryfallCardFaceDto> = emptyList(),
		oracleId: String? = "f28b21a6-f7ce-437a-8c5b-0423cb55cefb",
		layout: String? = "normal",
		power: String? = null,
	) = ScryfallCardDto(
		id = id,
		oracleId = oracleId,
		lang = "en",
		name = name,
		printedName = printedName,
		typeLine = "Enchantment",
		collectorNumber = "1",
		rarity = "common",
		set = "blb",
		setName = "Bloomburrow",
		colors = colors,
		cmc = 3.0,
		power = power,
		artist = "Zoltan Boros",
		layout = layout,
		finishes = finishes,
		imageUris = imageUris,
		cardFaces = faces,
	)

	// ============
	//  Sets

	@Test
	fun `a set is keyed by its printed code -- not its UUID`() {
		// `q=set:blb` takes the code, the code is what is printed on the card, and a UUID can
		// change if Scryfall rebuilds -- which would orphan every cached set.
		val vSet = ScryfallMapper.toSet(
			ScryfallSetDto(
				id = "a2f58272-bba6-439d-871e-7a46686ac018",
				code = "blb",
				name = "Bloomburrow",
				releasedAt = "2024-08-02",
				cardCount = 398,
			),
			mProvider,
		)

		assertNotNull(vSet)
		assertEquals("blb", vSet.id.local)
		assertEquals("BLB", vSet.code)
		assertEquals(2024, vSet.releaseDate?.year)
	}

	@Test
	fun `the set symbol is carried across and marked monochrome`() {
		// Scryfall's set SVGs carry no `fill` attribute, so they render in the SVG default of black
		// and vanish on a dark theme unless the UI recolours them. The flag is what tells it to.
		val vSet = ScryfallMapper.toSet(
			ScryfallSetDto(
				id = "a2f58272-bba6-439d-871e-7a46686ac018",
				code = "blb",
				name = "Bloomburrow",
				cardCount = 398,
				iconSvgUri = "https://svgs.scryfall.io/sets/blb.svg",
			),
			mProvider,
		)

		assertNotNull(vSet)
		assertEquals("https://svgs.scryfall.io/sets/blb.svg", vSet.symbol?.url)
		assertTrue(vSet.symbol?.isMonochrome == true)
	}

	@Test
	fun `a set with no icon gets no symbol rather than a blank one`() {
		val vSet = ScryfallMapper.toSet(
			ScryfallSetDto(id = "x", code = "xxx", name = "No Icon", cardCount = 1, iconSvgUri = ""),
			mProvider,
		)

		assertNotNull(vSet)
		assertNull(vSet.symbol)
	}

	// ============
	//  Cards

	@Test
	fun `a localised printing shows the printed name -- not the English one`() {
		// The whole point of the language feature. `printed_name` is absent on English cards, so
		// the fallback direction matters: preferring `name` would make every French card English.
		val vCard = ScryfallMapper.toPrinting(
			dto = card(printedName = "Lumière de bannissement"),
			provider = mProvider,
			set = null,
			language = CardLanguage.FRENCH,
		)

		assertNotNull(vCard)
		assertEquals("Lumière de bannissement", vCard.displayName)
		// The identity keeps the English name, so two printings of one card still match.
		assertEquals("Banishing Light", vCard.identity?.name)
	}

	@Test
	fun `an English printing falls back to name when there is no printed name`() {
		val vCard = ScryfallMapper.toPrinting(card(), mProvider, null, CardLanguage.ENGLISH)

		assertNotNull(vCard)
		assertEquals("Banishing Light", vCard.displayName)
	}

	@Test
	fun `oracle_id becomes a real card identity`() {
		val vCard = ScryfallMapper.toPrinting(card(), mProvider, null, CardLanguage.ENGLISH)

		assertNotNull(vCard)
		assertEquals(
			SourceId(mProvider, "f28b21a6-f7ce-437a-8c5b-0423cb55cefb"),
			vCard.identity?.id,
		)
	}

	@Test
	fun `a card with no oracle_id gets no invented identity`() {
		val vCard = ScryfallMapper.toPrinting(card(oracleId = null), mProvider, null, CardLanguage.ENGLISH)

		assertNotNull(vCard)
		assertNull(vCard.identity)
	}

	@Test
	fun `finishes are exhaustive -- so what is missing is genuinely absent`() {
		val vCard = ScryfallMapper.toPrinting(
			dto = card(finishes = listOf("nonfoil", "foil")),
			provider = mProvider,
			set = null,
			language = CardLanguage.ENGLISH,
		)

		assertNotNull(vCard)
		assertEquals(Availability.AVAILABLE, vCard.finishes.availabilityOf(Finish.NON_FOIL))
		assertEquals(Availability.AVAILABLE, vCard.finishes.availabilityOf(Finish.FOIL))
		assertEquals(Availability.UNAVAILABLE, vCard.finishes.availabilityOf(Finish.ETCHED))
		// Scryfall has no textured value at all, so its absence from the array is silence rather
		// than denial and must stay UNKNOWN.
		assertEquals(Availability.UNKNOWN, vCard.finishes.availabilityOf(Finish.TEXTURED))
	}

	@Test
	fun `an empty finishes array leaves finishes unstated`() {
		val vCard = ScryfallMapper.toPrinting(card(finishes = emptyList()), mProvider, null, CardLanguage.ENGLISH)

		assertNotNull(vCard)
		assertTrue(vCard.finishes.isUnstated)
	}

	@Test
	fun `a transforming card takes its art from the front face`() {
		// A double-faced card carries no top-level image_uris; each face has its own. Without the
		// fallback the whole set would render as placeholder tiles.
		val vCard = ScryfallMapper.toPrinting(
			dto = card(
				imageUris = null,
				faces = listOf(
					ScryfallCardFaceDto(
						name = "Front",
						colors = listOf("R"),
						artist = "Face Artist",
						imageUris = ScryfallImageUrisDto(
							grid = "https://cards.scryfall.io/grid/front.webp",
							display = "https://cards.scryfall.io/display/front.webp",
							large = "https://cards.scryfall.io/large/front.jpg",
						),
					),
					ScryfallCardFaceDto(name = "Back"),
				),
			),
			provider = mProvider,
			set = null,
			language = CardLanguage.ENGLISH,
		)

		assertNotNull(vCard)
		assertEquals("https://cards.scryfall.io/grid/front.webp", vCard.artwork.thumbnailUrl)
		assertEquals("https://cards.scryfall.io/display/front.webp", vCard.artwork.displayUrl)
	}

	@Test
	fun `the WebP variants are preferred over the JPEGs`() {
		val vCard = ScryfallMapper.toPrinting(card(), mProvider, null, CardLanguage.ENGLISH)

		assertNotNull(vCard)
		assertTrue(vCard.artwork.thumbnailUrl!!.endsWith(".webp"))
		assertTrue(vCard.artwork.displayUrl!!.endsWith(".webp"))
	}

	@Test
	fun `colours are spelled out for the filter chips`() {
		val vCard = ScryfallMapper.toPrinting(
			dto = card(colors = listOf("W", "U")),
			provider = mProvider,
			set = null,
			language = CardLanguage.ENGLISH,
		)

		assertNotNull(vCard)
		assertEquals(listOf("White", "Blue"), vCard.classification.domains)
	}

	@Test
	fun `a variable power is not pretended to be a number`() {
		// `*` and `1+*` are real values on real cards. `toIntOrNull` leaving them null is correct;
		// coercing them to 0 would sort them below every vanilla creature.
		val vStar = ScryfallMapper.toPrinting(card(power = "*"), mProvider, null, CardLanguage.ENGLISH)
		val vPlain = ScryfallMapper.toPrinting(card(power = "3"), mProvider, null, CardLanguage.ENGLISH)

		assertNull(vStar?.attributes?.primary)
		assertEquals(3, vPlain?.attributes?.primary)
	}

	@Test
	fun `only unambiguously wide layouts are landscape`() {
		// Split cards render in a portrait frame on Scryfall, so treating them as landscape would
		// give the grid a wrongly-shaped tile for a correctly-shaped image.
		assertEquals(
			CardOrientation.LANDSCAPE,
			ScryfallMapper.toPrinting(card(layout = "planar"), mProvider, null, CardLanguage.ENGLISH)?.orientation,
		)
		assertEquals(
			CardOrientation.PORTRAIT,
			ScryfallMapper.toPrinting(card(layout = "split"), mProvider, null, CardLanguage.ENGLISH)?.orientation,
		)
	}

	@Test
	fun `treatments come from stated fields -- most specific first`() {
		val vExtended = ScryfallMapper.toPrinting(
			dto = card().copy(frameEffects = listOf("extendedart")),
			provider = mProvider,
			set = null,
			language = CardLanguage.ENGLISH,
		)
		val vFullArt = ScryfallMapper.toPrinting(
			dto = card().copy(fullArt = true),
			provider = mProvider,
			set = null,
			language = CardLanguage.ENGLISH,
		)
		val vPlain = ScryfallMapper.toPrinting(card(), mProvider, null, CardLanguage.ENGLISH)

		assertEquals(ArtworkTreatment.EXTENDED_ART, vExtended?.artwork?.treatment)
		assertEquals(ArtworkTreatment.FULL_ART, vFullArt?.artwork?.treatment)
		assertEquals(ArtworkTreatment.STANDARD, vPlain?.artwork?.treatment)
	}
}
