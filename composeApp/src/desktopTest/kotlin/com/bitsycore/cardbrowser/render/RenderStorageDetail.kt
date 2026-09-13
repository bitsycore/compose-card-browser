package com.bitsycore.cardbrowser.render

import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.data.repository.KeptSet
import com.bitsycore.cardbrowser.ui.screen.storagedetail.StorageDetailContent
import com.bitsycore.cardbrowser.ui.screen.storagedetail.StorageDetailContract
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the storage breakdown off-screen, to look at how the groups read.
 *
 * Same idea and caveats as the other renderers: headless, no Koin, no network. `@Ignore` because
 * its output is looked at rather than asserted.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests '*StorageDetailRenderer*'
 * ```
 */
class StorageDetailRenderer {

	@Test
	@Ignore("A rendering tool, not a check. Remove the annotation to write the PNGs.")
	fun `writes the storage breakdown to build slash render`() {
		val vOut = File("build/render")
		vOut.mkdirs()

		renderToPng(vOut, "storage-detail", width = 660, height = 1200, density = 1.65f) {
			StorageDetailContent(state = twoLanguages(), dispatch = {})
		}
		renderToPng(vOut, "storage-detail-one", width = 660, height = 900, density = 1.65f) {
			StorageDetailContent(state = noLanguageStated(), dispatch = {})
		}

		assertTrue(vOut.listFiles().orEmpty().any { it.length() > 0 }, "nothing was rendered")
		println("Wrote ${vOut.absolutePath}")
	}
}

private fun twoLanguages() = StorageDetailContract.UiState(
	game = GameId("pokemon"),
	displayName = "Pokémon",
	isLoading = false,
	sets = listOf(
		KeptSet("tcgdex", "tcgdex:sv08", "en", "Surging Sparks", 252, 1_400_000),
		KeptSet("tcgdex", "tcgdex:sv07", "en", "Stellar Crown", 175, 980_000),
		KeptSet("tcgdex", "tcgdex:sv08", "fr", "Étincelles Déferlantes", 252, 1_410_000),
		// A bulk import brings sets the catalogue does not list. Rendered on purpose.
		KeptSet("tcgdex", "tcgdex:swshp", "en", "SWSH Black Star Promos", 307, 1_700_000, false),
	),
)

private fun noLanguageStated() = StorageDetailContract.UiState(
	game = GameId("onepiece"),
	displayName = "One Piece",
	isLoading = false,
	sets = listOf(
		KeptSet("optcg", "optcg:OP-01", "-", "Romance Dawn", 154, 620_000),
		KeptSet("optcg", "optcg:OP-02", "-", "Paramount War", 154, 615_000),
	),
)
