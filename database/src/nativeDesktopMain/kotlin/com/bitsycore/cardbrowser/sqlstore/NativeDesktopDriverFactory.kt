package com.bitsycore.cardbrowser.sqlstore

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.SynchronousFlag
import com.bitsycore.cardbrowser.sqlstore.db.CardDatabase
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * The card store on Kotlin/Native desktop -- Windows, Linux and macOS without a JVM.
 *
 * The same `NativeSqliteDriver` iOS uses, which is what makes this a class rather than a research
 * problem: SQLDelight publishes `native-driver` for `mingwX64`, `linuxX64`, `linuxArm64` and
 * `macosArm64`, so SQLite is linked into the binary exactly as it is into the iOS framework.
 *
 * What differs from iOS is only the path handling. `NativeSqliteDriver` takes a *name* and puts the
 * file where the platform's default is, which on a desktop is not where `AppStorage` says the cache
 * root is -- and two opinions about where the database lives is one too many, the same reason
 * `AndroidDriverFactory` records. So the directory is handed over explicitly.
 *
 * **Not run.** These targets compile; nothing here has been executed, because `:composeApp` could
 * not link until now and no native binary has been produced on this machine. See
 * docs/NATIVE_DESKTOP.md.
 */
class NativeDesktopDriverFactory : DriverFactory {

	override fun create(path: String?): SqlDriver {
		if (path == null) return NativeSqliteDriver(CardDatabase.Schema, ":memory:")
		val vPath = path.toPath()
		// The parent has to exist: SQLite creates a file, not the directory holding it.
		vPath.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
		return NativeSqliteDriver(
			schema = CardDatabase.Schema,
			name = vPath.name,
			// Durability set here rather than only as pragmas, for the reason `DesktopDriverFactory`
			// records: SQLiter keeps a pool, and a pragma sent once configures one connection of it.
			// These are applied to each connection as it opens.
			onConfiguration = { vConfig ->
				vConfig.copy(
					journalMode = JournalMode.WAL,
					// FULL, not NORMAL: durable against the machine losing power rather than only
					// against the process dying. See `CardStoreFactory`.
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

	override fun delete(path: String) {
		// WAL means three files. `mustExist = false` because this runs on a database already
		// established as unusable, where a missing sidecar is the ordinary case rather than a fault.
		for (vSuffix in listOf("", "-wal", "-shm")) {
			FileSystem.SYSTEM.delete((path + vSuffix).toPath(), mustExist = false)
		}
	}

	private companion object {

		/** The same wait `CardStoreFactory` asks for: a second writer waits rather than failing. */
		const val BUSY_TIMEOUT_MS = 5000
	}
}
