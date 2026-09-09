package com.bitsycore.cardbrowser.games.magic

import com.bitsycore.cardbrowser.core.game.GameProfile
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
	override val cardmarketSlug: String = "Magic"

}

/** Magic's mark. See [GameArt] for where each logo came from and what may be done with it. */
object MagicArt : GameArt {

	override val game: GameProfile = MagicGame

	override val logo: DrawableResource = Res.drawable.game_logo_magic

	override val accentArgb: Long = 0xFFD9A441
}
