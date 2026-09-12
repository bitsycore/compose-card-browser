package com.bitsycore.cardbrowser.ui.search

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.data.cache.CardSearchFilter
import com.bitsycore.cardbrowser.sqlstore.StoredFacets
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.data.repository.DataOrigin
import com.bitsycore.cardbrowser.data.repository.SearchScope
import com.bitsycore.lib.pulse.container.ContainerContract

/**
 * Cross-set search: its state, what can happen to it, and the pure transitions between them.
 *
 * The reducer is total and synchronous. Everything asynchronous is in [SearchViewModel].
 */
object SearchContract :
	ContainerContract<SearchContract.UiState, SearchContract.Intent, SearchContract.Effect>() {

	/**
	 * @property submitted the text the results on screen belong to, which is not [query] while the
	 *   user is still typing. Keeping the two apart is what lets the screen say "results for X"
	 *   truthfully rather than relabelling the previous results with the current keystrokes
	 * @property scope how much of the game was actually searched. The single most important field
	 *   here -- see [SearchScope]
	 * @property searchedSetCount / [knownSetCount] the coverage of a local search
	 * @property totalCount how many cards matched in total, when the provider says. Larger than
	 *   `results.size` whenever there are further pages
	 */
	data class UiState(
		val game: GameProfile? = null,
		val query: String = "",
		val submitted: String = "",
		val results: List<CardPrinting> = emptyList(),
		val scope: SearchScope? = null,
		val searchedSetCount: Int = 0,
		val knownSetCount: Int = 0,
		val totalCount: Int? = null,
		val hasMore: Boolean = false,
		val isProviderSearchable: Boolean = true,
		val isLoading: Boolean = false,
		val origin: DataOrigin = DataOrigin.NONE,
		val error: ProviderError? = null,
		/**
		 * The advanced filter, and what this game has to offer one.
		 *
		 * [filter] carries the text too, so a search is one value rather than a box plus a panel
		 * that can disagree with it. [facets] is what is *stored* -- an empty list means the
		 * sources served none of it, and the screen leaves that control out rather than showing an
		 * empty menu.
		 */
		val filter: CardSearchFilter = CardSearchFilter(),
		val facets: StoredFacets? = null,
		val isAdvancedOpen: Boolean = false,

		/**
		 * The set this search is confined to, by name, or null for the whole game.
		 *
		 * What it changes is what the screen may claim: a scoped search covers one set completely,
		 * so the notice about how many sets were reached has nothing to report and the title says
		 * where you are instead.
		 */
		val scopedSetName: String? = null,
		val requestGeneration: Int = 0,
	) {

		/**
		 * How many things the advanced filter narrows on, beyond the name.
		 *
		 * A number rather than a flag because it is shown: the button says how many are set, so a
		 * search that returns nothing while the panel is shut still has a visible cause. A cost
		 * range counts as one -- it is one control and one idea, whichever end is filled in.
		 */
		val activeAdvancedCount: Int
			get() = with(filter) {
				listOf(
					!excludeText.isNullOrBlank(),
					cardType != null,
					rarity != null,
					domain != null,
					minCost != null || maxCost != null,
				).count { it }
			}

		/** True when the advanced filter narrows on anything beyond the name. */
		val hasAdvancedFilters: Boolean get() = activeAdvancedCount > 0

		/** True before anything has been searched for. */
		val isIdle: Boolean get() = submitted.isBlank() && !isLoading

		/** True when a search ran and matched nothing. */
		val isEmptyResult: Boolean
			get() = submitted.isNotBlank() && !isLoading && results.isEmpty() && error == null

		/**
		 * True when the results came only from sets already downloaded, and that is less than the
		 * whole game.
		 *
		 * The screen must say so. A user who searches a game they have never browsed gets an empty
		 * list, and without this they would read it as "that card does not exist".
		 */
		val isLimitedByCache: Boolean
			get() = scope == SearchScope.LOCAL_CACHED_SETS && searchedSetCount < knownSetCount

		/** True when more matched than are shown. */
		val isTruncated: Boolean
			get() = hasMore || (totalCount != null && totalCount > results.size)

		/**
		 * What was actually searched, or `null` when the search saw the whole catalogue.
		 *
		 * On the state rather than in the screen so it can be tested, which is how a false claim
		 * got in: the sentence used to read "the N of M sets **you have downloaded** were
		 * searched", and M is [knownSetCount] -- how many sets the *game* has. For Pokémon that
		 * told the user they had downloaded 486 sets when they had downloaded two.
		 *
		 * The two numbers are both worth stating, just not as one possessive phrase: how much of
		 * the game a local search could see is exactly the caveat this strip exists for.
		 */
		val coverageNotice: String?
			get() = if (scopedSetName != null) {
				// One set, and the search reads what is stored of it: there is no "of 120 sets" to
				// report, and the title already says which set this is.
				null
			} else if (!isLimitedByCache) {
				null
			} else {
				// No branch for an unknown catalogue size, because there is no such state to
				// reach: [isLimitedByCache] requires `searchedSetCount < knownSetCount`, so a
				// `knownSetCount` of zero makes this null before it gets here. The old wording
				// carried that branch and it was dead -- a test written for it is what showed it.
				"Searched the $searchedSetCount " +
					"${if (searchedSetCount == 1) "set" else "sets"} you have downloaded, of " +
					"$knownSetCount. This source cannot search across sets."
			}
	}

	sealed interface Intent {

		/** The back arrow. Navigation goes through the container like everything else. */
		data object BackPressed : Intent

		/** A result was tapped. */
		data class CardOpened(val card: CardPrinting) : Intent

		/** This search covers one set, named here for the title. */
		data class ScopedToSet(val setName: String) : Intent

		/** The text field changed. Does not search; [Submit] does. */
		data class QueryChanged(val text: String) : Intent

		/**
		 * Run the search.
		 *
		 * Separate from [QueryChanged] on purpose. These are other people's servers and several of
		 * them ask callers to go easy; a request per keystroke against Scryfall would be rude and
		 * would mostly fetch results for prefixes nobody wanted.
		 */
		data object Submit : Intent

		/** Which game is being searched, from the route. */
		data class GameSet(val game: GameProfile, val isProviderSearchable: Boolean) : Intent

		/** Results arrived. [generation] identifies which search they belong to. */
		data class Loaded(
			val generation: Int,
			val results: List<CardPrinting>,
			val scope: SearchScope,
			val searchedSetCount: Int,
			val knownSetCount: Int,
			val totalCount: Int?,
			val hasMore: Boolean,
			val origin: DataOrigin,
			val error: ProviderError?,
		) : Intent

		/** The search ended, however it ended. A flow can finish without a final emission. */
		data class LoadFinished(val generation: Int) : Intent

		/** Start over. */
		data object Clear : Intent

		/** The advanced panel opened or closed. Closing does not clear what it holds. */
		data class AdvancedToggled(val isOpen: Boolean) : Intent

		/** Any part of the advanced filter changed. One intent, because it is one value. */
		data class FilterChanged(val filter: CardSearchFilter) : Intent

		/** What this game's stored cards contain, once the store has been asked. */
		data class FacetsLoaded(val facets: StoredFacets) : Intent
	}

	/**
	 * Navigation, emitted by the container rather than handed to the layout.
	 *
	 * The body takes a state and a dispatch and nothing else, so a result row reports "this card was
	 * tapped" and the container decides that means "go to the detail screen".
	 */
	sealed interface Effect {

		data object NavigateBack : Effect

		data class OpenCard(val card: CardPrinting) : Effect
	}

	override fun reduce(state: UiState, intent: Intent): UiState = when (intent) {

		is Intent.QueryChanged -> state.copy(query = intent.text)

		Intent.Submit ->
			if (state.query.isBlank()) {
				state
			} else {
				state.copy(
					submitted = state.query.trim(),
					// Cleared rather than kept: results for the previous term under the new term's
					// heading would be a straightforward lie about what matched.
					results = emptyList(),
					scope = null,
					totalCount = null,
					hasMore = false,
					isLoading = true,
					error = null,
					requestGeneration = state.requestGeneration + 1,
				)
			}

		is Intent.GameSet -> state.copy(
			game = intent.game,
			isProviderSearchable = intent.isProviderSearchable,
		)

		is Intent.Loaded ->
			// A response from a superseded search is discarded rather than merged.
			if (intent.generation != state.requestGeneration) {
				state
			} else {
				state.copy(
					results = intent.results,
					scope = intent.scope,
					searchedSetCount = intent.searchedSetCount,
					knownSetCount = intent.knownSetCount,
					totalCount = intent.totalCount,
					hasMore = intent.hasMore,
					origin = intent.origin,
					error = intent.error,
				)
			}

		is Intent.LoadFinished ->
			if (intent.generation == state.requestGeneration) state.copy(isLoading = false) else state

		is Intent.AdvancedToggled -> state.copy(isAdvancedOpen = intent.isOpen)

		is Intent.ScopedToSet -> state.copy(scopedSetName = intent.setName)

		is Intent.FilterChanged -> state.copy(filter = intent.filter)

		is Intent.FacetsLoaded -> state.copy(facets = intent.facets)

		Intent.Clear -> state.copy(
			query = "",
			submitted = "",
			// The advanced filter goes with it. "Clear" that left a rarity selected would produce
			// an empty result the user could not see the cause of.
			filter = CardSearchFilter(),
			results = emptyList(),
			scope = null,
			totalCount = null,
			hasMore = false,
			isLoading = false,
			error = null,
			requestGeneration = state.requestGeneration + 1,
		)

		// Navigation changes no state. The view model turns these into effects.
		Intent.BackPressed, is Intent.CardOpened -> state
	}
}
