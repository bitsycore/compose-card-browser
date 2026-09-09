package com.bitsycore.cardbrowser.ui.games

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CatchingPokemon
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Sailing
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.resources.Res
import com.bitsycore.cardbrowser.resources.game_logo_magic
import com.bitsycore.cardbrowser.resources.game_logo_onepiece
import com.bitsycore.cardbrowser.resources.game_logo_pokemon
import com.bitsycore.cardbrowser.resources.game_logo_wuwa
import com.bitsycore.cardbrowser.resources.game_logo_yugioh
import org.jetbrains.compose.resources.DrawableResource

/**
 * A game's mark: its real logo where one can legitimately be used, and a Material symbol where it
 * cannot.
 *
 * ## Where the logos come from, and why five of seven
 *
 * Every logo bundled here came from **Wikimedia Commons**, and that is the reason it can be bundled
 * at all. Commons accepts only freely-licensed media. A logo merely *shown* on Wikipedia usually
 * lives on Wikipedia itself under a non-free fair-use rationale that does not permit
 * redistribution; none of those are used here.
 *
 * Each file's licence was checked individually through the Commons API rather than assumed:
 *
 * | Game | Licence | Note |
 * | --- | --- | --- |
 * | Pokémon | Public domain | Below the threshold of originality. Trademarked. |
 * | Magic | Public domain | Below the threshold of originality. Trademarked. |
 * | Yu-Gi-Oh! | **CC BY 3.0** | Attribution required -- Kazuki Takahashi. Shown in the app. |
 * | One Piece | Public domain | Below the threshold of originality. Trademarked. |
 * | Wuthering Waves | Public domain | Below the threshold of originality. Trademarked. |
 *
 * "Public domain, trademarked" is the ordinary state of a wordmark: no one holds a *copyright* in
 * it, so redistributing the file is fine, while the *trademark* still belongs to its owner. Using
 * it to identify that owner's game -- the only thing this screen does with it -- is what trademarks
 * are for. The app remains unaffiliated with every publisher named, and says so on the same screen.
 *
 * **Riftbound and Altered have no logo here.** Both were searched for on Commons *and* on English
 * Wikipedia, under several names; Commons holds nothing, and the only Wikipedia files are a cover
 * and a card back uploaded under non-free fair-use rationales that do not permit redistribution.
 * Both games are recent enough that no freely-licensed mark exists yet. The alternatives were
 * scraping fan wikis for files of unknown provenance or hotlinking a publisher's CDN, neither of
 * which is worth doing to fill a tile, so they keep their Material mark -- which is why the
 * fallback below is not dead code.
 *
 * ## Monochrome marks
 *
 * [tintLogo] exists for one file. The Wuthering Waves mark is a solid black wordmark -- both the
 * mean luminance and the mean saturation of its visible pixels measure 0 -- so on the dark theme,
 * drawn as-is, it is a black shape on a near-black tile.
 *
 * It is therefore drawn in the *theme's* foreground colour rather than the row's accent: black on
 * the light theme, which is the file's own colour, and white on the dark one, which is how the
 * official mark is presented against dark backgrounds anyway. Never the accent colour -- a teal
 * Wuthering Waves logo is not its logo. The other four measure a mean saturation above 120 and are
 * full-colour artwork; tinting one would flatten it to a silhouette.
 *
 * @property icon a Material symbol. The fallback, and the whole answer for two of the seven
 * @property accent used for the tile, so the rows are distinguishable at a glance
 * @property logo the game's real logo, or `null` to use [icon]
 * @property tintLogo true only for a single-colour wordmark, which is then drawn in the theme's
 *   foreground colour so it stays legible on both themes. Never true for colour artwork
 */
data class GameVisual(
	val icon: ImageVector,
	val accent: Color,
	val logo: DrawableResource? = null,
	val tintLogo: Boolean = false,
) {

	companion object {

		/** The mark for [game]. Total, so a new game cannot be added without choosing one. */
		fun of(game: Game): GameVisual = when (game) {
			// No freely-licensed logo exists for these two; see the class doc.
			Game.RIFTBOUND -> GameVisual(Icons.Outlined.Bolt, Color(0xFF7C6BF5))
			Game.ALTERED -> GameVisual(Icons.Outlined.Terrain, Color(0xFF4FA97C))

			Game.ONE_PIECE -> GameVisual(
				icon = Icons.Outlined.Sailing,
				accent = Color(0xFF3E8FD0),
				logo = Res.drawable.game_logo_onepiece,
			)

			Game.POKEMON -> GameVisual(
				icon = Icons.Filled.CatchingPokemon,
				accent = Color(0xFFE4573D),
				logo = Res.drawable.game_logo_pokemon,
			)
			Game.MAGIC -> GameVisual(
				icon = Icons.Outlined.AutoAwesome,
				accent = Color(0xFFD9A441),
				logo = Res.drawable.game_logo_magic,
			)
			Game.YU_GI_OH -> GameVisual(
				icon = Icons.Outlined.Casino,
				accent = Color(0xFF9B5FC0),
				logo = Res.drawable.game_logo_yugioh,
			)
			Game.WUTHERING_WAVES -> GameVisual(
				icon = Icons.Outlined.Waves,
				accent = Color(0xFF2FA8A0),
				logo = Res.drawable.game_logo_wuwa,
				// A solid black silhouette. Untinted it disappears on the dark theme.
				tintLogo = true,
			)
		}

		/**
		 * The credit the Yu-Gi-Oh! logo's CC BY 3.0 licence requires.
		 *
		 * The four public-domain marks need none, so this is the only one. It is shown on the game
		 * picker rather than buried in a settings page, because an attribution nobody sees is not
		 * an attribution.
		 */
		const val LOGO_ATTRIBUTION: String =
			"Game logos from Wikimedia Commons. The Yu-Gi-Oh! logo is by Kazuki Takahashi, CC BY 3.0."
	}
}
