package com.bitsycore.cardbrowser.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.bitsycore.cardbrowser.ui.cards.CardGridScreen
import com.bitsycore.cardbrowser.ui.common.InstallImageLoader
import com.bitsycore.cardbrowser.ui.common.LocalSharedTransitionScope
import com.bitsycore.cardbrowser.ui.detail.CardDetailScreen
import com.bitsycore.cardbrowser.ui.sets.SetListScreen
import com.bitsycore.cardbrowser.ui.settings.SettingsScreen
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

	@Serializable
	data object Sets : Route

	@Serializable
	data class Cards(val setId: String, val setName: String, val setCode: String) : Route

	@Serializable
	data class Detail(val cardId: String, val setId: String?) : Route

	@Serializable
	data object Settings : Route
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
	CardBrowserTheme {
		// Before anything composes an image, so the first grid already uses the shared Ktor client
		// and the bounded disk cache rather than Coil's defaults.
		InstallImageLoader()

		val vBackStack = remember { mutableStateListOf<Route>(Route.Sets) }

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
			entryProvider = { vRoute ->
				when (vRoute) {

					is Route.Sets -> NavEntry(vRoute) {
						SetListScreen(
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
						)
					}

					is Route.Cards -> NavEntry(vRoute) {
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
