package com.bitsycore.cardbrowser.ui.games

import androidx.lifecycle.viewModelScope
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.lib.pulse.viewmodel.PulseViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Supplies the game picker with the games this build actually serves.
 *
 * No network at all. Everything on this screen is known from the routing table the moment the app
 * starts, which is why it draws instantly and works offline.
 *
 * The user's order and hidden list are applied on top. Both are persisted as they change rather
 * than on leaving the screen: the reducer has already produced the new state by the time this runs,
 * so writing it immediately is what keeps the file and the screen from disagreeing if the app is
 * killed mid-edit.
 */
class GameListViewModel(
	private val mRegistry: ProviderRegistry,
	private val mPreferences: PreferencesStore,
) : PulseViewModel<GameListContract.UiState, GameListContract.Intent, GameListContract.Effect>(
	initialState = GameListContract.UiState(),
	containerContract = GameListContract,
) {

	init {
		// Straight from the registry, which orders them as the routing table lists them. There is
		// no enum to order by any more, and that is the point: the routing table is now the only
		// statement anywhere of which games this build offers. What the user does with that order
		// is a preference layered over it, never a replacement for it.
		val vGames = mRegistry.games
		val vSources = vGames.mapNotNull { vGame ->
			mRegistry.resolve(vGame)?.let { vGame to it.displayName }
		}.toMap()

		viewModelScope.launch {
			mPreferences.load()
			val vPreferences = mPreferences.preferences.value
			val vLast = vPreferences.lastGame
				?.let { vId -> vGames.firstOrNull { it.id.value == vId } }
			dispatch(
				GameListContract.Intent.Loaded(
					games = vGames,
					sources = vSources,
					lastGame = vLast,
					order = vPreferences.gameOrder,
					hiddenIds = vPreferences.hiddenGames,
				),
			)
		}

		// And then keep following it, because this screen is not the only thing that writes it.
		//
		// The bug: the first-launch flow can be re-run from Settings, and it writes `hiddenGames`.
		// This view model is scoped to its back-stack entry, so it survives the trip out to Settings
		// and back -- and having read the preference once in `init`, it went on showing the list as
		// it was before. Un-ticking a game there did nothing visible until the app was restarted,
		// which is exactly how it was reported.
		//
		// Only the two fields a preference write can change, and only when they actually change:
		// this view model writes them too, so its own writes come back round and are dropped here
		// rather than re-entering the reducer with what it just produced.
		viewModelScope.launch {
			mPreferences.preferences
				.map { it.gameOrder to it.hiddenGames }
				.distinctUntilChanged()
				// The first emission is whatever was already in memory, which `init` above has
				// either just loaded or is about to.
				.drop(1)
				.collect { (vOrder, vHidden) ->
					dispatch(GameListContract.Intent.CustomisationChanged(vOrder, vHidden))
				}
		}
	}

	override suspend fun handleIntent(intent: GameListContract.Intent) {
		when (intent) {
			is GameListContract.Intent.GameOpened -> {
				mPreferences.update { it.copy(lastGame = intent.game.id.value) }
				emitEffect(GameListContract.Effect.OpenGame(intent.game))
			}

			GameListContract.Intent.SettingsRequested ->
				emitEffect(GameListContract.Effect.OpenSettings)

			GameListContract.Intent.StorageRequested ->
				emitEffect(GameListContract.Effect.OpenStorage)

			GameListContract.Intent.DownloadsRequested ->
				emitEffect(GameListContract.Effect.OpenDownloads)

			// Read back off the reduced state rather than recomputed here. `GameOrder` has already
			// been applied by the reducer, and applying it twice is how the two would drift.
			is GameListContract.Intent.GameMovedTo,
			is GameListContract.Intent.GameVisibilityToggled,
			is GameListContract.Intent.CustomisationReset,
			-> {
				val vState = stateFlow.value
				mPreferences.update {
					it.copy(gameOrder = vState.order, hiddenGames = vState.hiddenIds)
				}
			}

			else -> Unit
		}
	}
}
