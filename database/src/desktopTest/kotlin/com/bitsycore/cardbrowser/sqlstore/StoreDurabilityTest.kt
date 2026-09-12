package com.bitsycore.cardbrowser.sqlstore

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The durability pragmas actually took effect, rather than merely having been sent.
 *
 * ## Why "sent" was not enough
 *
 * The app crashed on its first launch on a phone, on an empty database, because
 * `PRAGMA journal_mode=WAL` was run through `execute` -- which on Android is
 * `SQLiteStatement.executeUpdateDelete`, and the framework refuses any statement that returns rows.
 * `journal_mode` returns one. So did `busy_timeout`. The JVM driver does not mind either form,
 * which is exactly why nothing here failed.
 *
 * A test that the pragma was *issued* would have passed then too. This one reads the values back
 * off the same connection, which is the only way to tell a pragma that ran from one that did not --
 * and a `Cursor` is lazy, so a query whose row is never read is a pragma that quietly did nothing.
 */
class StoreDurabilityTest {

	private val mFile = File(
		System.getProperty("java.io.tmpdir"),
		"cardbrowser-durability-test.db",
	)

	@AfterTest
	fun cleanUp() {
		DesktopDriverFactory().delete(mFile.absolutePath)
	}

	@Test
	fun `opening a store leaves its connection durable`() {
		DesktopDriverFactory().delete(mFile.absolutePath)
		val vDriver = DesktopDriverFactory().create(mFile.absolutePath)
		try {
			CardStoreFactory(DesktopDriverFactory()).verify(vDriver)

			// Every one of these is read back off the connection `verify` configured. Per-connection
			// settings, so there is nowhere else to look.
			assertEquals("wal", vDriver.ask("PRAGMA journal_mode"), "WAL is what survives a kill")
			assertEquals("2", vDriver.ask("PRAGMA synchronous"), "2 is FULL -- NORMAL is not power-safe")
			assertEquals("5000", vDriver.ask("PRAGMA busy_timeout"), "a second writer waits")
			assertEquals("1", vDriver.ask("PRAGMA foreign_keys"), "printings cannot outlive their set")
		} finally {
			vDriver.close()
		}
	}

	@Test
	fun `WAL is on the file, so the next launch inherits it`() {
		// journal_mode is recorded in the database header rather than per connection, so a second
		// connection reading "wal" is the file itself saying so.
		DesktopDriverFactory().delete(mFile.absolutePath)
		CardStoreFactory(DesktopDriverFactory()).open(mFile.absolutePath)

		val vNext = DesktopDriverFactory().create(mFile.absolutePath)
		try {
			assertEquals("wal", vNext.ask("PRAGMA journal_mode"))
		} finally {
			vNext.close()
		}
	}

	/** Reads a pragma's value. The test's own copy, so it cannot pass by sharing a bug with one. */
	private fun SqlDriver.ask(sql: String): String? = executeQuery(
		identifier = null,
		sql = sql,
		mapper = { vCursor ->
			QueryResult.Value(if (vCursor.next().value) vCursor.getString(0) else null)
		},
		parameters = 0,
	).value
}
