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
		secondaryStat = "Toughness",
	)

	/** As Scryfall spells it. `special` and `bonus` are Scryfall's own trailing tiers. */
	override val rarityLadder: List<String> =
		listOf("common", "uncommon", "rare", "mythic", "special", "bonus")

}

/** Magic's mark. See [GameArt] for where each logo came from and what may be done with it. */
object MagicArt : GameArt {

	override val game: GameProfile = MagicGame

	override val logo: DrawableResource = Res.drawable.game_logo_magic

	override val accentArgb: Long = 0xFFD9A441
}
