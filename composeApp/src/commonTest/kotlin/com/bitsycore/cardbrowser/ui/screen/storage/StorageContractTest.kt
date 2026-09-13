package com.bitsycore.cardbrowser.ui.screen.storage

import com.bitsycore.cardbrowser.data.repository.Completion
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.data.cache.CacheUsage
import com.bitsycore.cardbrowser.ui.screen.storage.StorageContract
import com.bitsycore.cardbrowser.ui.screen.storage.StorageContract.Intent
import com.bitsycore.cardbrowser.ui.screen.storage.StorageContract.KeptGame
import com.bitsycore.cardbrowser.ui.screen.storage.StorageContract.UiState
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
		// The whole point of the screen. "Clear browsed sets" must not offer to reclaim the
		// 462 MB of an import it will not touch.
		val vUsage = usage(total = 486_000_000, kept = 462_000_000)

		assertEquals(24_000_000, vUsage.metadataBrowsingBytes)
	}

	@Test
	fun `a kept total larger than the whole never goes negative`() {
		// Both numbers are measured separately and a write can land between them.
		assertEquals(0, usage(total = 100, kept = 400).metadataBrowsingBytes)
	}

	@Test
	fun `card data no row can account for is still shown`() {
		// Two things land here: set lists and card detail, which belong to no one game's row, and
		// sets held for a game whose own set list is gone -- without it they cannot be named.
		// Dropping either would make the rows disagree with the heading.
		val vState = UiState(
			usage = usage(total = 500, kept = 400),
			kept = listOf(game("magic", 250), game("riftbound", 50)),
		)

		assertEquals(300, vState.keptBytes)
		assertEquals(200, vState.unattributedKeptBytes)
		assertEquals(
			500,
			vState.keptBytes + vState.unattributedKeptBytes,
			"the parts must add up to what was measured",
		)
	}

	@Test
	fun `nothing unattributed when the rows account for it all`() {
		val vState = UiState(
			usage = usage(total = 300, kept = 300),
			kept = listOf(game("magic", 250), game("riftbound", 50)),
		)

		assertEquals(0, vState.unattributedKeptBytes)
	}

	@Test
	fun `rows adding to more than the measured total do not produce a negative remainder`() {
		// Both numbers are measured separately and a write can land between them.
		val vState = UiState(usage = usage(total = 100, kept = 100), kept = listOf(game("magic", 250)))

		assertEquals(0, vState.unattributedKeptBytes)
	}

	// ============
	//  What a row says

	@Test
	fun `a row says how much of the game is downloaded -- and of what`() {
		// The reported gap: a row said "2 sets · 21.0 MB" and left both real questions
		// unanswered -- how much of the game, and whether that is card info or pictures.
		val vGame = KeptGame(
			game = GameId("riftbound"),
			displayName = "Riftbound",
			sets = 2,
			bytes = 21_000_000,
			knownSets = 8,
			thumbnailSets = 2,
		)

		assertEquals("2/8 sets", vGame.summary, "pictures are said separately now, in their own unit")
		assertEquals("2/8 sets", vGame.artSummary)
	}

	@Test
	fun `a denominator nobody can supply is not invented`() {
		// `knownSets` goes missing exactly when the cache has been cleared, and "2/?" is worse
		// than "2".
		val vGame = KeptGame(
			game = GameId("riftbound"),
			displayName = "Riftbound",
			sets = 2,
			bytes = 21_000_000,
			knownSets = null,
		)

		assertEquals("2 sets", vGame.summary)
	}

	@Test
	fun `cards and pictures are measured in different units and each says which`() {
		// The percentage is a fraction of the game's *cards*; pictures are recorded one marker per
		// *set*, so they are counted in sets. Printing both as bare percentages would put two
		// differently counted numbers side by side, which is this project's oldest mistake.
		val vGame = KeptGame(
			game = GameId("riftbound"),
			displayName = "Riftbound",
			sets = 2,
			bytes = 21_000_000,
			knownSets = 8,
			thumbnailSets = 2,
			completion = Completion(heldCards = 376, totalCards = 1451, isEstimate = false),
		)

		assertEquals("25%", vGame.completion?.label, "376 of 1451 is 25.9%, and rounding is down")
		assertEquals("2/8 sets", vGame.artSummary, "pictures name their unit")
		assertEquals(0.25f, vGame.artFraction)
	}

	@Test
	fun `a game with no pictures says nothing about them`() {
		// Not "0/8 sets". Nobody asked for art here, so there is nothing to report.
		val vGame = KeptGame(GameId("g"), "G", sets = 2, bytes = 1, knownSets = 8)

		assertNull(vGame.artSummary)
		assertNull(vGame.artFraction)
	}

	@Test
	fun `pictures with no known set count are shown without a denominator`() {
		val vGame = KeptGame(GameId("g"), "G", sets = 2, bytes = 1, thumbnailSets = 2)

		assertEquals("2 sets", vGame.artSummary)
		assertNull(vGame.artFraction, "no denominator, no bar")
	}

	@Test
	fun `pictures are mentioned only when there are some`() {
		val vGame = KeptGame(
			game = GameId("magic"),
			displayName = "Magic",
			sets = 988,
			bytes = 441_000_000,
			knownSets = 988,
			thumbnailSets = 0,
		)

		assertEquals("988/988 sets", vGame.summary)
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

		vState = StorageContract.reduce(vState, Intent.DeleteConfirmed(vState.pendingDelete!!))

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
			Intent.Loaded(usage = usage(total = 0, kept = 0), kept = emptyList()),
		)

		assertFalse(vState.hasKept)
		assertEquals(0, vState.unattributedKeptBytes)
	}
}
