package com.bitsycore.cardbrowser.ui.games

import androidx.lifecycle.viewModelScope
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.lib.pulse.viewmodel.PulseViewModel
import kotlinx.coroutines.launch

/**
 * Supplies the game picker with the games this build actually serves.
 *
 * No network at all. Everything on this screen is known from the routing table the moment the app
 * starts, which is why it draws instantly and works offline.
 */
class GameListViewModel(
	private val mRegistry: ProviderRegistry,
	private val mPreferences: PreferencesStore,
) : PulseViewModel<GameListContract.UiState, GameListContract.Intent, GameListContract.Effect>(
	initialState = GameListContract.UiState(),
	containerContract = GameListContract,
) {

	init {
		// Ordered as `Game.entries` declares them rather than as the routes happen to be listed, so
		// the picker's order is stable across builds and reorderings of the routing table.
		val vGames = Game.entries.filter { it in mRegistry.games }
		val vSources = vGames.mapNotNull { vGame ->
			mRegistry.resolve(vGame)?.let { vGame to it.displayName }
		}.toMap()

		viewModelScope.launch {
			mPreferences.load()
			val vLast = mPreferences.preferences.value.lastGame
				?.let { vName -> vGames.firstOrNull { it.name == vName } }
			dispatch(GameListContract.Intent.Loaded(vGames, vSources, vLast))
		}
	}

	override suspend fun handleIntent(intent: GameListContract.Intent) {
		when (intent) {
			is GameListContract.Intent.GameOpened -> {
				mPreferences.update { it.copy(lastGame = intent.game.name) }
			}
			else -> Unit
		}
	}
}
