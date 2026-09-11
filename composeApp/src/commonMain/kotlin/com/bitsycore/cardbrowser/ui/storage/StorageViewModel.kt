package com.bitsycore.cardbrowser.ui.storage

import androidx.lifecycle.viewModelScope
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.cache.CacheManager
import com.bitsycore.cardbrowser.data.download.DownloadKind
import com.bitsycore.cardbrowser.data.repository.CardRepository
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.lib.pulse.viewmodel.PulseViewModel
import kotlinx.coroutines.launch

/** Reads what is on disk, and removes what the user decides to remove. */
class StorageViewModel(
	private val mCacheManager: CacheManager,
	private val mRepository: CardRepository,
	private val mPreferences: PreferencesStore,
	private val mRegistry: ProviderRegistry,
) : PulseViewModel<StorageContract.UiState, StorageContract.Intent, StorageContract.Effect>(
	initialState = StorageContract.UiState(),
	containerContract = StorageContract,
) {

	init {
		dispatch(StorageContract.Intent.Refresh)
	}

	override suspend fun handleIntent(intent: StorageContract.Intent) {
		when (intent) {
			StorageContract.Intent.Refresh -> load()

			StorageContract.Intent.DeleteConfirmed -> {
				// Read before the reducer clears it: confirming closes the dialog.
				val vTarget = stateFlow.value.pendingDelete ?: return
				viewModelScope.launch {
					val vRemoved = mRepository.deleteKept(vTarget.game)
					// The import record goes with the records it describes. Leaving it would have
					// the download dialog reporting a catalogue as already imported when none of
					// it is on the device any more.
					mPreferences.update { vPreferences ->
						vPreferences.copy(
							bulkImports = vPreferences.bulkImports - vTarget.game.value,
						)
					}
					emitEffect(StorageContract.Effect.Deleted(vTarget.displayName, vRemoved))
					dispatch(StorageContract.Intent.DeleteFinished)
					load()
				}
			}

			StorageContract.Intent.ClearBrowsingData -> viewModelScope.launch {
				// Only the reclaimable half. `clearMetadata` would take the downloads too, which
				// is the one thing this screen exists to stop happening by accident.
				mCacheManager.clearBrowsingMetadata()
				load()
			}

			StorageContract.Intent.ClearImages -> viewModelScope.launch {
				mCacheManager.clearImages()
				load()
			}

			else -> Unit
		}
	}

	private fun load() {
		viewModelScope.launch {
			val vUsage = mCacheManager.usage()
			val vImports = mPreferences.preferences.value.bulkImports
			val vNames = mRegistry.games.associate { it.id to it.displayName }
			// Which provider serves which game, so an image-download key -- which carries a
			// qualified set id and therefore a provider -- can be attributed to a game.
			val vGameOfProvider = mRegistry.games
				.mapNotNull { vGame -> mRegistry.resolve(vGame)?.id?.value?.let { it to vGame.id } }
				.toMap()
			val vImageSets = mPreferences.preferences.value.imageDownloads
				.filterValues { it.isComplete }
				.keys
				.mapNotNull { vKey ->
					// `setId|language|kind`, and the set id is `provider:local`.
					val vParts = vKey.split('|')
					if (vParts.size < 3) return@mapNotNull null
					val vGame = vGameOfProvider[vParts[0].substringBefore(':')] ?: return@mapNotNull null
					Triple(vGame, vParts[2], vParts[0])
				}
				.groupBy { it.first }

			val vKept = mRepository.keptByGame().map { vStorage ->
				val vImages = vImageSets[vStorage.game].orEmpty()
				StorageContract.KeptGame(
					game = vStorage.game,
					displayName = vNames[vStorage.game] ?: vStorage.game.value,
					sets = vStorage.sets,
					bytes = vStorage.bytes,
					knownSets = vStorage.knownSets,
					// Distinct sets, not records: one set downloaded in two languages is one set
					// with pictures, and the images are the same file either way.
					thumbnailSets = vImages
						.filter { it.second == DownloadKind.GRID_THUMBNAILS.name }
						.map { it.third }
						.distinct()
						.size,
					importedVariant = vImports[vStorage.game.value],
				)
			}
			dispatch(StorageContract.Intent.Loaded(vUsage, vKept))
		}
	}
}
