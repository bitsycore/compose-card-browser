package com.bitsycore.cardbrowser.ui.sets

import androidx.lifecycle.viewModelScope
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.data.repository.CardRepository
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.lib.pulse.viewmodel.PulseViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Loads the Riftbound set list and remembers which set was last opened.
 *
 * The only asynchronous work on this screen. Everything it learns re-enters the state through
 * [SetListContract.Intent.Loaded], so the state machine stays in one readable place.
 */
class SetListViewModel(
	private val mRepository: CardRepository,
	private val mPreferences: PreferencesStore,
) : PulseViewModel<SetListContract.UiState, SetListContract.Intent, SetListContract.Effect>(
	initialState = SetListContract.UiState(),
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
			dispatch(SetListContract.Intent.LastOpenedSetRestored(mPreferences.preferences.value.lastSetId))
		}
		dispatch(SetListContract.Intent.Refresh)
	}

	override suspend fun handleIntent(intent: SetListContract.Intent) {
		when (intent) {
			SetListContract.Intent.Refresh -> startLoad()
			is SetListContract.Intent.SetOpened -> {
				mPreferences.update { it.copy(lastSetId = intent.setId) }
			}
			else -> Unit
		}
	}

	/** Starts a load for the generation the reducer has just moved to. */
	private fun startLoad() {
		// Read after the reducer ran, so this is the generation this load owns.
		val vGeneration = stateFlow.value.requestGeneration
		mLoadJob?.cancel()
		mLoadJob = viewModelScope.launch {
			// collectLatest rather than collect: the repository emits cache then network, and if a
			// newer refresh supersedes this one mid-flight the collector unwinds instead of
			// finishing work nobody is waiting for.
			mRepository.setList(Game.RIFTBOUND).collectLatest { vSnapshot ->
				dispatch(
					SetListContract.Intent.Loaded(
						generation = vGeneration,
						sets = vSnapshot.value.orEmpty(),
						origin = vSnapshot.origin,
						isStale = vSnapshot.isStale,
						error = vSnapshot.error,
						// Whether *this emission* settles the screen. Whether the load is over is a
						// separate question, answered by LoadFinished below.
						isFinal = vSnapshot.origin != com.bitsycore.cardbrowser.data.repository.DataOrigin.CACHE ||
							vSnapshot.error != null,
					),
				)
			}
			dispatch(SetListContract.Intent.LoadFinished(vGeneration))
		}
	}
}
