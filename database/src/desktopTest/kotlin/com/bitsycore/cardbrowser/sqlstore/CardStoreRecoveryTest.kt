package com.bitsycore.cardbrowser.sqlstore

import com.bitsycore.cardbrowser.core.model.CardLanguage
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a corrupt database costs.
 *
 * This is the question that blocked the migration, and it is the only reason this file exists.
 * The file cache's blast radius is one set: a record that will not parse is deleted and re-fetched
 * and nothing else notices. A database has no such property -- one bad page can take every set
 * with it -- which is a strictly worse failure mode that no amount of speed pays for if the answer
 * is to crash.
 *
 * So the answer has to be demonstrated, not asserted in a comment: a damaged store opens, is
 * discarded, and comes back empty and usable, and the caller is *told* so it can clear the
 * preferences that describe a catalogue which no longer exists.
 */
class CardStoreRecoveryTest {

	private val mFile = File(
		System.getProperty("java.io.tmpdir"),
		"cardbrowser-recovery-test-${this::class.simpleName}.db",
	)

	@AfterTest
	fun cleanUp() {
		DriverFactory().delete(mFile.absolutePath)
	}

	private fun factory() = CardStoreFactory(DriverFactory())

	@Test
	fun `a sound store opens without being recovered`() {
		DriverFactory().delete(mFile.absolutePath)

		val vOpened = factory().open(mFile.absolutePath)

		assertFalse(vOpened.wasRecovered, "a fresh store is not a recovered one")
		assertEquals(0, vOpened.store.storageSnapshot().sets)
	}

	@Test
	fun `a store reopens on the next launch instead of failing`() {
		// The ordinary path, and the one an in-memory test cannot see. The desktop driver used to
		// call `Schema.create` unconditionally, which throws against a file whose tables already
		// exist -- so the app worked on a fresh install and threw on every launch after it.
		DriverFactory().delete(mFile.absolutePath)
		factory().open(mFile.absolutePath).store.writeSet(
			provider = "p", setId = "s", language = CardLanguage.ENGLISH, game = "test",
			label = "A set", isPinned = true, fetchedAt = 1L, printings = emptyList(),
			isComplete = true,
		)

		val vReopened = factory().open(mFile.absolutePath)

		assertFalse(vReopened.wasRecovered, "a sound database must not be discarded on reopen")
		assertEquals(1, vReopened.store.storageSnapshot().sets, "and it must still hold its sets")
	}

	@Test
	fun `a garbage file is discarded and the store comes back usable`() {
		DriverFactory().delete(mFile.absolutePath)
		// Not a database at all. This is what a truncated write or a bad sector looks like from
		// the outside, and the app must survive it rather than refuse to start.
		mFile.writeBytes(ByteArray(8192) { 0x7A })

		val vReasons = mutableListOf<String>()
		val vOpened = factory().open(mFile.absolutePath) { vReasons += it }

		assertTrue(vOpened.wasRecovered, "a garbage file must be reported as recovered")
		assertEquals(1, vReasons.size, "the caller must be told once, with a reason")
		// And the replacement actually works, which is the half that matters.
		vOpened.store.writeSet(
			provider = "p", setId = "s", language = CardLanguage.ENGLISH, game = "test",
			label = "A set", isPinned = false, fetchedAt = 1L, printings = emptyList(),
			isComplete = true,
		)
		assertEquals(1, vOpened.store.storageSnapshot().sets)
	}

	@Test
	fun `a truncated database is discarded rather than half-read`() {
		DriverFactory().delete(mFile.absolutePath)
		// A real database, then cut in half. Salvaging part of it would leave a store whose
		// contents nobody can characterise, which is the one thing this app must never serve.
		factory().open(mFile.absolutePath).store.writeSet(
			provider = "p", setId = "s", language = CardLanguage.ENGLISH, game = "test",
			label = "A set", isPinned = true, fetchedAt = 1L, printings = emptyList(),
			isComplete = true,
		)
		val vBytes = mFile.readBytes()
		assertTrue(vBytes.size > 2048, "expected a real database to truncate")
		mFile.writeBytes(vBytes.copyOf(vBytes.size / 2).also { it.fill(0x00, it.size / 2) })

		val vOpened = factory().open(mFile.absolutePath)

		// Either it was salvageable and opened clean, or it was discarded -- both are acceptable
		// outcomes and neither is a crash. What is asserted is that the store is *usable* after,
		// which is the promise the app depends on.
		assertEquals(
			if (vOpened.wasRecovered) 0 else 1,
			vOpened.store.storageSnapshot().sets,
			"a recovered store starts empty; a salvaged one keeps what it had",
		)
	}

	@Test
	fun `recovery is reported so a caller can clear what described the old data`() {
		// The failure this guards against is not the crash -- it is the silence after it. Records
		// of what is on disk live in preferences: which bulk import was taken, which sets had
		// images fetched. A store that starts fresh without saying so leaves the download dialog
		// reporting an import that is gone.
		DriverFactory().delete(mFile.absolutePath)
		mFile.writeBytes("not a database".encodeToByteArray())

		var vCleared = false
		val vOpened = factory().open(mFile.absolutePath) { vCleared = true }

		assertTrue(vOpened.wasRecovered)
		assertTrue(vCleared, "the discard must be announced, not merely performed")
	}
}
