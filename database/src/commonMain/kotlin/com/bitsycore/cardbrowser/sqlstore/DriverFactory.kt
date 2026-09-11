package com.bitsycore.cardbrowser.sqlstore

import app.cash.sqldelight.db.SqlDriver

/**
 * Opens the database, per platform.
 *
 * ## An interface, not an `expect class`
 *
 * It was the latter, and it was the only one in the build. Two reasons it is not any more.
 *
 * Kotlin still reports `expect`/`actual` *classes* as Beta, once per target per compilation, which
 * is four identical warnings a build about a declaration that is not going to change -- and the
 * suggested remedy is a compiler flag that would also silence the next one, which nobody would have
 * thought about.
 *
 * More to the point, this project already had an answer for "this differs per platform" and it was
 * not `expect`/`actual`. `AppStorage`'s roots and `LinkOpener` are both supplied by that platform's
 * Koin module, because the composition root is the one place that knows which platform it is and
 * Android needs a `Context` it can only get there. A driver is the same kind of thing, and being
 * the one exception is worth less than being consistent.
 *
 * The iOS implementation still lives in `iosMain` and still references `NativeSqliteDriver`
 * directly, which is what forces SQLite to be linked into the framework -- the thing worth checking
 * on the one platform this project cannot run.
 *
 * @see CardStoreFactory which is what callers actually use; this only opens files.
 */
interface DriverFactory {

	/**
	 * @param path where to put the file, or `null` for an in-memory database. Desktop honours the
	 *   path; Android and iOS use their own per-app locations and ignore it
	 */
	fun create(path: String?): SqlDriver

	/**
	 * Removes the database at [path], and whatever else belongs to it.
	 *
	 * WAL means a database is up to three files -- the main one, `-wal` and `-shm` -- and deleting
	 * only the first leaves a write-ahead log that the next open will try to replay into a file
	 * that is no longer its own. All three go.
	 */
	fun delete(path: String)
}
