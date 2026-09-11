package com.bitsycore.cardbrowser.di

import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.platform.LinkOpener
import com.bitsycore.cardbrowser.sqlstore.DriverFactory
import java.awt.Desktop
import java.net.URI
import okio.FileSystem
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Desktop bindings.
 *
 * Storage goes under the user's home in a dotted directory rather than a per-OS application data
 * path. The desktop target is for development and manual testing -- distribution is later work --
 * and one predictable path that is easy to inspect and delete is worth more here than three
 * platform-correct ones.
 */
actual fun platformModule(): Module = module {

	single {
		val vHome = System.getProperty("user.home") ?: "."
		val vRoot = "$vHome/.cardbrowser".toPath()
		AppStorage(
			fileSystem = FileSystem.SYSTEM,
			cacheRoot = vRoot / "cache",
			preferencesRoot = vRoot / "preferences",
		).also { it.prepare() }
	}

	single { DriverFactory() }

	single<LinkOpener> { DesktopLinkOpener() }
}

/** Hands a URL to the desktop's default browser through AWT. */
private class DesktopLinkOpener : LinkOpener {

	override fun open(url: String): Boolean = try {
		// Headless machines and several Linux desktops have no Desktop support at all, hence the
		// support check rather than a bare call.
		if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
			Desktop.getDesktop().browse(URI(url))
			true
		} else {
			false
		}
	} catch (vError: Exception) {
		false
	}
}
