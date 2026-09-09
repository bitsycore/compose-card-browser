package com.bitsycore.cardbrowser.games.wowtcg

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.GameId

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
