package com.bitsycore.cardbrowser.games.lorcana

import com.bitsycore.cardbrowser.core.game.GameDomain
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.games.api.GameArt
import com.bitsycore.cardbrowser.games.lorcana.resources.Res
import com.bitsycore.cardbrowser.games.lorcana.resources.game_logo_lorcana
import org.jetbrains.compose.resources.DrawableResource

/**
 * Disney Lorcana.
 */
object LorcanaGame : GameProfile {

	override val id: GameId = GameId("lorcana")

	override val displayName: String = "Disney Lorcana"

	override val shortName: String = "Lorcana"

	/**
	 * Lore has no slot and is deliberately not squeezed into one.
	 *
	 * A Lorcana character carries four numbers -- ink cost, Strength, Willpower and Lore -- and the
	 * shared model holds three. Cost, Strength and Willpower take the three slots because they are
	 * the ones every card type uses; Lore is quest-specific and would have to displace Willpower to
	 * fit. So it is left unmapped rather than put in a field labelled something it is not, which is
	 * the same reason these slots are named `cost`/`primary`/`secondary` and not after any one game.
	 */
	override val vocabulary: GameVocabulary = GameVocabulary(
		domain = "Ink",
		cost = "Ink cost",
		cardType = "Type",
		primaryStat = "Strength",
		secondaryStat = "Willpower",
	)

	/**
	 * Six of the eleven rarities the catalogue actually carries, in the order the game prints them.
	 *
	 * Measured across all 20 sets on TCGplayer: Common, Uncommon, Rare, Super Rare, Legendary,
	 * Enchanted, Epic, Iconic, Quest, Promo and the literal string `None`.
	 *
	 * The six here are the ordered ladder every set has used since the game launched. Epic and
	 * Iconic arrived with the later sets and where they sit relative to Enchanted has not been
	 * checked against anything authoritative, so they are left off -- `RarityLadder` parks an
	 * unnamed rarity after the ones it knows rather than inventing a rank for it, which is a
	 * visible "we have not placed this" instead of a confident wrong answer. Quest and Promo are
	 * not tiers at all: one is a product line and the other is how a card was distributed.
	 */
	override val rarityLadder: List<String> = listOf(
		"Common",
		"Uncommon",
		"Rare",
		"Super Rare",
		"Legendary",
		"Enchanted",
	)

	/**
	 * The six inks.
	 *
	 * A dual-ink card is both of its inks rather than a seventh thing, so the adapter splits the
	 * source's `Amethyst;Sapphire` and each half lands here -- which is what makes filtering by
	 * Amethyst match a dual card. Measured: 21 distinct values over 3241 cards, being the six
	 * singles plus fifteen pairs.
	 */
	override val domains: List<GameDomain> = listOf(
		GameDomain("amber", "Amber", 0xFFE0A526),
		GameDomain("amethyst", "Amethyst", 0xFF8E5CC0),
		GameDomain("emerald", "Emerald", 0xFF3F9A62),
		GameDomain("ruby", "Ruby", 0xFFD8453C),
		GameDomain("sapphire", "Sapphire", 0xFF3B7FC4),
		GameDomain("steel", "Steel", 0xFF8A8F98),
	)

	// No Cardmarket slug. The site does sell Lorcana, but this app only ships a path segment that
	// has been seen on a real page -- Cardmarket answers 403 to every scripted request, so a
	// browser is the only oracle and nobody has looked yet. See `GameProfile.cardmarketSlug`.
}

/** Lorcana's mark. See [GameArt] for where each logo came from and what may be done with it. */
object LorcanaArt : GameArt {

	override val game: GameProfile = LorcanaGame

	override val logo: DrawableResource = Res.drawable.game_logo_lorcana

	/** The gold of the wordmark itself, sampled from the artwork rather than chosen. */
	override val accentArgb: Long = 0xFFC0A878

	/**
	 * The gold wordmark washes out on a pale tile.
	 *
	 * Measured the same way the other three flagged logos were: 33% of its visible pixels fall
	 * below a 2:1 contrast ratio against a light tile, and the loss is not spread evenly -- by
	 * fifths of the image it runs 0%, 0%, 36%, 48%, 2%. Those middle bands are "LORCANA" itself,
	 * so what disappears is the name. The "Disney" script above it is fine, which is exactly the
	 * shape of the Riftbound case.
	 */
	override val backdropArgb: Long = GameArt.DARK_BACKDROP
}
