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

	/**
	 * The card store's database file.
	 *
	 * Under [preferencesRoot], not [cacheRoot], and that is the whole reason this property exists
	 * rather than a path built at the call site. The store holds downloaded sets -- the things a
	 * user explicitly asked to keep, and on a phone the things that took twenty minutes to import --
	 * so it must not sit in a directory Android and iOS are free to purge whenever they want the
	 * space back. Browsing records in it are evictable by our own budget, which is a decision this
	 * app makes rather than one the OS makes for it.
	 *
	 * Android and iOS ignore the path and use their own per-app database location, which has the
	 * same property. Desktop honours it.
	 */
	val databaseFile: Path get() = preferencesRoot / DATABASE_FILE

	/** Creates every directory the app writes to. Safe to call repeatedly. */
	fun prepare() {
		fileSystem.createDirectories(metadataCacheDir)
		fileSystem.createDirectories(imageCacheDir)
		fileSystem.createDirectories(preferencesRoot)
	}

	companion object {

		const val METADATA_DIR: String = "metadata"
		const val IMAGE_DIR: String = "images"
		const val DATABASE_FILE: String = "cards.db"
	}
}
