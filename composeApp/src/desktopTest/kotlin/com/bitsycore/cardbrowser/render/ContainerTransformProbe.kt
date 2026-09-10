package com.bitsycore.cardbrowser.render

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Does a shared container actually animate, and does the screen transition around it matter?
 *
 * Written because the set-to-grid transform was reported as "not connected" twice, and both fixes
 * were reasoning rather than measurement. An animation cannot be checked from one frame, but
 * `ImageComposeScene.render` takes a timestamp -- so the clock can be advanced by hand and the
 * shape measured at each step. This reproduces the app's pattern in miniature: a small box that
 * becomes a big one, sharing bounds, inside an `AnimatedContent` whose spec is the variable.
 *
 * The measurement is the width of the coloured region on each frame. A container that is animating
 * grows through intermediate widths; one that is not jumps from small to large in a single frame.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalComposeUiApi::class)
class ContainerTransformProbe {

	@Test
	fun `a fade around the shared bounds lets the container animate`() {
		val vWidths = widthsOverTime(
			ContentTransform(
				targetContentEnter = fadeIn(tween(FRAME_STEP_MILLIS * 6)),
				initialContentExit = fadeOut(tween(FRAME_STEP_MILLIS * 6)),
				sizeTransform = null,
			),
		)

		assertTrue(
			vWidths.distinct().size > 2,
			"The container should pass through intermediate widths, got $vWidths",
		)
	}

	@Test
	fun `standing the screen transition down does not stop the container animating`() {
		// Worth keeping as a recorded negative. The set-to-grid transform was reported as
		// disconnected and the first guess was that `EnterTransition.None` left the
		// `AnimatedContent` transition with nothing to time, so the shared element had no window to
		// travel in. Measured, that is simply false: the bounds animate either way, and the screen
		// transition only decides whether a *second* animation runs alongside.
		val vWidths = widthsOverTime(
			ContentTransform(
				targetContentEnter = EnterTransition.None,
				initialContentExit = ExitTransition.None,
				sizeTransform = null,
			),
		)

		assertTrue(
			vWidths.distinct().size > 2,
			"The container should animate regardless of the screen transition, got $vWidths",
		)
	}

	@Test
	fun `something is always on screen -- a delayed fade-in leaves the container empty`() {
		// The actual bug. With the outgoing content faded out over 120 ms and the incoming one
		// delayed by 120 ms before starting, there is a stretch in the middle of a 350 ms bounds
		// animation where neither side is drawn: an empty box travelling between the two screens.
		// On the way back that reads exactly as it was described -- the grid disappears, and then
		// the row appears.
		val vSequential = coverageOverTime(
			enterDelayMillis = FADE_MILLIS,
			fadeMillis = FADE_MILLIS,
		)
		val vOverlapping = coverageOverTime(
			enterDelayMillis = 0,
			fadeMillis = FADE_MILLIS * 3,
		)

		assertTrue(
			vSequential.any { it < EMPTY_ENOUGH },
			"Expected a blank frame with the delayed fade, got $vSequential",
		)
		assertTrue(
			vOverlapping.all { it >= EMPTY_ENOUGH },
			"Overlapping fades should never leave the container empty, got $vOverlapping",
		)
	}

	/** Flips the state, then measures the coloured region's width on each of several frames. */
	private fun widthsOverTime(transform: ContentTransform): List<Int> {
		var vExpanded by mutableStateOf(false)
		val vWidths = mutableListOf<Int>()

		val vScene = ImageComposeScene(width = SCENE, height = SCENE, density = Density(1f)) {
			SharedTransitionLayout {
				AnimatedContent(targetState = vExpanded, transitionSpec = { transform }) { vBig ->
					Box(Modifier.fillMaxSize().padding(4.dp)) {
						Box(
							modifier = with(this@SharedTransitionLayout) {
								Modifier
									.sharedBounds(
										rememberSharedContentState(key = "probe"),
										animatedVisibilityScope = this@AnimatedContent,
										resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
									)
									.width(if (vBig) BIG else SMALL)
									.height(if (vBig) BIG else SMALL)
							}.background(Color.Red),
						)
					}
				}
			}
		}

		try {
			vScene.render(0L)
			vExpanded = true
			for (vFrame in 1..FRAMES) {
				vWidths += colouredWidth(vScene.render(vFrame * FRAME_STEP_MILLIS * 1_000_000L))
			}
		} finally {
			vScene.close()
		}
		return vWidths
	}

	/**
	 * How much of the scene is painted on each frame, as a share of the widest frame.
	 *
	 * Measures *anything* drawn rather than one colour, because the question is whether the
	 * container is empty part way through -- not which of the two sides is showing.
	 */
	private fun coverageOverTime(enterDelayMillis: Int, fadeMillis: Int): List<Double> {
		var vExpanded by mutableStateOf(false)
		val vCoverage = mutableListOf<Double>()
		val vTransform = ContentTransform(
			targetContentEnter = EnterTransition.None,
			initialContentExit = ExitTransition.None,
			sizeTransform = null,
		)

		val vScene = ImageComposeScene(width = SCENE, height = SCENE, density = Density(1f)) {
			SharedTransitionLayout {
				AnimatedContent(targetState = vExpanded, transitionSpec = { vTransform }) { vBig ->
					Box(Modifier.fillMaxSize().padding(4.dp)) {
						Box(
							modifier = with(this@SharedTransitionLayout) {
								Modifier
									.sharedBounds(
										rememberSharedContentState(key = "probe"),
										animatedVisibilityScope = this@AnimatedContent,
										resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
										enter = fadeIn(
											tween(fadeMillis, delayMillis = enterDelayMillis),
										),
										exit = fadeOut(tween(fadeMillis)),
									)
									.width(if (vBig) BIG else SMALL)
									.height(if (vBig) BIG else SMALL)
							}.background(if (vBig) Color.Blue else Color.Red),
						)
					}
				}
			}
		}

		try {
			vScene.render(0L)
			vExpanded = true
			for (vFrame in 1..FRAMES) {
				vCoverage += paintedShare(vScene.render(vFrame * FRAME_STEP_MILLIS * 1_000_000L))
			}
		} finally {
			vScene.close()
		}
		return vCoverage
	}

	/** The share of pixels on the probe row that are not the empty background. */
	private fun paintedShare(image: org.jetbrains.skia.Image): Double {
		val vInfo = ImageInfo.makeN32(image.width, image.height, ColorAlphaType.PREMUL)
		val vBitmap = Bitmap().apply { allocPixels(vInfo) }
		check(image.readPixels(vBitmap)) { "could not read the rendered pixels" }
		var vPainted = 0
		for (vX in 0 until image.width) {
			if ((vBitmap.getColor(vX, 8) and 0x00FFFFFF) != 0) vPainted++
		}
		return vPainted.toDouble() / image.width
	}

	/** How many pixels wide the red region is on the row through the top of the box. */
	private fun colouredWidth(image: org.jetbrains.skia.Image): Int {
		val vInfo = ImageInfo.makeN32(image.width, image.height, ColorAlphaType.PREMUL)
		val vBitmap = Bitmap().apply { allocPixels(vInfo) }
		check(image.readPixels(vBitmap)) { "could not read the rendered pixels" }
		val vRow = 8
		var vCount = 0
		for (vX in 0 until image.width) {
			val vPixel = vBitmap.getColor(vX, vRow)
			val vRed = (vPixel shr 16) and 0xFF
			val vGreen = (vPixel shr 8) and 0xFF
			if (vRed > 100 && vGreen < 100) vCount++
		}
		return vCount
	}

	private companion object {
		const val SCENE = 400
		val SMALL = 40.dp
		val BIG = 360.dp
		const val FRAMES = 6
		const val FRAME_STEP_MILLIS = 30
		const val FADE_MILLIS = 120

		/** Below this share of the row painted, the container is empty for practical purposes. */
		const val EMPTY_ENOUGH = 0.02
	}
}
