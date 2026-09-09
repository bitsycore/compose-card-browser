package com.bitsycore.cardbrowser.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.bitsycore.cardbrowser.ui.theme.CardBrowserTheme
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The theme paints a background of its own.
 *
 * Sounds too obvious to test, and was wrong for the life of the project. Every screen paints its
 * own through its `Scaffold`, which hides the gap until something does not cover the whole window:
 * the uncovered strip while a screen slides in, or the area around a container transform growing
 * out of a row. What showed through then was the *platform's* window background -- white on both
 * Android and desktop -- so the dark theme flashed white during exactly the animations that were
 * added to make it feel modern.
 *
 * This renders the theme with nothing in it and looks at the pixel. A real check rather than one of
 * the `@Ignore`d render tools, because it can fail and the failure means something.
 */
class ThemeBackgroundTest {

	@Test
	fun `the dark theme is dark behind everything -- not the window's own white`() {
		val vPixel = backgroundPixel(isDark = true)

		assertTrue(
			luminance(vPixel) < 0.2,
			"The dark theme's floor should be dark, got ${vPixel.toHex()}",
		)
	}

	@Test
	fun `the light theme is light behind everything`() {
		val vPixel = backgroundPixel(isDark = false)

		assertTrue(
			luminance(vPixel) > 0.8,
			"The light theme's floor should be light, got ${vPixel.toHex()}",
		)
	}

	@Test
	fun `the two themes do not paint the same floor`() {
		// The failure mode this really guards: a root background that is present but hardcoded, so
		// it stops following the theme the moment someone switches to Light.
		assertTrue(backgroundPixel(isDark = true) != backgroundPixel(isDark = false))
	}

	/** Renders the theme wrapping nothing at all, and reads the middle pixel. */
	@OptIn(ExperimentalComposeUiApi::class)
	private fun backgroundPixel(isDark: Boolean): Int {
		val vScene = ImageComposeScene(width = 64, height = 64, density = Density(1f)) {
			CardBrowserTheme(useDarkTheme = isDark) {
				Box(Modifier.fillMaxSize())
			}
		}
		try {
			val vImage = vScene.render()
			val vInfo = ImageInfo.makeN32(vImage.width, vImage.height, ColorAlphaType.PREMUL)
			val vBitmap = Bitmap().apply { allocPixels(vInfo) }
			check(vImage.readPixels(vBitmap)) { "could not read the rendered pixels" }
			return vBitmap.getColor(vImage.width / 2, vImage.height / 2)
		} finally {
			vScene.close()
		}
	}

	private fun luminance(argb: Int): Double {
		fun channel(shift: Int): Double {
			val vValue = ((argb shr shift) and 0xFF) / 255.0
			return if (vValue <= 0.03928) vValue / 12.92 else ((vValue + 0.055) / 1.055).pow(2.4)
		}
		return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
	}

	private fun Int.toHex(): String = "#" + (this.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
}
