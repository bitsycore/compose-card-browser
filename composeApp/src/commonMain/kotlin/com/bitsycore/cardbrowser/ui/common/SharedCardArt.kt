package com.bitsycore.cardbrowser.ui.common

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/**
 * The [SharedTransitionScope] the whole app is drawn inside, or `null` outside one.
 *
 * A composition local rather than a parameter threaded through every screen: the scope belongs to
 * the navigation host, and the only things that care are two image call sites four levels down.
 * Nullable so a `@Preview` or a test renders normally instead of crashing.
 */
val LocalSharedTransitionScope: ProvidableCompositionLocal<SharedTransitionScope?> =
	compositionLocalOf { null }

/**
 * Marks a card's artwork as the same element on both sides of a navigation.
 *
 * Applied to the tile in the grid and to the large image in detail with the same key, so opening a
 * card grows it out of the tile that was tapped and going back drops it home again, instead of the
 * two screens cross-fading over each other.
 *
 * The key is the printing's source-qualified id, which is unique across the whole app -- two
 * printings of the same card are separate elements, as they should be, because they are separate
 * pictures.
 *
 * Silently does nothing when either scope is missing. That is the honest fallback: no shared
 * transition is a plain cross-fade, which is what the app did before and is nobody's emergency.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedCardArt(cardId: String): Modifier {
	val vSharedScope = LocalSharedTransitionScope.current ?: return this
	// Provided by NavDisplay to every entry's content. Read only from inside one -- which both call
	// sites are -- so the local always has a value here.
	val vAnimatedScope = LocalNavAnimatedContentScope.current

	return with(vSharedScope) {
		this@sharedCardArt.sharedElement(
			sharedContentState = rememberSharedContentState(key = "card-art:$cardId"),
			animatedVisibilityScope = vAnimatedScope,
			boundsTransform = CARD_BOUNDS_TRANSFORM,
		)
	}
}

/**
 * How the artwork travels between the grid tile and the detail screen.
 *
 * A spring rather than a duration, because this has to survive being scrubbed. Predictive back
 * drives the transition from a finger rather than a clock, and can reverse it half way; a tween has
 * to be restarted from wherever it was and visibly stutters, while a spring simply changes target
 * and keeps its velocity.
 *
 * No bounce -- a playing card is a physical object of known size, not a notification.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private val CARD_BOUNDS_TRANSFORM = BoundsTransform { _, _ ->
	spring(
		dampingRatio = Spring.DampingRatioNoBouncy,
		stiffness = Spring.StiffnessMediumLow,
		visibilityThreshold = Rect.VisibilityThreshold,
	)
}

/**
 * Marks a set's row and the card grid it opens as the same container.
 *
 * The Material container transform: tapping a set grows that row into the whole grid screen, and
 * going back shrinks it home. Applied to the row in the set list and to the grid screen's root with
 * the same key, which is the set's source-qualified id.
 *
 * ## Why `ScaleToBounds` and not `RemeasureToBounds`
 *
 * The two ends are wildly different shapes -- a 72 dp row against a full screen -- and remeasuring
 * would lay the entire grid out afresh on every animation frame at every intermediate size, which
 * for a 350-card set means re-measuring a lazy grid a hundred times during a 300 ms transition.
 * Scaling draws it once and transforms it, which is what the pattern is for.
 *
 * Silently does nothing when either scope is missing, exactly as [sharedCardArt] does, so previews
 * and tests render normally.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedSetContainer(setId: String): Modifier {
	val vSharedScope = LocalSharedTransitionScope.current ?: return this
	val vAnimatedScope = LocalNavAnimatedContentScope.current

	return with(vSharedScope) {
		this@sharedSetContainer.sharedBounds(
			sharedContentState = rememberSharedContentState(key = "set-container:$setId"),
			animatedVisibilityScope = vAnimatedScope,
			boundsTransform = CARD_BOUNDS_TRANSFORM,
			resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(
				contentScale = ContentScale.Crop,
				alignment = Alignment.TopStart,
			),
			// The contents cross-fade while the container travels. Without this the grid's text is
			// legible at row size for the first frames, which reads as a glitch rather than a grow.
			enter = fadeIn(tween(CONTAINER_FADE_MILLIS)),
			exit = fadeOut(tween(CONTAINER_FADE_MILLIS)),
		)
	}
}

/** Short: the fade is there to hide the size change, not to be seen in its own right. */
private const val CONTAINER_FADE_MILLIS = 120
