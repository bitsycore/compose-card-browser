package com.bitsycore.cardbrowser.providers.altered

import com.bitsycore.cardbrowser.core.model.Availability
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Altered mirror mapping. Deterministic and offline. */
class AlteredMapperTest {

	private val mProvider = ProviderId("altered-db")

	private val mSet = CardSet(
		id = SourceId(mProvider, "ALIZE"),
		game = Game.ALTERED,
		code = "TBF",
		name = "Épreuve du froid",
		cardCount = 327,
		releaseDate = null,
	)

	private val mImages = "https://cdn.jsdelivr.net/gh/PolluxTroy0/Altered-TCG-Card-Database@main/IMAGES"

	private fun card(
		reference: String = "ALT_ALIZE_A_AX_35_C",
		collectorNumber: String? = "TBF-005-C-FR",
		elements: Map<String, String> = mapOf(
			"MAIN_COST" to "2",
			"RECALL_COST" to "2",
			"MAIN_EFFECT" to "Vous pouvez jouer les cartes épuisées de votre Réserve.",
		),
		isBanned: Boolean = false,
	) = AlteredCardDto(
		reference = reference,
		id = "01J3GQBCXE936A2VRZPQ28B83V",
		name = "Vaike, l'énergéticienne",
		collectorNumber = collectorNumber,
		cardType = AlteredNamedRefDto(reference = "CHARACTER", name = "Personnage"),
		cardSubTypes = listOf(AlteredNamedRefDto(reference = "ENGINEER", name = "Ingénieur")),
		rarity = AlteredNamedRefDto(reference = "COMMON", name = "Commun"),
		mainFaction = AlteredFactionDto(reference = "AX", name = "Axiom", color = "#8c432a"),
		elements = elements,
		isBanned = isBanned,
	)

	// ============
	//  Sets

	@Test
	fun `createdAt is not used as a release date`() {
		// CORE's record was created 2024-01-04 and the set released that September. Putting it in
		// `releaseDate` would make the UI print a date that is simply not the release date.
		val vSet = AlteredMapper.toSet(
			AlteredSetDto(
				reference = "CORE",
				code = "BTG",
				name = "Au-delà des portes",
				id = "01HKAFJN3HG3TWKYV0E014K01G",
				createdAt = "2024-01-04T14:55:59+00:00",
			),
			mProvider,
		)

		assertNotNull(vSet)
		assertNull(vSet.releaseDate)
		// The set is keyed by its reference, which is the path segment every file lives under.
		assertEquals("CORE", vSet.id.local)
		// The printed code is what is shown.
		assertEquals("BTG", vSet.code)
	}

	// ============
	//  Cards

	@Test
	fun `two print runs of one card stay two cards`() {
		// ALIZE contains both ALT_ALIZE_A_AX_35_C and ALT_ALIZE_B_AX_35_C, and both state collector
		// number TBF-005-C-FR. Keying on the collector number would silently drop one of the pair,
		// so the reference is the id.
		val vA = AlteredMapper.toPrinting(card("ALT_ALIZE_A_AX_35_C"), mProvider, mSet, CardLanguage.FRENCH, mImages)
		val vB = AlteredMapper.toPrinting(card("ALT_ALIZE_B_AX_35_C"), mProvider, mSet, CardLanguage.FRENCH, mImages)

		assertNotNull(vA)
		assertNotNull(vB)
		assertTrue(vA.id != vB.id, "The two print runs must not collapse into one id")
		assertEquals(vA.collectorNumber, vB.collectorNumber)
		// And the repository's de-duplication must not merge them either.
		assertTrue(vA.dedupeKey != vB.dedupeKey)
	}

	@Test
	fun `the collector number is the numeric segment of the printed code`() {
		val vCard = AlteredMapper.toPrinting(card(), mProvider, mSet, CardLanguage.FRENCH, mImages)

		assertNotNull(vCard)
		// "TBF-005-C-FR" is set code, number, rarity letter, language.
		assertEquals("005", vCard.collectorNumber)
		assertEquals("TBF-005-C-FR", vCard.providerRawCollectorNumber)
	}

	@Test
	fun `a card with no collector number falls back to the reference's number`() {
		val vCard = AlteredMapper.toPrinting(
			card(collectorNumber = null),
			mProvider,
			mSet,
			CardLanguage.FRENCH,
			mImages,
		)

		assertNotNull(vCard)
		assertEquals("35", vCard.collectorNumber)
	}

	@Test
	fun `images point at the mirror -- not the official bucket that answers 403`() {
		val vCard = AlteredMapper.toPrinting(card(), mProvider, mSet, CardLanguage.FRENCH, mImages)

		assertNotNull(vCard)
		assertEquals("$mImages/fr/ALIZE/ALT_ALIZE_A_AX_35_C.jpg", vCard.artwork.imageUrl)
		assertTrue(
			"amazonaws" !in vCard.artwork.imageUrl,
			"The official S3 bucket answers 403 for every card image",
		)
		// The mirror has no resized variant, and the adapter must not pretend otherwise.
		assertNull(vCard.artwork.thumbnailUrl)
	}

	@Test
	fun `the downloaded language is confirmed and no other is denied`() {
		val vCard = AlteredMapper.toPrinting(card(), mProvider, mSet, CardLanguage.FRENCH, mImages)

		assertNotNull(vCard)
		assertEquals(Availability.AVAILABLE, vCard.languages.availabilityOf(CardLanguage.FRENCH))
		assertEquals(Availability.UNKNOWN, vCard.languages.availabilityOf(CardLanguage.JAPANESE))
		assertTrue(vCard.languages.absent.isEmpty())
	}

	@Test
	fun `the elements bag becomes costs and rules text`() {
		val vCard = AlteredMapper.toPrinting(card(), mProvider, mSet, CardLanguage.FRENCH, mImages)

		assertNotNull(vCard)
		assertEquals(2, vCard.attributes.energy)
		assertEquals(2, vCard.attributes.might)
		assertTrue(vCard.text.rules!!.startsWith("Vous pouvez jouer"))
		// The three region powers cannot be collapsed into one number, so none is chosen.
		assertNull(vCard.attributes.power)
	}

	@Test
	fun `a banned card is still shown -- and says it is banned`() {
		val vCard = AlteredMapper.toPrinting(card(isBanned = true), mProvider, mSet, CardLanguage.FRENCH, mImages)

		assertNotNull(vCard)
		assertTrue("Banned" in vCard.tags)
	}

	@Test
	fun `faction and rarity use the localised names`() {
		val vCard = AlteredMapper.toPrinting(card(), mProvider, mSet, CardLanguage.FRENCH, mImages)

		assertNotNull(vCard)
		assertEquals(listOf("Axiom"), vCard.classification.domains)
		assertEquals("Commun", vCard.classification.rarity)
		assertEquals("Personnage", vCard.classification.type)
	}
}
