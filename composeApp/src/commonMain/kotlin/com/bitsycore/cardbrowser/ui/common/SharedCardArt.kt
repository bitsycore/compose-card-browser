package com.bitsycore.cardbrowser.ui.common

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.OverlayClip
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
 * ## Why `RemeasureToBounds` and not `scaleToBounds`
 *
 * Scaling was tried first, on the reasoning that remeasuring a lazy grid at every intermediate size
 * is the expensive option. It is, and it is still the right one, because of what scaling actually
 * looks like here: each side is measured at its *own* size and then transformed to the animated
 * bounds, so the row is laid out at 72 dp tall and blown up to fill the screen. Its name and date
 * become enormous for the length of the transition. The set row visibly magnifies rather than the
 * screen growing.
 *
 * Remeasuring lays each side out at the size it currently occupies, so the type stays the size it
 * is meant to be and the container simply expands -- which is the whole point of the gesture. The
 * cost is real but smaller than it sounds: a lazy grid only composes what fits, so at the early,
 * frequent, small sizes it is measuring a handful of tiles rather than a set of 350.
 *
 * Silently does nothing when either scope is missing, exactly as [sharedCardArt] does, so previews
 * and tests render normally.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedSetContainer(setId: String, expandsFromCorner: Dp? = null): Modifier {
	val vSharedScope = LocalSharedTransitionScope.current ?: return this
	val vAnimatedScope = LocalNavAnimatedContentScope.current

	// The corner radius travels with the bounds. A row is a rounded card and a screen is not, so
	// without this the container is square from the first frame and the row's corners simply vanish
	// -- which reads as the row being replaced rather than becoming the screen. Material animates
	// the shape along with the size, and it is the shape that sells it.
	//
	// Only the growing side animates. The row keeps its own corners throughout, which is correct:
	// it is not becoming square, it is being grown *out of*.
	val vCorner = expandsFromCorner?.let { vStart ->
		vAnimatedScope.transition.animateDp(
			transitionSpec = { CONTAINER_CORNER_SPRING },
			label = "set-container-corner",
		) { vState ->
			when (vState) {
				EnterExitState.Visible -> 0.dp
				EnterExitState.PreEnter, EnterExitState.PostExit -> vStart
			}
		}
	}

	return with(vSharedScope) {
		this@sharedSetContainer.sharedBounds(
			sharedContentState = rememberSharedContentState(key = "set-container:$setId"),
			animatedVisibilityScope = vAnimatedScope,
			boundsTransform = CARD_BOUNDS_TRANSFORM,
			resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
			// The two sides cross-fade *over each other*, across the whole journey.
			//
			// They used to be short and sequential -- the outgoing faded out in 120 ms and the
			// incoming waited 120 ms before starting -- which left a stretch in the middle of a
			// ~350 ms bounds animation where neither side was drawn. An empty box travelled between
			// the two screens, and on the way back that reads exactly as it was reported: the grid
			// disappears, and then the row appears. `ContainerTransformProbe` measures both the
			// broken timing and this one.
			enter = fadeIn(tween(CONTAINER_FADE_MILLIS)),
			exit = fadeOut(tween(CONTAINER_FADE_MILLIS)),
			// Clipped in the *overlay*, which is the only place it can be done correctly here.
			//
			// A `clip` in the modifier chain applies to the child, and under `scaleToBounds` the
			// child is measured once at its full stable size and then scaled. So a 12 dp radius
			// clipped there is scaled along with everything else: by the time the container is row
			// sized it is a couple of pixels, and the corners read as square. That was the first
			// attempt, and it is why going back from the grid arrived at a rectangle.
			//
			// The overlay is where the element is actually drawn mid-flight, and its bounds are in
			// screen space at the animated size -- so a radius applied there is the radius you see.
			clipInOverlayDuringTransition = vCorner?.let { AnimatedCornerClip(it) }
				?: OverlayClip(RectangleShape),
		)
	}
}

/**
 * An overlay clip whose corner radius is read at draw time rather than baked in at composition.
 *
 * `OverlayClip(RoundedCornerShape(...))` would work too, but only by recomposing this modifier on
 * every frame of the animation to hand it a new shape. Reading the animated value inside
 * [getClipPath] keeps the whole thing to one composition and a path rebuild per frame, which is
 * what the interface's own documentation recommends.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private class AnimatedCornerClip(private val mCorner: State<Dp>) : SharedTransitionScope.OverlayClip {

	// Reused rather than allocated per frame, as the interface asks.
	private val mPath = Path()

	override fun getClipPath(
		sharedContentState: SharedTransitionScope.SharedContentState,
		bounds: Rect,
		layoutDirection: LayoutDirection,
		density: Density,
	): Path {
		val vRadius = with(density) { mCorner.value.toPx() }
		mPath.reset()
		// `bounds` already carries the element's position in the shared scope, so a round rect built
		// from it needs no further offsetting.
		mPath.addRoundRect(RoundRect(rect = bounds, cornerRadius = CornerRadius(vRadius)))
		return mPath
	}
}

/**
 * How the corner radius travels. The same spring as the bounds, so the two cannot drift apart.
 *
 * A shape lagging a frame behind its own container is more noticeable than either being slightly
 * wrong, because the mismatch shows as a sliver of the wrong colour at each corner.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private val CONTAINER_CORNER_SPRING = spring<Dp>(
	dampingRatio = Spring.DampingRatioNoBouncy,
	stiffness = Spring.StiffnessMediumLow,
)

/**
 * Long enough to span the bounds animation, so the container is never empty.
 *
 * Matched by eye to `CARD_BOUNDS_TRANSFORM`'s spring rather than derived from it -- a spring has no
 * duration to read. Erring long is the safe direction: two overlapping contents is a cross-fade,
 * and neither is a hole.
 */
private const val CONTAINER_FADE_MILLIS = 320
