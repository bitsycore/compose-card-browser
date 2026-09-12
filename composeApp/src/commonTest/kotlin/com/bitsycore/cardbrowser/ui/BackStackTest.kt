package com.bitsycore.cardbrowser.ui

import androidx.compose.runtime.mutableStateListOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The back stack cannot be emptied, whatever pops it.
 *
 * ## The crash this pins
 *
 * `IllegalArgumentException: NavDisplay backstack cannot be empty`, reported from the settings
 * screen. `NavDisplay` throws on an empty stack rather than degrading, so emptying it is a crash on
 * the next recomposition -- and every screen's back handler was `removeLastOrNull`, which takes the
 * last entry as happily as any other.
 *
 * Reaching it took two pops for one tap. "Run setup again" used to write `hasCompletedSetup = false`
 * *and* emit `NavigateBack`, with a `LaunchedEffect` in `App()` watching that flag to push the flow
 * -- one push and one pop of the same list, from two coroutines, in no fixed order. That path is
 * gone; re-running setup is an ordinary navigation now. These two functions are the belt to that
 * brace, and they hold whether or not anything ever double-pops again.
 */
class BackStackTest {

	@Test
	fun `popping past the root leaves the root`() {
		val vStack = mutableStateListOf<Route>(Route.Games, Route.Settings)

		repeat(5) { vStack.popRoute() }

		assertEquals(listOf<Route>(Route.Games), vStack.toList(), "the stack must never empty")
	}

	@Test
	fun `going to a card's set leaves the grid under it and the search behind`() {
		// "Go to set" from a search result. The grid has to end up *under* the card so that
		// leaving the card is a pop onto it -- the same container transform as coming from the
		// set -- rather than a new screen pushed over the top.
		val vStack = mutableStateListOf<Route>(
			Route.Games,
			Route.Sets("riftbound"),
			Route.Search("riftbound"),
			Route.Detail(cardId = "riftcodex:OGN-001", setId = "riftcodex:OGN", browseKey = "search:riftbound"),
		)

		vStack.slideSetUnderCard(Route.Cards("riftcodex:OGN", "Origins", "OGN"))

		assertEquals(
			listOf<Route>(
				Route.Games,
				Route.Sets("riftbound"),
				Route.Cards("riftcodex:OGN", "Origins", "OGN"),
				Route.Detail(cardId = "riftcodex:OGN-001", setId = "riftcodex:OGN", browseKey = "search:riftbound"),
			),
			vStack.toList(),
			"the grid belongs under the card, and the search is finished with",
		)

		vStack.popRoute()

		assertEquals(
			Route.Cards("riftcodex:OGN", "Origins", "OGN"),
			vStack.last(),
			"leaving the card lands on the grid",
		)
		assertEquals(
			Route.Sets("riftbound"),
			vStack[vStack.lastIndex - 1],
			"and back from the grid is the set list",
		)
	}

	@Test
	fun `popping a named route takes it from wherever it sits`() {
		// Setup is pushed over the picker on a first launch and over Settings on a re-run, so
		// finishing it cannot assume it is on top -- an effect emitted a frame earlier may have
		// put something above it.
		val vStack = mutableStateListOf<Route>(Route.Games, Route.Setup, Route.Settings)

		vStack.popRoute(Route.Setup)

		assertEquals(listOf(Route.Games, Route.Settings), vStack.toList())
	}

	@Test
	fun `a named pop will not empty the stack either`() {
		// The first-launch case with everything else already gone: the flow is the only thing left
		// and finishing it must not leave `NavDisplay` with nothing to draw.
		val vStack = mutableStateListOf<Route>(Route.Setup)

		vStack.popRoute(Route.Setup)

		assertEquals(listOf<Route>(Route.Setup), vStack.toList())
	}

	@Test
	fun `popping a route that is not there changes nothing`() {
		val vStack = mutableStateListOf<Route>(Route.Games, Route.Settings)

		vStack.popRoute(Route.Setup)

		assertEquals(listOf(Route.Games, Route.Settings), vStack.toList())
	}
}
