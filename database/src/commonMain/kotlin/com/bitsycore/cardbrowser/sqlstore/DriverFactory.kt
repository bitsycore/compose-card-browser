package com.bitsycore.cardbrowser.sqlstore

import app.cash.sqldelight.db.SqlDriver

/**
 * Opens the database, per platform.
 *
 * Exists mainly to make the iOS half of this spike real. A declared-but-unreferenced dependency
 * compiles and proves nothing; this forces `native-driver` to be linked into the iOS targets,
 * which is the risk worth measuring before anything commits to SQLite -- those targets have never
 * been linked at all, so a new native dependency lands entirely on the one platform that cannot be
 * tested here.
 *
 * @param path where to put the file, or `null` for an in-memory database. Desktop honours the
 *   path; Android and iOS use their own per-app locations and ignore it
 */
expect class DriverFactory() {

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
