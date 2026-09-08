package com.bitsycore.cardbrowser.ui.detail

import com.bitsycore.cardbrowser.core.cardmarket.CardmarketLink
import com.bitsycore.cardbrowser.core.cardmarket.CardmarketLinkBuilder
import com.bitsycore.cardbrowser.core.model.Availability
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.Finish
import com.bitsycore.cardbrowser.core.model.LanguageResolution
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.lib.pulse.container.ContainerContract

/**
 * The card detail state machine.
 *
 * The screen shows one card at a time but holds the whole list it can swipe through, so
 * [UiState.cards] is the list and [UiState.currentIndex] is where in it we are. Everything a page
 * needs is derived from *its own* card rather than read from state, which is what lets a pager
 * render the page either side of the current one correctly.
 *
 * Most of the interesting logic is about what *not* to claim -- see [languageOptionsFor],
 * [finishOptionsFor] and [artworkNote].
 */
object CardDetailContract :
	ContainerContract<CardDetailContract.UiState, CardDetailContract.Intent, CardDetailContract.Effect>() {

	/**
	 * @property cards every card the user can swipe to, in the order the grid showed them
	 * @property currentIndex where in [cards] we are. Always in bounds when [cards] is non-empty
	 * @property requestedLanguage what the preference order asks for, which may not be on offer
	 */
	data class UiState(
		val cards: List<CardPrinting> = emptyList(),
		val currentIndex: Int = 0,
		val set: CardSet? = null,
		val isLoading: Boolean = true,
		val error: ProviderError? = null,
		val requestedLanguage: CardLanguage = CardLanguage.ENGLISH,
		val selectedFinish: Finish? = null,
		val isZoomed: Boolean = false,
		val isFullscreen: Boolean = false,
		val providerStatesIdentity: Boolean = false,
		val providerStatesFinishes: Boolean = false,
		val attribution: String? = null,
	) {

		/** The card on screen, or `null` while loading or after a failure. */
		val card: CardPrinting? get() = cards.getOrNull(currentIndex)

		/** True when there is more than one card to move between, so the strip is worth showing. */
		val canSwipe: Boolean get() = cards.size > 1

		/** "12 of 352", for the top bar. */
		val positionLabel: String?
			get() = if (canSwipe) "${currentIndex + 1} of ${cards.size}" else null

		/**
		 * Which language is actually shown for [card] versus which was asked for.
		 *
		 * This is what stops the app labelling an English-only record as a French printing.
		 */
		fun languageResolutionFor(card: CardPrinting): LanguageResolution = LanguageResolution(
			requested = requestedLanguage,
			shown = card.languages.resolve(listOf(requestedLanguage) + CardLanguage.PREFERENCE_ORDER),
		)

		/**
		 * Every language, each with its real standing.
		 *
		 * All four are listed rather than only the confirmed one, because "we have no information
		 * about Korean" is itself information. Only [Availability.AVAILABLE] entries are selectable.
		 */
		fun languageOptionsFor(card: CardPrinting): List<LanguageOption> {
			val vShown = languageResolutionFor(card).shown
			return CardLanguage.PREFERENCE_ORDER.map { vLanguage ->
				LanguageOption(
					language = vLanguage,
					availability = card.languages.availabilityOf(vLanguage),
					isSelected = vLanguage == vShown,
				)
			}
		}

		/**
		 * Every finish and its standing, or an empty list when the provider records none at all.
		 *
		 * Empty is deliberate and different from "no finishes exist": [providerStatesFinishes] is
		 * what the screen uses to say which of the two it is.
		 */
		fun finishOptionsFor(card: CardPrinting): List<FinishOption> {
			if (card.finishes.isUnstated) return emptyList()
			return Finish.entries.map { vFinish ->
				FinishOption(
					finish = vFinish,
					availability = card.finishes.availabilityOf(vFinish),
					isSelected = vFinish == selectedFinish,
				)
			}
		}

		/**
		 * What to say about other artworks, or `null` when the provider states card identity and a
		 * real list could be shown.
		 */
		val artworkNote: String?
			get() = if (providerStatesIdentity) {
				null
			} else {
				"This card database lists each printing separately and does not link printings of " +
					"the same card, so other artworks cannot be listed here. They appear as their " +
					"own tiles in the set, and you can swipe to them."
			}

		/**
		 * The Cardmarket link for one card.
		 *
		 * Derived per card rather than stored, because the screen renders several cards at once
		 * while a swipe is in flight and each needs its own.
		 */
		fun cardmarketLinkFor(card: CardPrinting): CardmarketLink? =
			CardmarketLinkBuilder.linkFor(card, set)
	}

	/** One language row: what it is, whether it is offered, and whether it is showing. */
	data class LanguageOption(
		val language: CardLanguage,
		val availability: Availability,
		val isSelected: Boolean,
	) {

		/** Only a confirmed language may be chosen. Unknown is not a yes. */
		val isSelectable: Boolean get() = availability == Availability.AVAILABLE
	}

	/** One finish row. */
	data class FinishOption(
		val finish: Finish,
		val availability: Availability,
		val isSelected: Boolean,
	) {

		val isSelectable: Boolean get() = availability == Availability.AVAILABLE
	}

	sealed interface Intent {

		data class Load(val cardId: String, val setId: String?) : Intent

		data class Loaded(
			val cards: List<CardPrinting>,
			val currentIndex: Int,
			val set: CardSet?,
			val error: ProviderError?,
			val attribution: String?,
			val providerStatesIdentity: Boolean,
			val providerStatesFinishes: Boolean,
		) : Intent

		/** The pager settled on another card, or the preview strip was tapped. */
		data class PageChanged(val index: Int) : Intent

		data class LanguageSelected(val language: CardLanguage) : Intent

		data class FinishSelected(val finish: Finish) : Intent

		data class ZoomToggled(val isZoomed: Boolean) : Intent

		/** The card image was tapped, or the fullscreen viewer was dismissed. */
		data class FullscreenToggled(val isFullscreen: Boolean) : Intent

		/** Opening a link never places an order. */
		data class OpenCardmarket(val cardId: String) : Intent
	}

	sealed interface Effect {

		/** Shown when the platform refused to open a browser, so the tap is not silently lost. */
		data class LinkFailed(val url: String) : Effect
	}

	override fun reduce(state: UiState, intent: Intent): UiState = when (intent) {

		is Intent.Load -> state.copy(isLoading = true, error = null)

		is Intent.Loaded -> state.copy(
			cards = intent.cards,
			// Clamped rather than trusted: a card that is no longer in the list -- because the
			// filter changed underneath, or the session held a stale list -- must not leave the
			// screen pointing past the end of it.
			currentIndex = intent.currentIndex.coerceIn(0, (intent.cards.size - 1).coerceAtLeast(0)),
			set = intent.set,
			error = intent.error,
			attribution = intent.attribution,
			providerStatesIdentity = intent.providerStatesIdentity,
			providerStatesFinishes = intent.providerStatesFinishes,
			isLoading = false,
		)

		is Intent.PageChanged -> if (state.cards.isEmpty()) {
			state
		} else {
			state.copy(
				currentIndex = intent.index.coerceIn(0, state.cards.size - 1),
				// A new card is not the card that was zoomed in on.
				isZoomed = false,
				isFullscreen = false,
				// Finish is a property of the printing, so a selection does not carry across.
				selectedFinish = null,
			)
		}

		is Intent.LanguageSelected -> state.copy(requestedLanguage = intent.language)

		is Intent.FinishSelected -> state.copy(selectedFinish = intent.finish)

		is Intent.ZoomToggled -> state.copy(isZoomed = intent.isZoomed)

		is Intent.FullscreenToggled -> state.copy(isFullscreen = intent.isFullscreen)

		is Intent.OpenCardmarket -> state
	}
}
