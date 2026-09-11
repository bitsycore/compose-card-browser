package com.bitsycore.cardbrowser.ui.search

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.model.CardPrinting
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
		val requestGeneration: Int = 0,
	) {

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
			get() = if (!isLimitedByCache) {
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

		Intent.Clear -> state.copy(
			query = "",
			submitted = "",
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
