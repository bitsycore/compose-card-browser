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
		NativeSqliteDriver(CardDatabase.Schema, path ?: "cardbrowser-spike.db")
}
