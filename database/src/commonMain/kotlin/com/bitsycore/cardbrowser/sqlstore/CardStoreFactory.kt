package com.bitsycore.cardbrowser.sqlstore

import app.cash.sqldelight.db.SqlDriver

/**
 * How a store was opened, so a caller can tell a fresh database from a recovered one.
 *
 * @property wasRecovered true when the previous database could not be opened or failed its
 *   integrity check and was discarded. The app has to *act* on this, not merely log it: every
 *   record of what is on disk -- `bulkImports`, image-download records -- describes a catalogue
 *   that no longer exists, and leaving them would have the app claim sets it cannot show
 */
data class OpenedStore(val store: SqlCardStore, val wasRecovered: Boolean)

/**
 * Opens the card store, and answers the question that blocked this migration.
 *
 * ## What a corrupt database costs, and why that was the hard part
 *
 * The file cache's blast radius is one set: a record that will not parse is deleted and re-fetched,
 * and nothing else notices. A database has no such property -- one corrupt page can make the whole
 * store unreadable, and *every* set goes with it. That is a strictly worse failure mode and no
 * amount of speed pays for it if it is handled by crashing.
 *
 * So it is handled here, and the handling is deliberately blunt:
 *
 * 1. **Open, then verify.** `PRAGMA integrity_check` on open. It is not free -- it reads the whole
 *    file -- which is why it runs once per process at startup and never again.
 * 2. **A store that will not open, or will not verify, is deleted and recreated.** Not repaired.
 *    Everything in it is re-fetchable from the network by definition; a half-salvaged database is
 *    a store whose contents nobody can characterise, and this app's whole discipline is not
 *    serving data it cannot vouch for.
 * 3. **The caller is told.** [OpenedStore.wasRecovered] exists so the app can clear the
 *    preferences that describe what was on disk. Silently starting fresh would leave the download
 *    dialog reporting an import that is gone and the storage screen naming sets it cannot open.
 *
 * ## Why WAL
 *
 * `journal_mode=WAL` is what makes an interrupted write not corrupt a good record -- the property
 * per-file atomic replacement gave for free. A process killed mid-transaction leaves the last
 * transaction unapplied and everything before it intact, which is the same guarantee the file
 * cache made, at database granularity instead of per file.
 */
class CardStoreFactory(private val mDriverFactory: DriverFactory) {

	/**
	 * Opens [path], recreating it if it is unusable.
	 *
	 * @param onDiscard called with the reason before a damaged store is deleted, so a caller can
	 *   record what happened rather than discovering an empty cache and guessing
	 */
	fun open(path: String, onDiscard: (String) -> Unit = {}): OpenedStore {
		val vFirst = runCatching { openVerified(path) }
		vFirst.getOrNull()?.let { return OpenedStore(it, wasRecovered = false) }

		onDiscard(vFirst.exceptionOrNull()?.message ?: "the card store could not be opened")
		mDriverFactory.delete(path)

		// Second attempt on a clean file. A failure here is not recoverable by any means this
		// class has -- the disk is unwritable or the platform has no SQLite -- so it propagates
		// rather than being swallowed into a store that silently holds nothing.
		return OpenedStore(openVerified(path), wasRecovered = true)
	}

	private fun openVerified(path: String): SqlCardStore {
		val vDriver = mDriverFactory.create(path)
		val vFailure = runCatching { verify(vDriver) }.exceptionOrNull()
		if (vFailure != null) {
			runCatching { vDriver.close() }
			throw vFailure
		}
		return SqlCardStore(vDriver)
	}

	/**
	 * `PRAGMA integrity_check`, and WAL while we are here.
	 *
	 * The check reads every page, so it is the one slow thing at startup -- and it is the only
	 * thing that turns "this file is damaged" into a fact before the app has built a screen on
	 * top of it. A truncated or garbage file usually fails at `create` instead, which the caller
	 * above treats identically.
	 */
	private fun verify(driver: SqlDriver) {
		// Prevention, in the order it matters. Recovery below is the net; these are the reasons it
		// should rarely be needed.
		//
		// WAL: a process killed mid-transaction leaves that transaction unapplied and everything
		// before it intact. This is the property per-file atomic replacement gave for free, and
		// losing it was the strongest argument against migrating at all.
		driver.execute(null, "PRAGMA journal_mode=WAL", 0)
		// FULL, not NORMAL. Under WAL, `synchronous=NORMAL` does not fsync on commit -- it is
		// durable against a process crash but *not* against the device losing power, which on a
		// phone is an ordinary Tuesday rather than an edge case. The cost is paid per transaction,
		// and a transaction here is a whole set, not a card.
		driver.execute(null, "PRAGMA synchronous=FULL", 0)
		// A second writer waits instead of failing. The download queue runs one job at a time, but
		// the storage screen reads while it does, and "database is locked" surfacing as a failed
		// download would be a bug with no cause a user could see.
		driver.execute(null, "PRAGMA busy_timeout=5000", 0)
		// Rows cannot outlive the set that owns them. Eviction deletes both in one transaction, so
		// this is a belt on a brace -- but an orphaned printing is invisible: it would answer a
		// cross-set search from a set the app would say it does not have.
		driver.execute(null, "PRAGMA foreign_keys=ON", 0)
		val vResult = driver.executeQuery(
			identifier = null,
			sql = "PRAGMA integrity_check",
			mapper = { vCursor ->
				app.cash.sqldelight.db.QueryResult.Value(
					if (vCursor.next().value) vCursor.getString(0) else null,
				)
			},
			parameters = 0,
		).value
		// SQLite answers the single string "ok" for a sound database and a list of problems
		// otherwise. Anything that is not "ok" -- including nothing at all -- is a discard.
		if (vResult != "ok") {
			throw IllegalStateException("integrity_check said: ${vResult ?: "nothing"}")
		}
	}
}
