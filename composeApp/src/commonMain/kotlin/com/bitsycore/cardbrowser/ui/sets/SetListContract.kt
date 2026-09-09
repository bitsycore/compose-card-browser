package com.bitsycore.cardbrowser.ui.sets

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameRegion
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.data.repository.DataOrigin
import com.bitsycore.cardbrowser.data.settings.ImageDownloadRecord
import com.bitsycore.lib.pulse.container.ContainerContract

/**
 * The set list's state, the things that can happen to it, and the pure transitions between them.
 *
 * The reducer lives here and is total and synchronous: given a state and an intent it returns the
 * next state, with no I/O and no coroutine. Everything asynchronous is in [SetListViewModel].
 */
object SetListContract :
	ContainerContract<SetListContract.UiState, SetListContract.Intent, SetListContract.Effect>() {

	/**
	 * @property requestGeneration which load the state reflects. Bumped on every refresh so a slow
	 *   response from an earlier one can be recognised and dropped
	 * @property lastOpenedSetId remembered across launches, so the list can mark where you were
	 */
	data class UiState(
		val sets: List<CardSet> = emptyList(),
		/**
		 * The game being browsed.
		 *
		 * `null` until the registry answers, rather than a hardcoded game: this module no longer names
		 * this app was built for; the stored preference replaces it as soon as it loads.
		 */
		val game: GameProfile? = null,
		/**
		 * The games the app can actually serve, from the routing table.
		 *
		 * Whatever the registry routes. A game with no routed adapter is
		 * not a game this app offers, and putting it in the switcher would produce a menu item that
		 * leads to an empty screen. Cyberpunk TCG is exactly that case today.
		 */
		/**
		 * Every game this build serves.
		 *
		 * Populated, and currently read by nothing but the render harness -- the top bar shows the
		 * game's logo rather than a row of chips. Kept for the same reason as [Intent.GameChanged].
		 */
		val availableGames: List<GameProfile> = emptyList(),
		val search: String = "",
		val isLoading: Boolean = true,
		val origin: DataOrigin = DataOrigin.NONE,
		val isStale: Boolean = false,
		val error: ProviderError? = null,
		val requestGeneration: Int = 0,
		val lastOpenedSetId: String? = null,
		/**
		 * Which sets are already on disk, by qualified id.
		 *
		 * "Saved", not "complete" -- an interrupted fetch leaves a file too -- which is why the
		 * mark in the row says saved and promises nothing about how much of the set is there.
		 */
		val savedSetIds: Set<String> = emptySet(),
		/**
		 * What an image download fetched, per set id, for the language being browsed.
		 *
		 * Absent means no download was ever recorded -- **not** that no images are cached. Art
		 * arrives by browsing too, and that is not tracked, so this under-claims rather than
		 * over-claims. See `BrowsingPreferences.imageDownloads`.
		 */
		val imageDownloads: Map<String, ImageDownloadRecord> = emptyMap(),
		/**
		 * Which product line to show, or `null` for all of them.
		 *
		 * Pokemon ships four -- see `GameProfile.regions` -- and they are different products rather
		 * than translations, so they belong in one list with a way to narrow it. A game that
		 * declares no regions never sees this.
		 */
		val region: String? = null,
	) {

		/**
		 * The regions worth offering, in the game's own order.
		 *
		 * Only those the loaded sets actually use: a game declaring a line that the routed provider
		 * turns out not to serve should not get a chip that filters to nothing.
		 */
		val regionOptions: List<GameRegion>
			get() {
				val vPresent = sets.mapNotNullTo(mutableSetOf()) { it.region }
				return if (vPresent.size <= 1) {
					emptyList()
				} else {
					game?.regions.orEmpty().filter { it.key in vPresent }
				}
			}

		/**
		 * The sets actually shown, filtered by [region] and then by [search] over name and code.
		 *
		 * Computed rather than stored so it cannot drift out of step with [sets], and cheap enough
		 * to do per recomposition for a list of eight.
		 */
		val visibleSets: List<CardSet>
			get() {
				val vInRegion = if (region == null) sets else sets.filter { it.region == region }
				val vNeedle = search.trim()
				if (vNeedle.isEmpty()) return vInRegion
				return vInRegion.filter { vSet ->
					vSet.name.contains(vNeedle, ignoreCase = true) ||
						vSet.code.contains(vNeedle, ignoreCase = true)
				}
			}

		/** True when there is nothing to draw and no reason yet to explain why. */
		val isInitialLoad: Boolean get() = isLoading && sets.isEmpty() && error == null

		/** True when a search or a region filter matched nothing but sets did load. */
		val isEmptySearch: Boolean get() = sets.isNotEmpty() && visibleSets.isEmpty()
	}

	sealed interface Intent {

		/** Start, or start again. Bumps the generation, which invalidates anything in flight. */
		data object Refresh : Intent

		/** The search box changed. */
		data class SearchChanged(val text: String) : Intent

		/**
		 * A result arrived.
		 *
		 * [generation] is the load it belongs to. The reducer drops it if a newer load has since
		 * started, which is what stops a slow first response overwriting a fast retry.
		 */
		data class Loaded(
			val generation: Int,
			val sets: List<CardSet>,
			val origin: DataOrigin,
			val isStale: Boolean,
			val error: ProviderError?,
			val isFinal: Boolean,
		) : Intent

		/**
		 * The load ended, however it ended.
		 *
		 * Needed because a flow can finish without a final emission: a fresh cache emits once and
		 * returns, so nothing else was ever going to clear [UiState.isLoading].
		 */
		data class LoadFinished(val generation: Int) : Intent

		/** Preferences finished loading and told us where the user was. */
		data class LastOpenedSetRestored(val setId: String?) : Intent

		/** A set was tapped; remembered for next launch. */
		data class SetOpened(val setId: String) : Intent

		/**
		 * The user picked a different game.
		 *
		 * Clears the list rather than keeping the old one visible under a new title: the sets of
		 * one game are not a stale view of another game's, they are simply the wrong data, and
		 * leaving them on screen for the length of a load would show Pokémon sets under "Magic".
		 */
		/**
		 * Switch game without leaving this screen.
		 *
		 * **Nothing dispatches this today.** The chip row that did was removed once the game picker
		 * became a screen of its own: two ways to change game, one of which duplicated the screen
		 * above it. The transition is kept, and tested, because it is correct and because putting a
		 * switcher back is a plausible thing to want -- but it is unreachable from the UI as it
		 * stands, and this note exists so nobody spends an afternoon working out why their taps do
		 * nothing.
		 */
		data class GameChanged(val game: GameProfile) : Intent

		/** The routing table, and the remembered game, arrived from the registry and preferences. */
		data class GamesRestored(val games: List<GameProfile>, val game: GameProfile) : Intent

		/** Which sets are on disk. Computed after a load, since it depends on the set list. */
		data class SavedSetsResolved(
			val setIds: Set<String>,
			val imageDownloads: Map<String, ImageDownloadRecord> = emptyMap(),
		) : Intent

		/** A product line was picked, or `null` to see every line again. */
		data class RegionSelected(val region: String?) : Intent
	}

	sealed interface Effect

	override fun reduce(state: UiState, intent: Intent): UiState = when (intent) {

		Intent.Refresh -> state.copy(
			isLoading = true,
			error = null,
			requestGeneration = state.requestGeneration + 1,
		)

		is Intent.SearchChanged -> state.copy(search = intent.text)

		is Intent.Loaded -> {
			// The stale-response guard. A response from a superseded load is discarded entirely
			// rather than merged, because merging it would mean showing data the user's most recent
			// action asked us to stop showing.
			if (intent.generation != state.requestGeneration) {
				state
			} else {
				state.copy(
					// An error with no data does not blank a list that is already on screen: a
					// failed refresh must not cost the user what they could already see.
					sets = intent.sets.ifEmpty { if (intent.error != null) state.sets else emptyList() },
					origin = intent.origin,
					isStale = intent.isStale,
					error = intent.error,
					isLoading = !intent.isFinal,
				)
			}
		}

		is Intent.LoadFinished ->
			if (intent.generation == state.requestGeneration) state.copy(isLoading = false) else state

		is Intent.LastOpenedSetRestored -> state.copy(lastOpenedSetId = intent.setId)

		is Intent.SetOpened -> state.copy(lastOpenedSetId = intent.setId)

		is Intent.GameChanged ->
			if (intent.game == state.game) {
				state
			} else {
				state.copy(
					game = intent.game,
					sets = emptyList(),
					// One game's lines mean nothing to another's.
					region = null,
					// Cleared with the list. Leaving them would tick rows of the new game whose
					// ids happen to collide, and briefly claim the wrong sets are downloaded.
					savedSetIds = emptySet(),
					search = "",
					isLoading = true,
					error = null,
					requestGeneration = state.requestGeneration + 1,
				)
			}

		is Intent.GamesRestored -> state.copy(
			availableGames = intent.games,
			game = intent.game,
		)

		is Intent.SavedSetsResolved -> state.copy(
			savedSetIds = intent.setIds,
			imageDownloads = intent.imageDownloads,
		)

		// Purely a view of what is already loaded: every line arrives in one request, so narrowing
		// to one of them is not a reload.
		is Intent.RegionSelected -> state.copy(region = intent.region)
	}
}
