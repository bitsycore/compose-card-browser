package com.bitsycore.cardbrowser.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.LaunchedEffect
import com.bitsycore.cardbrowser.data.repository.SetCatalogueWarmer
import org.koin.compose.koinInject
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.ui.cards.CardGridScreen
import com.bitsycore.cardbrowser.ui.common.InstallImageLoader
import com.bitsycore.cardbrowser.ui.common.LocalSharedTransitionScope
import com.bitsycore.cardbrowser.ui.detail.CardDetailScreen
import com.bitsycore.cardbrowser.ui.games.GameListScreen
import com.bitsycore.cardbrowser.ui.search.SearchScreen
import com.bitsycore.cardbrowser.ui.sets.SetListScreen
import com.bitsycore.cardbrowser.ui.settings.SettingsScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.cardbrowser.ui.theme.CardBrowserTheme
import kotlinx.serialization.Serializable

// ==================
// MARK: Routes
// ==================

/**
 * Where the app can be.
 *
 * Arguments are plain strings rather than model objects: a route has to survive being saved and
 * restored, and a `CardPrinting` in a back stack entry would be a copy of data the cache already
 * owns and would go stale the moment it was refreshed.
 */
@Serializable
sealed interface Route : NavKey {

	/**
	 * The game picker, and the app's start destination.
	 *
	 * Ahead of the set list rather than replacing its game switcher: the picker is where you choose
	 * deliberately, the chips are for flicking between games once you are already browsing.
	 */
	@Serializable
	data object Games : Route

	@Serializable
	data class Sets(val game: String) : Route

	@Serializable
	data class Cards(val setId: String, val setName: String, val setCode: String) : Route

	@Serializable
	data class Detail(val cardId: String, val setId: String?) : Route

	@Serializable
	data object Settings : Route

	/**
	 * Cross-set search within one game.
	 *
	 * The game travels in the route rather than being read from preferences, so the back stack
	 * restores a search of the game it was actually opened for.
	 */
	@Serializable
	data class Search(val game: String) : Route
}

// ==================
// MARK: Root
// ==================

/**
 * The whole app, shared by every platform entry point.
 *
 * Navigation 3 rather than a hand-rolled stack, for one specific reason: its entry decorators scope
 * a `ViewModel` and a saveable state holder to a back-stack *entry*. That is what makes the card
 * grid keep its filters and its scroll position while the user is off looking at a card, and lets
 * both be released when they go back to the set list -- neither of which a plain `AnimatedContent`
 * with a shared view model store gives you.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun App() {
	// Read here rather than inside a view model, because the theme wraps every screen and so has no
	// screen of its own to belong to. `PreferencesStore` holds a `StateFlow`, so changing the mode
	// in settings recolours the app under the settings screen rather than on the next launch.
	val vPreferences = koinInject<PreferencesStore>()
	val vPrefs by vPreferences.preferences.collectAsState()

	CardBrowserTheme(useDarkTheme = vPrefs.themeMode.isDark(isSystemInDarkTheme())) {
		// Before anything composes an image, so the first grid already uses the shared Ktor client
		// and the bounded disk cache rather than Coil's defaults.
		InstallImageLoader()

		// Every game's set catalogue, fetched once in the background. Started here rather than
		// from a view model because it outlives any one screen and must not restart when the user
		// navigates. It is cache-first and its failures are silent -- see `SetCatalogueWarmer`.
		val vWarmer = koinInject<SetCatalogueWarmer>()
		LaunchedEffect(Unit) { vWarmer.start() }

		val vBackStack = remember { mutableStateListOf<Route>(Route.Games) }

		// Everything navigable is drawn inside one shared-transition scope, so a card's artwork can
		// be the same element in the grid and in detail rather than two images that cross-fade.
		SharedTransitionLayout {
			CompositionLocalProvider(LocalSharedTransitionScope provides this) {
		NavDisplay(
			backStack = vBackStack,
			onBack = { vBackStack.removeLastOrNull() },
			entryDecorators = listOf(
				rememberSaveableStateHolderNavEntryDecorator(),
				rememberViewModelStoreNavEntryDecorator(),
			),
			// Plain cross-fades, and -- the part that actually matters -- no size transform.
			//
			// Predictive back is a *separate* parameter with its own default, and on Android that
			// default is `ContentTransform(fadeIn(...), scaleOut(targetScale = 0.7f))`. The scaleOut is
			// what shrinks the whole screen into a little rectangle while a card is supposed to be
			// flying back to its tile. Overriding the push and pop specs alone leaves it in place.
			transitionSpec = fadeThrough(zIndex = 1f),
			popTransitionSpec = fadeThrough(zIndex = 0f),
			predictivePopTransitionSpec = { fadeThrough<Route>(zIndex = 0f).invoke(this) },
			entryProvider = { vRoute ->
				when (vRoute) {

					is Route.Games -> NavEntry(vRoute) {
						GameListScreen(
							onOpenGame = { vGame -> vBackStack.add(Route.Sets(vGame.id.value)) },
							onOpenSettings = { vBackStack.add(Route.Settings) },
						)
					}

					// The one lateral move in the app: picking a game goes sideways into its sets,
					// and back returns the way it came. Everything else keeps the cross-fade, and
					// that contrast is the point -- a slide that happens everywhere says nothing
					// about direction, and one that happens on a single hop reads as "in".
					is Route.Sets -> NavEntry(
						vRoute,
						metadata = NavDisplay.transitionSpec { slideForward() } +
							NavDisplay.popTransitionSpec { slideBack() } +
							NavDisplay.predictivePopTransitionSpec { slideBack() },
					) {
						SetListScreen(
							// A route naming a game this build no longer routes resolves to null in
							// the view model, which shows an error rather than crashing on a
							// restored back stack -- and rather than silently opening Riftbound,
							// which is what the old fallback did.
							game = GameId(vRoute.game),
							onBack = { vBackStack.removeLastOrNull() },
							onOpenSet = { vSet ->
								vBackStack.add(
									Route.Cards(
										setId = vSet.id.qualified,
										setName = vSet.name,
										setCode = vSet.code,
									),
								)
							},
							onOpenSettings = { vBackStack.add(Route.Settings) },
							onOpenSearch = { vGame -> vBackStack.add(Route.Search(vGame.id.value)) },
						)
					}

					is Route.Search -> NavEntry(vRoute) {
						SearchScreen(
							// A route naming a game this build no longer routes resolves to null in
							// the view model, which shows an error rather than crashing on a
							// restored back stack -- and rather than silently opening Riftbound,
							// which is what the old fallback did.
							game = GameId(vRoute.game),
							onBack = { vBackStack.removeLastOrNull() },
							onOpenCard = { vCard ->
								vBackStack.add(
									Route.Detail(
										cardId = vCard.id.qualified,
										// The card's own set, not the search. The detail screen
										// looks the set up in the cache to swipe through, and a
										// search result list is not a set.
										setId = vCard.setId.qualified,
									),
								)
							},
						)
					}

					// No screen-level transition at all: the container transform is the transition.
					//
					// A cross-fade here fights it. The grid fades in as a whole screen while the
					// shared container is separately growing out of the row, so the two read as
					// unrelated animations that happen to overlap -- the grid arrives on top of the
					// set list rather than out of one of its rows. Standing the screen transition
					// down leaves the shared bounds as the only thing moving, which is the point:
					// the row becomes the screen, and the set list simply waits underneath.
					is Route.Cards -> NavEntry(
						vRoute,
						metadata = NavDisplay.transitionSpec { heldStill(zIndex = 1f) } +
							NavDisplay.popTransitionSpec { heldStill(zIndex = 0f) } +
							NavDisplay.predictivePopTransitionSpec { heldStill(zIndex = 0f) },
					) {
						CardGridScreen(
							setId = vRoute.setId,
							setName = vRoute.setName,
							setCode = vRoute.setCode,
							onBack = { vBackStack.removeLastOrNull() },
							onOpenCard = { vCard ->
								vBackStack.add(
									Route.Detail(cardId = vCard.id.qualified, setId = vRoute.setId),
								)
							},
						)
					}

					is Route.Detail -> NavEntry(vRoute) {
						CardDetailScreen(
							cardId = vRoute.cardId,
							setId = vRoute.setId,
							onBack = { vBackStack.removeLastOrNull() },
						)
					}

					is Route.Settings -> NavEntry(vRoute) {
						SettingsScreen(onBack = { vBackStack.removeLastOrNull() })
					}
				}
			},
		)
			}
		}
	}
}

// ==================
// MARK: Motion
// ==================

/**
 * A cross-fade between screens, with the container's size left alone.
 *
 * `sizeTransform = null` is the whole point of writing this by hand. Both `fadeIn() togetherWith
 * fadeOut()` and the three-argument `ContentTransform` default to a live `SizeTransform()`, which
 * animates the container's bounds and clips its content to them -- that is the "unfolding out of the
 * top-left corner" effect, and it is not a fade at all.
 *
 * It is actively harmful here. The card artwork is a shared element flying from a grid tile to the
 * detail screen along its own path; a container simultaneously growing and clipping cuts that flight
 * in half and, under a predictive-back scrub where the two run at whatever progress the finger is
 * at, produces something that looks broken. With the size animation gone the container simply fades
 * and the shared element is the only thing moving, which is the point of having one.
 *
 * @param zIndex 1 to bring the arriving screen over the one it replaces, which is what a push looks
 *   like; 0 to leave the departing screen on top and let it dissolve to reveal what is underneath,
 *   which is what a pop looks like
 */
/**
 * Forward: the arriving screen slides in from the end, the leaving one drifts a quarter out.
 *
 * A quarter rather than the full width, which is the Material forward pattern: the outgoing screen
 * reads as being pushed aside and still present rather than as a second screen racing off. Both
 * carry a fade so neither is ever a hard edge sliding over the other.
 */
private fun AnimatedContentTransitionScope<Scene<*>>.slideForward(): ContentTransform =
	ContentTransform(
		targetContentEnter = slideIntoContainer(
			towards = AnimatedContentTransitionScope.SlideDirection.Start,
			animationSpec = tween(ENTER_MILLIS, easing = LinearOutSlowInEasing),
		) + fadeIn(tween(ENTER_MILLIS, easing = LinearOutSlowInEasing)),
		initialContentExit = slideOutOfContainer(
			towards = AnimatedContentTransitionScope.SlideDirection.Start,
			animationSpec = tween(EXIT_MILLIS, easing = FastOutLinearInEasing),
			targetOffset = { -it / OUTGOING_DRIFT_FRACTION },
		) + fadeOut(tween(EXIT_MILLIS, easing = FastOutLinearInEasing)),
		targetContentZIndex = 1f,
		sizeTransform = null,
	)

/** The same move reversed, for both the back button and a predictive back gesture. */
private fun AnimatedContentTransitionScope<Scene<*>>.slideBack(): ContentTransform =
	ContentTransform(
		targetContentEnter = slideIntoContainer(
			towards = AnimatedContentTransitionScope.SlideDirection.End,
			animationSpec = tween(ENTER_MILLIS, easing = LinearOutSlowInEasing),
			initialOffset = { -it / OUTGOING_DRIFT_FRACTION },
		) + fadeIn(tween(ENTER_MILLIS, easing = LinearOutSlowInEasing)),
		initialContentExit = slideOutOfContainer(
			towards = AnimatedContentTransitionScope.SlideDirection.End,
			animationSpec = tween(EXIT_MILLIS, easing = FastOutLinearInEasing),
		) + fadeOut(tween(EXIT_MILLIS, easing = FastOutLinearInEasing)),
		// Below the screen being uncovered, so the set list slides off *over* the picker rather
		// than the picker appearing on top of it.
		targetContentZIndex = 0f,
		sizeTransform = null,
	)

/** How far the screen being left behind drifts, as a fraction of its width. */
private const val OUTGOING_DRIFT_FRACTION = 4

/**
 * No enter and no exit, for a screen whose arrival is carried by a shared element instead.
 *
 * `ContentTransform` still decides which of the two is drawn on top, which is the part that matters
 * here: forward puts the arriving screen above, and a pop puts it below so the set list is revealed
 * rather than covered.
 */
private fun heldStill(zIndex: Float): ContentTransform =
	ContentTransform(
		targetContentEnter = EnterTransition.None,
		initialContentExit = ExitTransition.None,
		targetContentZIndex = zIndex,
		sizeTransform = null,
	)

private fun <T : Any> fadeThrough(
	zIndex: Float,
): AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform = {
	ContentTransform(
		targetContentEnter = fadeIn(tween(ENTER_MILLIS, easing = LinearOutSlowInEasing)),
		initialContentExit = fadeOut(tween(EXIT_MILLIS, easing = FastOutLinearInEasing)),
		targetContentZIndex = zIndex,
		sizeTransform = null,
	)
}

/** Slightly slower in than out, so the two overlap rather than leaving a gap of background. */
private const val ENTER_MILLIS = 280

private const val EXIT_MILLIS = 180
