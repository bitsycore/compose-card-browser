package com.bitsycore.cardbrowser.games.wowtcg

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.games.api.GameArt
import com.bitsycore.cardbrowser.games.wowtcg.resources.Res
import com.bitsycore.cardbrowser.games.wowtcg.resources.game_logo_wowtcg
import org.jetbrains.compose.resources.DrawableResource

/**
 * World of Warcraft Trading Card Game.
 *
 * Discontinued in 2013, which is the fact that shapes everything about this entry: there is no
 * publisher database left to ask, so the app serves it from a marketplace catalogue and can show
 * far less per card than it can for a living game. What that costs is spelled out on the adapter.
 */
object WowTcgGame : GameProfile {

	override val id: GameId = GameId("wowtcg")

	override val displayName: String = "World of Warcraft TCG"

	override val shortName: String = "WoW TCG"

	/**
	 * The game's own words, even though today's source supplies none of the numbers.
	 *
	 * A vocabulary is a fact about the *game* -- an ally has always had attack and health -- and it
	 * stays true whether or not a source happens to carry them. Declaring it here means a better
	 * source arriving later needs no change to this file, and a stat with no value simply does not
	 * appear on screen in the meantime.
	 */
	override val vocabulary: GameVocabulary = GameVocabulary(
		cost = "Cost",
		cardType = "Type",
		primaryStat = "Attack",
		secondaryStat = "Health",
	)

	/**
	 * The full ladder, expanded by the adapter from the single letters the source sends.
	 *
	 * Measured over 1549 products: the rarity field holds exactly `C`, `U`, `R`, `E` and `L` and
	 * nothing else, so this is the whole ladder rather than the part of it that has been checked.
	 */
	override val rarityLadder: List<String> = listOf(
		"Common",
		"Uncommon",
		"Rare",
		"Epic",
		"Legendary",
	)

	// No domains. The game groups cards by class and faction, and the source states neither, so
	// there is nothing to declare -- an axis with no data behind it would be an empty filter.

	// No Cardmarket slug: the game has been out of print since 2013 and no section was looked for.
}

/** The WoW TCG's mark. See [GameArt] for where each logo came from and what may be done with it. */
object WowTcgArt : GameArt {

	override val game: GameProfile = WowTcgGame

	/** The blue of the "Trading Card Game" line, sampled from the artwork rather than chosen. */
	override val accentArgb: Long = 0xFF0078A8

	override val logo: DrawableResource = Res.drawable.game_logo_wowtcg

	// Not flagged for a dark backdrop, and measured rather than assumed: 18% of its visible pixels
	// fall below a 2:1 contrast ratio against a light tile, spread evenly across the image (19, 14,
	// 29, 14, 9 per fifth) rather than concentrated in one element the way Lorcana's and
	// Riftbound's are. The gold has a dark outline that carries the shape on a pale tile.
}
