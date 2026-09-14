package com.bitsycore.tcgexplorer.ui.screen.storagedetail

import androidx.lifecycle.viewModelScope
import com.bitsycore.tcgexplorer.core.model.GameId
import com.bitsycore.tcgexplorer.core.provider.ProviderRegistry
import com.bitsycore.tcgexplorer.data.repository.CardRepository
import com.bitsycore.tcgexplorer.data.settings.PreferencesStore
import com.bitsycore.lib.pulse.viewmodel.PulseViewModel
import kotlinx.coroutines.launch

/** Which game's storage is being read. */
data class StorageDetailArgs(val game: GameId)

/** Reads one game's downloads, and removes the parts the user picks. */
class StorageDetailViewModel(
	private val mRepository: CardRepository,
	private val mPreferences: PreferencesStore,
	private val mRegistry: ProviderRegistry,
	private val mArgs: StorageDetailArgs,
) : PulseViewModel<
	StorageDetailContract.UiState,
	StorageDetailContract.Intent,
	StorageDetailContract.Effect,
	>(
	initialState = StorageDetailContract.UiState(game = mArgs.game),
	containerContract = StorageDetailContract,
) {

	init {
		dispatch(StorageDetailContract.Intent.Refresh)
	}

	override suspend fun handleIntent(intent: StorageDetailContract.Intent) {
		when (intent) {
			StorageDetailContract.Intent.Refresh -> load()

			is StorageDetailContract.Intent.DeleteConfirmed -> viewModelScope.launch {
				val vRemoved = when (val vTarget = intent.target) {
					is StorageDetailContract.Target.OneSet ->
						if (mRepository.deleteKeptSet(vTarget.set)) 1 else 0

					is StorageDetailContract.Target.WholeLanguage ->
						mRepository.deleteKeptLanguage(mArgs.game, vTarget.group.code)
				}
				forgetImportIfEmptied()
				emitEffect(StorageDetailContract.Effect.Deleted(intent.target.label, vRemoved))
				dispatch(StorageDetailContract.Intent.DeleteFinished)
				load()
			}

			StorageDetailContract.Intent.BackPressed ->
				emitEffect(StorageDetailContract.Effect.NavigateBack)

			else -> Unit
		}
	}

	/**
	 * Drops the bulk-import record once the last set it described is gone.
	 *
	 * Only then. The record says a catalogue was imported, and while any of it is still on disk
	 * that remains true -- clearing it on the first partial delete would have the download dialog
	 * offer an import of data the device already has.
	 */
	private suspend fun forgetImportIfEmptied() {
		if (mRepository.keptSets(mArgs.game).isNotEmpty()) return
		mPreferences.update { vPreferences ->
			vPreferences.copy(bulkImports = vPreferences.bulkImports - mArgs.game.value)
		}
	}

	private fun load() {
		viewModelScope.launch {
			val vSets = mRepository.keptSets(mArgs.game)
			if (vSets.isEmpty()) {
				// Nothing left to show. Staying would leave an empty screen whose only content is
				// the heading of a game that no longer has anything on the device.
				emitEffect(StorageDetailContract.Effect.NavigateBackEmpty)
				return@launch
			}
			val vName = mRegistry.games.firstOrNull { it.id == mArgs.game }?.displayName
				?: mArgs.game.value
			dispatch(StorageDetailContract.Intent.Loaded(vSets, vName))
		}
	}
}
