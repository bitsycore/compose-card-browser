package com.bitsycore.cardbrowser.games.cyberpunk

import com.bitsycore.cardbrowser.core.game.GameDomain
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.games.api.GameArt
import com.bitsycore.cardbrowser.games.cyberpunk.resources.Res
import com.bitsycore.cardbrowser.games.cyberpunk.resources.game_logo_cyberpunk
import org.jetbrains.compose.resources.DrawableResource

/**
 * Cyberpunk TCG, the card game set in Night City.
 *
 * This module exists because the game's data does: it was left out at first on the grounds that no
 * source served it, and that stopped being true when TCGplayer opened a category for it ahead of
 * the November 2026 release. See `docs/PROVIDER_RESEARCH.md`.
 */
object CyberpunkGame : GameProfile {

	override val id: GameId = GameId("cyberpunk")

	override val displayName: String = "Cyberpunk TCG"

	/**
	 * RAM has no slot, because it is not a number.
	 *
	 * The source states it as `x1`/`x2`, a multiplier rather than a quantity, and the shared model's
	 * three slots hold integers. Parsing the `x` off would turn a value the game writes one way into
	 * one it does not, so it stays out. Eddies is a flag rather than a stat and is likewise absent.
	 */
	override val vocabulary: GameVocabulary = GameVocabulary(
		domain = "Colour",
		cost = "Cost",
		cardType = "Type",
		primaryStat = "Power",
	)

	/**
	 * Four of the nine rarities the catalogue carries.
	 *
	 * Measured across all nine groups: Common, Uncommon, Rare, Epic, Nova Rare, Secret, and three
	 * `Iconic` variants. Only the first four are an order anyone can state without guessing -- where
	 * Nova Rare sits against Secret, or what separates Iconic Legend from Iconic Secret, is not
	 * something this app has checked. `RarityLadder` sorts the rest after these, alphabetically,
	 * which reads as unplaced rather than as a confident wrong ranking.
	 */
	override val rarityLadder: List<String> = listOf(
		"Common",
		"Uncommon",
		"Rare",
		"Epic",
	)

	/** The four colours, measured across all 408 cards the catalogue holds today. */
	override val domains: List<GameDomain> = listOf(
		GameDomain("red", "Red", 0xFFD8453C),
		GameDomain("blue", "Blue", 0xFF3B7FC4),
		GameDomain("green", "Green", 0xFF3F9A62),
		GameDomain("yellow", "Yellow", 0xFFE8C13A),
	)

	// No Cardmarket slug: the site has no section for this game yet, the first set being unreleased.
}

/** Cyberpunk's mark. See [GameArt] for where each logo came from and what may be done with it. */
object CyberpunkArt : GameArt {

	override val game: GameProfile = CyberpunkGame

	override val logo: DrawableResource = Res.drawable.game_logo_cyberpunk

	override val accentArgb: Long = 0xFFFCEE0A

	/**
	 * A single-colour glyph, so it is drawn in the theme's foreground and reads on both themes.
	 *
	 * This is the *franchise* wordmark rather than the card game's own lockup -- the TCG has no
	 * freely-licensed mark, and the game it is set in does. Commons serves it as a monochrome SVG,
	 * which is exactly the case [GameArt.tintLogo] exists for.
	 */
	override val tintLogo: Boolean = true
}
