package com.bitsycore.cardbrowser.di

import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.platform.LinkOpener
import com.bitsycore.cardbrowser.sqlstore.DriverFactory
import com.bitsycore.cardbrowser.sqlstore.NativeDesktopDriverFactory
import kotlin.experimental.ExperimentalNativeApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import okio.FileSystem
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.posix.getenv
import platform.posix.system

/**
 * Native desktop bindings.
 *
 * Storage keeps the JVM desktop's layout on purpose, so both desktop builds read one cache.
 */
actual fun platformModule(): Module = module {

	single {
		val vRoot = "${homeDirectory()}/.cardbrowser".toPath()
		AppStorage(
			fileSystem = FileSystem.SYSTEM,
			cacheRoot = vRoot / "cache",
			preferencesRoot = vRoot / "preferences",
		).also { it.prepare() }
	}

	single<DriverFactory> { NativeDesktopDriverFactory() }

	single<LinkOpener> { NativeDesktopLinkOpener() }
}

/** `HOME` on Unix; Windows sets `USERPROFILE` and only defines `HOME` under MSYS. */
@OptIn(ExperimentalForeignApi::class)
private fun homeDirectory(): String =
	getenv("HOME")?.toKString()?.takeIf { it.isNotBlank() }
		?: getenv("USERPROFILE")?.toKString()?.takeIf { it.isNotBlank() }
		?: "."

/**
 * Hands a URL to whatever this system calls a browser opener.
 *
 * A URL carrying anything a shell would read as more than text is refused rather than escaped --
 * the consumer is `system()`, and card names are provider-supplied. `false` is already what this
 * interface says for "could not open it".
 */
private class NativeDesktopLinkOpener : LinkOpener {

	@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
	override fun open(url: String): Boolean {
		if (!isSafe(url)) return false
		val vCommand = when (Platform.osFamily) {
			OsFamily.WINDOWS -> """start "" "$url""""
			OsFamily.MACOSX -> """open "$url""""
			OsFamily.LINUX -> """xdg-open "$url""""
			else -> return false
		}
		return system(vCommand) == 0
	}

	private fun isSafe(url: String): Boolean =
		(url.startsWith("https://") || url.startsWith("http://")) &&
			url.none { it.isWhitespace() || it in UNSAFE }

	private companion object {

		val UNSAFE = charArrayOf('"', '\'', '`', '$', '\\', '&', '|', ';', '<', '>', '(', ')', '\n')
	}
}
