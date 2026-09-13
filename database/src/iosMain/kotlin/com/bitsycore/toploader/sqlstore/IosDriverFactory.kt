package com.bitsycore.toploader.sqlstore

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.bitsycore.toploader.sqlstore.db.CardDatabase

/**
 * iOS's driver.
 *
 * `NativeSqliteDriver` links SQLite into the framework, which is the dependency this port adds to a
 * target the project compiles on every build.
 */
class IosDriverFactory : DriverFactory {

	/**
	 * Opens the database at [path], split into the name and directory SQLiter wants.
	 *
	 * ## SQLiter takes a name, not a path
	 *
	 * It rejects one containing a separator, in `DatabaseConfiguration`'s `init` -- before
	 * `onConfiguration` ever runs, so the name handed to the constructor has to be clean already.
	 * Passing the path straight through crashed the app on a device with *"File
	 * .../Documents/preferences/cards.db contains a path separator"*.
	 *
	 * The directory goes in `basePath`, and that is not only cosmetic: with no `basePath` SQLiter
	 * puts the file under Application Support/databases, which is not where [delete] looks. The
	 * recovery path in `CardStoreFactory.open` would then delete nothing, reopen the same damaged
	 * file and throw again on a database it was told to discard.
	 *
	 * A path with no separator is already a bare name, so SQLiter's own location applies. The
	 * directory must exist; `AppStorage.prepare` creates it.
	 */
	override fun create(path: String?): SqlDriver {
		if (path == null) {
			return NativeSqliteDriver(
				schema = CardDatabase.Schema,
				name = IN_MEMORY_NAME,
				onConfiguration = { it.copy(inMemory = true) },
			)
		}
		val vCut = path.lastIndexOf('/')
		val vName = path.substring(vCut + 1)
		// An empty prefix means the path was rooted, so the directory is the root itself.
		val vDirectory = if (vCut < 0) null else path.substring(0, vCut).ifEmpty { "/" }
		return NativeSqliteDriver(
			schema = CardDatabase.Schema,
			name = vName,
			onConfiguration = { it.copy(extendedConfig = it.extendedConfig.copy(basePath = vDirectory)) },
		)
	}

	// `removeItemAtPath` takes an error out-parameter, which is cinterop and therefore opt-in.
	// Errors are ignored on purpose: this is called to clear a database already established as
	// unusable, and a file that will not delete is no worse than one that will not open.
	@kotlin.OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
	override fun delete(path: String) {
		val vManager = platform.Foundation.NSFileManager.defaultManager
		for (vSuffix in listOf("", "-wal", "-shm")) {
			vManager.removeItemAtPath(path + vSuffix, null)
		}
	}

	private companion object {

		/**
		 * A name for the in-memory database, which SQLiter still wants one of.
		 *
		 * `inMemory` with a name opens `file:<name>?mode=memory&cache=shared`, so every connection
		 * the driver pools sees the same database. A null name would give `:memory:`, which is
		 * per-connection and would read back empty.
		 */
		const val IN_MEMORY_NAME = "toploader"
	}
}
