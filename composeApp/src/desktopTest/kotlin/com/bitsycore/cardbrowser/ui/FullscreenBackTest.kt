package com.bitsycore.cardbrowser.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import com.bitsycore.cardbrowser.ui.detail.CardDetailContent
import com.bitsycore.cardbrowser.ui.detail.CardDetailContract
import com.bitsycore.cardbrowser.ui.preview.PreviewData
import com.bitsycore.cardbrowser.ui.theme.CardBrowserTheme
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Back closes the fullscreen viewer, and leaves the card open behind it.
 *
 * Two things are on screen and back has to take the top one. Without this the viewer stayed up and
 * the *screen* was popped, so dismissing a picture threw away the card it belonged to.
 *
 * Desktop has no back gesture, so what stands in for it is Escape, and the screen handles that key
 * itself -- checked, by writing this test first and watching a `NavigationBackHandler` alone fail
 * it. The handler carries the platform back on Android; the key carries the keyboard.
 */
class FullscreenBackTest {

	@Test
	fun `escape closes the viewer rather than the screen`() = onSwingThread {
		val vIntents = mutableListOf<CardDetailContract.Intent>()
		val vScene = scene(isFullscreen = true, intents = vIntents)
		try {
			vScene.press(Key.Escape)
		} finally {
			vScene.close()
		}

		// Only the fullscreen intents: the pager reports where it settled on the way in, which
		// is composition doing its job rather than anything the key did.
		assertEquals<List<CardDetailContract.Intent>>(
			listOf(CardDetailContract.Intent.FullscreenToggled(false)),
			vIntents.filterIsInstance<CardDetailContract.Intent.FullscreenToggled>(),
			"escape should close the viewer: $vIntents",
		)
	}

	@Test
	fun `with no viewer open the handler stays out of the way`() = onSwingThread {
		// The other half of the same claim: a handler that swallowed back unconditionally would
		// trap the user on the card, which is a worse bug than the one being fixed.
		val vIntents = mutableListOf<CardDetailContract.Intent>()
		val vScene = scene(isFullscreen = false, intents = vIntents)
		try {
			vScene.press(Key.Escape)
		} finally {
			vScene.close()
		}

		assertTrue(
			vIntents.none { it is CardDetailContract.Intent.FullscreenToggled },
			"nothing was open to close: $vIntents",
		)
	}

	// ==================
	// MARK: Harness
	// ==================

	@OptIn(ExperimentalComposeUiApi::class)
	private fun scene(
		isFullscreen: Boolean,
		intents: MutableList<CardDetailContract.Intent>,
	): ImageComposeScene {
		val vScene = ImageComposeScene(width = 600, height = 800, density = Density(1.65f)) {
			CardBrowserTheme(useDarkTheme = true) {
				Surface(modifier = Modifier.fillMaxSize()) {
					Box(Modifier.fillMaxSize()) {
						CardDetailContent(
							state = CardDetailContract.UiState(
								cards = PreviewData.CARDS,
								currentIndex = 0,
								isLoading = false,
								isFullscreen = isFullscreen,
							),
							dispatch = { intents += it },
						)
					}
				}
			}
		}
		vScene.settle()
		return vScene
	}

	@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
	private fun ImageComposeScene.press(key: Key) {
		sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown))
		sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp))
		settle()
	}

	@OptIn(ExperimentalComposeUiApi::class)
	private fun ImageComposeScene.settle() {
		repeat(30) {
			mNanos += 16_000_000L
			render(mNanos)
		}
	}

	private var mNanos = 0L

	private fun onSwingThread(block: () -> Unit) {
		var vFailure: Throwable? = null
		SwingUtilities.invokeAndWait { runCatching(block).onFailure { vFailure = it } }
		vFailure?.let { throw it }
	}
}
