package com.bitsycore.cardbrowser.core

import com.bitsycore.cardbrowser.core.game.GameOrder
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.GameId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The user's game order and hidden list, applied over whatever games a build offers.
 *
 * The cases that matter are all disagreements between the two: an order naming a game this build
 * does not have, a build having a game the order never heard of, and hiding the last one.
 */
class GameOrderTest {

	private fun game(id: String) = object : GameProfile {
		override val id: GameId = GameId(id)
		override val displayName: String = id
		override val vocabulary: GameVocabulary = GameVocabulary()
	}

	private val mGames = listOf(game("riftbound"), game("pokemon"), game("magic"), game("lorcana"))

	private fun ids(games: List<GameProfile>) = games.map { it.id.value }

	@Test
	fun `no stored order leaves the registry's own order alone`() {
		assertEquals(mGames, GameOrder.sorted(mGames, emptyList()))
	}

	@Test
	fun `a stored order is applied`() {
		val vOrder = listOf("lorcana", "magic", "pokemon", "riftbound")

		assertEquals(vOrder, ids(GameOrder.sorted(mGames, vOrder)))
	}

	@Test
	fun `a game the order never heard of still appears -- after the ones it names`() {
		// The case that would otherwise lose a game for good: the user reorders, a later build adds
		// an adapter, and the new game is nowhere in the stored list. Dropping it would leave no way
		// to reach it, because this screen is the only route into the app.
		val vOrder = listOf("magic", "riftbound")

		assertEquals(listOf("magic", "riftbound", "pokemon", "lorcana"), ids(GameOrder.sorted(mGames, vOrder)))
	}

	@Test
	fun `an order naming a game this build does not offer is ignored rather than an error`() {
		// A preferences file written by a build with an adapter this one lacks.
		val vOrder = listOf("duelmasters", "magic", "cyberpunk", "riftbound")

		assertEquals(listOf("magic", "riftbound", "pokemon", "lorcana"), ids(GameOrder.sorted(mGames, vOrder)))
	}

	@Test
	fun `hidden games are kept out of the picker and listed for the editor`() {
		val vHidden = setOf("pokemon", "lorcana")

		assertEquals(listOf("riftbound", "magic"), ids(GameOrder.visible(mGames, emptyList(), vHidden)))
		assertEquals(listOf("pokemon", "lorcana"), ids(GameOrder.hidden(mGames, emptyList(), vHidden)))
	}

	@Test
	fun `hiding an id that names no game is inert`() {
		// Hide a game, install a build without it, install one with it again. The setting survives
		// the round trip rather than corrupting the list in between.
		val vHidden = setOf("duelmasters")

		assertEquals(mGames, GameOrder.visible(mGames, emptyList(), vHidden))
	}

	@Test
	fun `a game dragged up lands at the index it was dropped on`() {
		val vMoved = GameOrder.movedTo(mGames, emptyList(), emptySet(), mGames[2], toVisibleIndex = 1)

		assertEquals(listOf("riftbound", "magic", "pokemon", "lorcana"), vMoved)
	}

	@Test
	fun `a game dragged down lands at the index it was dropped on`() {
		val vMoved = GameOrder.movedTo(mGames, emptyList(), emptySet(), mGames[0], toVisibleIndex = 2)

		assertEquals(listOf("pokemon", "magic", "riftbound", "lorcana"), vMoved)
	}

	@Test
	fun `a drop past either end parks the row there rather than wrapping`() {
		assertEquals(
			ids(mGames),
			GameOrder.movedTo(mGames, emptyList(), emptySet(), mGames.first(), toVisibleIndex = -3),
		)
		assertEquals(
			ids(mGames),
			GameOrder.movedTo(mGames, emptyList(), emptySet(), mGames.last(), toVisibleIndex = 99),
		)
	}

	@Test
	fun `a move returns the whole order rather than a patch`() {
		// A partial order would leave the moved game's new neighbours unpinned, so a later build
		// that reordered its routing table could shuffle them back out from under the move.
		val vMoved = GameOrder.movedTo(mGames, emptyList(), emptySet(), mGames[1], toVisibleIndex = 2)

		assertEquals(mGames.size, vMoved.size, "Got $vMoved")
		assertEquals(mGames.map { it.id.value }.toSet(), vMoved.toSet())
	}

	@Test
	fun `a hidden game between two visible ones does not swallow the move`() {
		// The real hazard of indexing a reorder against the full list. Pokemon is hidden and sits
		// between Riftbound and Magic; dragging Magic to the top has to put it above Riftbound, not
		// merely above the row nobody can see -- which would change the stored order and nothing on
		// screen, so the gesture would look ignored.
		val vHidden = setOf("pokemon")

		val vMoved = GameOrder.movedTo(mGames, emptyList(), vHidden, mGames[2], toVisibleIndex = 0)

		assertEquals(listOf("magic", "riftbound", "lorcana"), ids(GameOrder.visible(mGames, vMoved, vHidden)))
	}

	@Test
	fun `a hidden game keeps its slot when the visible ones are reordered around it`() {
		// So un-hiding it puts it back roughly where its owner last saw it, rather than at the end.
		val vHidden = setOf("pokemon")

		val vMoved = GameOrder.movedTo(mGames, emptyList(), vHidden, mGames[3], toVisibleIndex = 0)

		// Pokemon still occupies the second slot of the full order it started in.
		assertEquals(1, vMoved.indexOf("pokemon"), "Got $vMoved")
		assertEquals(listOf("lorcana", "riftbound", "magic"), ids(GameOrder.visible(mGames, vMoved, vHidden)))
	}

	@Test
	fun `the last visible game cannot be hidden`() {
		// Every route into the app goes through the picker, and the control that would undo this is
		// on the screen that would have just emptied.
		val vAllButOne = setOf("pokemon", "magic", "lorcana")

		assertFalse(GameOrder.canHide(mGames, vAllButOne))
		assertEquals(
			vAllButOne,
			GameOrder.withVisibilityToggled(mGames, vAllButOne, mGames[0]),
			"Hiding the last visible game must be refused",
		)
	}

	@Test
	fun `un-hiding is always allowed -- including from the one-visible state`() {
		val vAllButOne = setOf("pokemon", "magic", "lorcana")

		val vResult = GameOrder.withVisibilityToggled(mGames, vAllButOne, mGames[1])

		assertEquals(setOf("magic", "lorcana"), vResult)
		assertTrue(GameOrder.canHide(mGames, vResult))
	}
}
