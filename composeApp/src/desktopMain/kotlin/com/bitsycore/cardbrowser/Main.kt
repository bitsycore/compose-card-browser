package com.bitsycore.cardbrowser

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import com.bitsycore.cardbrowser.di.appModule
import com.bitsycore.cardbrowser.di.platformModule
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.cardbrowser.platform.WindowChrome
import com.bitsycore.cardbrowser.ui.App
import org.koin.compose.koinInject
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.koin.core.context.startKoin

/**
 * The desktop entry point.
 *
 * Desktop exists here for fast development and manual testing; packaging and distribution are later
 * work. Everything the app is lives in `commonMain` -- this file is a window and a Koin start.
 */
fun main() {
	// Before AWT exists. macOS reads its appearance once while initialising, so this cannot be
	// done from inside the window like the Windows half is.
	WindowChrome.prepareForDarkTitleBars()

	startKoin {
		modules(appModule, platformModule())
	}

	application {
		Window(
			onCloseRequest = ::exitApplication,
			title = "CardBrowser",
			// The largest one, for the window itself. The taskbar and the title bar are handed the
			// whole set below, which is what stops them scaling this down badly.
			icon = windowIcon(),
			// Tall rather than wide: the content is a grid of portrait cards, and a default 16:9
			// window shows two rows of them and a lot of empty space.
			state = rememberWindowState(size = DpSize(1000.dp, 820.dp)),
		) {
			// The title bar, which is the one piece of chrome the app cannot draw for itself.
			//
			// The same rule the theme uses, from the same two inputs, rather than a second opinion
			// about what "dark" means: `ThemeMode.isDark` is the whole of that decision and both
			// callers ask it. Re-applied whenever the answer changes, so switching theme in
			// settings recolours the title bar with the app instead of on the next launch.
			val vPreferences = koinInject<PreferencesStore>()
			val vPrefs by vPreferences.preferences.collectAsState()
			val vIsDark = vPrefs.themeMode.isDark(isSystemInDarkTheme())
			LaunchedEffect(vIsDark) { WindowChrome.applyTitleBarTheme(window, vIsDark) }

			// Every size, rather than one for AWT to squeeze.
			//
			// Given a single image it scales that image itself, with a filter far cruder than the
			// one that drew these -- a 512px icon at 24px in a title bar is where "why is it
			// fuzzy?" comes from. Given a list it picks the nearest and barely scales at all. The
			// sizes are pre-rendered, and the small ones are not merely the big one shrunk: see
			// `composeApp/tools/shape_app_icon.py` for why 16px keeps a margin the others spend.
			LaunchedEffect(Unit) {
				val vIcons = windowIcons()
				if (vIcons.isNotEmpty()) window.iconImages = vIcons
			}

			App()
		}
	}
}

/**
 * The window icon, at the one size Compose's `Window` accepts.
 *
 * Returns `null` rather than throwing if the resource is missing: an icon is decoration, and a
 * development build should still open a window without one.
 */
private fun windowIcon(): Painter? = try {
	Main::class.java.getResourceAsStream(iconPath(LARGEST_ICON))?.use { vStream ->
		BitmapPainter(vStream.readAllBytes().decodeToImageBitmap())
	}
} catch (vError: Exception) {
	null
}

/**
 * Every pre-rendered size, for AWT to choose between.
 *
 * Missing sizes are skipped rather than fatal, for the same reason [windowIcon] returns null: this
 * is decoration, and a build that has not regenerated them should still open a window.
 */
private fun windowIcons(): List<BufferedImage> = ICON_SIZES.mapNotNull { vSize ->
	try {
		Main::class.java.getResourceAsStream(iconPath(vSize))?.use { ImageIO.read(it) }
	} catch (vError: Exception) {
		null
	}
}

private fun iconPath(size: Int): String = "/app-icon-$size.png"

/** A marker for `getResourceAsStream`, which needs a class to resolve the classpath against. */
private object Main

/** What `shape_app_icon.py` writes. Kept in step with that script's `WINDOW_SIZES`. */
private val ICON_SIZES = listOf(16, 24, 32, 48, 64, 128, 256, 512)

private val LARGEST_ICON = ICON_SIZES.last()
