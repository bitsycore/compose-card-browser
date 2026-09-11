package com.bitsycore.cardbrowser.render

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
import com.bitsycore.cardbrowser.games.magic.MagicGame
import com.bitsycore.cardbrowser.games.pokemon.PokemonGame
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import com.bitsycore.cardbrowser.ui.games.GameListContent
import com.bitsycore.cardbrowser.ui.games.GameListContract
import com.bitsycore.cardbrowser.ui.theme.CardBrowserTheme
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Image

/**
 * Proves the customise-list transition actually runs, by stepping a real composition through it.
 *
 * ## Why this is a test and not another renderer
 *
 * A still frame cannot tell an animation from a jump: both ends look correct either way. This
 * repository has shipped a transition that was never applied once before, and it shipped another
 * one here -- the row was two `Card` call sites, one per mode, which reads like a parameter and is
 * actually a composition identity. Toggling the mode destroyed the subtree and rebuilt it, so every
 * animation inside restarted at its target. The symptom reported was exactly that: "it appears
 * instantly and bounces".
 *
 * `ImageComposeScene.render(nanoTime)` drives the frame clock, so the row can be measured while it
 * is still moving. Where the first game's name starts, at 0, 16, 33, 50, 83, 133, 250 and 1000 ms
 * after the toggle:
 *
 * ```
 * two Cards   256, 256, 256, 254, 251, 247, 244, 243   lands past its resting place, drifts back
 * one Card    196, 196, 199, 203, 214, 228, 241, 243   slides
 * ```
 *
 * Both halves of that second row matter, and they are the two assertions below.
 */
class EditModeTransitionTest {

	@Test
	fun `entering edit mode slides the row across instead of snapping`() {
		val vEdges = nameEdgeThroughTransition()
		val vSettled = vEdges.last()

		assertTrue(vSettled > 0, "never found the game's name, measured $vEdges")

		// It animates. Somewhere in the middle the name must be neither where it started nor where
		// it ends up -- not merely "the first frame differs", which a jump-cut also satisfies if
		// the toggle happens to land between two frames.
		assertTrue(
			vEdges.any { it in 1 until vSettled },
			"the row was never caught part way across, so it snapped rather than animated: $vEdges",
		)

		// It does not overshoot. The name travels right as the handle makes room for itself, so a
		// frame further right than the resting place is either a bouncy spring or a layout that
		// jumped and is being animated back -- which is exactly what the two-`Card` version did.
		assertTrue(
			vEdges.none { it > vSettled },
			"the row went past where it settles, so it is springing or snapping back: $vEdges",
		)
	}

	/**
	 * Where the first game's name starts, frame by frame, from the moment editing is switched on.
	 *
	 * The name rather than the drag handle, though the handle is the thing being animated. The
	 * handle shares that corner of the row with the game's mark, and a first attempt at measuring
	 * it there read the mark sliding through the same strip and called that overshoot. The name is
	 * the one thing in the row far brighter than everything around it, so finding it takes no
	 * judgement about which of two dim shapes a pixel belongs to -- and it is carried across by
	 * exactly the layout change under test.
	 */
	@OptIn(ExperimentalComposeUiApi::class)
	private fun nameEdgeThroughTransition(): List<Int> {
		var vIsEditing by mutableStateOf(false)
		val vScene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = DENSITY) {
			CardBrowserTheme(useDarkTheme = true) {
				Surface(modifier = Modifier.fillMaxSize()) {
					Box(Modifier.fillMaxSize()) {
						GameListContent(
							state = GameListContract.UiState(
								allGames = listOf(RiftboundGame, PokemonGame, MagicGame),
								isLoading = false,
								isEditing = vIsEditing,
							),
							dispatch = {},
						)
					}
				}
			}
		}
		return try {
			// Settle the browsing state first, so what follows is the toggle and nothing else.
			vScene.render(0L)
			vIsEditing = true
			FRAME_OFFSETS_MILLIS.map { vMillis ->
				// The image this frame produced. A bare `render()` to fetch it afterwards would
				// advance the clock and measure a frame nobody asked for.
				nameLeftEdge(vScene.render(vMillis * 1_000_000L))
			}
		} finally {
			vScene.close()
		}
	}

	/** The first column of the first row carrying text, or -1 if none does. */
	private fun nameLeftEdge(image: Image): Int {
		val vBytes = image.encodeToData()?.bytes ?: error("could not encode the frame")
		val vFrame = ImageIO.read(ByteArrayInputStream(vBytes))
		for (vX in SEARCH_LEFT until SEARCH_RIGHT) {
			val vLit = (ROW_TOP until ROW_BOTTOM).any { vY ->
				val vRgb = vFrame.getRGB(vX, vY)
				((vRgb shr 16 and 0xFF) + (vRgb shr 8 and 0xFF) + (vRgb and 0xFF)) / 3 > TEXT_LUMA
			}
			if (vLit) return vX
		}
		return -1
	}

	private companion object {

		const val WIDTH = 700
		const val HEIGHT = 400
		val DENSITY = Density(1.65f)

		/**
		 * When to look, in milliseconds after the toggle.
		 *
		 * Close together early, because most of a `StiffnessMediumLow` spring's travel is over
		 * quickly, and one far enough out that whatever it reads is the resting place.
		 */
		val FRAME_OFFSETS_MILLIS = listOf(0L, 16L, 33L, 50L, 83L, 133L, 250L, 1000L)

		// Where to look for the name, in pixels at DENSITY. Starts right of the game's mark so the
		// mark cannot be taken for it, and stops well short of the trailing icon.
		const val SEARCH_LEFT = 190
		const val SEARCH_RIGHT = 400

		// The first row's vertical band, clear of the ones below it.
		const val ROW_TOP = 150
		const val ROW_BOTTOM = 210

		/**
		 * Bright enough to be text, 0-255.
		 *
		 * The name is drawn in `onSurface`, near white on the dark theme. The mark's tile and the
		 * drag handle are both well below this, which is the whole point of the number.
		 */
		const val TEXT_LUMA = 190
	}
}
