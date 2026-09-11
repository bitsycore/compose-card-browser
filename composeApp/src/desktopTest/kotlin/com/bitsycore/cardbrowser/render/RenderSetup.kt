package com.bitsycore.cardbrowser.render

import com.bitsycore.cardbrowser.games.altered.AlteredArt
import com.bitsycore.cardbrowser.games.altered.AlteredGame
import com.bitsycore.cardbrowser.games.cyberpunk.CyberpunkArt
import com.bitsycore.cardbrowser.games.cyberpunk.CyberpunkGame
import com.bitsycore.cardbrowser.games.lorcana.LorcanaArt
import com.bitsycore.cardbrowser.games.lorcana.LorcanaGame
import com.bitsycore.cardbrowser.games.magic.MagicArt
import com.bitsycore.cardbrowser.games.magic.MagicGame
import com.bitsycore.cardbrowser.games.onepiece.OnePieceArt
import com.bitsycore.cardbrowser.games.onepiece.OnePieceGame
import com.bitsycore.cardbrowser.games.pokemon.PokemonArt
import com.bitsycore.cardbrowser.games.pokemon.PokemonGame
import com.bitsycore.cardbrowser.games.riftbound.RiftboundArt
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import com.bitsycore.cardbrowser.games.wowtcg.WowTcgArt
import com.bitsycore.cardbrowser.games.wowtcg.WowTcgGame
import com.bitsycore.cardbrowser.games.wutheringwaves.WutheringWavesArt
import com.bitsycore.cardbrowser.games.wutheringwaves.WutheringWavesGame
import com.bitsycore.cardbrowser.games.yugioh.YuGiOhArt
import com.bitsycore.cardbrowser.games.yugioh.YuGiOhGame
import com.bitsycore.cardbrowser.ui.games.GameArtRegistry
import com.bitsycore.cardbrowser.ui.setup.SetupContent
import com.bitsycore.cardbrowser.ui.setup.SetupContract
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the first-launch setup off-screen, at two window widths.
 *
 * The width is the point: the flow is centred and capped, so a desktop window and a phone have to
 * be looked at separately -- one exercises the cap and the grid's wide arrangement, the other the
 * narrow one. Same caveats as the other renderers: headless, no Koin, no network, `@Ignore` because
 * the output is looked at rather than asserted.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests '*SetupRenderer*'
 * ```
 */
class SetupRenderer {

	@Test
	@Ignore("A rendering tool, not a check. Remove the annotation to write the PNGs.")
	fun `writes the setup flow to build slash render`() {
		val vOut = File("build/render")
		vOut.mkdirs()
		val vArt = GameArtRegistry(
			listOf(
				RiftboundArt, PokemonArt, MagicArt, OnePieceArt, AlteredArt,
				YuGiOhArt, WutheringWavesArt, CyberpunkArt, LorcanaArt, WowTcgArt,
			),
		)

		// Pixels, at density 1.65 -- so these are roughly 667dp of desktop window and 400dp of
		// phone, which is what the grid's column count actually keys off. Rendering a "phone" at
		// 400 *pixels* made it 242dp and showed one column, which is not what any phone does.
		for ((vLabel, vWidth) in listOf("setup-desktop" to 1100, "setup-phone" to 660)) {
			renderToPng(vOut, vLabel, width = vWidth, height = 1200, density = 1.65f) {
				SetupContent(
					state = SetupContract.UiState(games = GAMES, selected = setOf("magic", "pokemon")),
					dispatch = {},
					artFor = vArt::forGame,
				)
			}
		}
		// The other two pages at one width, because what is being looked at there is the centring
		// and the measure of the prose rather than a grid that changes shape.
		for (vPage in listOf(SetupContract.SetupPage.LANGUAGE, SetupContract.SetupPage.ABOUT)) {
			renderToPng(vOut, "setup-${vPage.name.lowercase()}", 1100, 1200, density = 1.65f) {
				SetupContent(state = SetupContract.UiState(page = vPage), dispatch = {})
			}
		}

		assertTrue(vOut.listFiles().orEmpty().any { it.length() > 0 }, "nothing was rendered")
		println("Wrote ${vOut.absolutePath}")
	}

	private companion object {

		/** Every shipped game, because the point of the picker is how ten marks read together. */
		val GAMES = listOf(
			RiftboundGame, PokemonGame, MagicGame, OnePieceGame, AlteredGame,
			YuGiOhGame, WutheringWavesGame, CyberpunkGame, LorcanaGame, WowTcgGame,
		)
	}
}
