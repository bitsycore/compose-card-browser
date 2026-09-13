package com.bitsycore.toploader.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bitsycore.toploader.ui.navigation.App

/**
 * The Android entry point.
 *
 * One activity, and it does two things: go edge to edge, and show the shared `App()`. Koin is
 * started in [ToploaderApplication] rather than here, so it is up before any screen -- including
 * one restored by the system after a process death -- asks it for anything.
 */
class MainActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		setContent { App() }
	}
}
