package com.bitsycore.cardbrowser.ui

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.ui.setup.SetupContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The first-launch setup's transitions.
 *
 * Two rules carry real weight here and the rest is paging. The flow must open with *everything*
 * selected, because that is where the app stands without it and a setup whose first action is to
 * hide every game would make its own Next button destructive. And it must not let someone leave
 * the first page having chosen nothing, because the app would then have nothing to show and the
 * cause would be three screens behind them.
 */
class SetupContractTest {

	private fun game(id: String) = object : GameProfile {
		override val id: GameId = GameId(id)
		override val displayName: String = id
		override val vocabulary: GameVocabulary = GameVocabulary()
	}

	private val mGames = listOf(game("magic"), game("pokemon"), game("riftbound"))

	private fun loaded(): SetupContract.UiState =
		SetupContract.reduce(SetupContract.UiState(), SetupContract.Intent.GamesLoaded(mGames))

	@Test
	fun `the flow opens with every game selected`() {
		// Where the app stands without a setup screen. Starting from none would mean the default
		// path through the flow -- Next, Next, Start -- left a user with an empty app.
		assertEquals(setOf("magic", "pokemon", "riftbound"), loaded().selected)
	}

	@Test
	fun `choosing nothing blocks the first page and nothing else`() {
		val vNone = SetupContract.reduce(loaded(), SetupContract.Intent.NoGamesSelected)
		assertFalse(vNone.canAdvance)

		// Advancing is refused rather than silently accepted, so the button's disabled state and
		// the reducer agree -- two rules in two layers is how they drift.
		assertEquals(
			SetupContract.SetupPage.GAMES,
			SetupContract.reduce(vNone, SetupContract.Intent.Advanced).page,
		)

		val vOne = SetupContract.reduce(vNone, SetupContract.Intent.GameToggled("magic"))
		assertTrue(vOne.canAdvance)
	}

	@Test
	fun `a language page never blocks`() {
		// Every language has a working default, and the fallback chain behind it means even a
		// language a source cannot serve resolves to one it can. Nothing here is worth a wall.
		val vLanguage = SetupContract.reduce(
			loaded().copy(page = SetupContract.SetupPage.LANGUAGE, selected = emptySet()),
			SetupContract.Intent.Advanced,
		)

		assertEquals(SetupContract.SetupPage.ABOUT, vLanguage.page)
	}

	@Test
	fun `paging stops at both ends rather than wrapping`() {
		val vFirst = SetupContract.reduce(loaded(), SetupContract.Intent.WentBack)
		assertEquals(SetupContract.SetupPage.GAMES, vFirst.page)

		val vLast = SetupContract.reduce(
			loaded().copy(page = SetupContract.SetupPage.ABOUT),
			SetupContract.Intent.Advanced,
		)
		assertEquals(SetupContract.SetupPage.ABOUT, vLast.page)
		assertTrue(vLast.isLastPage)
	}

	@Test
	fun `toggling a game is symmetric`() {
		val vOff = SetupContract.reduce(loaded(), SetupContract.Intent.GameToggled("magic"))
		assertFalse("magic" in vOff.selected)

		val vOn = SetupContract.reduce(vOff, SetupContract.Intent.GameToggled("magic"))
		assertTrue("magic" in vOn.selected)
	}

	@Test
	fun `picking a language replaces rather than accumulates`() {
		val vState = SetupContract.reduce(
			SetupContract.reduce(loaded(), SetupContract.Intent.LanguagePicked(CardLanguage.FRENCH)),
			SetupContract.Intent.LanguagePicked(CardLanguage.JAPANESE),
		)

		assertEquals(CardLanguage.JAPANESE, vState.language)
	}

	@Test
	fun `finishing and skipping both lock the buttons`() {
		// One tap, not two. Both write preferences and emit the same effect, and a second tap
		// mid-write would queue a duplicate.
		assertTrue(SetupContract.reduce(loaded(), SetupContract.Intent.Finished).isSaving)
		assertTrue(SetupContract.reduce(loaded(), SetupContract.Intent.Skipped).isSaving)
	}
}
