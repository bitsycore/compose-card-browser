package com.bitsycore.cardbrowser.ui.detail

import com.bitsycore.cardbrowser.core.cardmarket.CardmarketLink
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
 * Most of the interesting logic on this screen is about what *not* to claim, and it lives in
 * [UiState.languageOptions], [UiState.finishOptions] and [UiState.artworkNote].
 */
object CardDetailContract :
	ContainerContract<CardDetailContract.UiState, CardDetailContract.Intent, CardDetailContract.Effect>() {

	/**
	 * @property requestedLanguage what the user's preference order asks for, which may not be what
	 *   the provider has
	 * @property providerStatesIdentity whether the routed provider links printings of the same card
	 */
	data class UiState(
		val card: CardPrinting? = null,
		val set: CardSet? = null,
		val isLoading: Boolean = true,
		val error: ProviderError? = null,
		val requestedLanguage: CardLanguage = CardLanguage.ENGLISH,
		val selectedFinish: Finish? = null,
		val isZoomed: Boolean = false,
		val providerStatesIdentity: Boolean = false,
		val providerStatesFinishes: Boolean = false,
		val cardmarketLink: CardmarketLink? = null,
		val attribution: String? = null,
	) {

		/**
		 * Which language is actually on screen versus which was asked for.
		 *
		 * This is what stops the app labelling an English-only record as a French printing. When
		 * the user prefers French and the provider has only English, [LanguageResolution.isFallback]
		 * is true and the screen says "showing English" rather than pretending.
		 */
		val languageResolution: LanguageResolution?
			get() = card?.let {
				LanguageResolution(
					requested = requestedLanguage,
					shown = it.languages.resolve(listOf(requestedLanguage) + CardLanguage.PREFERENCE_ORDER),
				)
			}

		/**
		 * Every language, each with its real standing.
		 *
		 * All four are listed rather than only the confirmed one, because "we have no information
		 * about Korean" is itself information the user wants. Only [Availability.AVAILABLE] entries
		 * are selectable.
		 */
		val languageOptions: List<LanguageOption>
			get() {
				val vCard = card ?: return emptyList()
				return CardLanguage.PREFERENCE_ORDER.map { vLanguage ->
					LanguageOption(
						language = vLanguage,
						availability = vCard.languages.availabilityOf(vLanguage),
						isSelected = vLanguage == languageResolution?.shown,
					)
				}
			}

		/**
		 * Every finish and its standing, or an empty list when the provider records none at all.
		 *
		 * Empty is deliberate and different from "no finishes exist": [providerStatesFinishes] is
		 * what the screen uses to say which of the two it is.
		 */
		val finishOptions: List<FinishOption>
			get() {
				val vCard = card ?: return emptyList()
				if (vCard.finishes.isUnstated) return emptyList()
				return Finish.entries.map { vFinish ->
					FinishOption(
						finish = vFinish,
						availability = vCard.finishes.availabilityOf(vFinish),
						isSelected = vFinish == selectedFinish,
					)
				}
			}

		/**
		 * What to say about other artworks of this card.
		 *
		 * `null` when the provider states card identity and the artwork list can be trusted. When it
		 * does not, this explains why no list is shown instead of leaving an empty section that
		 * reads as "there are no others".
		 */
		val artworkNote: String?
			get() = when {
				card == null -> null
				providerStatesIdentity -> null
				else -> "This card database lists each printing separately and does not link " +
					"printings of the same card, so other artworks cannot be listed here. " +
					"They appear as their own tiles in the set."
			}
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
			val card: CardPrinting?,
			val set: CardSet?,
			val error: ProviderError?,
			val cardmarketLink: CardmarketLink?,
			val attribution: String?,
			val providerStatesIdentity: Boolean,
			val providerStatesFinishes: Boolean,
		) : Intent

		data class LanguageSelected(val language: CardLanguage) : Intent

		data class FinishSelected(val finish: Finish) : Intent

		data class ZoomToggled(val isZoomed: Boolean) : Intent

		/** The Cardmarket button. Opening a link never places an order. */
		data object OpenCardmarket : Intent
	}

	sealed interface Effect {

		/** Shown when the platform refused to open a browser, so the tap is not silently lost. */
		data class LinkFailed(val url: String) : Effect
	}

	override fun reduce(state: UiState, intent: Intent): UiState = when (intent) {

		is Intent.Load -> state.copy(isLoading = true, error = null)

		is Intent.Loaded -> state.copy(
			card = intent.card,
			set = intent.set,
			error = intent.error,
			cardmarketLink = intent.cardmarketLink,
			attribution = intent.attribution,
			providerStatesIdentity = intent.providerStatesIdentity,
			providerStatesFinishes = intent.providerStatesFinishes,
			isLoading = false,
		)

		is Intent.LanguageSelected -> state.copy(requestedLanguage = intent.language)

		is Intent.FinishSelected -> state.copy(selectedFinish = intent.finish)

		is Intent.ZoomToggled -> state.copy(isZoomed = intent.isZoomed)

		Intent.OpenCardmarket -> state
	}
}
