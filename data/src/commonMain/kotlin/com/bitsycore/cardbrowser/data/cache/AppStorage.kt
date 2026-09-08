package com.bitsycore.cardbrowser.data.cache

import okio.FileSystem
import okio.Path

/**
 * Where this app is allowed to write, and through which file system.
 *
 * Supplied per platform by that platform's Koin module rather than by an expect/actual, because the
 * only thing that differs is a directory and Android needs a `Context` to name it. Tests bind an
 * Okio `FakeFileSystem` here and exercise the real cache code with no disk at all.
 *
 * The two roots are separate on purpose and the separation is load-bearing:
 *
 * - [cacheRoot] holds disposable things. Clearing it costs a re-download and nothing else. On
 *   Android and iOS this is the OS cache directory, which the system may evict under pressure.
 * - [preferencesRoot] holds what the user chose -- last set, filters, language preference. Losing
 *   it is a visible regression, so it never lives under a directory anything is allowed to purge
 *   and "clear cache" never touches it.
 */
class AppStorage(
	val fileSystem: FileSystem,
	val cacheRoot: Path,
	val preferencesRoot: Path,
) {

	/** Metadata cache: JSON records of sets and cards. Bounded and evicted separately from images. */
	val metadataCacheDir: Path get() = cacheRoot / METADATA_DIR

	/** Image cache: the bytes Coil stores. Bounded and evicted separately from metadata. */
	val imageCacheDir: Path get() = cacheRoot / IMAGE_DIR

	/** Creates every directory the app writes to. Safe to call repeatedly. */
	fun prepare() {
		fileSystem.createDirectories(metadataCacheDir)
		fileSystem.createDirectories(imageCacheDir)
		fileSystem.createDirectories(preferencesRoot)
	}

	companion object {

		const val METADATA_DIR: String = "metadata"
		const val IMAGE_DIR: String = "images"
	}
}
