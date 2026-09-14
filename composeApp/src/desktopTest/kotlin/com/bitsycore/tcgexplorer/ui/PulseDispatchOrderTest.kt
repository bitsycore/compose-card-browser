package com.bitsycore.tcgexplorer.ui

import com.bitsycore.lib.pulse.container.ContainerContract
import com.bitsycore.lib.pulse.viewmodel.PulseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull

/*
Pins when the reducer runs relative to `handleIntent`, because a screen got it backwards.

`StorageViewModel` read `pendingDelete` inside `handleIntent` with a comment claiming it ran
"before the reducer clears it". If that is false the value is always null, the delete silently
does nothing, and `isDeleting` is never lowered -- which is exactly the stuck, greyed trash icon
that was reported. This asks the framework instead of guessing.
*/
@OptIn(ExperimentalCoroutinesApi::class)
class PulseDispatchOrderTest {

	@BeforeTest
	fun setUp() {
		// A view model's scope is Dispatchers.Main.immediate, which a plain JVM test does not have.
		Dispatchers.setMain(StandardTestDispatcher())
	}

	@AfterTest
	fun tearDown() {
		Dispatchers.resetMain()
	}

	@Test
	fun `handleIntent sees the state the reducer already produced`() = runTest {
		val vModel = ProbeViewModel()
		vModel.dispatch(Probe.Intent.Clear)
		testScheduler.advanceUntilIdle()
		assertNull(
			vModel.seen,
			"handleIntent ran before the reducer -- StorageViewModel's assumption would hold",
		)
	}

	private object Probe : ContainerContract<Probe.UiState, Probe.Intent, Probe.Effect>() {

		data class UiState(val value: String? = "set")

		sealed interface Intent {

			data object Clear : Intent
		}

		sealed interface Effect

		override fun reduce(state: UiState, intent: Intent): UiState = state.copy(value = null)
	}

	private class ProbeViewModel : PulseViewModel<Probe.UiState, Probe.Intent, Probe.Effect>(
		initialState = Probe.UiState(),
		containerContract = Probe,
	) {

		/** What `handleIntent` observed. Null means the reducer had already run. */
		var seen: String? = "unread"
			private set

		override suspend fun handleIntent(intent: Probe.Intent) {
			seen = stateFlow.value.value
		}
	}
}
