package com.bitsycore.cardbrowser.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import com.bitsycore.cardbrowser.ui.detail.CardDetailContent
import com.bitsycore.cardbrowser.ui.detail.CardDetailContract
import com.bitsycore.cardbrowser.ui.preview.PreviewData
import com.bitsycore.cardbrowser.ui.theme.CardBrowserTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Arrow keys really move between cards on the detail screen.
 *
 * Driven through a real composition with real key events, because there is no other way to know.
 * A key handler that is never reached compiles, renders correctly and does nothing -- and the two
 * ways to get there are both easy: putting the modifier on a node that never holds focus, or using
 * `onPreviewKeyEvent` and having something above swallow the key first.
 *
 * What is asserted is the intent the screen reports, not a pixel. `PageChanged` is what a settled
 * swipe emits, so "the arrow key did what a swipe does" is exactly the claim -- and it is the claim
 * that matters, since everything downstream of the pager is already driven by that one intent.
 */
class ArrowKeyNavigationTest {

	@Test
	fun `right then left walks forward and back through the cards`() = onSwingThread {
		val vSeen = mutableListOf<Int>()
		scene(currentIndex = 2, onPageChanged = { vSeen += it }).use { vScene ->
			vScene.press(Key.DirectionRight)
			vScene.press(Key.DirectionLeft)
		}

		// The pager animates, so the settled page arrives over several frames and the exact number
		// of reports is the animation's business rather than this test's. What must be true is
		// where it went, in order, and that it moved at all.
		assertEquals(listOf(3, 2), vSeen.movesFrom(2), "arrows did not move the pager")
	}

	@Test
	fun `a second press mid-animation moves on again rather than being swallowed`() = onSwingThread {
		// The report: "I can't go fast when using arrow, it seems to block until the end of the
		// animation." It did. The step was counted from `currentIndex`, which only moves when a
		// page *settles*, so the second press measured from the card being left and asked for the
		// page already on its way -- a no-op that looked like the key had been eaten.
		val vSeen = mutableListOf<Int>()
		scene(currentIndex = 2, onPageChanged = { vSeen += it }).use { vScene ->
			vScene.press(Key.DirectionRight, settle = false)
			vScene.press(Key.DirectionRight)
		}

		assertEquals(listOf(4), vSeen.movesFrom(2), "the second press did not add a card")
	}

	@Test
	fun `the first card ignores left and the last ignores right`() = onSwingThread {
		val vFirst = mutableListOf<Int>()
		scene(currentIndex = 0, onPageChanged = { vFirst += it }).use { it.press(Key.DirectionLeft) }
		assertEquals(emptyList(), vFirst.movesFrom(0), "left ran off the front of the list")

		val vLast = mutableListOf<Int>()
		val vEnd = PreviewData.CARDS.lastIndex
		scene(currentIndex = vEnd, onPageChanged = { vLast += it }).use { it.press(Key.DirectionRight) }
		assertEquals(emptyList(), vLast.movesFrom(vEnd), "right ran off the end of the list")
	}

	// ==================
	// MARK: Harness
	// ==================

	/** A scene showing the detail screen, already settled on [currentIndex]. */
	@OptIn(ExperimentalComposeUiApi::class)
	private fun scene(currentIndex: Int, onPageChanged: (Int) -> Unit): ImageComposeScene {
		val vScene = ImageComposeScene(width = 700, height = 900, density = Density(1.65f)) {
			CardBrowserTheme(useDarkTheme = true) {
				Surface(modifier = Modifier.fillMaxSize()) {
					Box(Modifier.fillMaxSize()) {
						CardDetailContent(
							state = CardDetailContract.UiState(
								cards = PreviewData.CARDS,
								currentIndex = currentIndex,
								set = PreviewData.ORIGINS,
								isLoading = false,
								providerLanguages = setOf(CardLanguage.ENGLISH),
								providerDisplayName = "Riftcodex",
								game = RiftboundGame,
							),
							dispatch = { vIntent ->
								if (vIntent is CardDetailContract.Intent.PageChanged) {
									onPageChanged(vIntent.index)
								}
							},
						)
					}
				}
			}
		}
		// Let the pager settle on the starting page. Arriving there is reported like any other
		// page, and `movesFrom` below is what tells that apart from a move.
		vScene.settle(from = 0L)
		return vScene
	}

	/** Presses and releases a key, then runs the frames the pager needs to settle. */
	@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
	private fun ImageComposeScene.press(key: Key, settle: Boolean = true) {
		// The `KeyEvent(key = , type = )` factory, not the value class's own constructor. They
		// have the same name, and passing a `java.awt.event.KeyEvent` picks the constructor --
		// which compiles, and then throws `ClassCastException` the first time anything reads the
		// key off it, a long way from where the mistake was made.
		sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown))
		sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp))
		// A couple of frames either way, so the press is processed. `settle = false` stops short
		// of the animation finishing, which is the whole point of the test that asks for it.
		settle(from = mNanos, frames = if (settle) FRAMES_TO_SETTLE else FRAMES_MID_ANIMATION)
	}

	/** Renders a second of frames, which is more than any of these animations needs. */
	@OptIn(ExperimentalComposeUiApi::class)
	private fun ImageComposeScene.settle(from: Long, frames: Int = FRAMES_TO_SETTLE) {
		var vAt = from
		repeat(frames) {
			vAt += FRAME_NANOS
			render(vAt)
		}
		mNanos = vAt
	}

	/** Where the scene's clock has got to. Frames must not go backwards between presses. */
	private var mNanos = 0L

	/**
	 * The pages this actually moved to, from a start of [from].
	 *
	 * Two kinds of noise to take out, and both are the pager being honest rather than wrong. It
	 * reports the same settled page more than once while an animation finishes, and it reports its
	 * *starting* page on arrival there -- which is not a move, and is indistinguishable from one
	 * without knowing where it began.
	 */
	private fun List<Int>.movesFrom(from: Int): List<Int> =
		filterIndexed { vIndex, vPage -> vIndex == 0 || this[vIndex - 1] != vPage }
			.dropWhile { it == from }

	/**
	 * Runs everything on the event dispatch thread.
	 *
	 * Not optional. The detail screen's effects dispatch to `Dispatchers.Main`, which on desktop is
	 * Swing's queue, so composition lands on the EDT while a test that renders from the test worker
	 * is reading the same state from another thread -- and Compose detects that and throws rather
	 * than corrupting quietly. Creating, driving and rendering the scene from one thread is the fix,
	 * and the EDT is the one thread all of it can agree on.
	 */
	private fun onSwingThread(block: () -> Unit) {
		var vFailure: Throwable? = null
		SwingUtilities.invokeAndWait {
			runCatching(block).onFailure { vFailure = it }
		}
		vFailure?.let { throw it }
	}

	private companion object {

		const val FRAMES_TO_SETTLE = 60

		/** Enough for a press to be handled and the scroll to start, nowhere near enough to land. */
		const val FRAMES_MID_ANIMATION = 2

		const val FRAME_NANOS = 16_000_000L
	}
}

/** `use` for a scene, which holds native Skia resources. */
@OptIn(ExperimentalComposeUiApi::class)
private inline fun ImageComposeScene.use(block: (ImageComposeScene) -> Unit) {
	try {
		block(this)
	} finally {
		close()
	}
}
