package com.bitsycore.cardbrowser.games.magic

import com.bitsycore.cardbrowser.core.game.GameDomain
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameRarityColour
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.games.api.GameArt
import com.bitsycore.cardbrowser.games.magic.resources.Res
import com.bitsycore.cardbrowser.games.magic.resources.game_logo_magic
import org.jetbrains.compose.resources.DrawableResource

/**
 * Magic: The Gathering.
 *
 * Colours are its axis and mana value its single cost -- not "energy", which is what the shared
 * field used to be called.
 */
object MagicGame : GameProfile {

	override val id: GameId = GameId("magic")

	override val displayName: String = "Magic: The Gathering"

	override val shortName: String = "Magic"

	override val vocabulary: GameVocabulary = GameVocabulary(
		domain = "Colour",
		cost = "Mana value",
		primaryStat = "Power",
		secondaryStat = "Toughness"
	)

	/** As Scryfall spells it. `special` and `bonus` are Scryfall's own trailing tiers. */
	override val rarityLadder: List<String> =
		listOf("common", "uncommon", "rare", "mythic", "special", "bonus")

	/**
	 * The expansion symbol's own colours, which Magic has printed since Exodus.
	 *
	 * Not invented: black, silver, gold and the orange-red mythic are what is stamped on the card,
	 * and a player reads the rarity off that colour before reading any word. `special` and `bonus`
	 * are Scryfall's buckets for timeshifted and Un-set oddities, which have no printed symbol
	 * colour of their own, so they state none rather than being given a plausible one.
	 *
	 * Matched case-insensitively against Scryfall's lowercase strings -- see
	 * `GameProfile.rarityColourFor`.
	 */
	override val rarityColours: List<GameRarityColour> = listOf(
		GameRarityColour("common", 0xFF3A3A3E),
		GameRarityColour("uncommon", 0xFFA8B4BE),
		GameRarityColour("rare", 0xFFC9A227),
		GameRarityColour("mythic", 0xFFD1512D),
	)

	/**
	 * Cardmarket's path segment for this game.
	 *
	 * Confirmed against a real Cardmarket URL supplied by the project owner. It was a guess before
	 * that -- the site answers 403 to every scripted request, so a browser is the only oracle.
	 */
	override val cardmarketSlug: String = "Magic"

	/**
	 * WUBRG, plus colourless.
	 *
	 * The order is the one every Magic player and every Magic product uses, and no alphabetical
	 * sort produces it -- which is most of the reason this list exists rather than being derived
	 * from whatever the provider happened to send. The keys are Scryfall's own single letters, so
	 * the adapter has nothing to translate.
	 */
	override val domains: List<GameDomain> = listOf(
		GameDomain("W", "White", 0xFFF8F4E4),
		GameDomain("U", "Blue", 0xFF3B7FC4),
		GameDomain("B", "Black", 0xFF4A4351),
		GameDomain("R", "Red", 0xFFD8503C),
		GameDomain("G", "Green", 0xFF3F9A62),
		GameDomain("C", "Colourless", 0xFF9A9AA5),
	)

}

/** Magic's mark. See [GameArt] for where each logo came from and what may be done with it. */
object MagicArt : GameArt {

	override val game: GameProfile = MagicGame

	override val logo: DrawableResource = Res.drawable.game_logo_magic

	override val accentArgb: Long = 0xFFD9A441
}
