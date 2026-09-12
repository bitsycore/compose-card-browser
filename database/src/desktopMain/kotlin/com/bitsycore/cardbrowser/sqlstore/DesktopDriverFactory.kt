package com.bitsycore.cardbrowser.sqlstore

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import com.bitsycore.cardbrowser.sqlstore.db.CardDatabase
import java.util.Properties

class DesktopDriverFactory : DriverFactory {

	/**
	 * Opens the database, creating or migrating it as its recorded version requires.
	 *
	 * The schema is handed to the driver rather than created directly. `Schema.create` on an
	 * existing file throws -- the tables are already there -- so the first version of this threw on
	 * every launch after the first, which is not a failure a test against an in-memory database can
	 * see.
	 */
	override fun create(path: String?): SqlDriver = JdbcSqliteDriver(
		url = if (path == null) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:$path",
		properties = DURABILITY,
		schema = CardDatabase.Schema,
	)

	override fun delete(path: String) {
		for (vSuffix in listOf("", "-wal", "-shm")) {
			java.io.File(path + vSuffix).delete()
		}
	}

	private companion object {

		/**
		 * The durability settings, on every connection this driver ever opens.
		 *
		 * Not a set of pragmas run once after opening, which is what `CardStoreFactory.verify` does
		 * and what this file relied on until it was measured. A file-backed `JdbcSqliteDriver` uses
		 * `ThreadedConnectionManager`, which opens a connection per statement and **closes it again**
		 * unless a transaction is running -- so a pragma applied at startup is applied to a
		 * connection that is gone by the next query. `PRAGMA busy_timeout=5000` read back as 3000,
		 * sqlite-jdbc's own default, which is what gave this away.
		 *
		 * As connection properties they are sqlite-jdbc's `SQLiteConfig`, applied as each connection
		 * opens, so every one of them carries the same settings. `journal_mode` is here for
		 * completeness; WAL lives in the database header and outlives any connection.
		 */
		val DURABILITY = Properties().apply {
			setProperty("journal_mode", "WAL")
			// FULL, not NORMAL: durable against the device losing power, not merely against the
			// process dying. See `CardStoreFactory`.
			setProperty("synchronous", "FULL")
			setProperty("foreign_keys", "true")
			setProperty("busy_timeout", "5000")
		}
	}
}
