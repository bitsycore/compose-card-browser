package com.bitsycore.cardbrowser.ui.settings

import androidx.lifecycle.viewModelScope
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.cache.CacheManager
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.lib.pulse.viewmodel.PulseViewModel
import kotlinx.coroutines.launch

/** Reads cache usage on open, and writes preference changes straight through. */
class SettingsViewModel(
	private val mCacheManager: CacheManager,
	private val mPreferences: PreferencesStore,
	private val mRegistry: ProviderRegistry,
) : PulseViewModel<SettingsContract.UiState, SettingsContract.Intent, SettingsContract.Effect>(
	initialState = SettingsContract.UiState(),
	containerContract = SettingsContract,
) {

	init {
		dispatch(SettingsContract.Intent.Refresh)
		dispatch(
			SettingsContract.Intent.PreferencesRead(mPreferences.preferences.value.preferredLanguages),
		)
		dispatch(
			SettingsContract.Intent.AttributionRead(
				mRegistry.resolve(Game.RIFTBOUND)?.capabilities?.attribution?.text,
			),
		)
	}

	override suspend fun handleIntent(intent: SettingsContract.Intent) {
		when (intent) {
			SettingsContract.Intent.Refresh -> readUsage()

			SettingsContract.Intent.ClearMetadata -> {
				mCacheManager.clearMetadata()
				readUsage()
			}

			SettingsContract.Intent.ClearImages -> {
				mCacheManager.clearImages()
				readUsage()
			}

			is SettingsContract.Intent.PromoteLanguage -> {
				// The reducer has already reordered the list; this persists what it produced rather
				// than recomputing the order in two places.
				mPreferences.update { it.copy(preferredLanguages = stateFlow.value.preferredLanguages) }
			}

			else -> Unit
		}
	}

	private fun readUsage() {
		viewModelScope.launch {
			dispatch(SettingsContract.Intent.UsageRead(mCacheManager.usage()))
		}
	}
}
