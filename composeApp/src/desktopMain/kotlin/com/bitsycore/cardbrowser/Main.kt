package com.bitsycore.cardbrowser

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
			// Tall rather than wide: the content is a grid of portrait cards, and a default 16:9
			// window shows two rows of them and a lot of empty space.
			state = rememberWindowState(size = DpSize(1000.dp, 820.dp)),
		) {
			App()
		}
	}
}
