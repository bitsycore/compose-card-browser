package com.bitsycore.cardbrowser.ui.cards

import com.bitsycore.cardbrowser.core.filter.CardFacets
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.provider.CardSortField
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.core.provider.SortDirection
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
	) {

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

		/** True when the notice describes a failure the user can act on. */
		val noticeIsRetryable: Boolean get() = error != null && cards.isNotEmpty()
	}

	sealed interface Intent {

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
		) : Intent
	}

	sealed interface Effect

	override fun reduce(state: UiState, intent: Intent): UiState = when (intent) {

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
				)
			}
		}

		is Intent.LoadFinished ->
			if (intent.generation == state.requestGeneration) state.copy(isLoading = false) else state

		is Intent.FacetsComputed -> state.copy(facets = intent.facets)

		is Intent.CapabilitiesResolved -> state.copy(
			supportedFilters = intent.supportedFilters,
			game = intent.game,
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

	/** Whether [direction] is the descending one, for the sort toggle. */
	fun isDescending(direction: SortDirection): Boolean = direction == SortDirection.DESCENDING
}
