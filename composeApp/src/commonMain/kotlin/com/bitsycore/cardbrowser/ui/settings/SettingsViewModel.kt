package com.bitsycore.cardbrowser.ui.settings

import androidx.lifecycle.viewModelScope
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

			SettingsContract.Intent.BackPressed ->
				emitEffect(SettingsContract.Effect.NavigateBack)

			SettingsContract.Intent.Refresh -> Unit

			// Just go there. Nothing is written: the choices stay in force until the flow writes
			// new ones, so backing out of a re-run leaves everything as it was.
			//
			// This used to clear `hasCompletedSetup` and emit `NavigateBack`, and rely on a
			// `LaunchedEffect` elsewhere to notice the flag and push the flow. Two asynchronous
			// mutations of one back stack, in no particular order -- and when the pop won, it
			// removed the entry the flag had just pushed. Worse, the flag was *already* false by
			// then, so tapping again wrote the same value, the `StateFlow` did not emit, and the
			// flow could not be opened again at all until the app was restarted.
			SettingsContract.Intent.RerunSetup -> emitEffect(SettingsContract.Effect.OpenSetup)

			is SettingsContract.Intent.ImageCacheLimitChosen -> {
				mPreferences.update { it.copy(imageCacheLimitBytes = intent.bytes) }
			}

			is SettingsContract.Intent.MetadataCacheLimitChosen -> {
				mPreferences.update { it.copy(metadataCacheLimitBytes = intent.bytes) }
				// The new ceiling applies to the next write, so evict down to it now rather than
				// leaving the cache above a limit the user has just lowered.
				mCacheManager.trimMetadata()
			}

			is SettingsContract.Intent.HideEmptySetsChanged -> {
				mPreferences.update { it.copy(hideEmptySets = intent.hide) }
			}

			is SettingsContract.Intent.PrefetchRadiusChosen -> {
				mPreferences.update { it.copy(prefetchRadius = intent.radius) }
			}

			is SettingsContract.Intent.RevalidateOnLaunchChanged -> {
				mPreferences.update { it.copy(revalidateSetsOnLaunch = intent.isEnabled) }
			}

			is SettingsContract.Intent.ThemeModeChosen -> {
				mPreferences.update { it.copy(themeMode = intent.mode) }
			}

			is SettingsContract.Intent.PromoteLanguage -> {
				// The reducer has already reordered the list; this persists what it produced rather
				// than recomputing the order in two places.
				mPreferences.update { it.copy(preferredLanguages = stateFlow.value.preferredLanguages) }
			}

			else -> Unit
		}
	}

}
