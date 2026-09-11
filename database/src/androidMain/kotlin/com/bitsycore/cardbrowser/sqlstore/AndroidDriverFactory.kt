package com.bitsycore.cardbrowser.sqlstore

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.bitsycore.cardbrowser.sqlstore.db.CardDatabase
import java.io.File

/**
 * Android's driver needs a `Context`, which is the one platform difference this class exists for.
 *
 * The context is supplied at construction rather than looked up, so nothing here reaches for a
 * static application instance -- the composition root already has one and hands it over.
 *
 * The path is honoured rather than ignored: `AndroidSqliteDriver`'s name-based constructor puts
 * the file where the framework wants it, which is not where `AppStorage` says the cache root is,
 * and two opinions about where the database lives is one too many.
 */
class AndroidDriverFactory(private val mContext: Context) : DriverFactory {

	override fun create(path: String?): SqlDriver = AndroidSqliteDriver(
		schema = CardDatabase.Schema,
		context = mContext,
		// A null path means in-memory, which is what `AndroidSqliteDriver` does for a null name.
		name = path?.let { File(it).name },
	)

	override fun delete(path: String) {
		for (vSuffix in listOf("", "-wal", "-shm")) {
			File(path + vSuffix).delete()
			// And the framework's own location, which is where `create` actually put it.
			runCatching { mContext.deleteDatabase(File(path).name + vSuffix) }
		}
	}
}
