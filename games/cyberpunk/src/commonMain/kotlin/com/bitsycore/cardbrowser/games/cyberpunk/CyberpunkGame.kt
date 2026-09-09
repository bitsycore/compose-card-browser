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
	 * A single-colour mark, so it is painted rather than plated.
	 *
	 * The publisher draws this two ways -- yellow on black and black on yellow -- and the bundled
	 * artwork is one flat colour either way, which means the app can simply paint it to suit the
	 * background instead of putting a background behind it. Black on the light theme, and its own
	 * yellow on the dark one, where a plain foreground tint would make it white and lose the brand.
	 *
	 * That replaced a yellow plate with the black mark on it. The plate worked and was worse: it
	 * put a saturated yellow block in the app bar next to the back arrow, when the mark on its own
	 * reads perfectly well against both themes once it is painted the right colour. What it *did*
	 * fix is real, though, and still applies to the three logos that cannot be recoloured because
	 * they are full-colour artwork -- see [GameArt.backdropArgb].
	 *
	 * Measured on the yellow version of the artwork: 100% of its visible pixels fall below a 2:1
	 * contrast ratio against a light tile, every fifth of the image alike. That is why the light
	 * theme gets the black one rather than the yellow one, and not the other way around.
	 */
	override val tintLogo: Boolean = true

	/** See [GameArt.logoTintDarkArgb]. The brand yellow, sampled from the supplied mark. */
	override val logoTintDarkArgb: Long = 0xFFFEEC00
}
