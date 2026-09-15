package com.bitsycore.tcgexplorer.ui

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
import com.bitsycore.tcgexplorer.render.pokemonArrangingState
import com.bitsycore.tcgexplorer.ui.screen.sets.SetListContent
import com.bitsycore.tcgexplorer.ui.theme.TcgExplorerTheme
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
 * measures: the name's left edge, frame by frame, through the whole transition. One animation gives
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
			vResting = vScene.nameLeft()

			mEditing = true
			// Every frame of the transition, not just its ends -- an overshoot is only ever visible
			// in the middle.
			repeat(60) {
				vScene.settle(frames = 1)
				vEdges += vScene.nameLeft()
			}
			vScene.settle(frames = 40)
			vSettled = vScene.nameLeft()
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
	 * The left edge of the first set's name, in pixels.
	 *
	 * Found by column rather than by coordinate, so it survives the layout above it changing.
	 *
	 * ## Why the name, and why brightness
	 *
	 * This used to find the set's monogram by looking for a tall run of *coloured* pixels, which
	 * worked because the monogram was the only tinted block on the lower half of the screen. There
	 * is no monogram now: a row without a published symbol draws no tile at all, and the set's
	 * colour moved to a faint hatch across the whole card -- which is coloured, is the width of the
	 * row, and does not move, so colour has stopped being able to find anything.
	 *
	 * Brightness can. In the dark theme the name is `onSurface` and nothing else on the card comes
	 * near it: the drag handle and the metadata line are `onSurfaceVariant`, about 30 levels
	 * darker, the hatch is a 7% tint, and the tab's label is near-black on its own colour. A
	 * threshold between the two greys finds the name and nothing to the left of it.
	 *
	 * The column returned is a pixel or two inside the true edge, because a glyph's first column is
	 * anti-aliased. That is constant frame to frame, and this test measures movement.
	 */
	@OptIn(ExperimentalComposeUiApi::class)
	private fun ImageComposeScene.nameLeft(): Int {
		val vPng = render(mNanos).encodeToData()?.bytes ?: error("could not encode")
		val vImage = ImageIO.read(ByteArrayInputStream(vPng))
		for (vX in 0 until vImage.width) {
			var vRun = 0
			for (vY in vImage.height / 2 until vImage.height) {
				val vRgb = vImage.getRGB(vX, vY)
				val vDarkest = minOf(vRgb shr 16 and 0xFF, vRgb shr 8 and 0xFF, vRgb and 0xFF)
				vRun = if (vDarkest >= BRIGHT) vRun + 1 else 0
				if (vRun >= GLYPH_RUN) return vX
			}
		}
		error("no set name found on screen")
	}

	@OptIn(ExperimentalComposeUiApi::class)
	private fun scene(): ImageComposeScene {
		mEditing = false
		mNanos = 0L
		return ImageComposeScene(width = 660, height = 700, density = Density(1.65f)) {
			TcgExplorerTheme(useDarkTheme = true) {
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

		/**
		 * How light a pixel must be to be part of the set's name.
		 *
		 * Between the dark theme's two foregrounds: `onSurface`, which the name uses, sits above
		 * this, and `onSurfaceVariant`, which the handle and the metadata line use, sits below it.
		 */
		const val BRIGHT = 215

		/** A glyph's stem, not a stray anti-aliased pixel. */
		const val GLYPH_RUN = 3
	}
}
