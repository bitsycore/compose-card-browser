package com.bitsycore.toploader.android

import android.app.Application
import com.bitsycore.toploader.di.appModule
import com.bitsycore.toploader.di.platformModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * Starts Koin before any activity exists.
 *
 * The Android platform module needs a `Context` for its cache and files directories, which is why
 * the graph is built here and given `androidContext` rather than being built lazily from a screen.
 */
class ToploaderApplication : Application() {

	override fun onCreate() {
		super.onCreate()
		startKoin {
			androidContext(this@ToploaderApplication)
			modules(appModule, platformModule())
		}
	}
}
