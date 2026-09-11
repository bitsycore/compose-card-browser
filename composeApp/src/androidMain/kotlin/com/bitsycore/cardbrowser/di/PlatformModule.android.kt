package com.bitsycore.cardbrowser.di

import android.content.Context
import android.content.Intent
import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.platform.LinkOpener
import com.bitsycore.cardbrowser.sqlstore.AndroidDriverFactory
import com.bitsycore.cardbrowser.sqlstore.DriverFactory
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import androidx.core.net.toUri

/**
 * Android bindings.
 *
 * Cache goes in `cacheDir`, which the system may purge under storage pressure -- correct for
 * something disposable. Preferences go in `filesDir`, which it may not.
 */
actual fun platformModule(): Module = module {

	single {
		val vContext = androidContext()
		AppStorage(
			fileSystem = FileSystem.SYSTEM,
			cacheRoot = vContext.cacheDir.toOkioPath(),
			preferencesRoot = vContext.filesDir.toOkioPath() / "preferences",
		).also { it.prepare() }
	}

	// The one platform whose SQLite driver needs a context. Handed over rather than looked up.
	single<DriverFactory> { AndroidDriverFactory(androidContext()) }

	single<LinkOpener> { AndroidLinkOpener(androidContext()) }
}

/** Fires an ACTION_VIEW at whatever browser the user has. */
private class AndroidLinkOpener(private val mContext: Context) : LinkOpener {

	override fun open(url: String): Boolean = try {
		val vIntent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
			// Launched from an application context rather than an activity, so the new task flag
			// is required or Android refuses it outright.
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}
		mContext.startActivity(vIntent)
		true
	} catch (_: Exception) {
		// No browser installed, or the intent was blocked. Not worth a crash.
		false
	}
}
