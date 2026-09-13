package com.bitsycore.toploader.ui.screen.about

import com.bitsycore.lib.pulse.container.ContainerContract

/**
 * What this app is, where its data comes from, and whose the cards are.
 *
 * Three things that are not settings and were homeless. The scope statements were the setup flow's
 * third page until it was removed; the source credits were fine print at the bottom of Settings; the
 * trademark notice existed nowhere at all. None of them is a preference, and all three are read
 * together or not at all.
 *
 * Reached from Settings. Not from setup: the worst moment to read what an app does not do is before
 * having used it once.
 */
object AboutContract : ContainerContract<AboutContract.UiState, AboutContract.Intent, AboutContract.Effect>() {

	data class UiState(
		val version: String = "",
		/** One per routed source, with the games it serves. */
		val sources: List<SourceCredit> = emptyList(),
	)

	/**
	 * One data source, and what it answers for.
	 *
	 * @property games the games this source serves, by display name. Listed because "Riftcodex" means
	 *   nothing on its own and "Riftcodex — Riftbound" is the whole explanation
	 * @property text the source's own required wording, unedited
	 * @property url where to go to read more, when the source states one
	 */
	data class SourceCredit(
		val name: String,
		val games: List<String>,
		val text: String,
		val url: String? = null,
	)

	sealed interface Intent {

		data class Loaded(val version: String, val sources: List<SourceCredit>) : Intent

		data object BackPressed : Intent

		/** A source's own site. Opened outside the app; there is no browser in here. */
		data class LinkOpened(val url: String) : Intent
	}

	sealed interface Effect {

		data object NavigateBack : Effect
	}

	override fun reduce(state: UiState, intent: Intent): UiState = when (intent) {

		is Intent.Loaded -> state.copy(version = intent.version, sources = intent.sources)

		Intent.BackPressed, is Intent.LinkOpened -> state
	}
}
