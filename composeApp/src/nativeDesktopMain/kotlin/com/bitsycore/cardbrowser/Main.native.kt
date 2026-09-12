package com.bitsycore.cardbrowser

import com.bitsycore.cardbrowser.di.appModule
import com.bitsycore.cardbrowser.di.platformModule
import com.bitsycore.cardbrowser.ui.App
import com.compose.sdl.nativeComposeWindow
import org.koin.core.context.startKoin

/**
 * The native desktop entry point: Compose on Kotlin/Native rather than on the JVM.
 *
 * The source set only exists under `-PnativeDesktop`, so nothing here affects the ordinary build.
 * What it produces is a binary with no JVM under it -- SDL3 and Skia in place of AWT, and the same
 * `App()` as every other target above that.
 *
 * Koin is started before the window rather than inside it, for the reason the desktop entry point
 * does the same: the content lambda is composable and runs again on recomposition, and starting a
 * DI container twice throws.
 *
 * **Compiled and linked, never run.** No binary produced here has been started, on any of the four
 * targets. The window, the title bar, the storage paths and the card store are all unexercised --
 * see docs/NATIVE_DESKTOP.md for what that leaves open.
 */
fun main() {
	startKoin {
		modules(appModule, platformModule())
	}

	nativeComposeWindow(title = "CardBrowser") {
		App()
	}
}
