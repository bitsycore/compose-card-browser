package com.bitsycore.cardbrowser

import com.bitsycore.cardbrowser.di.appModule
import com.bitsycore.cardbrowser.di.platformModule
import com.bitsycore.cardbrowser.ui.App
import com.bitsycore.compose.desktop.native.nativeComposeWindow
import org.koin.core.context.startKoin

/**
 * The native desktop entry point: Compose on Kotlin/Native rather than on the JVM.
 *
 * ## This does not build yet, and that is deliberate
 *
 * The source set only exists under `-PnativeDesktop`, so nothing here affects the ordinary build.
 * It is written now because the scaffolding around it -- the targets, the bridge plugin, the
 * source-set group -- is what proves the rest of the project can be compiled natively, and that
 * part *is* verified: every module below the UI compiles for `linuxX64` and `macosArm64` today.
 *
 * What stops this module is a handful of dependencies that publish no klibs for these targets. The
 * list, and which of them are ours to fix, is in [docs/NATIVE_DESKTOP.md](../../../../../../docs/NATIVE_DESKTOP.md).
 *
 * Two things here will also need writing when they do land:
 *
 * - **`platformModule()` has no native-desktop actual.** It supplies `AppStorage`'s two roots and a
 *   `LinkOpener`, and both are per-platform by nature -- a cache directory and a way to open a URL.
 * - **`DriverFactory` has no native-desktop implementation.** SQLDelight's `native-driver` does
 *   publish for `mingwX64` and `linuxX64`, so this is a class rather than a research problem.
 */
fun main() = nativeComposeWindow(title = "CardBrowser") {
	startKoin {
		modules(appModule, platformModule())
	}
	App()
}
