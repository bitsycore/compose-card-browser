package com.bitsycore.cardbrowser.ui

import com.bitsycore.cardbrowser.core.model.Artwork
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardClassification
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.Finish
import com.bitsycore.cardbrowser.core.model.FinishCoverage
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.model.LanguageCoverage
import com.bitsycore.cardbrowser.core.model.LocalizedText
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.ui.detail.CardDetailContract
import com.bitsycore.cardbrowser.ui.detail.CardDetailContract.Intent
import com.bitsycore.cardbrowser.ui.detail.CardDetailContract.UiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The card detail reducer, with the swipe list.
 *
 * The index arithmetic is the part worth pinning down: a list that changes underneath the screen
 * must not leave it pointing past the end.
 */
class CardDetailContractTest {

	private val mProvider = ProviderId("riftcodex")

	private fun card(
		number: String,
		languages: LanguageCoverage = LanguageCoverage.ENGLISH_ONLY,
		finishes: FinishCoverage = FinishCoverage(),
	) = CardPrinting(
		id = SourceId(mProvider, "card-$number"),
		game = Game.RIFTBOUND,
		setId = SourceId(mProvider, "OGN"),
		setCode = "OGN",
		setName = "Origins",
		collectorNumber = number,
		providerRawCollectorNumber = number,
		identity = null,
		text = LocalizedText(CardLanguage.ENGLISH, "Card $number"),
		artwork = Artwork(
			id = SourceId(mProvider, "card-$number"),
			imageUrl = "https://example.test/$number.png",
			thumbnailUrl = null,
			artist = null,
			treatment = ArtworkTreatment.STANDARD,
			language = CardLanguage.ENGLISH,
		),
		classification = CardClassification(rarity = "Common"),
		languages = languages,
		finishes = finishes,
	)

	private fun reduce(state: UiState, vararg intents: Intent): UiState =
		intents.fold(state) { vState, vIntent -> CardDetailContract.reduce(vState, vIntent) }

	private fun loaded(cards: List<CardPrinting>, index: Int) = Intent.Loaded(
		cards = cards,
		currentIndex = index,
		set = null,
		error = null,
		attribution = null,
		providerStatesIdentity = false,
		providerStatesFinishes = false,
	)

	private val mThree = listOf(card("1"), card("2"), card("3"))

	// ============
	//  Paging

	@Test
	fun `the opened card is the one shown`() {
		val vState = reduce(UiState(), loaded(mThree, 1))

		assertEquals("2", vState.card?.collectorNumber)
		assertEquals("2 of 3", vState.positionLabel)
		assertTrue(vState.canSwipe)
	}

	@Test
	fun `paging moves through the list`() {
		var vState = reduce(UiState(), loaded(mThree, 0))

		vState = reduce(vState, Intent.PageChanged(2))

		assertEquals("3", vState.card?.collectorNumber)
		assertEquals("3 of 3", vState.positionLabel)
	}

	@Test
	fun `an index past the end is clamped rather than trusted`() {
		// A stale session, or a filter that shrank the list, must not point off the end of it.
		val vState = reduce(UiState(), loaded(mThree, 99))

		assertEquals(2, vState.currentIndex)
		assertEquals("3", vState.card?.collectorNumber)
	}

	@Test
	fun `a negative index is clamped too`() {
		val vState = reduce(UiState(), loaded(mThree, -4))

		assertEquals(0, vState.currentIndex)
	}

	@Test
	fun `an empty list leaves no card and no crash`() {
		val vState = reduce(UiState(), loaded(emptyList(), 3))

		assertEquals(0, vState.currentIndex)
		assertNull(vState.card)
		assertFalse(vState.canSwipe)
		assertNull(vState.positionLabel)
	}

	@Test
	fun `paging an empty list is a no-op`() {
		val vState = reduce(UiState(), loaded(emptyList(), 0), Intent.PageChanged(2))

		assertEquals(0, vState.currentIndex)
		assertNull(vState.card)
	}

	@Test
	fun `a single card offers no swipe affordance`() {
		val vState = reduce(UiState(), loaded(listOf(card("1")), 0))

		assertFalse(vState.canSwipe)
		assertNull(vState.positionLabel, "a lone card should not be labelled 1 of 1")
	}

	@Test
	fun `moving to another card drops the zoom and the finish selection`() {
		// Both belong to the card that was on screen, not to the screen.
		var vState = reduce(
			UiState(),
			loaded(mThree, 0),
			Intent.ZoomToggled(true),
			Intent.FinishSelected(Finish.FOIL),
		)
		assertTrue(vState.isZoomed)

		vState = reduce(vState, Intent.PageChanged(1))

		assertFalse(vState.isZoomed, "a new card is not the card that was zoomed in on")
		assertNull(vState.selectedFinish)
	}

	@Test
	fun `the language preference survives moving between cards`() {
		// Unlike finish, this is a preference about the reader rather than about the printing.
		var vState = reduce(
			UiState(),
			loaded(mThree, 0),
			Intent.LanguageSelected(CardLanguage.JAPANESE),
		)

		vState = reduce(vState, Intent.PageChanged(2))

		assertEquals(CardLanguage.JAPANESE, vState.requestedLanguage)
	}

	// ============
	//  Per-card honesty, across a swipe

	@Test
	fun `each card's language options are derived from that card -- not from the screen`() {
		// The pager renders neighbouring pages at once, so a page must not read the current card's
		// coverage. Here one card has a French printing and its neighbour does not.
		val vFrench = card("2", languages = LanguageCoverage(confirmed = setOf(CardLanguage.FRENCH)))
		val vState = reduce(UiState(), loaded(listOf(card("1"), vFrench), 0))

		val vEnglishOnly = vState.languageOptionsFor(vState.cards[0])
		val vFrenchCard = vState.languageOptionsFor(vState.cards[1])

		assertTrue(vEnglishOnly.single { it.language == CardLanguage.ENGLISH }.isSelectable)
		assertFalse(vEnglishOnly.single { it.language == CardLanguage.FRENCH }.isSelectable)
		assertTrue(vFrenchCard.single { it.language == CardLanguage.FRENCH }.isSelectable)
	}

	@Test
	fun `a card with no finish data offers nothing while its neighbour offers what it has`() {
		val vFoil = card("2", finishes = FinishCoverage(confirmed = setOf(Finish.FOIL)))
		val vState = reduce(UiState(), loaded(listOf(card("1"), vFoil), 0))

		assertTrue(vState.finishOptionsFor(vState.cards[0]).isEmpty())
		assertTrue(
			vState.finishOptionsFor(vState.cards[1])
				.single { it.finish == Finish.FOIL }
				.isSelectable,
		)
	}

	@Test
	fun `preferring French still resolves to English and is marked a fallback`() {
		val vState = reduce(
			UiState(),
			loaded(mThree, 0),
			Intent.LanguageSelected(CardLanguage.FRENCH),
		)

		val vResolution = vState.languageResolutionFor(vState.cards[0])

		assertEquals(CardLanguage.ENGLISH, vResolution.shown)
		assertTrue(vResolution.isFallback)
	}

	@Test
	fun `the artwork note explains the missing list when the provider states no identity`() {
		val vState = reduce(UiState(), loaded(mThree, 0))

		val vNote = vState.artworkNote
		assertTrue(vNote != null && vNote.contains("does not link printings"))
	}
}
