package com.bitsycore.cardbrowser.ui

import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.data.cache.CacheUsage
import com.bitsycore.cardbrowser.ui.storage.StorageContract
import com.bitsycore.cardbrowser.ui.storage.StorageContract.Intent
import com.bitsycore.cardbrowser.ui.storage.StorageContract.KeptGame
import com.bitsycore.cardbrowser.ui.storage.StorageContract.UiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The storage screen's arithmetic, which is the part that can lie.
 *
 * Every number here is about the user's own device, so being wrong is worse than being absent: a
 * screen that says 24 MB of browsing data when 486 MB is on disk, or that quietly drops 20 MB it
 * cannot attribute, is the sort of thing this codebase treats as a defect rather than a rounding.
 */
class StorageContractTest {

	private fun usage(total: Long, kept: Long, limit: Long = 1_000_000_000) = CacheUsage(
		metadataBytes = total,
		metadataEntries = 100,
		metadataLimitBytes = limit,
		metadataKeptBytes = kept,
		imageBytes = 0,
		imageLimitBytes = limit,
	)

	private fun game(id: String, bytes: Long, sets: Int = 1) = KeptGame(
		game = GameId(id),
		displayName = id,
		sets = sets,
		bytes = bytes,
	)

	// ============
	//  The split

	@Test
	fun `browsing data excludes what is kept`() {
		// The whole point of the screen. An import of 462 MB under a 1 GB limit does not mean
		// browsing has 462 MB of its allowance spent -- the limit has no power over those bytes.
		val vUsage = usage(total = 486_000_000, kept = 462_000_000)

		assertEquals(24_000_000, vUsage.metadataBrowsingBytes)
	}

	@Test
	fun `a kept total larger than the whole never goes negative`() {
		// Both numbers are measured separately and a write can land between them.
		assertEquals(0, usage(total = 100, kept = 400).metadataBrowsingBytes)
	}

	@Test
	fun `kept bytes that cannot be attributed to a game are still shown`() {
		// A game whose set list has been evicted still has its pinned sets on disk, and they
		// cannot be named without it. Dropping them would make the rows disagree with the total.
		val vState = UiState(
			usage = usage(total = 500, kept = 400),
			kept = listOf(game("magic", 250), game("riftbound", 50)),
		)

		assertEquals(300, vState.keptBytes)
		assertEquals(100, vState.unattributedKeptBytes)
	}

	@Test
	fun `nothing unattributed when the rows account for it all`() {
		val vState = UiState(
			usage = usage(total = 500, kept = 300),
			kept = listOf(game("magic", 250), game("riftbound", 50)),
		)

		assertEquals(0, vState.unattributedKeptBytes)
	}

	@Test
	fun `rows adding to more than the measured total do not produce a negative remainder`() {
		val vState = UiState(usage = usage(total = 500, kept = 100), kept = listOf(game("magic", 250)))

		assertEquals(0, vState.unattributedKeptBytes)
	}

	// ============
	//  Ordering and flow

	@Test
	fun `the biggest thing to delete is listed first`() {
		// A storage screen is opened to reclaim space, so the row worth acting on goes at the top.
		val vState = StorageContract.reduce(
			UiState(),
			Intent.Loaded(
				usage = usage(total = 500, kept = 400),
				kept = listOf(game("small", 10), game("huge", 900), game("middling", 100)),
			),
		)

		assertEquals(listOf("huge", "middling", "small"), vState.kept.map { it.displayName })
		assertFalse(vState.isLoading)
		assertTrue(vState.hasKept)
	}

	@Test
	fun `confirming a delete closes the dialog before the work starts`() {
		// Otherwise the dialog sits over a busy screen inviting a second press of a button that
		// has already been pressed.
		var vState = StorageContract.reduce(UiState(), Intent.DeleteRequested(game("magic", 100)))
		assertTrue(vState.pendingDelete != null)

		vState = StorageContract.reduce(vState, Intent.DeleteConfirmed)

		assertNull(vState.pendingDelete)
		assertTrue(vState.isDeleting)
	}

	@Test
	fun `cancelling leaves everything alone`() {
		var vState = StorageContract.reduce(UiState(), Intent.DeleteRequested(game("magic", 100)))

		vState = StorageContract.reduce(vState, Intent.DeleteRequested(null))

		assertNull(vState.pendingDelete)
		assertFalse(vState.isDeleting)
	}

	@Test
	fun `an empty device says so rather than showing an empty heading`() {
		val vState = StorageContract.reduce(
			UiState(),
			Intent.Loaded(usage = usage(total = 500, kept = 0), kept = emptyList()),
		)

		assertFalse(vState.hasKept)
		assertEquals(0, vState.unattributedKeptBytes)
	}
}
