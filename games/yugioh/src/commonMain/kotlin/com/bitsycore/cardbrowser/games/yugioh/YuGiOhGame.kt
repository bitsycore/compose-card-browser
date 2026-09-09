package com.bitsycore.cardbrowser.games.yugioh

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.games.api.GameArt
import com.bitsycore.cardbrowser.games.yugioh.resources.Res
import com.bitsycore.cardbrowser.games.yugioh.resources.game_logo_yugioh
import org.jetbrains.compose.resources.DrawableResource

/**
 * Yu-Gi-Oh!
 *
 * Its logo is the one bundled mark that carries a licence requiring credit -- see [GameArt].
 */
object YuGiOhGame : GameProfile {

	override val id: GameId = GameId("yugioh")

	override val displayName: String = "Yu-Gi-Oh!"

	override val shortName: String = "Yu-Gi-Oh!"

	override val vocabulary: GameVocabulary = GameVocabulary(
		domain = "Attribute",
		// Level is the closest thing Yu-Gi-Oh has to a single play cost. Spells and traps have
		// none, which is why the slot is nullable.
		cost = "Level",
		primaryStat = "ATK",
		secondaryStat = "DEF",
	)

	/**
	 * The tiers that occur across most sets.
	 *
	 * YGOPRODeck reports dozens of set-specific rarities -- "Starlight Rare", "Quarter Century
	 * Secret Rare", "Prismatic Ultimate Rare" and so on. Ordering those against each other would
	 * be an opinion rather than a fact, so only the widely agreed ladder is named and everything
	 * else sorts after it alphabetically.
	 */
	override val rarityLadder: List<String> = listOf(
		"Common",
		"Rare",
		"Super Rare",
		"Ultra Rare",
		"Ultimate Rare",
		"Secret Rare",
	)

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
	override val cardmarketSlug: String = "YuGiOh"

}

/** YuGiOh's mark. See [GameArt] for where each logo came from and what may be done with it. */
object YuGiOhArt : GameArt {

	override val game: GameProfile = YuGiOhGame

	override val logo: DrawableResource = Res.drawable.game_logo_yugioh

	override val accentArgb: Long = 0xFF9B5FC0
}
