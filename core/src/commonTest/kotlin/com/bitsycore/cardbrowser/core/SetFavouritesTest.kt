package com.bitsycore.cardbrowser.core

import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SetFavourites
import com.bitsycore.cardbrowser.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pinned sets: which ones, in what order, and what happens when the two lists disagree.
 *
 * The disagreements are the interesting part, because favourites are stored for every game at once
 * while a set list only ever shows one game's.
 */
class SetFavouritesTest {

	private val mProvider = ProviderId("test")

	private fun set(code: String) = CardSet(
		id = SourceId(mProvider, code),
		game = GameId("riftbound"),
		code = code,
		name = code,
		cardCount = null,
		releaseDate = null,
	)

	private val mSets = listOf(set("OGN"), set("VEN"), set("SPF"), set("UNL"))

	private fun id(code: String) = "test:$code"

	private fun codes(sets: List<CardSet>) = sets.map { it.code }

	@Test
	fun `with no favourites nothing is pinned and everything keeps its order`() {
		assertTrue(SetFavourites.pinned(mSets, emptyList()).isEmpty())
		assertEquals(mSets, SetFavourites.unpinned(mSets, emptyList()))
	}

	@Test
	fun `pinned sets come back in the stored order -- not the set list's`() {
		// The stored order is the thing the user arranged, so it wins over release order.
		val vFavourites = listOf(id("UNL"), id("OGN"))

		assertEquals(listOf("UNL", "OGN"), codes(SetFavourites.pinned(mSets, vFavourites)))
		assertEquals(listOf("VEN", "SPF"), codes(SetFavourites.unpinned(mSets, vFavourites)))
	}

	@Test
	fun `a favourite from another game is skipped rather than breaking the list`() {
		// The stored list holds every game's favourites and this set list is one game's, so ids
		// that name nothing here are the normal case, not an error.
		val vFavourites = listOf("tcgdex:sv1", id("VEN"), "scryfall:znr")

		assertEquals(listOf("VEN"), codes(SetFavourites.pinned(mSets, vFavourites)))
		assertEquals(listOf("OGN", "SPF", "UNL"), codes(SetFavourites.unpinned(mSets, vFavourites)))
	}

	@Test
	fun `a new favourite goes on the end rather than the top`() {
		// The user has expressed no opinion about where it belongs; putting it first would demote
		// whatever they had already arranged.
		val vFavourites = SetFavourites.toggled(listOf(id("UNL")), id("OGN"))

		assertEquals(listOf(id("UNL"), id("OGN")), vFavourites)
	}

	@Test
	fun `toggling a favourite off removes it`() {
		val vFavourites = SetFavourites.toggled(listOf(id("UNL"), id("OGN")), id("UNL"))

		assertEquals(listOf(id("OGN")), vFavourites)
	}

	@Test
	fun `un-favouriting and favouriting again puts it at the end -- it is not remembered`() {
		// Worth stating: the order is not a hidden preference that survives removal.
		var vFavourites = listOf(id("OGN"), id("VEN"), id("SPF"))
		vFavourites = SetFavourites.toggled(vFavourites, id("OGN"))
		vFavourites = SetFavourites.toggled(vFavourites, id("OGN"))

		assertEquals(listOf(id("VEN"), id("SPF"), id("OGN")), vFavourites)
	}

	@Test
	fun `a favourite can be dragged to a new position`() {
		val vFavourites = SetFavourites.movedTo(
			listOf(id("OGN"), id("VEN"), id("SPF")),
			setId = id("SPF"),
			toIndex = 0,
		)

		assertEquals(listOf(id("SPF"), id("OGN"), id("VEN")), vFavourites)
	}

	@Test
	fun `a drop past either end parks it there rather than wrapping or throwing`() {
		val vStart = listOf(id("OGN"), id("VEN"))

		assertEquals(listOf(id("VEN"), id("OGN")), SetFavourites.movedTo(vStart, id("OGN"), 9))
		assertEquals(listOf(id("VEN"), id("OGN")), SetFavourites.movedTo(vStart, id("VEN"), -4))
	}

	@Test
	fun `moving something that is not a favourite changes nothing`() {
		val vStart = listOf(id("OGN"))

		assertEquals(vStart, SetFavourites.movedTo(vStart, id("VEN"), toIndex = 0))
	}

	@Test
	fun `pinned and unpinned together are every set exactly once`() {
		// The property that matters most: whatever the stored list says, no set is lost from the
		// screen and none is drawn twice -- which in a LazyColumn keyed by id would crash.
		val vFavourites = listOf(id("SPF"), "tcgdex:sv1", id("OGN"))

		val vAll = SetFavourites.pinned(mSets, vFavourites) + SetFavourites.unpinned(mSets, vFavourites)

		assertEquals(mSets.size, vAll.size, "Got ${codes(vAll)}")
		assertEquals(mSets.toSet(), vAll.toSet())
	}
}
