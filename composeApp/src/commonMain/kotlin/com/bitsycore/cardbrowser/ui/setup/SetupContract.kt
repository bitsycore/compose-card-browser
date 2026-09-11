package com.bitsycore.cardbrowser.ui.setup

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.lib.pulse.container.ContainerContract

/**
 * The first-launch setup: which games, which language, and what this app is.
 *
 * ## Why there is one at all
 *
 * Ten games is a lot to meet at once, and nine of them are noise to someone who plays one. The
 * game picker can already hide and reorder games, but a new user has no reason to look for that,
 * and the first thing they see is a list mostly about other people's hobbies.
 *
 * ## What it deliberately does not ask
 *
 * Cache ceilings, prefetching, theme. Those are real settings and they live in Settings -- but
 * they are the settings *least* meaningful before the app has been used once, and a setup flow
 * that asks how many megabytes of card data you want is asking a question nobody can answer yet.
 * The last page points at Settings instead.
 *
 * ## Skipping is a choice, not a deferral
 *
 * Skip takes the defaults -- every game shown, the app's own language preference order -- and
 * records the flow as done. Asking again next launch would be nagging someone who has already
 * said no. Settings can re-run it, which is the honest way back.
 */
object SetupContract : ContainerContract<SetupContract.UiState, SetupContract.Intent, SetupContract.Effect>() {

	/**
	 * @property games every game this build serves, in registry order
	 * @property selected which games will be *shown*. Starts as whatever is not hidden right now,
	 *   which on a first launch is all of them -- a setup that started by hiding everything would
	 *   make its own Next button destructive
	 * @property languages the languages the app knows, in its own preference order
	 */
	data class UiState(
		val page: SetupPage = SetupPage.GAMES,
		val games: List<GameProfile> = emptyList(),
		val selected: Set<String> = emptySet(),
		val languages: List<CardLanguage> = CardLanguage.PREFERENCE_ORDER,
		val language: CardLanguage = CardLanguage.ENGLISH,
		val isSaving: Boolean = false,
	) {

		/**
		 * Whether the current page can be left.
		 *
		 * Only the game page can block, and only on the one state that would leave the app with
		 * nothing to show. Everything else is a preference with a working default.
		 */
		val canAdvance: Boolean
			get() = page != SetupPage.GAMES || selected.isNotEmpty()

		val isLastPage: Boolean get() = page == SetupPage.ABOUT
	}

	/** The three pages, in order. */
	enum class SetupPage { GAMES, LANGUAGE, ABOUT }

	sealed interface Intent {

		/**
		 * The registry answered, and the preference said which of them are currently hidden.
		 *
		 * @property hiddenIds what is hidden *now*. Empty on a first launch, which is why the
		 *   effect below is "everything ticked" without that being stated twice
		 */
		data class GamesLoaded(
			val games: List<GameProfile>,
			val hiddenIds: Set<String> = emptySet(),
		) : Intent

		data class GameToggled(val gameId: String) : Intent

		/** Both exist because "none" and "all" are each one tap from a ten-item list. */
		data object AllGamesSelected : Intent

		data object NoGamesSelected : Intent

		data class LanguagePicked(val language: CardLanguage) : Intent

		data object Advanced : Intent

		data object WentBack : Intent

		/** Finish, writing the choices. */
		data object Finished : Intent

		/** Leave without choosing, taking the defaults. */
		data object Skipped : Intent
	}

	sealed interface Effect {

		/** Setup is over, however it ended. The host replaces it rather than stacking on it. */
		data object Done : Effect
	}

	override fun reduce(state: UiState, intent: Intent): UiState = when (intent) {

		is Intent.GamesLoaded -> state.copy(
			games = intent.games,
			// What is on screen right now, which on a first launch is everything -- nothing is
			// hidden yet, so this starts where the app would have been without this screen at all.
			//
			// Re-running the flow from Settings is the case that needs saying. Ticking everything
			// regardless would show a hidden game as shown, and then *unhide* it on Finish, so the
			// flow would quietly undo a choice the user made on the game list. A game added by a
			// later build has no entry in `hiddenIds` and so appears ticked, which is the right
			// default for something the user has never been asked about.
			selected = intent.games
				.map { it.id.value }
				.filterNotTo(mutableSetOf()) { it in intent.hiddenIds },
		)

		is Intent.GameToggled -> state.copy(
			selected = if (intent.gameId in state.selected) {
				state.selected - intent.gameId
			} else {
				state.selected + intent.gameId
			},
		)

		Intent.AllGamesSelected -> state.copy(
			selected = state.games.mapTo(mutableSetOf()) { it.id.value },
		)

		Intent.NoGamesSelected -> state.copy(selected = emptySet())

		is Intent.LanguagePicked -> state.copy(language = intent.language)

		Intent.Advanced -> if (!state.canAdvance) {
			state
		} else {
			state.copy(page = SetupPage.entries.getOrElse(state.page.ordinal + 1) { state.page })
		}

		Intent.WentBack -> state.copy(
			page = SetupPage.entries.getOrElse(state.page.ordinal - 1) { state.page },
		)

		// The work happens in the view model; the reducer only records that it started, so the
		// button cannot be tapped twice while preferences are being written.
		Intent.Finished, Intent.Skipped -> state.copy(isSaving = true)
	}
}
