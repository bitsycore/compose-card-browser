package com.bitsycore.cardbrowser.sqlstore

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.bitsycore.cardbrowser.sqlstore.db.CardDatabase

/**
 * Android needs a `Context`, which this spike does not have and does not want.
 *
 * Left unimplemented on purpose: the Android half is not what is being measured, and wiring a
 * context through a module nothing depends on would be scaffolding for its own sake. The
 * dependency is declared so the driver is on the Android compile path.
 */
actual class DriverFactory {

	actual fun create(path: String?): SqlDriver =
		throw UnsupportedOperationException(
			"The Android driver needs a Context. See ${AndroidSqliteDriver::class.simpleName}; " +
				"this spike measures desktop and only compiles the rest.",
		)
}
