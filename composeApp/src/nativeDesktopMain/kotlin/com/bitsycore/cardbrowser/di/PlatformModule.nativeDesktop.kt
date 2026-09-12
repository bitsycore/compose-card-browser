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
 * Kotlin/Native desktop bindings -- the same three things the JVM desktop module supplies, without
 * a JVM to supply them.
 *
 * Storage keeps the JVM desktop's layout deliberately: one dotted directory under the user's home,
 * rather than three platform-correct application-data paths. The reason is the one recorded there
 * -- this target is for development, and one predictable path that is easy to inspect and delete is
 * worth more than being right about `%LOCALAPPDATA%`. It also means the two desktop builds read the
 * same cache, which is what makes comparing them possible at all.
 *
 * **Not run.** These targets compile and link; no binary has been started. See
 * docs/NATIVE_DESKTOP.md.
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

/**
 * The user's home directory.
 *
 * `HOME` on Unix; Windows sets `USERPROFILE` instead and only defines `HOME` under MSYS or Cygwin,
 * so both are asked for. A process with neither falls back to the working directory, which is what
 * the JVM module does when `user.home` is missing.
 */
@OptIn(ExperimentalForeignApi::class)
private fun homeDirectory(): String =
	getenv("HOME")?.toKString()?.takeIf { it.isNotBlank() }
		?: getenv("USERPROFILE")?.toKString()?.takeIf { it.isNotBlank() }
		?: "."

/**
 * Hands a URL to the desktop's browser, through whatever this OS calls that.
 *
 * There is no AWT here and no cross-platform API underneath: each system has its own opener, and
 * which one to call is decided from `Platform.osFamily` rather than from a source set, because
 * these four targets share one.
 *
 * The URL is checked before it reaches a shell. Every URL this app opens is built by
 * `CardmarketLinkBuilder` or comes from a provider record, so none of them should ever contain a
 * quote or a control character -- but "should" is not a guarantee when the consumer is `system()`,
 * and a card name is provider-supplied text. A URL that fails the check is refused rather than
 * escaped: `false` is already what this interface says for "could not open it", and the callers
 * handle it.
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

	/** An http(s) URL with nothing in it that a shell would read as anything but text. */
	private fun isSafe(url: String): Boolean =
		(url.startsWith("https://") || url.startsWith("http://")) &&
			url.none { it.isWhitespace() || it in UNSAFE }

	private companion object {

		/** Quoting, substitution and chaining -- everything that would end the argument. */
		val UNSAFE = charArrayOf('"', '\'', '`', '$', '\\', '&', '|', ';', '<', '>', '(', ')', '\n')
	}
}
