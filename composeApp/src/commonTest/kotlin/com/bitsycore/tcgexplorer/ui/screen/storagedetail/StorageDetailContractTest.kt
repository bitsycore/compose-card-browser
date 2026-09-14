package com.bitsycore.tcgexplorer.ui.screen.storagedetail

import com.bitsycore.tcgexplorer.core.model.CardLanguage
import com.bitsycore.tcgexplorer.core.model.GameId
import com.bitsycore.tcgexplorer.data.repository.KeptSet
import com.bitsycore.tcgexplorer.ui.screen.storagedetail.StorageDetailContract.Intent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
The counting rules on this screen, which are the part most likely to go quietly wrong.

This project has shipped a wrong ratio three times, each time because the two numbers either side
of a separator counted different populations. The same trap is here: a set held in two languages is
two rows, one set, and two card totals.
*/
class StorageDetailContractTest {

	@Test
	fun `a set held in two languages is one set and two editions`() {
		val vState = state(
			KeptSet("p", "s1", "en", "Set One", cardCount = 100, bytes = 1000),
			KeptSet("p", "s1", "fr", "Set One", cardCount = 100, bytes = 1000),
			KeptSet("p", "s2", "en", "Set Two", cardCount = 50, bytes = 500),
		)

		assertEquals(2, vState.setCount, "s1 in two languages is one set")
		assertEquals(3, vState.sets.size, "and three editions")
		assertEquals(250, vState.totalCards)
	}

	@Test
	fun `the headline counts cards against the editions they belong to`() {
		// "2 sets - 250 cards" would be false: those two sets hold 150 cards between them, and the
		// other 100 are the French printing of one of them.
		val vState = state(
			KeptSet("p", "s1", "en", "Set One", cardCount = 100, bytes = 1000),
			KeptSet("p", "s1", "fr", "Set One", cardCount = 100, bytes = 1000),
			KeptSet("p", "s2", "en", "Set Two", cardCount = 50, bytes = 500),
		)

		assertEquals("3 editions of 2 sets · 250 cards", vState.summary)
	}

	@Test
	fun `one language says sets -- every set is its only edition`() {
		val vState = state(
			KeptSet("p", "s1", "en", "Set One", cardCount = 100, bytes = 1000),
			KeptSet("p", "s2", "en", "Set Two", cardCount = 50, bytes = 500),
		)

		assertEquals("2 sets · 150 cards", vState.summary)
		assertTrue(!vState.hasSeveralLanguages)
	}

	@Test
	fun `a count of one is not plural`() {
		val vState = state(KeptSet("p", "s1", "en", "Set One", cardCount = 1, bytes = 10))

		assertEquals("1 set · 1 card", vState.summary)
	}

	@Test
	fun `a set stored under no language is its own group and says so`() {
		// OPTCG states no language. Calling that English would be a claim its source never made.
		val vState = state(KeptSet("p", "s1", "-", "Set One", cardCount = 10, bytes = 100))

		val vGroup = vState.byLanguage.single()
		assertNull(vGroup.language)
		assertEquals("No language stated", vGroup.displayName)
	}

	@Test
	fun `sets are ordered by code rather than by name`() {
		// The code leads the row now, so "OP-02, OP-01" reads as a mistake even though the names
		// are alphabetical.
		val vState = state(
			KeptSet("p", "s2", "en", "Paramount War", "OP-02", 1, 1),
			KeptSet("p", "s1", "en", "Romance Dawn", "OP-01", 1, 1),
		)

		assertEquals(listOf("OP-01", "OP-02"), vState.byLanguage.single().sets.map { it.code })
	}

	@Test
	fun `a set with no code sorts after the ones that have one`() {
		val vState = state(
			KeptSet("p", "s2", "en", "Anonymous", null, 1, 1),
			KeptSet("p", "s1", "en", "Romance Dawn", "OP-01", 1, 1),
		)

		assertEquals(
			listOf("OP-01", null),
			vState.byLanguage.single().sets.map { it.code },
		)
	}

	@Test
	fun `groups are heaviest first`() {
		val vState = state(
			KeptSet("p", "s1", "en", "Set One", cardCount = 10, bytes = 100),
			KeptSet("p", "s1", "fr", "Set One", cardCount = 10, bytes = 900),
		)

		assertEquals(
			listOf(CardLanguage.FRENCH, CardLanguage.ENGLISH),
			vState.byLanguage.map { it.language },
		)
	}

	@Test
	fun `confirming carries its target rather than reading the closed dialog back`() {
		// The reducer clears `pendingDelete` before the view model runs -- see
		// `PulseDispatchOrderTest`. Reading it there is how the storage screen's delete became a
		// no-op that greyed every icon on the screen.
		val vSet = KeptSet("p", "s1", "en", "Set One", cardCount = 10, bytes = 100)
		val vTarget = StorageDetailContract.Target.OneSet(vSet)
		var vState = StorageDetailContract.reduce(state(vSet), Intent.DeleteRequested(vTarget))
		assertEquals(vTarget, vState.pendingDelete)

		vState = StorageDetailContract.reduce(vState, Intent.DeleteConfirmed(vTarget))

		assertNull(vState.pendingDelete)
		assertTrue(vState.isDeleting)
	}

	private fun state(vararg sets: KeptSet) = StorageDetailContract.UiState(
		game = GameId("test"),
		displayName = "Test",
		sets = sets.toList(),
		isLoading = false,
	)
}
