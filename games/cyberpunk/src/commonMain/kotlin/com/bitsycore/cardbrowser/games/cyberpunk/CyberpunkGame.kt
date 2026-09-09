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

/**
 * Cyberpunk's mark -- the card game's own, not the video game's.
 *
 * See [GameArt] for where each logo came from and what may be done with it.
 */
object CyberpunkArt : GameArt {

	override val game: GameProfile = CyberpunkGame

	override val logo: DrawableResource = Res.drawable.game_logo_cyberpunk

	/** The brand yellow, sampled off the supplied mark: `#FEEC00` over 80% of its opaque pixels. */
	override val accentArgb: Long = 0xFFFEEC00

	/**
	 * The brand's own yellow plate, with the black wordmark on it.
	 *
	 * The publisher publishes this mark two ways -- yellow on black, and black on yellow -- and the
	 * second is what is bundled, at the project owner's suggestion. It sidesteps the problem the
	 * first one has outright: measured, **100%** of the yellow version's visible pixels fall below a
	 * 2:1 contrast ratio against a light tile, every fifth of the image alike, with no dark outline
	 * anywhere to carry the shape and the "TRADING CARD GAME" line gone completely.
	 *
	 * A dark grey plate would have fixed that too, and this is better: the tile is the game's colour
	 * rather than a neutral one, and the mark is drawn exactly as its owner draws it. Being able to
	 * say so is why [GameArt.backdropArgb] is a colour and not the boolean it replaced.
	 *
	 * Not [GameArt.tintLogo], despite the artwork now being single-colour. Tinting means "follow the
	 * theme's foreground", which on the dark theme would turn this white -- and white on yellow is
	 * the thing being avoided. The plate is fixed, so the mark on it must be too.
	 */
	override val backdropArgb: Long = 0xFFFEEC00
}
