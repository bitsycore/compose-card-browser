package com.bitsycore.cardbrowser.ui.sets

import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.data.repository.DataOrigin
import com.bitsycore.lib.pulse.container.ContainerContract

/**
 * The set list's state, the things that can happen to it, and the pure transitions between them.
 *
 * The reducer lives here and is total and synchronous: given a state and an intent it returns the
 * next state, with no I/O and no coroutine. Everything asynchronous is in [SetListViewModel].
 */
object SetListContract :
	ContainerContract<SetListContract.UiState, SetListContract.Intent, SetListContract.Effect>() {

	/**
	 * @property requestGeneration which load the state reflects. Bumped on every refresh so a slow
	 *   response from an earlier one can be recognised and dropped
	 * @property lastOpenedSetId remembered across launches, so the list can mark where you were
	 */
	data class UiState(
		val sets: List<CardSet> = emptyList(),
		/**
		 * The game being browsed.
		 *
		 * Riftbound is the initial value rather than "whatever is first" because it is the game
		 * this app was built for; the stored preference replaces it as soon as it loads.
		 */
		val game: Game = Game.RIFTBOUND,
		/**
		 * The games the app can actually serve, from the routing table.
		 *
		 * Never [Game.entries]. A game named in the enum but not routed to a registered adapter is
		 * not a game this app offers, and putting it in the switcher would produce a menu item that
		 * leads to an empty screen. Cyberpunk TCG is exactly that case today.
		 */
		val availableGames: List<Game> = listOf(Game.RIFTBOUND),
		val search: String = "",
		val isLoading: Boolean = true,
		val origin: DataOrigin = DataOrigin.NONE,
		val isStale: Boolean = false,
		val error: ProviderError? = null,
		val requestGeneration: Int = 0,
		val lastOpenedSetId: String? = null,
		/**
		 * Which sets are already on disk, by qualified id.
		 *
		 * "Saved", not "complete" -- an interrupted fetch leaves a file too -- which is why the
		 * mark in the row says saved and promises nothing about how much of the set is there.
		 */
		val savedSetIds: Set<String> = emptySet(),
	) {

		/**
		 * The sets actually shown, filtered by [search] over name and code.
		 *
		 * Computed rather than stored so it cannot drift out of step with [sets], and cheap enough
		 * to do per recomposition for a list of eight.
		 */
		val visibleSets: List<CardSet>
			get() {
				val vNeedle = search.trim()
				if (vNeedle.isEmpty()) return sets
				return sets.filter { vSet ->
					vSet.name.contains(vNeedle, ignoreCase = true) ||
						vSet.code.contains(vNeedle, ignoreCase = true)
				}
			}

		/** True when there is nothing to draw and no reason yet to explain why. */
		val isInitialLoad: Boolean get() = isLoading && sets.isEmpty() && error == null

		/** True when a search matched nothing but sets did load. */
		val isEmptySearch: Boolean get() = sets.isNotEmpty() && visibleSets.isEmpty()
	}

	sealed interface Intent {

		/** Start, or start again. Bumps the generation, which invalidates anything in flight. */
		data object Refresh : Intent

		/** The search box changed. */
		data class SearchChanged(val text: String) : Intent

		/**
		 * A result arrived.
		 *
		 * [generation] is the load it belongs to. The reducer drops it if a newer load has since
		 * started, which is what stops a slow first response overwriting a fast retry.
		 */
		data class Loaded(
			val generation: Int,
			val sets: List<CardSet>,
			val origin: DataOrigin,
			val isStale: Boolean,
			val error: ProviderError?,
			val isFinal: Boolean,
		) : Intent

		/**
		 * The load ended, however it ended.
		 *
		 * Needed because a flow can finish without a final emission: a fresh cache emits once and
		 * returns, so nothing else was ever going to clear [UiState.isLoading].
		 */
		data class LoadFinished(val generation: Int) : Intent

		/** Preferences finished loading and told us where the user was. */
		data class LastOpenedSetRestored(val setId: String?) : Intent

		/** A set was tapped; remembered for next launch. */
		data class SetOpened(val setId: String) : Intent

		/**
		 * The user picked a different game.
		 *
		 * Clears the list rather than keeping the old one visible under a new title: the sets of
		 * one game are not a stale view of another game's, they are simply the wrong data, and
		 * leaving them on screen for the length of a load would show Pokémon sets under "Magic".
		 */
		data class GameChanged(val game: Game) : Intent

		/** The routing table, and the remembered game, arrived from the registry and preferences. */
		data class GamesRestored(val games: List<Game>, val game: Game) : Intent

		/** Which sets are on disk. Computed after a load, since it depends on the set list. */
		data class SavedSetsResolved(val setIds: Set<String>) : Intent
	}

	sealed interface Effect

	override fun reduce(state: UiState, intent: Intent): UiState = when (intent) {

		Intent.Refresh -> state.copy(
			isLoading = true,
			error = null,
			requestGeneration = state.requestGeneration + 1,
		)

		is Intent.SearchChanged -> state.copy(search = intent.text)

		is Intent.Loaded -> {
			// The stale-response guard. A response from a superseded load is discarded entirely
			// rather than merged, because merging it would mean showing data the user's most recent
			// action asked us to stop showing.
			if (intent.generation != state.requestGeneration) {
				state
			} else {
				state.copy(
					// An error with no data does not blank a list that is already on screen: a
					// failed refresh must not cost the user what they could already see.
					sets = intent.sets.ifEmpty { if (intent.error != null) state.sets else emptyList() },
					origin = intent.origin,
					isStale = intent.isStale,
					error = intent.error,
					isLoading = !intent.isFinal,
				)
			}
		}

		is Intent.LoadFinished ->
			if (intent.generation == state.requestGeneration) state.copy(isLoading = false) else state

		is Intent.LastOpenedSetRestored -> state.copy(lastOpenedSetId = intent.setId)

		is Intent.SetOpened -> state.copy(lastOpenedSetId = intent.setId)

		is Intent.GameChanged ->
			if (intent.game == state.game) {
				state
			} else {
				state.copy(
					game = intent.game,
					sets = emptyList(),
					// Cleared with the list. Leaving them would tick rows of the new game whose
					// ids happen to collide, and briefly claim the wrong sets are downloaded.
					savedSetIds = emptySet(),
					search = "",
					isLoading = true,
					error = null,
					requestGeneration = state.requestGeneration + 1,
				)
			}

		is Intent.GamesRestored -> state.copy(
			availableGames = intent.games,
			game = intent.game,
		)

		is Intent.SavedSetsResolved -> state.copy(savedSetIds = intent.setIds)
	}
}
