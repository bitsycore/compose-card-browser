package com.bitsycore.tcgexplorer.di

import com.bitsycore.tcgexplorer.data.cache.AppStorage
import com.bitsycore.tcgexplorer.platform.LinkOpener
import com.bitsycore.tcgexplorer.sqlstore.DriverFactory
import com.bitsycore.tcgexplorer.sqlstore.IosDriverFactory
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

	single<DriverFactory> { IosDriverFactory() }

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

/** Hands a URL to iOS, which opens whichever browser the user has set as default. */
private class IosLinkOpener : LinkOpener {

	/**
	 * No `canOpenURL` gate, which is what stopped every link on a device.
	 *
	 * Since iOS 9 that call answers false for any scheme the app has not listed under
	 * `LSApplicationQueriesSchemes`, and this app lists none -- so it rejected each URL before
	 * anything was handed over, and the screen showed "could not open" for a link that was fine.
	 * `openURL:options:completionHandler:` needs no such declaration. Nothing here wants the gate
	 * anyway: the only URLs that reach this are `https` ones the app built itself.
	 *
	 * The completion handler is the one place iOS says whether it worked and it runs after this
	 * function has returned, so the answer here is "a web URL went to the system" -- which is all
	 * that can honestly be said without making the whole interface suspend.
	 */
	@OptIn(ExperimentalForeignApi::class)
	override fun open(url: String): Boolean {
		val vUrl = NSURL.URLWithString(url) ?: return false
		if (vUrl.scheme?.lowercase() !in WEB_SCHEMES) return false
		UIApplication.sharedApplication.openURL(vUrl, emptyMap<Any?, Any?>(), null)
		return true
	}

	private companion object {

		/** Everything this app links to. Anything else is a bug upstream, not a link to follow. */
		val WEB_SCHEMES = setOf("http", "https")
	}
}
