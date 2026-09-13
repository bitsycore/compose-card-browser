package com.bitsycore.cardbrowser.render

import com.bitsycore.cardbrowser.data.settings.CardRowHeight
import com.bitsycore.cardbrowser.data.settings.CardTileSize
import com.bitsycore.cardbrowser.data.settings.CardViewMode
import com.bitsycore.cardbrowser.ui.screen.cards.CardGridContent
import com.bitsycore.cardbrowser.ui.screen.cards.CardGridContract
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
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
						// So a domain chip is drawn in the game's own colour rather than as its key.
						game = RiftboundGame,
					),
					dispatch = {},
				)
			}
		}

		// A set the source has no scans of at all -- the Japanese MEGA line on TCGdex. What this
		// one is for is whether "No image" reads as a stated fact rather than as a failure.
		renderToPng(vOut, "card-grid-no-artwork-small", 620, 700, density = 1.65f) {
			CardGridContent(
				state = CardGridContract.UiState(
					setName = "インフェルノX",
					setCode = "M2",
					cards = PreviewData.CARDS.map {
						it.copy(
							artwork = it.artwork.copy(
								imageUrl = "",
								thumbnailUrl = null,
								displayUrl = null,
							),
						)
					},
					isLoading = false,
					isCompleteSet = true,
					cachedCardCount = 10,
					knownSetSize = 10,
					tileSize = CardTileSize.entries.first(),
					game = RiftboundGame,
				),
				dispatch = {},
			)
		}

		renderToPng(vOut, "card-grid-no-artwork", 620, 700, density = 1.65f) {
			CardGridContent(
				state = CardGridContract.UiState(
					setName = "インフェルノX",
					setCode = "M2",
					cards = PreviewData.CARDS.map {
						it.copy(
							artwork = it.artwork.copy(
								imageUrl = "",
								thumbnailUrl = null,
								displayUrl = null,
							),
						)
					},
					isLoading = false,
					// A whole set, so the only notice is the one about pictures.
					isCompleteSet = true,
					cachedCardCount = 10,
					knownSetSize = 10,
					game = RiftboundGame,
				),
				dispatch = {},
			)
		}

		// The chrome above the grid, on the light theme where its edges can be seen: the bar, the
		// search field under it and the first row of tiles. What this one is for is the two gaps
		// either side of the field, which are easy to get unequal and hard to judge by eye.
		renderToPng(vOut, "card-grid-chrome", 620, 520, density = 1.65f, isDark = false) {
			CardGridContent(
				state = CardGridContract.UiState(
					setName = "Origins",
					setCode = "OGN",
					cards = PreviewData.CARDS,
					isLoading = false,
					knownSetSize = 352,
					isSearchOpen = true,
					game = RiftboundGame,
				),
				dispatch = {},
			)
		}

		// And the grid's three, which are a minimum tile width rather than a row height -- so what
		// is worth looking at is how many fit across, not how tall anything is.
		CardTileSize.entries.forEach { vSize ->
			renderToPng(vOut, "card-grid-${vSize.name.lowercase()}", 620, 700, density = 1.65f) {
				CardGridContent(
					state = CardGridContract.UiState(
						setName = "Origins",
						setCode = "OGN",
						cards = PreviewData.CARDS,
						isLoading = false,
						knownSetSize = 352,
						viewMode = CardViewMode.GRID,
						tileSize = vSize,
					),
					dispatch = {},
				)
			}
		}

		assertTrue(vOut.listFiles().orEmpty().any { it.length() > 0 }, "nothing was rendered")
	}
}
