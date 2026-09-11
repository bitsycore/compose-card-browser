package com.bitsycore.cardbrowser.render

import com.bitsycore.cardbrowser.data.settings.BrowsingPreferences
import com.bitsycore.cardbrowser.data.settings.ThemeMode
import com.bitsycore.cardbrowser.ui.settings.SettingsContent
import com.bitsycore.cardbrowser.ui.settings.SettingsContract
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the settings screen off-screen, to read its sections and its fine print.
 *
 * Same idea and caveats as the other renderers: headless, no Koin, no network. `@Ignore` because its
 * output is looked at rather than asserted.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests '*SettingsRenderer*'
 * ```
 */
class SettingsRenderer {

	@Test
	@Ignore("A rendering tool, not a check. Remove the annotation to write the PNGs.")
	fun `writes the settings screen to build slash render`() {
		val vOut = File("build/render")
		vOut.mkdirs()

		renderToPng(vOut, "settings", width = 660, height = 2500, density = 1.65f) {
			SettingsContent(state = settingsState(), dispatch = {})
		}
		renderToPng(vOut, "settings-custom", width = 660, height = 2500, density = 1.65f) {
			SettingsContent(
				state = settingsState().copy(imageLimitBytes = 300L * 1024 * 1024),
				dispatch = {},
			)
		}

		assertTrue(vOut.listFiles().orEmpty().any { it.length() > 0 }, "nothing was rendered")
		println("Wrote ${vOut.absolutePath}")
	}
}

private fun settingsState() = SettingsContract.UiState(
	metadataLimitBytes = BrowsingPreferences.DEFAULT_METADATA_CACHE_LIMIT_BYTES,
	imageLimitBytes = BrowsingPreferences.DEFAULT_IMAGE_CACHE_LIMIT_BYTES,
	themeMode = ThemeMode.SYSTEM,
	prefetchRadius = BrowsingPreferences.DEFAULT_PREFETCH_RADIUS,
	revalidateSetsOnLaunch = true,
	hideEmptySets = true,
	apiCalls = listOf("api.scryfall.com" to 14, "api.tcgdex.net" to 12),
	attributions = listOf(
		SettingsContract.ProviderCredit(
			source = "Riftcodex",
			text = "Card data from Riftcodex, an unofficial fan project not affiliated with " +
				"Riot Games.",
		),
		SettingsContract.ProviderCredit(
			source = "TCGdex",
			text = "Pokémon card data from TCGdex, a community project not affiliated with " +
				"Nintendo, Creatures or GAME FREAK.",
		),
	),
)
