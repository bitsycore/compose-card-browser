package com.bitsycore.toploader

import com.bitsycore.toploader.di.appModule
import com.bitsycore.toploader.di.platformModule
import com.bitsycore.toploader.ui.navigation.App
import com.compose.sdl.nativeComposeWindow
import org.koin.core.context.startKoin

/**
 * The native desktop entry point: Compose on Kotlin/Native, SDL3 and Skia in place of AWT.
 *
 * Koin starts before the window, not inside it: the content lambda is composable and runs again on
 * recomposition.
 */
fun main() {
	startKoin {
		modules(appModule, platformModule())
	}

	nativeComposeWindow(title = "Toploader") {
		App()
	}
}
