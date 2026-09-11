package com.bitsycore.cardbrowser.sqlstore

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.bitsycore.cardbrowser.sqlstore.db.CardDatabase

/**
 * The half of this spike that carries the real risk.
 *
 * `NativeSqliteDriver` links SQLite into the framework. That is the dependency a migration would
 * add to targets this project has compiled but never linked or run, and referencing it here is
 * what makes the ordinary build prove at least that it resolves and compiles.
 */
actual class DriverFactory {

	actual fun create(path: String?): SqlDriver =
		NativeSqliteDriver(CardDatabase.Schema, path ?: "cardbrowser.db")

	// `removeItemAtPath` takes an error out-parameter, which is cinterop and therefore opt-in.
	// Errors are ignored on purpose: this is called to clear a database already established as
	// unusable, and a file that will not delete is no worse than one that will not open.
	@kotlin.OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
	actual fun delete(path: String) {
		val vManager = platform.Foundation.NSFileManager.defaultManager
		for (vSuffix in listOf("", "-wal", "-shm")) {
			vManager.removeItemAtPath(path + vSuffix, null)
		}
	}
}
