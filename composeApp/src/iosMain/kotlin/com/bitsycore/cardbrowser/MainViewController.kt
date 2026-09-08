package com.bitsycore.cardbrowser

import androidx.compose.ui.window.ComposeUIViewController
import com.bitsycore.cardbrowser.di.appModule
import com.bitsycore.cardbrowser.di.platformModule
import com.bitsycore.cardbrowser.ui.App
import org.koin.core.context.startKoin
import platform.UIKit.UIViewController

/**
 * What the Xcode project links against, and the whole of the iOS shell's Kotlin side.
 *
 * The Swift half is two small files under `iosApp/`, because there is nothing for it to do: the app
 * is `commonMain` and the platform surface is the actuals beside this file.
 */
private var gStarted = false

/**
 * Builds the object graph, once per process.
 *
 * Called from the Swift app on launch and again from [MainViewController], because a
 * `UIViewControllerRepresentable` can be rebuilt and neither side should have to know which of them
 * ran first. Idempotent for exactly that reason.
 */
fun startCardBrowser() {
	if (gStarted) return
	gStarted = true
	startKoin {
		modules(appModule, platformModule())
	}
}

/** The app, in a view controller SwiftUI can host. */
fun MainViewController(): UIViewController {
	startCardBrowser()
	return ComposeUIViewController { App() }
}
