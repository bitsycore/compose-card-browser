package com.bitsycore.cardbrowser.ui

import com.bitsycore.cardbrowser.games.lorcana.LorcanaGame
import com.bitsycore.cardbrowser.games.magic.MagicGame
import com.bitsycore.cardbrowser.games.pokemon.PokemonGame
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import com.bitsycore.cardbrowser.ui.games.GameListContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The game picker's reducer: reordering, hiding and resetting.
 *
 * `GameOrder` already covers the ordering rules themselves against synthetic profiles. What is
 * asserted here is the wiring -- that the reducer keeps the whole game list and derives the visible
 * one, rather than mutating a list and losing the games it filtered out.
 */
class GameListContractTest {

	private val mGames = listOf(RiftboundGame, PokemonGame, MagicGame, LorcanaGame)

	private fun loaded() = GameListContract.reduce(
		GameListContract.UiState(),
		GameListContract.Intent.Loaded(
			games = mGames,
			sources = emptyMap(),
			lastGame = null,
			order = emptyList(),
			hiddenIds = emptySet(),
		),
	)

	private fun ids(state: GameListContract.UiState) = state.games.map { it.id.value }

	@Test
	fun `the picker starts in the routing table's own order`() {
		val vState = loaded()

		assertEquals(mGames.map { it.id.value }, ids(vState))
		assertFalse(vState.isLoading)
		assertFalse(vState.isCustomised, "Nothing has been customised yet")
	}

	@Test
	fun `dragging a game reorders the list`() {
		val vState = GameListContract.reduce(
			loaded(),
			GameListContract.Intent.GameMovedTo(MagicGame, toVisibleIndex = 1),
		)

		assertEquals(listOf("riftbound", "magic", "pokemon", "lorcana"), ids(vState))
		assertTrue(vState.isCustomised)
	}

	@Test
	fun `a drag is indexed against the visible list even with a game hidden among them`() {
		// The reducer has to pass the hidden set through, or dropping a row at the top lands it
		// above a hidden row instead and nothing visibly moves.
		var vState = GameListContract.reduce(
			loaded(),
			GameListContract.Intent.GameVisibilityToggled(PokemonGame),
		)

		vState = GameListContract.reduce(
			vState,
			GameListContract.Intent.GameMovedTo(MagicGame, toVisibleIndex = 0),
		)

		assertEquals(listOf("magic", "riftbound", "lorcana"), ids(vState))
	}

	@Test
	fun `hiding a game takes it off the picker but keeps it in the editor`() {
		// The whole list is retained on the state; only the derived view drops it. Mutating the
		// list instead would make the hidden game unrecoverable, because the control that brings it
		// back is rendered from that same list.
		val vState = GameListContract.reduce(
			loaded(),
			GameListContract.Intent.GameVisibilityToggled(PokemonGame),
		)

		assertEquals(listOf("riftbound", "magic", "lorcana"), ids(vState))
		assertEquals(listOf("pokemon"), vState.hiddenGames.map { it.id.value })
		assertEquals(mGames.size, vState.allGames.size, "The full list must survive hiding")
	}

	@Test
	fun `hiding and un-hiding returns the game to its place rather than the end`() {
		var vState = GameListContract.reduce(
			loaded(),
			GameListContract.Intent.GameVisibilityToggled(PokemonGame),
		)
		vState = GameListContract.reduce(
			vState,
			GameListContract.Intent.GameVisibilityToggled(PokemonGame),
		)

		assertEquals(mGames.map { it.id.value }, ids(vState))
	}

	@Test
	fun `the last visible game cannot be hidden`() {
		var vState = loaded()
		for (vGame in listOf(PokemonGame, MagicGame, LorcanaGame)) {
			vState = GameListContract.reduce(
				vState,
				GameListContract.Intent.GameVisibilityToggled(vGame),
			)
		}

		assertEquals(listOf("riftbound"), ids(vState))
		assertFalse(vState.canHideMore, "The control must be disabled at this point")

		val vAfter = GameListContract.reduce(
			vState,
			GameListContract.Intent.GameVisibilityToggled(RiftboundGame),
		)

		assertEquals(listOf("riftbound"), ids(vAfter), "An empty picker is a dead end")
	}

	@Test
	fun `resetting clears both the order and the hidden list`() {
		var vState = GameListContract.reduce(
			loaded(),
			GameListContract.Intent.GameMovedTo(LorcanaGame, toVisibleIndex = 1),
		)
		vState = GameListContract.reduce(
			vState,
			GameListContract.Intent.GameVisibilityToggled(MagicGame),
		)
		assertTrue(vState.isCustomised)

		val vReset = GameListContract.reduce(vState, GameListContract.Intent.CustomisationReset)

		assertEquals(mGames.map { it.id.value }, ids(vReset))
		assertFalse(vReset.isCustomised)
		assertTrue(vReset.hiddenGames.isEmpty())
	}

	@Test
	fun `editing is a mode rather than a setting`() {
		// Not persisted and not part of Loaded: you are in it, you do not have it.
		val vEditing = GameListContract.reduce(loaded(), GameListContract.Intent.EditingToggled)

		assertTrue(vEditing.isEditing)
		assertFalse(
			GameListContract.reduce(vEditing, GameListContract.Intent.EditingToggled).isEditing,
		)
	}

	@Test
	fun `a hidden game stays hidden when it was the last one opened`() {
		// `lastGame` only drives a highlight, so pointing at a hidden game must not resurrect it.
		var vState = GameListContract.reduce(
			loaded(),
			GameListContract.Intent.GameOpened(PokemonGame),
		)
		vState = GameListContract.reduce(
			vState,
			GameListContract.Intent.GameVisibilityToggled(PokemonGame),
		)

		assertEquals(PokemonGame, vState.lastGame)
		assertFalse(PokemonGame in vState.games)
	}
}
