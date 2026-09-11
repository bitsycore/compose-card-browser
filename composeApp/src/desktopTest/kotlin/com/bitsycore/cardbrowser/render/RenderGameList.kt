package com.bitsycore.cardbrowser.render

import com.bitsycore.cardbrowser.games.altered.AlteredArt
import com.bitsycore.cardbrowser.games.altered.AlteredGame
import com.bitsycore.cardbrowser.games.magic.MagicArt
import com.bitsycore.cardbrowser.games.magic.MagicGame
import com.bitsycore.cardbrowser.games.onepiece.OnePieceArt
import com.bitsycore.cardbrowser.games.onepiece.OnePieceGame
import com.bitsycore.cardbrowser.games.pokemon.PokemonArt
import com.bitsycore.cardbrowser.games.pokemon.PokemonGame
import com.bitsycore.cardbrowser.games.riftbound.RiftboundArt
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import com.bitsycore.cardbrowser.ui.games.GameArtRegistry
import com.bitsycore.cardbrowser.ui.games.GameListContent
import com.bitsycore.cardbrowser.ui.games.GameListContract
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the game picker in both of its modes, browsing and customising.
 *
 * Both, deliberately: the two modes animate into each other now, and what a still frame *can* check
 * is that neither end state moved -- that the row's trailing corner is the same width with a
 * chevron in it as with an eye, so the name beside it does not shuffle sideways mid-fade. The
 * transition itself is not something a PNG can show.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests '*GameListRenderer*'
 * ```
 */
class GameListRenderer {

	@Test
	@Ignore("A rendering tool, not a check. Remove the annotation to write the PNGs.")
	fun `writes the picker in both modes to build slash render`() {
		val vOut = File("build/render")
		vOut.mkdirs()
		val vArt = GameArtRegistry(
			listOf(RiftboundArt, PokemonArt, MagicArt, OnePieceArt, AlteredArt),
		)

		renderToPng(vOut, "games-browsing", width = 700, height = 900, density = 1.65f) {
			GameListContent(state = state(isEditing = false), dispatch = {}, artFor = vArt::forGame)
		}
		renderToPng(vOut, "games-editing", width = 700, height = 900, density = 1.65f) {
			GameListContent(state = state(isEditing = true), dispatch = {}, artFor = vArt::forGame)
		}

		assertTrue(vOut.listFiles().orEmpty().any { it.length() > 0 }, "nothing was rendered")
		println("Wrote ${vOut.absolutePath}")
	}

	/** One game hidden, so the editing shot also carries the hidden section and its heading. */
	private fun state(isEditing: Boolean) = GameListContract.UiState(
		allGames = listOf(RiftboundGame, PokemonGame, MagicGame, OnePieceGame, AlteredGame),
		isLoading = false,
		isEditing = isEditing,
		hiddenIds = setOf(AlteredGame.id.value),
		sources = mapOf(
			RiftboundGame to "Riftcodex",
			PokemonGame to "TCGdex",
			MagicGame to "Scryfall",
			OnePieceGame to "OPTCG",
			AlteredGame to "Altered",
		),
	)
}
