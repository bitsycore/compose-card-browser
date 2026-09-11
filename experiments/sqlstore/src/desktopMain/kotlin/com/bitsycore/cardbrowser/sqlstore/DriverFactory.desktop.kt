package com.bitsycore.cardbrowser.sqlstore

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bitsycore.cardbrowser.sqlstore.db.CardDatabase

actual class DriverFactory {

	actual fun create(path: String?): SqlDriver {
		val vDriver = JdbcSqliteDriver(
			if (path == null) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:$path",
		)
		CardDatabase.Schema.create(vDriver)
		return vDriver
	}
}
