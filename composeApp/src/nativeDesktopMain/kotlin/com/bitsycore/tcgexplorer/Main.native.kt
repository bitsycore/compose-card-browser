package com.bitsycore.tcgexplorer

import com.bitsycore.tcgexplorer.di.appModule
import com.bitsycore.tcgexplorer.di.platformModule
import com.bitsycore.tcgexplorer.ui.navigation.App
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

	nativeComposeWindow(title = "TCG Explorer") {
		App()
	}
}
