package com.bitsycore.cardbrowser.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.bitsycore.cardbrowser.render.pokemonArrangingState
import com.bitsycore.cardbrowser.ui.sets.SetListContent
import com.bitsycore.cardbrowser.ui.theme.CardBrowserTheme
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Turning arranging on slides the set rows across once, and does not spring past where they land.
 *
 * ## Why this is measured rather than looked at
 *
 * Because this row has bounced three times, each time for a different reason and each time invisible
 * in the code: an eased row inset running beside the handle's spring, a download button collapsing
 * its width at the far end of the same row, and a start padding that stepped 16dp to 4dp in a single
 * frame while the handle was still opening. Every one of them compiled, rendered a correct-looking
 * still, and read as a "boing" in the hand.
 *
 * What they have in common is a second layout change on the same corner, so that is what this
 * measures: the mark's left edge, frame by frame, through the whole transition. One animation gives
 * a monotone slide that stops where it stops. Two give an overshoot, a reversal, or both.
 */
class ArrangingMotionTest {

	@Test
	fun `the row slides once and does not overshoot`() = onSwingThread {
		val vEdges = mutableListOf<Int>()
		var vResting = 0
		var vSettled = 0

		val vScene = scene()
		try {
			vScene.settle(frames = 40)
			vResting = vScene.markLeft()

			mEditing = true
			// Every frame of the transition, not just its ends -- an overshoot is only ever visible
			// in the middle.
			repeat(60) {
				vScene.settle(frames = 1)
				vEdges += vScene.markLeft()
			}
			vScene.settle(frames = 40)
			vSettled = vScene.markLeft()
		} finally {
			vScene.close()
		}

		assertTrue(vSettled > vResting, "the handle did not push the row across: $vResting -> $vSettled")
		// The resting frame belongs to the track. The 16dp-to-4dp padding step was invisible without
		// it: an instant jump *left* on the first frame of the transition, then a slide right over
		// the rest of it, which is monotone from the second frame on and reads as a snap in the hand.
		val vTrack = listOf(vResting) + vEdges
		// Past its resting place and back is the bounce, whatever produced it. A pixel of tolerance
		// because the edge is found on a threshold and an anti-aliased corner can read either way.
		val vFurthest = vTrack.max()
		assertTrue(
			vFurthest <= vSettled + 1,
			"the row overshot to $vFurthest and came back to $vSettled",
		)
		// And it only ever went one way. A spring that wobbles without passing its target still
		// reverses, and reversing is what is felt.
		val vBacktracks = vTrack.zipWithNext().count { (vBefore, vAfter) -> vAfter < vBefore - 1 }
		assertTrue(vBacktracks == 0, "the row moved backwards on $vBacktracks frames: $vTrack")
	}

	// ==================
	// MARK: Harness
	// ==================

	/**
	 * The left edge of the first set's mark, in pixels.
	 *
	 * Found by column rather than by coordinate, so it survives the layout above it changing: the
	 * mark is the only thing on the lower half of the screen with a tall unbroken run of bright
	 * pixels. The drag handle is bright too and sits further left, but it is a pair of thin bars and
	 * has no run anywhere near [MARK_RUN].
	 */
	@OptIn(ExperimentalComposeUiApi::class)
	private fun ImageComposeScene.markLeft(): Int {
		val vPng = render(mNanos).encodeToData()?.bytes ?: error("could not encode")
		val vImage = ImageIO.read(ByteArrayInputStream(vPng))
		for (vX in 0 until vImage.width) {
			var vRun = 0
			for (vY in vImage.height / 2 until vImage.height) {
				val vRgb = vImage.getRGB(vX, vY)
				val vLuminance = ((vRgb shr 16 and 0xFF) * 299 +
					(vRgb shr 8 and 0xFF) * 587 +
					(vRgb and 0xFF) * 114) / 1000
				vRun = if (vLuminance > BRIGHT) vRun + 1 else 0
				if (vRun >= MARK_RUN) return vX
			}
		}
		error("no set mark found on screen")
	}

	@OptIn(ExperimentalComposeUiApi::class)
	private fun scene(): ImageComposeScene {
		mEditing = false
		mNanos = 0L
		return ImageComposeScene(width = 660, height = 700, density = Density(1.65f)) {
			CardBrowserTheme(useDarkTheme = true) {
				Surface(modifier = Modifier.fillMaxSize()) {
					Box(Modifier.fillMaxSize()) {
						SetListContent(pokemonArrangingState(isEditing = mEditing), {})
					}
				}
			}
		}
	}

	@OptIn(ExperimentalComposeUiApi::class)
	private fun ImageComposeScene.settle(frames: Int) {
		repeat(frames) {
			mNanos += 16_000_000L
			render(mNanos)
		}
	}

	/** Read from the composition, so writing it recomposes the screen into the other mode. */
	private var mEditing by mutableStateOf(false)

	private var mNanos = 0L

	private fun onSwingThread(block: () -> Unit) {
		var vFailure: Throwable? = null
		SwingUtilities.invokeAndWait { runCatching(block).onFailure { vFailure = it } }
		vFailure?.let { throw it }
	}

	private companion object {

		/** Above the card and the row text, below the set marks, which are solid blocks of colour. */
		const val BRIGHT = 140

		/** Taller than the drag handle's bars and shorter than a mark. */
		const val MARK_RUN = 60
	}
}
