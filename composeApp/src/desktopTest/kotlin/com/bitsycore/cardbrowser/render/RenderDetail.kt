package com.bitsycore.cardbrowser.render

import androidx.compose.ui.ImageComposeScene
import com.bitsycore.cardbrowser.core.model.CardIdentity
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.Finish
import com.bitsycore.cardbrowser.core.model.FinishCoverage
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.ui.detail.CardDetailContent
import com.bitsycore.cardbrowser.ui.detail.CardDetailContract
import com.bitsycore.cardbrowser.ui.preview.PreviewData
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the card detail screen off-screen and writes PNGs, so a layout change can be looked at
 * without a device attached.
 *
 * Headless: `ImageComposeScene` draws into a Skia surface with no window and no event loop, so this
 * needs neither a display nor a phone. Card images do not load -- there is no network here and none
 * is wanted -- so what the files show is the layout, the wrapping and the type, which is what a
 * rearrangement of the sections under a card actually needs checking against.
 *
 * `@Ignore` because it asserts almost nothing and is not a regression test: it is a tool, run by
 * hand with `--tests '*DetailRenderer*' -Dtest.single.render=1`, and its output is looked at rather
 * than compared. Left in the test source set rather than in `desktopMain` so it cannot end up in a
 * shipped app image.
 */
class DetailRenderer {

	@Test
	@Ignore("A rendering tool, not a check. Remove the annotation to write the PNGs.")
	fun `writes the card detail screen to build slash render`() {
		val vOut = File("build/render")
		vOut.mkdirs()

		// 3800 px tall on purpose. The screen scrolls, and a viewport the size of a real phone
		// would show the card image and its name and nothing else -- which is the part of this
		// screen that was already fine.

		renderToPng(vOut, "detail-riftbound", width = 420, height = 3800, density = 1.6f) {
			CardDetailContent(state = riftboundState(), dispatch = {})
		}
		renderToPng(vOut, "detail-many-languages", width = 420, height = 3800, density = 1.6f) {
			CardDetailContent(state = manyLanguagesState(), dispatch = {})
		}
		renderToPng(vOut, "detail-light", width = 420, height = 3800, density = 1.6f, isDark = false) {
			CardDetailContent(state = linkedArtworkState(), dispatch = {})
		}

		assertTrue(vOut.listFiles().orEmpty().any { it.length() > 0 }, "nothing was rendered")
		println("Wrote ${vOut.absolutePath}")
	}
}

// ==================
// MARK: States
// ==================

/** What Riftcodex really gives: English only, no finishes, no linked printings. */
private fun riftboundState() = CardDetailContract.UiState(
	cards = PreviewData.CARDS,
	currentIndex = 0,
	set = PreviewData.ORIGINS,
	isLoading = false,
	providerLanguages = setOf(CardLanguage.ENGLISH),
	providerDisplayName = "Riftcodex",
	game = RiftboundGame,
	attribution = "Card data from Riftcodex, an unofficial fan project not affiliated with Riot Games.",
)

/** A source that serves every language the app knows, with French asked for. */
private fun manyLanguagesState() = riftboundState().copy(
	currentIndex = 5,
	requestedLanguage = CardLanguage.FRENCH,
	providerLanguages = CardLanguage.entries.toSet(),
	providerDisplayName = "Scryfall",
)

/** Finishes and linked printings, which only Scryfall-like sources supply. */
private fun linkedArtworkState(): CardDetailContract.UiState {
	val vIdentity = CardIdentity(SourceId(PreviewData.CARDS[0].id.provider, "oracle-1"), "Annie")
	val vCards = PreviewData.CARDS.take(4).map { vCard ->
		vCard.copy(
			identity = vIdentity,
			finishes = FinishCoverage(
				confirmed = setOf(Finish.NON_FOIL, Finish.FOIL),
				absent = setOf(Finish.ETCHED),
			),
		)
	}
	return riftboundState().copy(
		cards = vCards,
		providerStatesIdentity = true,
		providerStatesFinishes = true,
	)
}
