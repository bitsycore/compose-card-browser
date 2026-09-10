package com.bitsycore.cardbrowser.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.bitsycore.cardbrowser.games.api.GameArt
import com.bitsycore.cardbrowser.games.altered.AlteredArt
import com.bitsycore.cardbrowser.games.cyberpunk.CyberpunkArt
import com.bitsycore.cardbrowser.games.lorcana.LorcanaArt
import com.bitsycore.cardbrowser.games.onepiece.OnePieceArt
import com.bitsycore.cardbrowser.games.riftbound.RiftboundArt
import com.bitsycore.cardbrowser.games.wutheringwaves.WutheringWavesArt
import com.bitsycore.cardbrowser.ui.sets.SetListContent
import com.bitsycore.cardbrowser.ui.sets.SetListContract
import com.bitsycore.cardbrowser.ui.theme.CardBrowserTheme
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the set list's title bar for each game whose mark needs special handling, on both themes.
 *
 * The logos are the part of this app that has been got wrong most often, and always in the same
 * way: a mark is checked on one theme, or in the picker but not the title bar, and it turns out to
 * be invisible on the other. Measuring contrast catches some of that and misses the rest -- the
 * Yu-Gi-Oh mark measures 44% "low contrast" and reads perfectly, because the figure is counting the
 * white insides of outlined letters.
 *
 * So this writes the picture and lets someone look. `@Ignore` because it is a tool, not a check:
 * there is nothing here that can fail meaningfully, and its output is a directory of PNGs.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests '*TitleLogoRenderer*'
 * ```
 */
class TitleLogoRenderer {

	@Test
	@Ignore("A rendering tool, not a check. Remove the annotation to write the PNGs.")
	fun `writes each title bar to build slash render`() {
		val vOut = File("build/render")
		vOut.mkdirs()

		val vCases = listOf(
			"cyberpunk" to CyberpunkArt,
			"onepiece" to OnePieceArt,
			"wutheringwaves" to WutheringWavesArt,
			"altered" to AlteredArt,
			"riftbound" to RiftboundArt,
			"lorcana" to LorcanaArt,
		)
		for ((vName, vArt) in vCases) {
			for (vDark in listOf(true, false)) {
				val vSuffix = if (vDark) "dark" else "light"
				renderTitle(vOut, "title-$vName-$vSuffix", vArt, vDark)
			}
		}

		assertTrue(vOut.listFiles().orEmpty().any { it.length() > 0 }, "nothing was rendered")
		println("Wrote ${vOut.absolutePath}")
	}
}

/** Just the bar: short, so several fit on screen side by side when comparing them. */
@OptIn(ExperimentalComposeUiApi::class)
private fun renderTitle(directory: File, name: String, art: GameArt, isDark: Boolean) {
	val vScene = ImageComposeScene(width = 660, height = 130, density = Density(1.65f)) {
		CardBrowserTheme(useDarkTheme = isDark) {
			Surface(modifier = Modifier.fillMaxSize()) {
				Box(Modifier.fillMaxSize()) {
					SetListContent(
						state = SetListContract.UiState(isLoading = false),
						dispatch = {},
						onOpenSet = {},
						onOpenSettings = {},
						gameArt = art,
					)
				}
			}
		}
	}
	try {
		val vImage = vScene.render()
		File(directory, "$name.png").writeBytes(
			vImage.encodeToData()?.bytes ?: error("could not encode $name"),
		)
	} finally {
		vScene.close()
	}
}
