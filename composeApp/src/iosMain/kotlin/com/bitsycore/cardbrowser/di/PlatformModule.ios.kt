package com.bitsycore.cardbrowser.di

import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.platform.LinkOpener
import com.bitsycore.cardbrowser.sqlstore.DriverFactory
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplication

/**
 * iOS bindings.
 *
 * Caches go in the Caches directory, which iOS may purge when the device is short of space --
 * correct for something disposable and re-fetchable. Preferences go in Documents, which it does not
 * purge.
 */
actual fun platformModule(): Module = module {

	single {
		AppStorage(
			fileSystem = FileSystem.SYSTEM,
			cacheRoot = iosDirectory(NSCachesDirectory).toPath(),
			preferencesRoot = (iosDirectory(NSDocumentDirectory) + "/preferences").toPath(),
		).also { it.prepare() }
	}

	single { DriverFactory() }

	single<LinkOpener> { IosLinkOpener() }
}

/**
 * The path of one of iOS's standard directories for this app.
 *
 * Falls back to `NSTemporaryDirectory`-adjacent behaviour only in the sense that an empty result
 * would be a broken install; the non-null assertion is deliberate, because an app with no Caches
 * directory has nothing useful left to do.
 */
private fun iosDirectory(directory: platform.Foundation.NSSearchPathDirectory): String =
	NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true).first() as String

/** Hands a URL to iOS, which opens Safari or whatever handles the scheme. */
private class IosLinkOpener : LinkOpener {

	@OptIn(ExperimentalForeignApi::class)
	override fun open(url: String): Boolean {
		val vUrl = NSURL.URLWithString(url) ?: return false
		if (!UIApplication.sharedApplication.canOpenURL(vUrl)) return false
		UIApplication.sharedApplication.openURL(vUrl, emptyMap<Any?, Any?>(), null)
		return true
	}
}
