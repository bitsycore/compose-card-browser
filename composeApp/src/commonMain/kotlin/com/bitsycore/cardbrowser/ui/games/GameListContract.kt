package com.bitsycore.cardbrowser.ui.games

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.lib.pulse.container.ContainerContract

/**
 * The game picker's state and the pure transitions over it.
 *
 * Small, because the screen is: which games this build serves, which one was last opened, and one
 * line per game saying where its data comes from.
 */
object GameListContract :
	ContainerContract<GameListContract.UiState, GameListContract.Intent, GameListContract.Effect>() {

	/**
	 * @property games the games with a routed provider. Never `Game.entries` -- a game the app
	 *   cannot actually serve must not be offered, which is why this comes from the registry
	 * @property sources one line per game naming the provider behind it, so the attribution the
	 *   sources ask for is visible before a single request is made
	 * @property lastGame highlighted, so returning to the app lands where you left off
	 */
	data class UiState(
		val games: List<GameProfile> = emptyList(),
		val sources: Map<GameProfile, String> = emptyMap(),
		val lastGame: GameProfile? = null,
		val isLoading: Boolean = true,
	)

	sealed interface Intent {

		/** The registry and preferences answered. */
		data class Loaded(
			val games: List<GameProfile>,
			val sources: Map<GameProfile, String>,
			val lastGame: GameProfile?,
		) : Intent

		/** A game was chosen; remembered for next launch. */
		data class GameOpened(val game: GameProfile) : Intent
	}

	sealed interface Effect

	override fun reduce(state: UiState, intent: Intent): UiState = when (intent) {

		is Intent.Loaded -> state.copy(
			games = intent.games,
			sources = intent.sources,
			lastGame = intent.lastGame,
			isLoading = false,
		)

		is Intent.GameOpened -> state.copy(lastGame = intent.game)
	}
}
