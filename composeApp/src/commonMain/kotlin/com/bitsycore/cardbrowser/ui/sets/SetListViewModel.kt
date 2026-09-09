package com.bitsycore.cardbrowser.ui.sets

import androidx.lifecycle.viewModelScope
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.repository.CardRepository
import com.bitsycore.cardbrowser.data.repository.DataOrigin
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.lib.pulse.viewmodel.PulseViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Which game this set list is for. Passed at construction so the first frame already knows. */
data class SetListArgs(val game: GameId)

/**
 * Loads the set list for the chosen game, and remembers both the game and the set last opened.
 *
 * The only asynchronous work on this screen. Everything it learns re-enters the state through
 * [SetListContract.Intent.Loaded], so the state machine stays in one readable place.
 */
class SetListViewModel(
	private val mRepository: CardRepository,
	private val mPreferences: PreferencesStore,
	private val mRegistry: ProviderRegistry,
	private val mArgs: SetListArgs,
) : PulseViewModel<SetListContract.UiState, SetListContract.Intent, SetListContract.Effect>(
	// Seeded at construction from the route, so the first frame already names the right game
	// rather than showing "Riftbound" for a moment on the way to Pokémon.
	initialState = SetListContract.UiState(game = mRegistry.profileFor(mArgs.game)),
	containerContract = SetListContract,
) {

	/**
	 * The load in flight.
	 *
	 * Cancelled before a new one starts, so a refresh tapped twice does not leave two collectors
	 * racing to write the same state. The generation counter in the contract is the second half of
	 * the same defence and covers the response that is already past cancellation.
	 */
	private var mLoadJob: Job? = null

	init {
		viewModelScope.launch {
			mPreferences.load()
			val vPreferences = mPreferences.preferences.value

			// Straight from the registry, which orders them as the routing table lists them. There
			// is no enum to order by any more, and the routing table is now the only statement
			// anywhere of which games this build offers.
			val vGames = mRegistry.games
			// The route wins over the remembered game: the user just picked one, and honouring a
			// stale preference over an explicit choice would open the wrong game. A route naming a
			// game this build does not serve falls back to the first it does.
			val vGame = vGames.firstOrNull { it.id == mArgs.game } ?: vGames.firstOrNull() ?: return@launch

			dispatch(SetListContract.Intent.GamesRestored(games = vGames, game = vGame))
			dispatch(SetListContract.Intent.LastOpenedSetRestored(vPreferences.lastSetId))
			// Started only once the game is known, so the first request is not fired against
			// whichever game the initial state happened to name.
			dispatch(SetListContract.Intent.Refresh)
		}
	}

	override suspend fun handleIntent(intent: SetListContract.Intent) {
		when (intent) {
			SetListContract.Intent.Refresh -> startLoad()

			is SetListContract.Intent.SetOpened -> {
				mPreferences.update { it.copy(lastSetId = intent.setId) }
			}

			is SetListContract.Intent.GameChanged -> {
				mPreferences.update { it.copy(lastGame = intent.game.id.value) }
				// The reducer has already bumped the generation and cleared the list; this starts
				// the load for the game it moved to.
				startLoad()
			}

			else -> Unit
		}
	}

	/** Starts a load for the generation and game the reducer has just moved to. */
	private fun startLoad() {
		// Read after the reducer ran, so these are the generation and game this load owns.
		val vState = stateFlow.value
		val vGeneration = vState.requestGeneration
		// Nothing to load until the registry has answered. A route naming an unrouted game leaves
		// this null, and an empty set list is the honest outcome.
		val vGame = vState.game ?: return
		val vLanguage = mPreferences.preferences.value.primaryLanguage

		mLoadJob?.cancel()
		mLoadJob = viewModelScope.launch {
			// collectLatest rather than collect: the repository emits cache then network, and if a
			// newer refresh supersedes this one mid-flight the collector unwinds instead of
			// finishing work nobody is waiting for.
			mRepository.setList(vGame.id, vLanguage).collectLatest { vSnapshot ->
				dispatch(
					SetListContract.Intent.Loaded(
						generation = vGeneration,
						sets = vSnapshot.value.orEmpty(),
						origin = vSnapshot.origin,
						isStale = vSnapshot.isStale,
						error = vSnapshot.error,
						// Whether *this emission* settles the screen. Whether the load is over is a
						// separate question, answered by LoadFinished below.
						isFinal = vSnapshot.origin != DataOrigin.CACHE || vSnapshot.error != null,
					),
				)
			}
			dispatch(SetListContract.Intent.LoadFinished(vGeneration))

			// After the list settles, because it is a lookup *over* the list. Cheap -- one file
			// existence check per set -- and guarded on the generation so a superseded load cannot
			// mark the wrong game's sets.
			val vSets = stateFlow.value.sets
			if (stateFlow.value.requestGeneration == vGeneration && vSets.isNotEmpty()) {
				dispatch(
					SetListContract.Intent.SavedSetsResolved(
						mRepository.savedSetIds(vGame.id, vSets, vLanguage),
					),
				)
			}
		}
	}
}
