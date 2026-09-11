package com.bitsycore.cardbrowser.sqlstore

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import com.bitsycore.cardbrowser.sqlstore.db.CardDatabase
import java.util.Properties

actual class DriverFactory {

	/**
	 * Opens the database, creating or migrating it as its recorded version requires.
	 *
	 * The schema is handed to the driver rather than created directly. `Schema.create` on an
	 * existing file throws -- the tables are already there -- so the first version of this threw on
	 * every launch after the first, which is not a failure a test against an in-memory database can
	 * see.
	 */
	actual fun create(path: String?): SqlDriver = JdbcSqliteDriver(
		url = if (path == null) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:$path",
		properties = Properties(),
		schema = CardDatabase.Schema,
	)

	actual fun delete(path: String) {
		for (vSuffix in listOf("", "-wal", "-shm")) {
			java.io.File(path + vSuffix).delete()
		}
	}
}
