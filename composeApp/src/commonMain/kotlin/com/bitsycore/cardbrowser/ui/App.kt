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
import com.bitsycore.cardbrowser.data.cache.CacheReconciler
import com.bitsycore.cardbrowser.data.cache.SetRecordStore
import com.bitsycore.cardbrowser.data.repository.SetCatalogueWarmer
import org.koin.compose.koinInject
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.bitsycore.cardbrowser.ui.browse.BrowseSession
import androidx.compose.runtime.snapshots.SnapshotStateList
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
import com.bitsycore.cardbrowser.ui.sets.SetListScreen
import com.bitsycore.cardbrowser.ui.settings.SettingsScreen
import com.bitsycore.cardbrowser.ui.setup.SetupScreen
import com.bitsycore.cardbrowser.ui.storage.StorageScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.cardbrowser.data.download.DownloadManager
import com.bitsycore.cardbrowser.ui.downloads.DownloadsScreen
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
	/**
	 * Cards: one set, or a whole game.
	 *
	 * The same screen for both, which is the point -- opening a set is a search already pointed at
	 * it. `setId` empty and `game` set is the other entry, from the set list's search button.
	 */
	data class Cards(
		val setId: String,
		val setName: String,
		val setCode: String,
		val game: String? = null,
	) : Route

	@Serializable
	/**
	 * One card, and the list it is being walked through.
	 *
	 * @param setId the card's own set: what a fallback load reads, and where "go to set" goes
	 * @param browseKey the list to swipe -- a search's key, or null for the set itself
	 */
	data class Detail(
		val cardId: String,
		val setId: String?,
		val browseKey: String? = null,
	) : Route

	/**
	 * The first-launch setup.
	 *
	 * Replaces the stack rather than sitting on it: once it is done there is nothing to go back
	 * to, and Back from the game list must not walk into setup again.
	 */
	@Serializable
	data object Setup : Route

	@Serializable
	data object Settings : Route

	/** What is on the device, and what can be deleted. Reached from the bar menu. */
	@Serializable
	data object Storage : Route

	/**
	 * The download queue.
	 *
	 * A destination rather than a dialog: a download of Magic runs for a long time and this is the
	 * only place that says how it is going, so it is something a user comes back to. A dialog is a
	 * thing you dismiss.
	 */
	data object Downloads : Route
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
		val vReconciler = koinInject<CacheReconciler>()
		val vSetStore = koinInject<SetRecordStore>()
		LaunchedEffect(Unit) {
			// Before the warmer, and before any screen reads a record about storage. Two things
			// make the app's claims about what is on disk stale -- a database that had to be
			// recreated, and an install that predates the store entirely -- and both are cleared
			// here rather than discovered by a user tapping an import that is already recorded as
			// done. See `CacheReconciler`.
			vReconciler.reconcile(wasStoreRecovered = vSetStore.wasRecovered)
			vWarmer.start()
		}

		// Setup on a fresh install, and only there. Re-running it from Settings is an ordinary
		// navigation now -- see `SettingsContract.Effect.OpenSetup` -- because having this effect
		// serve both meant a flag and a pop racing over the same list.
		val vBackStack = remember { mutableStateListOf<Route>(Route.Games) }

		// "Go to set", from a card opened through a search.
		//
		// The grid is put *underneath* the card and the card is then popped onto it, rather than
		// pushed over it: what the user asked for is to leave this card the way they would have
		// left it had they arrived from the set, and that is the ordinary container transform back
		// into the tile. Pushing the grid instead would slide a new screen over the card and leave
		// the transform with nothing to run between.
		//
		// The search goes with it. Back from the grid belongs to the set list -- where the card
		// would have been reached from -- which is what was asked for and is the only arrangement
		// where every screen's Back means the thing above it.
		var vGoToSet by remember { mutableStateOf<Route.Cards?>(null) }
		LaunchedEffect(vGoToSet) {
			val vRoute = vGoToSet ?: return@LaunchedEffect
			vBackStack.slideSetUnderCard(vRoute)
			// A frame, so the grid is composed and its tile exists before the pop asks the shared
			// element where to land.
			withFrameNanos { }
			vBackStack.popRoute()
			vGoToSet = null
		}
		LaunchedEffect(vPrefs.hasCompletedSetup) {
			// `!in`, not "is not on top". Pushing a second copy of a route that is already on the
			// stack gives `NavDisplay` two entries with one key, and popping one of them is then
			// a guess about which.
			if (!vPrefs.hasCompletedSetup && Route.Setup !in vBackStack) {
				vBackStack.add(Route.Setup)
			}
		}

		// Everything navigable is drawn inside one shared-transition scope, so a card's artwork can
		// be the same element in the grid and in detail rather than two images that cross-fade.
		SharedTransitionLayout {
			CompositionLocalProvider(LocalSharedTransitionScope provides this) {
		NavDisplay(
			backStack = vBackStack,
			onBack = { vBackStack.popRoute() },
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

					is Route.Setup -> NavEntry(vRoute) {
						SetupScreen(
							// Replaced, not popped: the flow writes `hasCompletedSetup`, and
							// removing it from the stack is what stops the effect above putting
							// it straight back.
							onDone = { vBackStack.popRoute(Route.Setup) },
						)
					}

					is Route.Games -> NavEntry(vRoute) {
						GameListScreen(
							onOpenGame = { vGame -> vBackStack.add(Route.Sets(vGame.id.value)) },
							onOpenSettings = { vBackStack.add(Route.Settings) },
							onOpenStorage = { vBackStack.add(Route.Storage) },
							onOpenDownloads = { vBackStack.add(Route.Downloads) },
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
							onBack = { vBackStack.popRoute() },
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
							onOpenStorage = { vBackStack.add(Route.Storage) },
							// The card grid with nothing ticked: the same screen a set opens, over
							// everything downloaded instead of over one set.
							onOpenSearch = { vGame ->
								vBackStack.add(
									Route.Cards(
										setId = "",
										setName = "Search ${vGame.shortName}",
										setCode = "",
										game = vGame.id.value,
									),
								)
							},
							onOpenDownloads = { vBackStack.add(Route.Downloads) },
						)
					}

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
							gameId = vRoute.game,
							onBack = { vBackStack.popRoute() },
							onOpenCard = { vCard ->
								vBackStack.add(
									Route.Detail(cardId = vCard.id.qualified, setId = vRoute.setId),
								)
							},
							onOpenDownloads = { vBackStack.add(Route.Downloads) },
						)
					}

					is Route.Detail -> NavEntry(vRoute) {
						CardDetailScreen(
							cardId = vRoute.cardId,
							setId = vRoute.setId,
							browseKey = vRoute.browseKey,
							onBack = { vBackStack.popRoute() },
							onOpenSet = { vSetId, vSetName, vSetCode ->
								vGoToSet = Route.Cards(vSetId, vSetName, vSetCode)
							},
						)
					}

					is Route.Settings -> NavEntry(vRoute) {
						SettingsScreen(
						onBack = { vBackStack.popRoute() },
						onOpenSetup = { vBackStack.add(Route.Setup) },
					)
					}

					is Route.Storage -> NavEntry(vRoute) {
						StorageScreen(
							onBack = { vBackStack.popRoute() },
							onOpenCacheSettings = { vBackStack.add(Route.Settings) },
						)
					}

					is Route.Downloads -> NavEntry(vRoute) {
						val vDownloads = koinInject<DownloadManager>()
						val vJobs by vDownloads.jobs.collectAsState()
						DownloadsScreen(
							jobs = vJobs,
							onBack = { vBackStack.popRoute() },
							onCancel = vDownloads::cancel,
							onCancelAll = vDownloads::cancelAll,
							onClearFinished = vDownloads::clearFinished,
						)
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

/**
 * Pops the top route, unless it is the only one left.
 *
 * `NavDisplay` throws `IllegalArgumentException: NavDisplay backstack cannot be empty` rather than
 * degrading, so an empty stack is a crash on the next recomposition -- and it was reachable. Every
 * screen's back handler called `removeLastOrNull`, which will happily take the last entry, and the
 * re-run-setup path could pop twice for one tap.
 *
 * Refusing is the right answer rather than pushing a home route back on: the root of this stack is
 * the game picker, and "back from the first screen" is the platform's business -- on Android it
 * leaves the app, on desktop it does nothing.
 */
internal fun SnapshotStateList<Route>.popRoute() {
	if (size > 1) removeAt(lastIndex)
}

/**
 * Removes [route] wherever it is, unless it is the only one left.
 *
 * By identity rather than by position, because the flow that uses it can be finished from anywhere
 * the stack happens to be -- it is pushed over the picker on a first launch and over Settings on a
 * re-run, and it must come off in both cases without assuming which.
 */
internal fun SnapshotStateList<Route>.popRoute(route: Route) {
	if (size > 1) remove(route)
}

/**
 * Puts a set's grid underneath the card on top, and drops the search that led there.
 *
 * The half of "go to set" that can be reasoned about without a composition: what is left is
 * `[… , Sets, Cards, Detail]`, so popping the card lands on the grid with the ordinary pop
 * transition, and Back from the grid is the set list rather than a search the user has finished
 * with. The pop itself is a frame later, once the grid has been composed for the shared element to
 * land in.
 *
 * Does nothing to a stack with nothing on top to slide under.
 */
internal fun SnapshotStateList<Route>.slideSetUnderCard(cards: Route.Cards) {
	if (isEmpty()) return
	// The game-wide list the card was opened from goes with it: that is the search, and Back from
	// the set's grid belongs to the set list -- where the card would have been reached from had it
	// been browsed to.
	removeAll { it is Route.Cards && it.setId.isBlank() }
	add(lastIndex, cards)
}
