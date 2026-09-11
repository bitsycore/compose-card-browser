package com.bitsycore.cardbrowser.ui

import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.cardbrowser.ui.games.GameListViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The game picker follows `hiddenGames` after something else writes it.
 *
 * The only view-model test in the project, and it is here because the bug it pins is *only* in a
 * view model. The picker read the preference once in `init`, and its view model is scoped to a
 * back-stack entry -- so it survived the trip out to Settings, through a re-run of the first-launch
 * flow, and back, still showing the list as it had been. Un-ticking a game in that flow did nothing
 * until the app was restarted.
 *
 * A reducer test cannot see that. `CustomisationChanged` reduced correctly the whole time; nothing
 * was dispatching it.
 */
class GameListPreferenceFollowTest {

	@BeforeTest
	fun useTestDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

	@AfterTest
	fun releaseDispatcher() = Dispatchers.resetMain()

	@Test
	fun `hiding a game from somewhere else reaches the picker without a restart`() = runTest {
		val vPreferences = preferences()
		val vViewModel = GameListViewModel(
			// No providers, so no games: this is about whether the write arrives, and a registry
			// with real providers would drag an HTTP stack into a test about a preference.
			mRegistry = ProviderRegistry(providers = emptyList(), routes = emptyList()),
			mPreferences = vPreferences,
		)
		assertEquals(emptySet(), vViewModel.stateFlow.value.hiddenIds, "nothing is hidden yet")

		// What re-running the setup flow does.
		vPreferences.update { it.copy(hiddenGames = setOf("pokemon")) }

		assertEquals(setOf("pokemon"), vViewModel.stateFlow.value.hiddenIds)
	}

	private fun preferences() = PreferencesStore(
		AppStorage(FakeFileSystem(), "/cache".toPath(), "/prefs".toPath()).also { it.prepare() },
		Json { ignoreUnknownKeys = true },
		UnconfinedTestDispatcher(),
	)
}
