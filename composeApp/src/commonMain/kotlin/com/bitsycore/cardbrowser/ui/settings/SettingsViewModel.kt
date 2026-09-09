package com.bitsycore.cardbrowser.ui.settings

import androidx.lifecycle.viewModelScope
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.cache.CacheManager
import com.bitsycore.cardbrowser.data.net.ApiCallStats
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.lib.pulse.viewmodel.PulseViewModel
import kotlinx.coroutines.launch

/** Reads cache usage on open, and writes preference changes straight through. */
class SettingsViewModel(
	private val mCacheManager: CacheManager,
	private val mPreferences: PreferencesStore,
	private val mRegistry: ProviderRegistry,
	private val mApiCalls: ApiCallStats,
) : PulseViewModel<SettingsContract.UiState, SettingsContract.Intent, SettingsContract.Effect>(
	initialState = SettingsContract.UiState(),
	containerContract = SettingsContract,
) {

	init {
		dispatch(SettingsContract.Intent.Refresh)
		dispatch(SettingsContract.Intent.PreferencesRead(mPreferences.preferences.value))
		dispatch(
			SettingsContract.Intent.AttributionRead(
				// Every routed source, deduplicated: one provider can serve several games, and
				// several games can share none. Taking only the first credited Riftcodex to users
				// of all seven games.
				mRegistry.games
					.mapNotNull { mRegistry.resolve(it) }
					.distinctBy { it.id }
					.mapNotNull { vProvider ->
						vProvider.capabilities.attribution?.text?.let { vText ->
							SettingsContract.ProviderCredit(vProvider.displayName, vText)
						}
					},
			),
		)
	}

	init {
		// Collected rather than read once: the counters climb while the screen is open, which is
		// exactly when someone is watching them to see whether something is re-fetching.
		viewModelScope.launch {
			mApiCalls.counts.collect { dispatch(SettingsContract.Intent.ApiCallsRead(it)) }
		}
	}

	override suspend fun handleIntent(intent: SettingsContract.Intent) {
		when (intent) {

			is SettingsContract.Intent.ResetApiCalls -> mApiCalls.reset()

			SettingsContract.Intent.Refresh -> readUsage()

			SettingsContract.Intent.ClearMetadata -> {
				mCacheManager.clearMetadata()
				readUsage()
			}

			SettingsContract.Intent.ClearImages -> {
				mCacheManager.clearImages()
				readUsage()
			}

			is SettingsContract.Intent.ImageCacheLimitChosen -> {
				mPreferences.update { it.copy(imageCacheLimitBytes = intent.bytes) }
				readUsage()
			}

			is SettingsContract.Intent.MetadataCacheLimitChosen -> {
				mPreferences.update { it.copy(metadataCacheLimitBytes = intent.bytes) }
				// The new ceiling applies to the next write, so evict down to it now rather than
				// leaving the reported usage above a limit the user has just lowered.
				mCacheManager.trimMetadata()
				readUsage()
			}

			is SettingsContract.Intent.PrefetchRadiusChosen -> {
				mPreferences.update { it.copy(prefetchRadius = intent.radius) }
			}

			is SettingsContract.Intent.RevalidateOnLaunchChanged -> {
				mPreferences.update { it.copy(revalidateSetsOnLaunch = intent.isEnabled) }
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
