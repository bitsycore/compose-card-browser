package com.bitsycore.cardbrowser.core

import com.bitsycore.cardbrowser.core.filter.CardFilterEngine
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.Finish
import com.bitsycore.cardbrowser.core.model.FinishCoverage
import com.bitsycore.cardbrowser.core.model.LanguageCoverage
import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.provider.CardSortField
import com.bitsycore.cardbrowser.core.provider.SortDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Local filtering and sorting. */
class CardFilterEngineTest {

	private val mAnnie = TestCards.printing(
		id = "a", name = "Annie - Fiery", collectorNumber = "1",
		rarity = "Epic", type = "Unit", domains = listOf("Fury"), energy = 5,
	)
	private val mFirestorm = TestCards.printing(
		id = "b", name = "Firestorm", collectorNumber = "2",
		rarity = "Common", type = "Spell", domains = listOf("Fury"), energy = 3,
	)
	private val mLux = TestCards.printing(
		id = "c", name = "Lux - Illuminated", collectorNumber = "10",
		rarity = "Epic", type = "Unit", domains = listOf("Order"), energy = 5,
		treatment = ArtworkTreatment.ALTERNATE_ART,
	)
	private val mEpee = TestCards.printing(
		id = "d", name = "Épée de Fureur", collectorNumber = "11",
		rarity = "Rare", type = "Gear", domains = listOf("Fury"), energy = null,
	)

	private val mAll = listOf(mAnnie, mFirestorm, mLux, mEpee)

	// ============
	//  Text

	@Test
	fun `a name search is case-insensitive and matches a substring`() {
		val vResult = CardFilterEngine.apply(mAll, CardQuery(text = "annie"))

		assertEquals(listOf("a"), vResult.map { it.id.local })
	}

	@Test
	fun `an accented name is found without typing the accents`() {
		// A French card name should be reachable from a keyboard that is not set up for it.
		assertEquals(listOf("d"), CardFilterEngine.apply(mAll, CardQuery(text = "epee")).map { it.id.local })
		// And with them.
		assertEquals(listOf("d"), CardFilterEngine.apply(mAll, CardQuery(text = "Épée")).map { it.id.local })
	}

	@Test
	fun `a collector number search matches by prefix, not substring`() {
		// Typing "1" should offer 1, 10 and 11 -- not every card with a 1 buried in its number.
		val vResult = CardFilterEngine.apply(mAll, CardQuery(text = "1"))

		assertEquals(listOf("1", "10", "11"), vResult.map { it.collectorNumber })
	}

	@Test
	fun `blank text matches everything`() {
		assertEquals(4, CardFilterEngine.apply(mAll, CardQuery(text = "   ")).size)
		assertEquals(4, CardFilterEngine.apply(mAll, CardQuery(text = null)).size)
	}

	// ============
	//  Fields

	@Test
	fun `values within a field are OR-ed`() {
		val vResult = CardFilterEngine.apply(mAll, CardQuery(rarities = setOf("Epic", "Rare")))

		assertEquals(setOf("a", "c", "d"), vResult.map { it.id.local }.toSet())
	}

	@Test
	fun `fields are AND-ed with each other`() {
		val vResult = CardFilterEngine.apply(
			mAll,
			CardQuery(rarities = setOf("Epic"), domains = setOf("Fury")),
		)

		assertEquals(listOf("a"), vResult.map { it.id.local })
	}

	@Test
	fun `a card with no energy is excluded by an energy filter rather than counted as zero`() {
		val vResult = CardFilterEngine.apply(mAll, CardQuery(energyCosts = setOf(5)))

		assertEquals(setOf("a", "c"), vResult.map { it.id.local }.toSet())
		assertFalse(vResult.contains(mEpee))
	}

	@Test
	fun `an artwork treatment filter selects only that treatment`() {
		val vResult = CardFilterEngine.apply(
			mAll,
			CardQuery(treatments = setOf(ArtworkTreatment.ALTERNATE_ART)),
		)

		assertEquals(listOf("c"), vResult.map { it.id.local })
	}

	// ============
	//  Unknown is not a match

	@Test
	fun `an unknown finish does not satisfy a finish filter`() {
		// Riftcodex states no finishes at all. A foil filter must return nothing rather than
		// everything -- "we do not know" is not "yes".
		val vResult = CardFilterEngine.apply(mAll, CardQuery(finishes = setOf(Finish.FOIL)))

		assertTrue(vResult.isEmpty())
	}

	@Test
	fun `a confirmed finish does satisfy the filter`() {
		val vFoil = TestCards.printing(
			id = "foil",
			finishes = FinishCoverage(confirmed = setOf(Finish.FOIL)),
		)

		val vResult = CardFilterEngine.apply(mAll + vFoil, CardQuery(finishes = setOf(Finish.FOIL)))

		assertEquals(listOf("foil"), vResult.map { it.id.local })
	}

	@Test
	fun `an unknown language does not satisfy a language filter`() {
		val vResult = CardFilterEngine.apply(mAll, CardQuery(languages = setOf(CardLanguage.FRENCH)))

		assertTrue(vResult.isEmpty())
	}

	@Test
	fun `a confirmed language does satisfy the filter`() {
		val vFrench = TestCards.printing(
			id = "fr",
			languages = LanguageCoverage(confirmed = setOf(CardLanguage.FRENCH)),
		)

		val vResult = CardFilterEngine.apply(mAll + vFrench, CardQuery(languages = setOf(CardLanguage.FRENCH)))

		assertEquals(listOf("fr"), vResult.map { it.id.local })
	}

	// ============
	//  Sorting

	@Test
	fun `the default sort is natural collector order`() {
		val vResult = CardFilterEngine.apply(mAll.reversed(), CardQuery())

		assertEquals(listOf("1", "2", "10", "11"), vResult.map { it.collectorNumber })
	}

	@Test
	fun `sorting by name folds accents so E-acute is not exiled to the end`() {
		val vResult = CardFilterEngine.apply(mAll, CardQuery(sortBy = CardSortField.NAME))

		assertEquals(
			listOf("Annie - Fiery", "Épée de Fureur", "Firestorm", "Lux - Illuminated"),
			vResult.map { it.displayName },
		)
	}

	@Test
	fun `sorting by energy puts cards with no cost last, not first`() {
		val vResult = CardFilterEngine.apply(mAll, CardQuery(sortBy = CardSortField.ENERGY_COST))

		// A card with no energy is not a zero-cost card.
		assertEquals("11", vResult.last().collectorNumber)
		assertEquals(listOf("2", "1", "10", "11"), vResult.map { it.collectorNumber })
	}

	@Test
	fun `descending reverses the order`() {
		val vResult = CardFilterEngine.apply(
			mAll,
			CardQuery(sortDirection = SortDirection.DESCENDING),
		)

		assertEquals(listOf("11", "10", "2", "1"), vResult.map { it.collectorNumber })
	}

	// ============
	//  Facets

	@Test
	fun `facets list only what is present, sorted`() {
		val vFacets = CardFilterEngine.facetsOf(mAll)

		assertEquals(listOf("Fury", "Order"), vFacets.domains)
		assertEquals(listOf("Gear", "Spell", "Unit"), vFacets.cardTypes)
		assertEquals(listOf("Common", "Epic", "Rare"), vFacets.rarities)
		assertEquals(listOf(3, 5), vFacets.energyCosts)
		assertEquals(
			listOf(ArtworkTreatment.STANDARD, ArtworkTreatment.ALTERNATE_ART),
			vFacets.treatments,
		)
	}

	@Test
	fun `facets of nothing are empty`() {
		assertTrue(CardFilterEngine.facetsOf(emptyList()).isEmpty)
	}
}
