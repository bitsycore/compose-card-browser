package com.bitsycore.cardbrowser

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.bitsycore.cardbrowser.di.appModule
import com.bitsycore.cardbrowser.di.platformModule
import com.bitsycore.cardbrowser.ui.App
import org.koin.core.context.startKoin

/**
 * The desktop entry point.
 *
 * Desktop exists here for fast development and manual testing; packaging and distribution are later
 * work. Everything the app is lives in `commonMain` -- this file is a window and a Koin start.
 */
fun main() {
	startKoin {
		modules(appModule, platformModule())
	}

	application {
		Window(
			onCloseRequest = ::exitApplication,
			title = "CardBrowser",
			// The same mark the phone shows, so the taskbar entry matches the app. Loaded straight
			// off the classpath rather than through Compose resources, which is all a window icon
			// needs and keeps it working before the resource generator has run.
			icon = windowIcon(),
			// Tall rather than wide: the content is a grid of portrait cards, and a default 16:9
			// window shows two rows of them and a lot of empty space.
			state = rememberWindowState(size = DpSize(1000.dp, 820.dp)),
		) {
			App()
		}
	}
}

/**
 * The window and taskbar icon.
 *
 * Returns `null` rather than throwing if the resource is missing: an icon is decoration, and a
 * development build should still open a window without one.
 */
private fun windowIcon(): Painter? = try {
	Main::class.java.getResourceAsStream(WINDOW_ICON_PATH)?.use { vStream ->
		BitmapPainter(loadImageBitmap(vStream))
	}
} catch (vError: Exception) {
	null
}

/** A marker for `getResourceAsStream`, which needs a class to resolve the classpath against. */
private object Main

private const val WINDOW_ICON_PATH = "/app-icon-512.png"
