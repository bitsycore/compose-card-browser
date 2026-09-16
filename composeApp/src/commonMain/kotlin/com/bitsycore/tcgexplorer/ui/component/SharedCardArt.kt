package com.bitsycore.tcgexplorer.ui.component

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
import androidx.compose.ui.graphics.graphicsLayer
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
 * Fades an ornament that sits on top of a shared container without being part of it.
 *
 * The set row's tab is the case this exists for. It is drawn over its card and cannot join the
 * transform: the other half of that transform is a whole screen, and a tab stretched across one is
 * not a tab. So while the card flies it is in the shared overlay, above everything, and the tab was
 * left behind on the page -- under the travelling card for the whole flight and back over it the
 * frame the transition ended, which is the pop that was reported.
 *
 * `renderInSharedTransitionScopeOverlay` puts the tab in that same overlay a layer above the card,
 * for as long as the transition runs. That is the z-order fault itself rather than its symptom, and
 * with it gone the fade below is a deliberate exit rather than a cover for one. The tab still does
 * not *travel*: it holds the row's position while the card leaves it. Making it fly would need a
 * `sharedElement` of its own and something on the grid screen to fly to, and the grid draws the set
 * code nowhere.
 *
 * ## Why not simply put it inside the container
 *
 * Tried, and it breaks the transform. `RemeasureToBounds` gives the *shared node* the animated
 * size: on the card, the card is measured at every intermediate size, which is the expansion. On a
 * box wrapping the card and its tab, that box takes the sizes and the card inside keeps its own
 * wrap-content height -- so the container grows and the row stays row-sized in the corner of it.
 * Measured rather than argued, in `ContainerTransformProbe`: the card's own height runs
 * `[40, 40, 83, 159, 229]` across the flight and the wrapped card's runs `[40, 40, 40, 40, 40]`.
 *
 * Fading it with the transition removes the pop. The two directions are deliberately not
 * symmetrical:
 *
 * - **Leaving**, it goes at once. An ornament still sitting where a row used to be, after the row
 *   has left, is the same artefact the other way round.
 * - **Arriving**, it waits for the transition to *end*. The tab is laid out at the row's final
 *   position from the first frame, while the card is still somewhere between the two screens -- so
 *   drawing it early puts a tab over empty space, attached to nothing.
 *
 * Arriving used to wait a fixed 200 ms, matched by eye to the bounds spring. That is a guess at
 * when the card lands, and predictive back makes the guess wrong: the gesture drives the
 * transition from a finger, so a tab timed off a clock appears part way through a slow drag and
 * again on one that is abandoned. Waiting for the transition to settle is the same intent measured
 * rather than estimated, and needs no case for the gesture at all.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.fadesWithSharedContainer(setId: String, isTransitioning: Boolean = true): Modifier {
	// No shared scope means no transform and nothing to hide from: a preview, or a screen that is
	// not one of the pair. The ornament is simply drawn.
	val vSharedScope = LocalSharedTransitionScope.current ?: return this

	// Only the row whose container is actually flying. Every row reads the same screen transition,
	// so without this opening one set faded every tab in the list, and coming back made all of them
	// wait for an animation that only one of them was in.
	//
	// Told rather than asked. The shared element's own `isMatchFound` is the obvious source and
	// cannot be used: it is only true once the *other* side has composed, which
	// `ContainerTransformProbe` measured as five frames of tab still drawn after the flight began.
	if (!isTransitioning) return this

	return with(vSharedScope) {
		this@fadesWithSharedContainer
			// A shared element of its own, so it *travels* with the container instead of sitting
			// where the row used to be. Its match is [sharedSetTabTarget] on the grid screen, which
			// exists only to say where the tab is going: the top-left corner the container's own
			// corner becomes.
			//
			// Above the card in the overlay, which is the fault this started as. Left on the page
			// the tab was under the travelling card for the whole flight and back over it the frame
			// the transition ended -- the pop. In the overlay a layer up it is never occluded, so
			// the fade is a deliberate exit rather than a cover for one.
			.sharedBounds(
				sharedContentState = rememberSharedContentState(key = "set-tab:$setId"),
				animatedVisibilityScope = LocalNavAnimatedContentScope.current,
				boundsTransform = CARD_BOUNDS_TRANSFORM,
				// Both sides are the same tab, so there is nothing to scale between them.
				resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
				// Held, then let go. The tab rides the container's corner before it fades, which is
				// what reads as attached rather than as a thing that vanished when tapped.
				exit = fadeOut(tween(ORNAMENT_FADE_OUT_MILLIS, delayMillis = ORNAMENT_HOLD_MILLIS)),
				// Coming back it is attached from the first frame, so it can return part way rather
				// than waiting for the end -- there is no longer any empty space for it to hover in.
				enter = fadeIn(tween(ORNAMENT_FADE_IN_MILLIS, delayMillis = ORNAMENT_RETURN_MILLIS)),
				zIndexInOverlay = TAB_Z_IN_OVERLAY,
			)
	}
}

/**
 * The other end of [fadesWithSharedContainer]: where the set's tab flies to.
 *
 * Drawn nowhere -- the grid screen has no code pill and is not gaining one. This exists only so the
 * tab has a match, and therefore a destination: without one a shared element stays put and the tab
 * would fade on the spot. Placed at the corner the container's own corner becomes.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedSetTabTarget(setId: String): Modifier {
	val vSharedScope = LocalSharedTransitionScope.current ?: return this
	return with(vSharedScope) {
		this@sharedSetTabTarget
			.sharedBounds(
				sharedContentState = rememberSharedContentState(key = "set-tab:$setId"),
				animatedVisibilityScope = LocalNavAnimatedContentScope.current,
				boundsTransform = CARD_BOUNDS_TRANSFORM,
				resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
				zIndexInOverlay = TAB_Z_IN_OVERLAY,
			)
			// Never seen. The match is the point of this, not the pixels.
			.graphicsLayer { alpha = 0f }
	}
}

/** Above the container, which takes the overlay's default of zero. */
private const val TAB_Z_IN_OVERLAY = 1f

private const val ORNAMENT_FADE_IN_MILLIS = 120

/** Long enough to read as a fade while the container is still visibly travelling. */
private const val ORNAMENT_FADE_OUT_MILLIS = 180

/** About half way, so it is back and attached for the second half of a scrubbed gesture. */
private const val ORNAMENT_RETURN_MILLIS = 140

/**
 * Stuck to the corner before it lets go.
 *
 * Both this and [ORNAMENT_RETURN_MILLIS] are read on the *transition's* timeline, not the wall
 * clock: `sharedBounds` times its enter and exit from the transition, which predictive back seeks
 * with the finger. So a slowly dragged back shows the tab at the same point in the gesture rather
 * than at the same number of milliseconds.
 */
private const val ORNAMENT_HOLD_MILLIS = 70

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
