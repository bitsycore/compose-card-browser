package com.bitsycore.cardbrowser.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import com.bitsycore.cardbrowser.games.altered.AlteredGame
import com.bitsycore.cardbrowser.games.cyberpunk.CyberpunkGame
import com.bitsycore.cardbrowser.games.lorcana.LorcanaGame
import com.bitsycore.cardbrowser.games.magic.MagicGame
import com.bitsycore.cardbrowser.games.onepiece.OnePieceGame
import com.bitsycore.cardbrowser.games.pokemon.PokemonGame
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import com.bitsycore.cardbrowser.games.wowtcg.WowTcgGame
import com.bitsycore.cardbrowser.games.wutheringwaves.WutheringWavesGame
import com.bitsycore.cardbrowser.games.yugioh.YuGiOhGame
import com.bitsycore.cardbrowser.ui.games.GameListContent
import com.bitsycore.cardbrowser.ui.games.GameListContract
import com.bitsycore.cardbrowser.ui.theme.CardBrowserTheme
import javax.swing.SwingUtilities
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The arrow keys move through the game list.
 *
 * ## Why this is asserted on a real composition
 *
 * Because the first attempt at this shipped and did nothing, on an assumption that was never
 * checked: that giving a lazy list's container the focus was enough, and Compose's own traversal
 * would take it from there. It is not enough -- a `LazyColumn`'s items are composed lazily, so at
 * the moment the effect asks for focus there is frequently nothing yet to give it to, and the
 * request fails silently.
 *
 * Nothing about that is visible from reading the code, and nothing about it fails a build. The only
 * way to know is to press the key and look, which is what this does.
 *
 * A frame comparison rather than a look at focus state: what a user means by "the arrows work" is
 * that the screen responds, and the list scrolling as focus walks past the fold is exactly that.
 */
class ListArrowNavigationTest {

	@Test
	fun `down walks the game list and scrolls it`() = onSwingThread {
		var vBefore = ""
		var vAfter = ""
		val vScene = scene()
		try {
			vBefore = vScene.frame()
			// Far enough to walk past the bottom of a 600px window, whatever the row height.
			repeat(12) { vScene.press(Key.DirectionDown) }
			vAfter = vScene.frame()
		} finally {
			vScene.close()
		}

		assertTrue(vAfter != vBefore, "twelve presses of Down changed nothing on screen")
	}

	@Test
	@Ignore("A rendering tool, not a check -- it has nothing to assert that the test above does not.")
	fun `the selection is drawn where the keyboard has got to`() = onSwingThread {
		// Not only that something changed -- that the change is visible as a cursor. A selection
		// that moves invisibly is the same to a user as one that does not move.
		val vScene = scene()
		try {
			vScene.press(Key.DirectionDown)
			vScene.press(Key.DirectionDown)
			java.io.File("build/render").mkdirs()
			java.io.File("build/render/game-list-selection.png")
				.writeBytes(vScene.renderNow())
		} finally {
			vScene.close()
		}
	}

	// ==================
	// MARK: Harness
	// ==================

	@OptIn(ExperimentalComposeUiApi::class)
	private fun scene(): ImageComposeScene {
		val vScene = ImageComposeScene(width = 600, height = 600, density = Density(1.65f)) {
			CardBrowserTheme(useDarkTheme = true) {
				Surface(modifier = Modifier.fillMaxSize()) {
					Box(Modifier.fillMaxSize()) {
						GameListContent(
							state = GameListContract.UiState(
								allGames = ALL_GAMES,
								isLoading = false,
							),
							dispatch = {},
						)
					}
				}
			}
		}
		vScene.settle(from = 0L)
		return vScene
	}

	@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
	private fun ImageComposeScene.press(key: Key) {
		sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown))
		sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp))
		settle(from = mNanos, frames = 8)
	}

	@OptIn(ExperimentalComposeUiApi::class)
	private fun ImageComposeScene.settle(from: Long, frames: Int = 40) {
		var vAt = from
		repeat(frames) {
			vAt += 16_000_000L
			render(vAt)
		}
		mNanos = vAt
	}

	@OptIn(ExperimentalComposeUiApi::class)
	private fun ImageComposeScene.renderNow(): ByteArray =
		render(mNanos).encodeToData()?.bytes ?: error("could not encode")

	@OptIn(ExperimentalComposeUiApi::class)
	private fun ImageComposeScene.frame(): String =
		(render(mNanos).encodeToData()?.bytes ?: error("could not encode")).contentHashCode().toString()

	private var mNanos = 0L

	private fun onSwingThread(block: () -> Unit) {
		var vFailure: Throwable? = null
		SwingUtilities.invokeAndWait { runCatching(block).onFailure { vFailure = it } }
		vFailure?.let { throw it }
	}

	private companion object {

		/** Every game, so the list is longer than the window and has somewhere to scroll to. */
		val ALL_GAMES = listOf(
			RiftboundGame, PokemonGame, MagicGame, OnePieceGame, AlteredGame,
			YuGiOhGame, WutheringWavesGame, CyberpunkGame, LorcanaGame, WowTcgGame,
		)
	}
}
