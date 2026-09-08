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
 * @property icon a Material symbol, never a logo
 * @property accent used for the icon tile, so the rows are distinguishable at a glance
 */
data class GameVisual(
	val icon: ImageVector,
	val accent: Color,
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
