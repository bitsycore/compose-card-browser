package com.bitsycore.cardbrowser.render

import com.bitsycore.cardbrowser.data.settings.CardRowHeight
import com.bitsycore.cardbrowser.data.settings.CardViewMode
import com.bitsycore.cardbrowser.ui.cards.CardGridContent
import com.bitsycore.cardbrowser.ui.cards.CardGridContract
import com.bitsycore.cardbrowser.ui.preview.PreviewData
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Draws the card list at each of its three heights.
 *
 * The heights only differ by how much room the artwork gets, and whether the type line fits, so
 * three PNGs side by side is the only way to tell whether the three are actually different enough
 * to be worth being three.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests '*CardListRenderer*'
 * ```
 */
class CardListRenderer {

	@Test
	@Ignore("A rendering tool, not a check. Remove the annotation to write the PNGs.")
	fun `writes the card list at every height`() {
		val vOut = File("build/render")
		vOut.mkdirs()

		CardRowHeight.entries.forEach { vHeight ->
			renderToPng(vOut, "card-list-${vHeight.name.lowercase()}", 620, 900, density = 1.65f) {
				CardGridContent(
					state = CardGridContract.UiState(
						setName = "Origins",
						setCode = "OGN",
						cards = PreviewData.CARDS,
						isLoading = false,
						knownSetSize = 352,
						viewMode = CardViewMode.LIST,
						rowHeight = vHeight,
					),
					dispatch = {},
				)
			}
		}

		assertTrue(vOut.listFiles().orEmpty().any { it.length() > 0 }, "nothing was rendered")
	}
}
