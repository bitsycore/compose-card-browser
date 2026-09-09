package com.bitsycore.cardbrowser.games.altered

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.games.api.GameArt
import com.bitsycore.cardbrowser.games.altered.resources.Res
import com.bitsycore.cardbrowser.games.altered.resources.game_logo_altered
import org.jetbrains.compose.resources.DrawableResource

/**
 * Altered.
 */
object AlteredGame : GameProfile {

	override val id: GameId = GameId("altered")

	override val displayName: String = "Altered"

	override val shortName: String = "Altered"

	override val vocabulary: GameVocabulary = GameVocabulary(
		domain = "Faction",
		cost = "Hand cost",
		// Altered's second number is its reserve cost, which is not a "stat" in the sense the
		// other games use the slot for. The slot is generic; this is what to call it here.
		primaryStat = "Reserve cost",
	)

	/**
	 * Only Common and Rare occur in a printed set.
	 *
	 * Unique cards are generated per player and are the top tier when the mirror carries any.
	 */
	override val rarityLadder: List<String> = listOf("Common", "Rare", "Unique")

	/**
	 * Cardmarket's path segment. **Not verified against a live page.**
	 *
	 * Every other fact in this project was measured. This one could not be: Cardmarket answers 403
	 * to every scripted request, including one for the Riftbound path that is known to work, and
	 * the 403/404 split is not an existence oracle either -- a nonsense path answered 403 on one
	 * attempt and 404 on the next.
	 *
	 * So this follows Cardmarket's observable convention -- the game's name, PascalCase, no
	 * separators -- at the project owner's request, having been told it is a guess. If it is wrong
	 * the button lands on a 404, and the fix is this one line.
	 */
	override val cardmarketSlug: String = "Altered"

}

/** Altered's mark. See [GameArt] for where each logo came from and what may be done with it. */
object AlteredArt : GameArt {

	override val game: GameProfile = AlteredGame

	override val logo: DrawableResource = Res.drawable.game_logo_altered

	override val accentArgb: Long = 0xFF4FA97C

	// A near-white wordmark: 49% of its visible pixels fall below a 2:1 contrast ratio against a
	// light tile.
	override val prefersDarkBackdrop: Boolean = true
}
