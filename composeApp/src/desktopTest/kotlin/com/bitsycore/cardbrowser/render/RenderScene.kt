package com.bitsycore.cardbrowser.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.bitsycore.cardbrowser.ui.theme.CardBrowserTheme
import java.io.File

/**
 * Draws [content] into a PNG, once, off screen.
 *
 * This project has no Mac and, for most screens, no device to look at — so a rendered PNG is the
 * only way to see what a change did. The tools in this package all wanted the same twenty lines to
 * get one, and all had their own copy: four near-identical `render` helpers differing only in
 * canvas size and density, which is the kind of duplication that drifts until two of them theme
 * differently and nobody notices.
 *
 * @param width canvas width in pixels. Each caller has its own, and the differences are real —
 *   a card detail page is rendered 3800 px tall because it scrolls, and a title bar 130
 * @param density the scale factor. Around 1.6 is a phone; the tools pick per surface
 */
@OptIn(ExperimentalComposeUiApi::class)
fun renderToPng(
	directory: File,
	name: String,
	width: Int,
	height: Int,
	density: Float = 1.65f,
	isDark: Boolean = true,
	content: @Composable () -> Unit,
) {
	val vScene = ImageComposeScene(width = width, height = height, density = Density(density)) {
		CardBrowserTheme(useDarkTheme = isDark) {
			Surface(modifier = Modifier.fillMaxSize()) {
				Box(Modifier.fillMaxSize()) { content() }
			}
		}
	}
	try {
		val vImage = vScene.render()
		File(directory, "$name.png").writeBytes(
			vImage.encodeToData()?.bytes ?: error("could not encode $name"),
		)
	} finally {
		// A scene holds native Skia resources, and these tools render dozens in a loop.
		vScene.close()
	}
}
