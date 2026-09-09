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
import com.bitsycore.cardbrowser.resources.game_logo_altered
import com.bitsycore.cardbrowser.resources.game_logo_magic
import com.bitsycore.cardbrowser.resources.game_logo_onepiece
import com.bitsycore.cardbrowser.resources.game_logo_pokemon
import com.bitsycore.cardbrowser.resources.game_logo_riftbound
import com.bitsycore.cardbrowser.resources.game_logo_wuwa
import com.bitsycore.cardbrowser.resources.game_logo_yugioh
import org.jetbrains.compose.resources.DrawableResource

/**
 * A game's mark: its real logo where one can legitimately be used, and a Material symbol where it
 * cannot.
 *
 * ## Where the logos come from
 *
 * Two different provenances, and the difference matters enough to keep straight.
 *
 * ### Five from Wikimedia Commons, licence-checked
 *
 * Commons accepts only freely-licensed media, which is what makes these bundleable. A logo merely
 * *shown* on Wikipedia usually lives on Wikipedia itself under a non-free fair-use rationale that
 * does not permit redistribution; none of those are used. Each licence was checked individually
 * through the Commons API rather than assumed:
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
 * ### Two supplied by the project owner
 *
 * Riftbound and Altered are **not** in that table and are not equivalent to it. Neither exists on
 * Commons -- both games are recent enough that no freely-licensed mark has been uploaded, and the
 * only English Wikipedia files are a cover and a card back under non-free fair-use rationales. The
 * two bundled here were chosen and supplied by the owner of this project from third-party sites (a
 * card shop's CDN and a retailer's blog), and were downloaded and resized on request.
 *
 * So: no licence was verified for those two, because there is none to verify. They are the
 * publishers' trademarks used to identify the publishers' own games, which is the ordinary
 * nominative use every card database relies on, but anyone redistributing this app should make
 * their own decision about them rather than assume they carry the same clearance as the five above.
 *
 * The Material fallback below is therefore no longer reached by any shipped game -- it remains
 * because a logo that fails to load still has to draw something, and because the next game added
 * will not have one on day one.
 *
 * ## Artwork drawn for dark backgrounds
 *
 * Three of these logos have no dark outline: Altered is a near-white wordmark, One Piece is flat
 * yellow, and Riftbound sets a white "LEAGUE OF LEGENDS" under its orange title. On the light theme
 * they wash out -- measured as the share of visible pixels falling below a 2:1 contrast ratio
 * against a light tile, Altered loses 49% and One Piece 76%. Riftbound's overall figure is a milder
 * 31%, but it is concentrated entirely in the subtitle, which disappears completely.
 *
 * Those three keep a dark tile on both themes, so the artwork sits on what it was drawn for.
 *
 * Magic, Pokémon and Yu-Gi-Oh are *not* flagged despite similar raw numbers, because their dark
 * outlines carry the shape: they read correctly against a pale tile where an unoutlined wordmark
 * does not. That is a judgement from looking at all seven on both themes, with the measurement as
 * supporting evidence rather than as the rule.
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
 * @property icon a Material symbol. The fallback when a logo is absent or fails to load
 * @property accent used for the tile, so the rows are distinguishable at a glance
 * @property logo the game's real logo, or `null` to use [icon]
 * @property tintLogo true only for a single-colour wordmark, which is then drawn in the theme's
 *   foreground colour so it stays legible on both themes. Never true for colour artwork
 * @property prefersDarkBackdrop true for artwork drawn for dark backgrounds, which then keeps a
 *   dark tile on the light theme too. See the note below
 */
data class GameVisual(
	val icon: ImageVector,
	val accent: Color,
	val logo: DrawableResource? = null,
	val tintLogo: Boolean = false,
	val prefersDarkBackdrop: Boolean = false,
) {

	companion object {

		/** The mark for [game]. Total, so a new game cannot be added without choosing one. */
		fun of(game: Game): GameVisual = when (game) {
			Game.RIFTBOUND -> GameVisual(
				icon = Icons.Outlined.Bolt,
				accent = Color(0xFF7C6BF5),
				logo = Res.drawable.game_logo_riftbound,
				prefersDarkBackdrop = true,
			)
			Game.ALTERED -> GameVisual(
				icon = Icons.Outlined.Terrain,
				accent = Color(0xFF4FA97C),
				logo = Res.drawable.game_logo_altered,
				prefersDarkBackdrop = true,
			)
			Game.ONE_PIECE -> GameVisual(
				icon = Icons.Outlined.Sailing,
				accent = Color(0xFF3E8FD0),
				logo = Res.drawable.game_logo_onepiece,
				prefersDarkBackdrop = true,
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
		 * Only the Commons files carry a licence at all, and of those only Yu-Gi-Oh! requires a
		 * credit. It is shown on the game
		 * picker rather than buried in a settings page, because an attribution nobody sees is not
		 * an attribution.
		 */
		const val LOGO_ATTRIBUTION: String =
			"Game logos from Wikimedia Commons. The Yu-Gi-Oh! logo is by Kazuki Takahashi, CC BY 3.0."
	}
}
