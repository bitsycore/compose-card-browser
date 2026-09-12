package com.bitsycore.cardbrowser.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
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
 * Arranging leaves one control on a row, and every one of them lines up.
 *
 * The state this renders is deliberately mixed -- one set with nothing left to fetch, the rest with
 * something -- because that is what broke it. A row that could still be downloaded kept its button,
 * and a row that had been downloaded kept two marks saying so, so the stars sat at three different
 * distances from the edge depending on what each set happened to hold.
 *
 * Measured on the right-hand quarter of the screen, where the only things drawn in the theme's
 * primary colour are those controls.
 */
class ArrangingAlignmentTest {

	@Test
	fun `every star sits at the same distance from the edge`() = onSwingThread {
		val vColumns = primaryColumns(arranging = true)

		assertTrue(vColumns.isNotEmpty(), "no stars were drawn at all")
		// One band: the widest star is 20dp, so anything beyond that is a second column of them.
		val vSpread = vColumns.max() - vColumns.min()
		assertTrue(vSpread <= STAR_WIDTH_PX, "the stars span $vSpread px: ${vColumns.sorted()}")
	}

	@Test
	fun `browsing is the case that does not line up`() = onSwingThread {
		// The other half of the claim, and the reason arranging has to do something about it: while
		// browsing, a row carries whatever it holds -- a download button, the marks for what is on
		// disk -- and those legitimately push the star around.
		val vColumns = primaryColumns(arranging = false)

		val vSpread = vColumns.max() - vColumns.min()
		assertTrue(
			vSpread > STAR_WIDTH_PX,
			"browsing rows were aligned too, so this test is measuring nothing: $vSpread px",
		)
	}

	// ==================
	// MARK: Harness
	// ==================

	/** The x of every pixel in the right-hand quarter drawn in the theme's primary colour. */
	@OptIn(ExperimentalComposeUiApi::class)
	private fun primaryColumns(arranging: Boolean): List<Int> {
		val vScene = scene(arranging)
		val vPng = try {
			var vAt = 0L
			repeat(60) {
				vAt += 16_000_000L
				vScene.render(vAt)
			}
			vScene.render(vAt).encodeToData()?.bytes ?: error("could not encode")
		} finally {
			vScene.close()
		}

		val vImage = ImageIO.read(ByteArrayInputStream(vPng))
		val vColumns = mutableListOf<Int>()
		for (vX in (vImage.width * 3 / 4) until vImage.width) {
			for (vY in 0 until vImage.height) {
				val vRgb = vImage.getRGB(vX, vY)
				val vRed = vRgb shr 16 and 0xFF
				val vGreen = vRgb shr 8 and 0xFF
				val vBlue = vRgb and 0xFF
				// The primary is a light violet: blue well above green, and both well above black.
				if (vBlue > 180 && vRed in 140..220 && vBlue - vGreen > 40) {
					vColumns += vX
					break
				}
			}
		}
		return vColumns
	}

	@OptIn(ExperimentalComposeUiApi::class)
	private fun scene(arranging: Boolean): ImageComposeScene =
		ImageComposeScene(width = 660, height = 700, density = Density(1.65f)) {
			CardBrowserTheme(useDarkTheme = true) {
				Surface(modifier = Modifier.fillMaxSize()) {
					Box(Modifier.fillMaxSize()) {
						SetListContent(pokemonArrangingState(arranging), {})
					}
				}
			}
		}

	private fun onSwingThread(block: () -> Unit) {
		var vFailure: Throwable? = null
		SwingUtilities.invokeAndWait { runCatching(block).onFailure { vFailure = it } }
		vFailure?.let { throw it }
	}

	private companion object {

		/** 20dp at the density this renders at, rounded up. */
		const val STAR_WIDTH_PX = 34
	}
}
