package com.bitsycore.cardbrowser.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.ui.cards.CardGridContent
import com.bitsycore.cardbrowser.ui.cards.CardGridContract
import com.bitsycore.cardbrowser.ui.preview.PreviewData
import com.bitsycore.cardbrowser.ui.theme.CardBrowserTheme
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Arriving at a list that was left part-way down does not scroll it back to the top.
 *
 * ## Why this is a test rather than a fix and a shrug
 *
 * Because it was not in the scroll-restoring code, which worked. It was the *keyboard cursor*: an
 * effect that keeps the selection on screen, running on arrival with a selection of 0 and dragging
 * a restored list up to meet it. Every back navigation in the app did this, and nothing about
 * either piece looks wrong on its own.
 *
 * What the screen reports through `ScrollPositionChanged` is the assertion, because it is also what
 * the view model stores -- so a scroll to the top does not merely look wrong for a moment, it
 * overwrites the position that was being restored.
 */
class RestoredScrollTest {

	@Test
	fun `a grid restored part-way down stays there`() = onSwingThread {
		val vIntents = mutableListOf<CardGridContract.Intent>()
		val vScene = scene(vIntents)
		try {
			// Long enough for the cursor effect to have run and animated, had it been going to.
			vScene.settle(frames = 90)
		} finally {
			vScene.close()
		}

		val vReported = vIntents.filterIsInstance<CardGridContract.Intent.ScrollPositionChanged>()
			.map { it.index }
		assertTrue(
			vReported.none { it == 0 },
			"the grid scrolled back to the top on arrival: $vReported",
		)
	}

	// ==================
	// MARK: Harness
	// ==================

	@OptIn(ExperimentalComposeUiApi::class)
	private fun scene(intents: MutableList<CardGridContract.Intent>): ImageComposeScene =
		ImageComposeScene(width = 600, height = 800, density = Density(1.65f)) {
			CardBrowserTheme(useDarkTheme = true) {
				Surface(modifier = Modifier.fillMaxSize()) {
					Box(Modifier.fillMaxSize()) {
						CardGridContent(
							state = CardGridContract.UiState(
								setName = "Origins",
								setCode = "OGN",
								cards = MANY_CARDS,
								isLoading = false,
								knownSetSize = MANY_CARDS.size,
								// Where the user was when they opened a card, which is what the
								// view model hands back on the way in.
								firstVisibleIndex = RESTORED_TO,
							),
							dispatch = { intents += it },
						)
					}
				}
			}
		}

	@OptIn(ExperimentalComposeUiApi::class)
	private fun ImageComposeScene.settle(frames: Int) {
		var vAt = 0L
		repeat(frames) {
			vAt += 16_000_000L
			render(vAt)
		}
	}

	private fun onSwingThread(block: () -> Unit) {
		var vFailure: Throwable? = null
		SwingUtilities.invokeAndWait { runCatching(block).onFailure { vFailure = it } }
		vFailure?.let { throw it }
	}

	private companion object {

		/** Far enough down that a scroll to the top is unmistakable. */
		const val RESTORED_TO = 40

		/** The preview set, repeated with distinct ids: a lazy grid throws on a duplicate key. */
		val MANY_CARDS = (0 until 80).map { vIndex ->
			val vCard = PreviewData.CARDS[vIndex % PreviewData.CARDS.size]
			vCard.copy(id = SourceId(vCard.id.provider, "card-$vIndex"))
		}
	}
}
