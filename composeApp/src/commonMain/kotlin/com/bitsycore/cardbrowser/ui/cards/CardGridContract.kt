package com.bitsycore.cardbrowser.ui.cards

import com.bitsycore.cardbrowser.core.filter.CardFacets
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.provider.CardSortField
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.data.repository.DataOrigin
import com.bitsycore.lib.pulse.container.ContainerContract

/**
 * The card grid's state machine.
 *
 * Two things here are worth reading closely: [UiState.requestGeneration], which is how a superseded
 * response is recognised and dropped, and [UiState.coverageNotice], which is how the screen avoids
 * describing a partial set as a complete one.
 */
object CardGridContract :
	ContainerContract<CardGridContract.UiState, CardGridContract.Intent, CardGridContract.Effect>() {

	/**
	 * @property query what the user has asked for. Changing it bumps [requestGeneration]
	 * @property isCompleteSet whether [cards] were filtered from the whole set or from part of it
	 * @property cachedCardCount how many cards of the set the app holds, for the partial notice
	 * @property knownSetSize the provider's own count for the set
	 */
	data class UiState(
		val setId: String = "",
		val setName: String = "",
		val setCode: String = "",
		/**
		 * Which game this set belongs to, resolved from the set id's provider.
		 *
		 * Drives the filter sheet's wording -- "Colour" for Magic, "Faction" for Altered -- via
		 * [GameVocabulary], and nothing else. Riftbound until the set is selected.
		 */
		val game: GameProfile? = null,
		val cards: List<CardPrinting> = emptyList(),
		val facets: CardFacets = CardFacets(),
		val query: CardQuery = CardQuery(),
		val supportedFilters: Set<com.bitsycore.cardbrowser.core.provider.CardFilterField> = emptySet(),
		val isLoading: Boolean = true,
		val isCompleteSet: Boolean = false,
		val cachedCardCount: Int = 0,
		val knownSetSize: Int? = null,
		val origin: DataOrigin = DataOrigin.NONE,
		val isStale: Boolean = false,
		val error: ProviderError? = null,
		val requestGeneration: Int = 0,
		val isFilterSheetOpen: Boolean = false,
		val isSearchOpen: Boolean = false,
		/** Restored when coming back from detail, so the grid returns to where it was. */
		val firstVisibleIndex: Int = 0,
		/**
		 * Which edition of the set is on screen.
		 *
		 * Seeded from the user's preference and changeable here, because the set is the natural
		 * place to change it: the detail screen could already switch language, but the only way to
		 * browse a set in another one was to change the global preference and come back.
		 */
		val language: CardLanguage? = null,
		/** Every language the source behind this set can be asked for. Empty until resolved. */
		val availableLanguages: Set<CardLanguage> = emptySet(),
		/** The language to fall back to when a switch turns out to be impossible. */
		val previousLanguage: CardLanguage? = null,
		/**
		 * The language the user would have had, when this set opened in a downloaded one instead.
		 *
		 * Set only for the fixable case -- the preferred language is not on disk and another one
		 * was deliberately downloaded. A set that simply has no edition in the preferred language
		 * leaves this null, because there is nothing to offer and saying "not downloaded" about a
		 * printing that does not exist would be a lie. See `CardRepository.OpeningLanguage`.
		 */
		val languageSubstitutedFor: CardLanguage? = null,
		/** True while a chosen language is being fetched, so the control can show it is busy. */
		val isChangingLanguage: Boolean = false,
	) {

		/**
		 * The languages worth offering, in the app's preference order.
		 *
		 * A single-language source gets no control at all: a menu with one item that is already
		 * selected is furniture.
		 */
		val languageOptions: List<CardLanguage>
			get() = if (availableLanguages.size <= 1) {
				emptyList()
			} else {
				CardLanguage.PREFERENCE_ORDER.filter { it in availableLanguages }
			}

		/** True when nothing has arrived yet and there is nothing to explain. */
		val isInitialLoad: Boolean get() = isLoading && cards.isEmpty() && error == null

		/** True when the set loaded but the filters exclude everything. */
		val isEmptyAfterFilter: Boolean
			get() = !isLoading && cards.isEmpty() && error == null && cachedCardCount > 0

		/** How many filters are on, for the badge on the filter button. */
		val activeFilterCount: Int get() = query.activeCount

		/**
		 * The sentence that keeps the screen honest, or `null` when nothing needs saying.
		 *
		 * Three cases, in order of how misleading their absence would be:
		 *
		 * 1. Filtering a set the app only partly holds. The results are real but not exhaustive,
		 *    and saying so is the difference between "there are four Fury epics" and "there are
		 *    four among the 200 cards we have".
		 * 2. Holding part of a set with no filter on. Less dangerous, still worth stating.
		 * 3. Showing a saved copy while a refresh runs, or after one failed.
		 */
		val coverageNotice: String?
			get() = when {
				!isCompleteSet && knownSetSize != null && cachedCardCount < knownSetSize ->
					if (query.isEmpty) {
						"Partial set: $cachedCardCount of $knownSetSize cards downloaded."
					} else {
						"Filtered from $cachedCardCount of $knownSetSize downloaded cards — not the whole set."
					}
				error != null && cards.isNotEmpty() -> "Showing saved cards. Refresh failed."
				origin == DataOrigin.CACHE && isStale -> "Saved copy, refreshing…"
				else -> null
			}

		/**
		 * The count under the set's name: "227 cards", or "12 of 227" while a filter narrows them.
		 *
		 * The denominator is how many cards the app is holding, never the provider's own count for
		 * the set, because those are not the same unit. Riftbound's Vendetta is 358 *records* -- one
		 * per variant -- which collapse to 227 distinct cards, so comparing the two made a fully
		 * downloaded set read "227 of 358": indistinguishable from a download that gave up two
		 * thirds of the way through. How much of the set is actually held is [coverageNotice]'s job,
		 * and it says so in words rather than leaving a ratio to be misread.
		 */
		val countLabel: String
			get() {
				val vHeld = maxOf(cachedCardCount, cards.size)
				return if (vHeld > 0 && cards.size != vHeld) {
					"${cards.size} of $vHeld"
				} else {
					"${cards.size} cards"
				}
			}

		/** True when the notice describes a failure the user can act on. */
		val noticeIsRetryable: Boolean get() = error != null && cards.isNotEmpty()
	}

	sealed interface Intent {

		/** The back arrow. Navigation goes through the container like everything else. */
		data object BackPressed : Intent

		/** A tile was tapped. */
		data class CardOpened(val card: CardPrinting) : Intent

		/** The downloads button in the bar. */
		data object DownloadsRequested : Intent

		/** The screen opened, or the user pulled to refresh. */
		data object Load : Intent

		/** The set this grid is for. Dispatched once, from the navigation argument. */
		data class SetSelected(val setId: String, val setName: String, val setCode: String) : Intent

		/** Any change to the query. Bumps the generation, superseding anything in flight. */
		data class QueryChanged(val query: CardQuery) : Intent

		/** Clears every filter but keeps the sort. */
		data object ClearFilters : Intent

		data class FilterSheetToggled(val isOpen: Boolean) : Intent

		/** The search button. Hides the field without discarding what was typed. */
		data class SearchToggled(val isOpen: Boolean) : Intent

		data class ScrollPositionChanged(val index: Int) : Intent

		/** A result arrived, tagged with the load it belongs to. */
		data class Loaded(
			val generation: Int,
			val cards: List<CardPrinting>,
			val isCompleteSet: Boolean,
			val cachedCardCount: Int,
			val knownSetSize: Int?,
			val origin: DataOrigin,
			val isStale: Boolean,
			val error: ProviderError?,
			val isFinal: Boolean,
		) : Intent

		/**
		 * The load ended, however it ended.
		 *
		 * A flow can finish without a final emission -- a fresh cached set emits once and returns --
		 * so without this [UiState.isLoading] stayed true forever. That is not merely untidy:
		 * [UiState.isEmptyAfterFilter] is gated on it, so filtering a cached set down to nothing
		 * showed neither cards nor the "no matches" message.
		 */
		data class LoadFinished(val generation: Int) : Intent

		/** The filter values present in the set, once the whole set is known. */
		data class FacetsComputed(val facets: CardFacets) : Intent

		/** What the routed provider can filter on, so the sheet offers only what works. */
		data class CapabilitiesResolved(
			val supportedFilters: Set<com.bitsycore.cardbrowser.core.provider.CardFilterField>,
			/** Which game's words the filter sheet should use. See [GameVocabulary]. */
			val game: GameProfile,
			val languages: Set<CardLanguage>,
			val language: CardLanguage?,
			/** What was wanted, when [language] is a downloaded stand-in for it. */
			val substitutedFor: CardLanguage? = null,
		) : Intent

		/**
		 * The language menu was opened, so its options are worth confirming.
		 *
		 * Confirmation is a request per candidate -- eleven for a Magic set -- so it is paid when
		 * a menu is actually looked at rather than on every set open. `CardRepository` caches the
		 * answer, so this costs once per set per TTL and nothing at all for a set whose cards are
		 * on disk.
		 */
		data object LanguageOptionsRequested : Intent

		/** The confirmed list, replacing the claimed one the menu opened with. */
		data class LanguageOptionsResolved(val languages: Set<CardLanguage>) : Intent

		/** The user picked another edition of this set. */
		data class LanguageSelected(val language: CardLanguage) : Intent

		/**
		 * The chosen language could not be shown, so the previous one is restored.
		 *
		 * Not every source can answer for every set. TCGdex keys each locale by its own set ids --
		 * the English `base1` is `PMCG1` in Japanese and absent from Korean -- so there is no
		 * Korean edition of an English Pokémon set to fetch. Leaving the user on an empty grid
		 * would be worse than not offering the switch.
		 */
		data object LanguageUnavailable : Intent
	}

	sealed interface Effect {

		/** Shown when a chosen language has nothing for this set, so the tap is not silently lost. */
		data class LanguageUnavailable(val language: CardLanguage, val reason: String) : Effect

		data object NavigateBack : Effect

		data class OpenCard(val card: CardPrinting) : Effect

		data object OpenDownloads : Effect
	}

	override fun reduce(state: UiState, intent: Intent): UiState = when (intent) {

		// Navigation changes no state. The view model turns these into effects.
		Intent.BackPressed, is Intent.CardOpened, Intent.DownloadsRequested -> state

		Intent.Load -> state.copy(
			isLoading = true,
			error = null,
			requestGeneration = state.requestGeneration + 1,
		)

		is Intent.SetSelected -> state.copy(
			setId = intent.setId,
			setName = intent.setName,
			setCode = intent.setCode,
		)

		is Intent.QueryChanged -> state.copy(
			query = intent.query,
			isLoading = true,
			error = null,
			// Every query change is a new load. Without this, a response for "Fury" could land
			// after the user cleared it and repopulate a grid they had just emptied.
			requestGeneration = state.requestGeneration + 1,
		)

		Intent.ClearFilters -> state.copy(
			query = CardQuery(sortBy = state.query.sortBy, sortDirection = state.query.sortDirection),
			isLoading = true,
			error = null,
			requestGeneration = state.requestGeneration + 1,
		)

		is Intent.FilterSheetToggled -> state.copy(isFilterSheetOpen = intent.isOpen)

		// Closing keeps the query. Losing a search because the field was dismissed would be a
		// nasty surprise, and the text stays visible either way as a removable chip.
		is Intent.SearchToggled -> state.copy(isSearchOpen = intent.isOpen)

		is Intent.ScrollPositionChanged -> state.copy(firstVisibleIndex = intent.index)

		is Intent.Loaded -> {
			// The guard. Anything from a superseded generation is discarded outright.
			if (intent.generation != state.requestGeneration) {
				state
			} else {
				state.copy(
					cards = intent.cards,
					isCompleteSet = intent.isCompleteSet,
					cachedCardCount = intent.cachedCardCount,
					knownSetSize = intent.knownSetSize ?: state.knownSetSize,
					origin = intent.origin,
					isStale = intent.isStale,
					error = intent.error,
					isLoading = !intent.isFinal,
					// The switch has landed, and it stuck: nothing to fall back to any more.
					isChangingLanguage = if (intent.isFinal) false else state.isChangingLanguage,
					previousLanguage = if (intent.isFinal) null else state.previousLanguage,
				)
			}
		}

		is Intent.LoadFinished ->
			if (intent.generation == state.requestGeneration) state.copy(isLoading = false) else state

		is Intent.FacetsComputed -> state.copy(facets = intent.facets)

		is Intent.CapabilitiesResolved -> state.copy(
			supportedFilters = intent.supportedFilters,
			game = intent.game,
			availableLanguages = intent.languages,
			// Only seeded, never overwritten: a resolve that lands after the user has already
			// chosen must not undo their choice.
			language = state.language ?: intent.language,
			// Same rule, and for the same reason: once the user has picked a language, the notice
			// about the one they were given instead is no longer about what is on screen.
			languageSubstitutedFor = if (state.language == null) intent.substitutedFor else null,
		)

		is Intent.LanguageOptionsRequested -> state

		// Narrowing only. A confirmation that arrives after the user has already picked must not
		// widen the menu back to the claim, and an empty answer is a failed probe rather than a
		// set with no languages -- see `CardProvider.confirmLanguages`.
		is Intent.LanguageOptionsResolved -> if (intent.languages.isEmpty()) {
			state
		} else {
			state.copy(availableLanguages = intent.languages)
		}

		// The cards on screen are kept while the new edition loads. Blanking the grid to a spinner
		// makes a switch that turns out to be impossible look like one that destroyed the set.
		is Intent.LanguageSelected -> if (intent.language == state.language) {
			state
		} else {
			state.copy(
				language = intent.language,
				previousLanguage = state.language,
				isChangingLanguage = true,
				isLoading = true,
				error = null,
				requestGeneration = state.requestGeneration + 1,
				// The notice offered this, and it has been taken. Whatever happens next -- the
				// fetch works, or the set has no such edition and `LanguageUnavailable` puts the
				// old one back -- "you were given a stand-in" is no longer the state of things.
				languageSubstitutedFor = null,
			)
		}

		Intent.LanguageUnavailable -> state.copy(
			language = state.previousLanguage ?: state.language,
			previousLanguage = null,
			isChangingLanguage = false,
			isLoading = false,
		)
	}

	/**
	 * Sort options offered in the UI, paired with what to show for them.
	 *
	 * A function of the game rather than a constant, because the cost axis is not called the same
	 * thing twice: it is Energy in Riftbound, Mana value in Magic, Cost in One Piece and Level in
	 * Yu-Gi-Oh. A game with no single cost number -- Pokémon -- does not get the option at all,
	 * rather than getting one that sorts every card equally.
	 */
	fun sortOptions(game: GameProfile?): List<Pair<CardSortField, String>> = buildList {
		add(CardSortField.COLLECTOR_NUMBER to "Collector number")
		add(CardSortField.NAME to "Name")
		add(CardSortField.RARITY to "Rarity")
		game?.vocabulary?.cost?.let { add(CardSortField.COST to it) }
	}

	/** Human wording for a treatment chip. */
	fun treatmentLabel(treatment: ArtworkTreatment): String = treatment.displayName

	/** Flips a value in or out of a set, which is what every filter chip does. */
	fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
}
