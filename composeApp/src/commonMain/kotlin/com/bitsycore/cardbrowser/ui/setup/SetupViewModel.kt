package com.bitsycore.cardbrowser.ui.setup

import androidx.lifecycle.viewModelScope
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.lib.pulse.viewmodel.PulseViewModel
import kotlinx.coroutines.launch

/**
 * Loads the games on offer and writes what the user chose.
 *
 * The only asynchronous work in the flow, and there is very little of it: the registry answers
 * synchronously and the rest is one preferences write.
 */
class SetupViewModel(
	private val mPreferences: PreferencesStore,
	private val mRegistry: ProviderRegistry,
) : PulseViewModel<SetupContract.UiState, SetupContract.Intent, SetupContract.Effect>(
	initialState = SetupContract.UiState(),
	containerContract = SetupContract,
) {

	init {
		viewModelScope.launch {
			mPreferences.load()
			val vPreferences = mPreferences.preferences.value
			// Both seeded from the preference rather than hardcoded, so re-running the flow from
			// Settings opens on what is currently in force instead of arguing with it. The games
			// half of that was the claim this comment made while only the language did it, and a
			// re-run therefore offered to un-hide everything the user had hidden.
			dispatch(
				SetupContract.Intent.GamesLoaded(
					games = mRegistry.games,
					hiddenIds = vPreferences.hiddenGames,
				),
			)
			dispatch(SetupContract.Intent.LanguagePicked(vPreferences.primaryLanguage))
		}
	}

	override suspend fun handleIntent(intent: SetupContract.Intent) {
		when (intent) {
			SetupContract.Intent.Finished -> finish(save = true)
			SetupContract.Intent.Skipped -> finish(save = false)
			else -> Unit
		}
	}

	/**
	 * Writes the choices, or only the flag.
	 *
	 * Skipping still writes [com.bitsycore.cardbrowser.data.settings.BrowsingPreferences.hasCompletedSetup]:
	 * declining to choose is a choice, and asking again next launch would be nagging.
	 */
	private fun finish(save: Boolean) {
		val vState = stateFlow.value
		viewModelScope.launch {
			mPreferences.update { vPreferences ->
				if (!save) {
					vPreferences.copy(hasCompletedSetup = true)
				} else {
					vPreferences.copy(
						hasCompletedSetup = true,
						// Stored as what to *hide*, because that is what the rest of the app
						// reads -- and because a game added by a later build should appear rather
						// than be invisible for having not existed when this ran.
						hiddenGames = vState.games
							.map { it.id.value }
							.filterNot { it in vState.selected }
							.toSet(),
						// Moved to the front rather than replacing the order. The rest of the
						// list is the app's fallback chain for a set this language has no cards
						// in, and dropping it would leave a Japan-only set with nowhere to go.
						preferredLanguages = listOf(vState.language) +
							CardLanguage.PREFERENCE_ORDER.filterNot { it == vState.language },
					)
				}
			}
			emitEffect(SetupContract.Effect.Done)
		}
	}
}
