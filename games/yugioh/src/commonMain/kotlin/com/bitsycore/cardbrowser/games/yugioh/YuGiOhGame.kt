package com.bitsycore.cardbrowser.games.yugioh

import com.bitsycore.cardbrowser.core.game.GameDomain
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
 * Its logo is the publisher's own current mark, supplied by the project owner, and carries no
 * verified licence. It used to be a CC BY Commons file whose credit the app displayed; that credit
 * left with the file. [GameArt] owns the licence story for all ten logos.
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
	 * Cardmarket's path segment for this game.
	 *
	 * Confirmed against a real Cardmarket URL supplied by the project owner. It was a guess before
	 * that -- the site answers 403 to every scripted request, so a browser is the only oracle.
	 */
	override val cardmarketSlug: String = "YuGiOh"

	// No category id: Yu-Gi-Oh's own search submits `idCategory=0`, which is "any" and therefore
	// the same as sending nothing. See [GameProfile.cardmarketCategoryId].

	/**
	 * The seven attributes.
	 *
	 * Six were measured from a 120-card sample -- `DARK`, `EARTH`, `FIRE`, `LIGHT`, `WATER`,
	 * `WIND`. `DIVINE` is the seventh and is declared too: it belongs to three cards in the whole
	 * game, so a sample that misses it is not evidence it does not exist.
	 */
	override val domains: List<GameDomain> = listOf(
		GameDomain("light", "Light", 0xFFF0E2A8),
		GameDomain("dark", "Dark", 0xFF6A4A8E),
		GameDomain("water", "Water", 0xFF3B7FC4),
		GameDomain("fire", "Fire", 0xFFD8503C),
		GameDomain("earth", "Earth", 0xFF9A7B4F),
		GameDomain("wind", "Wind", 0xFF5FAE85),
		GameDomain("divine", "Divine", 0xFFE0B23A),
	)

}

/** YuGiOh's mark. See [GameArt] for where each logo came from and what may be done with it. */
object YuGiOhArt : GameArt {

	override val game: GameProfile = YuGiOhGame

	override val logo: DrawableResource = Res.drawable.game_logo_yugioh

	/** The red of the flames, sampled from the artwork rather than chosen. */
	override val accentArgb: Long = 0xFFE01020

	// No dark backdrop, and this one is worth recording because the measurement says otherwise.
	// 44% of its visible pixels fall below a 2:1 contrast ratio against a light tile -- close to
	// Altered's flagged 49% -- and the loss is spread evenly rather than concentrated. Looking at
	// it settles it: the figure is counting the *white interiors* of the letters, and every one of
	// them sits inside a heavy black outline that carries the shape on either theme. This is the
	// case `GameArt`'s doc means by "the measurement is supporting evidence rather than the rule".
}
