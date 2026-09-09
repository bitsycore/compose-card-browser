package com.bitsycore.cardbrowser.core.game

import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CollectorNumberComparator

/**
 * Ranking and ordering by a game's rarity ladder.
 *
 * The mechanism only. The ladders themselves are [GameProfile.rarityLadder], declared by each game
 * module, so this file names no game and needs no editing when one is added -- which is the whole
 * reason it stopped being a `when` over a closed enum.
 *
 * Anything a ladder does not name sorts after everything it does, alphabetically among itself. That
 * is the honest fallback: a rarity this app has not heard of is not evidence of a new tier at the
 * bottom, so it is parked at the end rather than silently ranked.
 */
object RarityLadder {

	/**
	 * Where [rarity] sits in [ladder].
	 *
	 * Matched case-insensitively, because a provider's capitalisation is its own business. Returns
	 * [Int.MAX_VALUE] for anything unrecognised, including `null`, which sorts it last.
	 */
	fun rankOf(ladder: List<String>, rarity: String?): Int {
		if (rarity == null) return Int.MAX_VALUE
		val vIndex = ladder.indexOfFirst { it.equals(rarity, ignoreCase = true) }
		return if (vIndex >= 0) vIndex else Int.MAX_VALUE
	}

	/**
	 * Orders rarity names by [ladder], unknown ones alphabetically at the end.
	 *
	 * Used for the filter chips as well as the sort, so the two agree about what "ascending rarity"
	 * means.
	 */
	fun sorted(ladder: List<String>, rarities: Collection<String>): List<String> =
		rarities.sortedWith(compareBy({ rankOf(ladder, it) }, { it }))

	/** Orders printings by rarity, then by collector number to keep ties stable. */
	fun comparatorFor(ladder: List<String>): Comparator<CardPrinting> =
		compareBy<CardPrinting> { rankOf(ladder, it.classification.rarity) }
			.thenBy { it.classification.rarity ?: "" }
			.then(CollectorNumberComparator)
}
