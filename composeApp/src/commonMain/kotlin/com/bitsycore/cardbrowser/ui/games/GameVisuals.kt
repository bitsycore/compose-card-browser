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

/**
 * A mark and a colour for each game.
 *
 * **These are not the games' logos, and none of them is passed off as one.** Every game here is a
 * trademarked product and this app is not affiliated with any of their publishers; shipping their
 * artwork would be both a licensing problem and the same category of dishonesty the rest of this
 * codebase avoids. Riftcodex, TCGdex and the others supply card art, not brand assets.
 *
 * So each game gets a Material icon that gestures at its subject -- waves for Wuthering Waves, a
 * sail for One Piece -- plus a distinct colour. That is enough to tell seven rows apart at a
 * glance, which is what the icon is for, without implying any of them is official.
 *
 * Colours are picked for separation from one another rather than to match a brand.
 *
 * ## Supplying real logos
 *
 * [logoUrl] is the slot for them, and it is empty in this repository on purpose.
 *
 * No provider publishes one. All seven were checked: TCGdex, Scryfall and YGOPRODeck publish *set*
 * and *series* artwork, which is a different thing, and the publishers' own sites either serve no
 * logo as a static asset or serve one in a style that matches none of the others. Committing brand
 * artwork scraped from fan wikis would mean shipping files of unknown provenance and hotlinking
 * somebody's CDN, so nothing is committed here.
 *
 * Filling a slot in is one line each. Point it at a bundled asset or a URL you are happy to use,
 * and the picker shows it instead of the Material mark; a slot left null, or an image that fails to
 * load, falls back to the mark automatically, so a broken or missing logo can never leave an empty
 * row.
 *
 * @property icon a Material symbol, never a logo. The fallback, and today the default
 * @property accent used for the tile, so the rows are distinguishable at a glance
 * @property logoUrl the game's real logo, when one has been supplied. `null` uses [icon]
 */
data class GameVisual(
	val icon: ImageVector,
	val accent: Color,
	val logoUrl: String? = null,
) {

	companion object {

		/** The mark for [game]. Total, so a new game cannot be added without choosing one. */
		fun of(game: Game): GameVisual = when (game) {
			Game.RIFTBOUND -> GameVisual(Icons.Outlined.Bolt, Color(0xFF7C6BF5))
			// Material ships an actual Poké Ball glyph. It is a Material icon rather than
			// Nintendo's mark, which is exactly the distinction this class is about.
			Game.POKEMON -> GameVisual(Icons.Filled.CatchingPokemon, Color(0xFFE4573D))
			Game.MAGIC -> GameVisual(Icons.Outlined.AutoAwesome, Color(0xFFD9A441))
			Game.ONE_PIECE -> GameVisual(Icons.Outlined.Sailing, Color(0xFF3E8FD0))
			Game.ALTERED -> GameVisual(Icons.Outlined.Terrain, Color(0xFF4FA97C))
			Game.YU_GI_OH -> GameVisual(Icons.Outlined.Casino, Color(0xFF9B5FC0))
			Game.WUTHERING_WAVES -> GameVisual(Icons.Outlined.Waves, Color(0xFF2FA8A0))
		}
	}
}
