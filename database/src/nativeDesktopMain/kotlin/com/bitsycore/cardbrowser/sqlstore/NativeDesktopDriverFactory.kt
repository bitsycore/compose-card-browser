package com.bitsycore.cardbrowser.sqlstore

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.SynchronousFlag
import com.bitsycore.cardbrowser.sqlstore.db.CardDatabase
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * The card store on Kotlin/Native desktop -- the same `NativeSqliteDriver` iOS uses.
 *
 * SQLiter's default location is not where `AppStorage` says the cache lives, so the directory is
 * handed over explicitly. Durability is connection configuration rather than pragmas, because
 * SQLiter pools connections and a pragma sent once configures one of them.
 */
class NativeDesktopDriverFactory : DriverFactory {

	override fun create(path: String?): SqlDriver {
		if (path == null) return NativeSqliteDriver(CardDatabase.Schema, ":memory:")
		val vPath = path.toPath()
		// SQLite creates the file, not the directory holding it.
		vPath.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
		return NativeSqliteDriver(
			schema = CardDatabase.Schema,
			name = vPath.name,
			onConfiguration = { vConfig ->
				vConfig.copy(
					journalMode = JournalMode.WAL,
					extendedConfig = vConfig.extendedConfig.copy(
						basePath = vPath.parent?.toString(),
						synchronousFlag = SynchronousFlag.FULL,
						foreignKeyConstraints = true,
						busyTimeout = BUSY_TIMEOUT_MS,
					),
				)
			},
		)
	}

	/** WAL means three files, and a missing sidecar is the ordinary case here. */
	override fun delete(path: String) {
		for (vSuffix in listOf("", "-wal", "-shm")) {
			FileSystem.SYSTEM.delete((path + vSuffix).toPath(), mustExist = false)
		}
	}

	private companion object {

		const val BUSY_TIMEOUT_MS = 5000
	}
}
