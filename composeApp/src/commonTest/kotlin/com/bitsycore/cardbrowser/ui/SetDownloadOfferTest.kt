package com.bitsycore.cardbrowser.ui

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.games.pokemon.PokemonGame
import com.bitsycore.cardbrowser.ui.sets.SetListContract
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A row offers a download only once the store has been asked.
 *
 * "No complete sets" and "not looked yet" are the same empty set, and drawing them the same way is
 * how a fully downloaded game came to show a download button on every row for a moment: the buttons
 * appeared, the scan landed, and every row moved as they went away.
 */
class SetDownloadOfferTest {

	@Test
	fun `nothing is known before the scan answers`() {
		assertFalse(SetListContract.UiState().isDownloadStateKnown, "a fresh screen has not looked")
	}

	@Test
	fun `the scan landing is what makes it known`() {
		val vState = scanned()

		assertTrue(vState.isDownloadStateKnown)
	}

	@Test
	fun `a new game is a new question`() {
		// The answer belongs to the game it was asked about. Keeping it would offer -- or withhold
		// -- a download on the strength of what the *previous* game held.
		val vSwitched = SetListContract.reduce(
			scanned(),
			SetListContract.Intent.GameChanged(PokemonGame),
		)

		assertFalse(vSwitched.isDownloadStateKnown)
	}

	@Test
	fun `a new language is a new question too`() {
		// A download is recorded per language, and the marks are cleared with it -- what is cleared
		// is not known.
		val vSwitched = SetListContract.reduce(
			scanned(),
			SetListContract.Intent.BrowsingLanguageSelected(CardLanguage.FRENCH),
		)

		assertFalse(vSwitched.isDownloadStateKnown)
	}

	private fun scanned() = SetListContract.reduce(
		SetListContract.UiState(),
		SetListContract.Intent.SavedSetsResolved(setIds = emptySet()),
	)
}
