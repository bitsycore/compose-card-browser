package com.bitsycore.cardbrowser.ui.games

import androidx.compose.ui.graphics.Color
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.games.api.GameArt

/**
 * Every game's mark, looked up by profile.
 *
 * The art itself lives in the game modules -- `RiftboundArt` next to `RiftboundGame`, with the logo
 * file beside them -- and this is only the lookup. It replaced a `when (game)` over a closed enum
 * that had to be edited, in this module, for every game added; the list of entries now lives in the
 * Koin graph, which is the one place a new module has to be mentioned anyway.
 *
 * A game with no entry draws no logo rather than disappearing. That split is deliberate: which
 * games the app offers is decided by the routing table, and forgetting a logo should not silently
 * remove a game from the picker.
 */
class GameArtRegistry(art: List<GameArt>) {

	private val mByGame: Map<String, GameArt> = art.associateBy { it.game.id.value }

	/** The art for [game], or `null` when its module ships none. */
	fun forGame(game: GameProfile): GameArt? = mByGame[game.id.value]
}

/**
 * The tile colour, as Compose wants it.
 *
 * [GameArt] states a plain `0xAARRGGBB` `Long` so that interface needs no graphics dependency, and
 * this is the one place it becomes a `Color`.
 */
val GameArt.accent: Color get() = Color(accentArgb.toInt())
