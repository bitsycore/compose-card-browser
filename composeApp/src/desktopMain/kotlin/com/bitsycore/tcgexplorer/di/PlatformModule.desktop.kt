package com.bitsycore.tcgexplorer.di

import com.bitsycore.tcgexplorer.data.cache.AppStorage
import com.bitsycore.tcgexplorer.platform.LinkOpener
import com.bitsycore.tcgexplorer.sqlstore.DesktopDriverFactory
import com.bitsycore.tcgexplorer.sqlstore.DriverFactory
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
		val vRoot = "$vHome/.tcgexplorer".toPath()
		AppStorage(
			fileSystem = FileSystem.SYSTEM,
			cacheRoot = vRoot / "cache",
			preferencesRoot = vRoot / "preferences",
		).also { it.prepare() }
	}

	single<DriverFactory> { DesktopDriverFactory() }

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
