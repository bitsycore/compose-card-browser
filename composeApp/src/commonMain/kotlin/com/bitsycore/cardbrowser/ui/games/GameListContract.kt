package com.bitsycore.cardbrowser.ui.games

import com.bitsycore.cardbrowser.core.game.GameOrder
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.lib.pulse.container.ContainerContract

/**
 * The game picker's state and the pure transitions over it.
 *
 * Small, because the screen is: which games this build serves, which one was last opened, one line
 * per game saying where its data comes from, and the user's own order and hidden list over the top.
 *
 * The ordering itself is not done here. [GameOrder] holds the rules -- what a partial order means,
 * what a stale id means, whether one more game may be hidden -- so this reducer only has to keep
 * `allGames`, `order` and `hiddenIds` and derive the two visible lists from them. Putting the rules
 * in core is what lets them be tested without a view model.
 */
object GameListContract :
	ContainerContract<GameListContract.UiState, GameListContract.Intent, GameListContract.Effect>() {

	/**
	 * @property allGames every game with a routed provider, in registry order and including hidden
	 *   ones. Never `Game.entries` -- a game the app cannot actually serve must not be offered,
	 *   which is why this comes from the registry. Kept whole so ordering stays derivable
	 * @property order the user's order, as `GameId` values. Empty means the registry's own
	 * @property hiddenIds games the user has hidden, as `GameId` values
	 * @property sources one line per game naming the provider behind it, so the attribution the
	 *   sources ask for is visible before a single request is made
	 * @property lastGame highlighted, so returning to the app lands where you left off. May name a
	 *   game the user has since hidden, in which case nothing is highlighted and that is correct
	 * @property isEditing whether the reorder and hide controls are showing. Not persisted: it is a
	 *   mode you are in, not a setting you have
	 */
	data class UiState(
		val allGames: List<GameProfile> = emptyList(),
		val order: List<String> = emptyList(),
		val hiddenIds: Set<String> = emptySet(),
		val sources: Map<GameProfile, String> = emptyMap(),
		val lastGame: GameProfile? = null,
		val isEditing: Boolean = false,
		val isLoading: Boolean = true,
	) {

		/** What the picker lists: the user's order, minus anything hidden. */
		val games: List<GameProfile> get() = GameOrder.visible(allGames, order, hiddenIds)

		/** The hidden games, shown only while editing so they can be brought back. */
		val hiddenGames: List<GameProfile> get() = GameOrder.hidden(allGames, order, hiddenIds)

		/** False when only one game is left visible; the hide control is disabled rather than failing. */
		val canHideMore: Boolean get() = GameOrder.canHide(allGames, hiddenIds)

		/** True when the user has customised anything, which is what "Reset" is offered for. */
		val isCustomised: Boolean get() = order.isNotEmpty() || hiddenIds.isNotEmpty()
	}

	sealed interface Intent {

		/** The registry and preferences answered. */
		data class Loaded(
			val games: List<GameProfile>,
			val sources: Map<GameProfile, String>,
			val lastGame: GameProfile?,
			val order: List<String>,
			val hiddenIds: Set<String>,
		) : Intent

		/** A game was chosen; remembered for next launch. */
		data class GameOpened(val game: GameProfile) : Intent

		/** The reorder and hide controls were shown or dismissed. */
		data object EditingToggled : Intent

		/**
		 * A game was dragged to [toVisibleIndex] of the visible list.
		 *
		 * Indexed against what is on screen rather than the stored order, because that is what the
		 * finger is over. Past either end it clamps rather than wrapping.
		 */
		data class GameMovedTo(val game: GameProfile, val toVisibleIndex: Int) : Intent

		/** A game was hidden or brought back. Refused for the last visible game. */
		data class GameVisibilityToggled(val game: GameProfile) : Intent

		/** Order and hidden list both cleared, back to what the routing table says. */
		data object CustomisationReset : Intent
	}

	sealed interface Effect

	override fun reduce(state: UiState, intent: Intent): UiState = when (intent) {

		is Intent.Loaded -> state.copy(
			allGames = intent.games,
			sources = intent.sources,
			lastGame = intent.lastGame,
			order = intent.order,
			hiddenIds = intent.hiddenIds,
			isLoading = false,
		)

		is Intent.GameOpened -> state.copy(lastGame = intent.game)

		is Intent.EditingToggled -> state.copy(isEditing = !state.isEditing)

		is Intent.GameMovedTo -> state.copy(
			order = GameOrder.movedTo(
				games = state.allGames,
				order = state.order,
				hiddenIds = state.hiddenIds,
				game = intent.game,
				toVisibleIndex = intent.toVisibleIndex,
			),
		)

		is Intent.GameVisibilityToggled -> state.copy(
			hiddenIds = GameOrder.withVisibilityToggled(state.allGames, state.hiddenIds, intent.game),
		)

		is Intent.CustomisationReset -> state.copy(order = emptyList(), hiddenIds = emptySet())
	}
}
