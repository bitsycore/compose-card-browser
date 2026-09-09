package com.bitsycore.cardbrowser.ui

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.model.Artwork
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardClassification
import com.bitsycore.cardbrowser.core.model.CardIdentity
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.Finish
import com.bitsycore.cardbrowser.core.model.FinishCoverage
import com.bitsycore.cardbrowser.core.model.LanguageCoverage
import com.bitsycore.cardbrowser.core.model.LocalizedText
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
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
		identity: CardIdentity? = null,
	) = CardPrinting(
		id = SourceId(mProvider, "card-$number"),
		game = RiftboundGame.id,
		setId = SourceId(mProvider, "OGN"),
		setCode = "OGN",
		setName = "Origins",
		collectorNumber = number,
		providerRawCollectorNumber = number,
		identity = identity,
		text = LocalizedText(CardLanguage.ENGLISH, "Card $number"),
		artwork = Artwork(
			id = SourceId(mProvider, "art-$number"),
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

	private fun loaded(
		cards: List<CardPrinting>,
		index: Int,
		providerLanguages: Set<CardLanguage> = setOf(CardLanguage.ENGLISH),
	) = Intent.Loaded(
		cards = cards,
		currentIndex = index,
		set = null,
		error = null,
		attribution = null,
		providerStatesIdentity = false,
		providerStatesFinishes = false,
		providerLanguages = providerLanguages,
		providerDisplayName = "Riftcodex",
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
		// coverage. Here one card has a French printing and its neighbour does not, against a source
		// that states English only.
		val vFrench = card("2", languages = LanguageCoverage(confirmed = setOf(CardLanguage.FRENCH)))
		val vState = reduce(UiState(), loaded(listOf(card("1"), vFrench), 0))

		val vEnglishOnly = vState.languageOptionsFor(vState.cards[0]).map { it.language }
		val vFrenchCard = vState.languageOptionsFor(vState.cards[1]).map { it.language }

		assertEquals(listOf(CardLanguage.ENGLISH), vEnglishOnly)
		// French is confirmed on this printing, so it is listed even though the source's capability
		// set does not mention it: a fact about the card outranks a claim about the source.
		assertTrue(CardLanguage.FRENCH in vFrenchCard)
	}

	@Test
	fun `only languages worth offering are listed`() {
		// The bug this pins: every card used to list all of the app's languages, most of them greyed
		// out with "-- unknown" beside them, which is a row of disclaimers rather than a choice.
		val vState = reduce(
			UiState(),
			loaded(mThree, 0, providerLanguages = setOf(CardLanguage.ENGLISH, CardLanguage.JAPANESE)),
		)

		val vOptions = vState.languageOptionsFor(vState.cards[0])

		assertEquals(listOf(CardLanguage.JAPANESE, CardLanguage.ENGLISH), vOptions.map { it.language })
		// Unstated is not a no: the source serves Japanese, so it can be asked for this card.
		assertTrue(vOptions.single { it.language == CardLanguage.JAPANESE }.isSelectable)
		// The one already showing is not something to switch to.
		assertFalse(vOptions.single { it.language == CardLanguage.ENGLISH }.isSelectable)
	}

	@Test
	fun `a language the source cannot serve is not offered at all`() {
		val vState = reduce(UiState(), loaded(mThree, 0, providerLanguages = emptySet()))

		// English is what the card itself confirms, and it is the whole list.
		assertEquals(
			listOf(CardLanguage.ENGLISH),
			vState.languageOptionsFor(vState.cards[0]).map { it.language },
		)
	}

	@Test
	fun `switching language re-finds the card by collector number`() {
		// A source may reissue its ids per locale -- Wuthering Waves does -- so the index cannot be
		// reused and the id cannot be matched. Here the new list is a different length and reordered.
		var vState = reduce(UiState(), loaded(mThree, 1))

		vState = reduce(
			vState,
			Intent.LanguageSelected(CardLanguage.JAPANESE),
			Intent.LanguageChanged(
				language = CardLanguage.JAPANESE,
				cards = listOf(card("9"), card("2"), card("3"), card("4")),
				collectorNumber = "2",
			),
		)

		assertEquals("2", vState.card?.collectorNumber)
		assertEquals(CardLanguage.JAPANESE, vState.requestedLanguage)
		assertFalse(vState.isChangingLanguage)
	}

	@Test
	fun `a language change that comes back empty leaves the screen as it was`() {
		var vState = reduce(UiState(), loaded(mThree, 1))

		vState = reduce(
			vState,
			Intent.LanguageSelected(CardLanguage.JAPANESE),
			Intent.LanguageChanged(CardLanguage.JAPANESE, cards = emptyList(), collectorNumber = "2"),
		)

		assertEquals("2", vState.card?.collectorNumber, "an empty answer is not a language change")
		assertEquals(3, vState.cards.size)
		assertFalse(vState.isChangingLanguage)
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

	// ============
	//  Other artwork

	@Test
	fun `a source that links no printings offers no other artwork`() {
		// And offers nothing rather than a paragraph about offering nothing: the section is simply
		// not drawn. Every card here has a null identity, which is what most sources supply.
		val vState = reduce(UiState(), loaded(mThree, 0))

		assertTrue(vState.otherArtworksFor(vState.cards[0]).isEmpty())
	}

	@Test
	fun `other artworks are the printings the provider itself linked`() {
		val vIdentity = CardIdentity(SourceId(mProvider, "oracle-1"), "Annie")
		val vA = card("1", identity = vIdentity)
		val vB = card("2", identity = vIdentity)
		val vUnrelated = card("3", identity = CardIdentity(SourceId(mProvider, "oracle-2"), "Yi"))
		val vState = reduce(UiState(), loaded(listOf(vA, vB, vUnrelated), 0))

		val vOthers = vState.otherArtworksFor(vA)

		assertEquals(listOf("2"), vOthers.map { it.collectorNumber })
	}

	// ============
	//  Details

	@Test
	fun `the details table reports what the provider stated`() {
		val vState = reduce(UiState(), loaded(mThree, 0))

		val vFacts = vState.factsFor(vState.cards[0])

		assertTrue(vFacts.any { it.label == "Rarity" && it.value == "Common" })
		assertTrue(vFacts.any { it.label == "Set" && it.value == "Origins (OGN)" })
		assertTrue(vFacts.any { it.label == "Data source" && it.value == "Riftcodex" })
		// Riftbound calls its cost energy -- not "Energy Cost", and not Magic's "Mana value". This
		// card states none, so there is no row for it at all.
		assertFalse(vFacts.any { it.label == "Energy" })
	}

	@Test
	fun `a field the provider did not state gets no row`() {
		val vState = reduce(UiState(), loaded(mThree, 0))

		val vFacts = vState.factsFor(vState.cards[0])

		assertFalse(vFacts.any { it.label == "Artist" }, "these cards state no artist")
		assertFalse(vFacts.any { it.value.isBlank() })
	}
}
