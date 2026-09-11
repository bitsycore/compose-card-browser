package com.bitsycore.cardbrowser.ui

import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.ui.sets.SetListContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Favourites as the set list screen sees them: pinned above, everything else below.
 *
 * `SetFavouritesTest` covers the ordering rules themselves. What matters here is the wiring -- that
 * the two derived lists partition what is on screen, and that they follow the search rather than
 * floating above it.
 */
class SetFavouritesReducerTest {

	private val mProvider = ProviderId("test")

	private fun set(code: String, name: String = code) = CardSet(
		id = SourceId(mProvider, code),
		game = GameId("riftbound"),
		code = code,
		name = name,
		cardCount = null,
		releaseDate = null,
	)

	private val mSets = listOf(set("OGN", "Origins"), set("VEN", "Vendetta"), set("SPF", "Spiritforged"))

	private fun loaded() = SetListContract.UiState(sets = mSets, isLoading = false)

	private fun id(code: String) = "test:$code"

	private fun codes(sets: List<CardSet>) = sets.map { it.code }

	@Test
	fun `with nothing pinned everything is in the lower group`() {
		val vState = loaded()

		assertTrue(vState.favouriteSets.isEmpty())
		assertEquals(listOf("OGN", "VEN", "SPF"), codes(vState.otherSets))
	}

	@Test
	fun `a pinned set moves to the top group and leaves the lower one`() {
		val vState = SetListContract.reduce(loaded(), SetListContract.Intent.FavouriteToggled(id("SPF")))

		assertEquals(listOf("SPF"), codes(vState.favouriteSets))
		assertEquals(listOf("OGN", "VEN"), codes(vState.otherSets))
	}

	@Test
	fun `the two groups always partition what is on screen`() {
		// A set drawn twice would crash the LazyColumn on a duplicate key; one drawn in neither
		// would silently vanish from the list. Both are worth pinning down.
		var vState = SetListContract.reduce(loaded(), SetListContract.Intent.FavouriteToggled(id("VEN")))
		vState = SetListContract.reduce(vState, SetListContract.Intent.FavouriteToggled(id("OGN")))

		val vAll = vState.favouriteSets + vState.otherSets

		assertEquals(vState.visibleSets.size, vAll.size, "Got ${codes(vAll)}")
		assertEquals(vState.visibleSets.toSet(), vAll.toSet())
	}

	@Test
	fun `favourites follow the search rather than floating above it`() {
		// Searching for one set and still being shown three pinned ones reads as a broken search.
		var vState = SetListContract.reduce(loaded(), SetListContract.Intent.FavouriteToggled(id("SPF")))
		vState = SetListContract.reduce(vState, SetListContract.Intent.SearchChanged("vendetta"))

		assertTrue(vState.favouriteSets.isEmpty(), "A pinned set that does not match must not show")
		assertEquals(listOf("VEN"), codes(vState.otherSets))
	}

	@Test
	fun `dragging is refused while a search narrows the list`() {
		// The drag reorders the stored list, and what is on screen may be a subset of it -- so a
		// drop between two visible rows has no single right answer. The handles go away instead.
		var vState = SetListContract.reduce(loaded(), SetListContract.Intent.EditingToggled)
		for (vCode in listOf("OGN", "VEN", "SPF")) {
			vState = SetListContract.reduce(vState, SetListContract.Intent.FavouriteToggled(id(vCode)))
		}
		// Arranging, because dragging is gated on that too now. Stated here so what follows is
		// about the search and nothing else.
		assertTrue(vState.canReorderFavourites)

		val vSearching = SetListContract.reduce(vState, SetListContract.Intent.SearchChanged("e"))

		assertFalse(vSearching.canReorderFavourites)
	}

	@Test
	fun `one favourite is not worth dragging`() {
		// Arranging, so this still fails for the reason it is named after. Without it the
		// assertion would hold because the list is merely not in edit mode, which is a different
		// rule and one the test above covers.
		val vEditing = SetListContract.reduce(loaded(), SetListContract.Intent.EditingToggled)
		val vState = SetListContract.reduce(vEditing, SetListContract.Intent.FavouriteToggled(id("OGN")))

		assertFalse(vState.canReorderFavourites)
	}

	@Test
	fun `a favourite naming another game's set is carried without being shown`() {
		// The stored list is global. Opening a different game must neither show its favourites nor
		// drop them from the preference.
		val vState = SetListContract.reduce(
			loaded(),
			SetListContract.Intent.FavouritesRestored(listOf("tcgdex:sv1", id("VEN"))),
		)

		assertEquals(listOf("VEN"), codes(vState.favouriteSets))
		assertEquals(listOf("tcgdex:sv1", id("VEN")), vState.favouriteIds, "The other game's id must survive")
	}
}
