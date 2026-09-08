package com.bitsycore.cardbrowser.core.model

/**
 * The order a game's rarities go in, where it is known.
 *
 * Rarity arrives from providers as a bare string -- Riftcodex says `"Epic"` and nothing about where
 * Epic sits relative to Rare. Sorting those alphabetically puts Common between Uncommon and Epic,
 * which is not an order anybody recognises, and it makes the rarity filter chips read as a jumble.
 *
 * So the ladder lives here, per game, as knowledge about the *game* rather than about any provider.
 * A second Riftbound provider spelling rarities the same way inherits it for free.
 *
 * Anything a ladder does not name is sorted after everything it does, alphabetically among itself.
 * That is the honest fallback: a rarity this app has not heard of is not evidence of a new tier at
 * the bottom, so it is parked at the end rather than silently ranked.
 */
object RarityLadder {

	/**
	 * Riftbound, lowest to highest.
	 *
	 * Showcase sits above Epic: it is the premium treatment tier rather than another power tier.
	 */
	private val RIFTBOUND = listOf("Common", "Uncommon", "Rare", "Epic", "Showcase")

	/** The ladder for [game], or an empty list when this app does not know one. */
	fun forGame(game: Game): List<String> = when (game) {
		Game.RIFTBOUND -> RIFTBOUND
		// Not filled in for a game with no adapter: a guessed ladder is worse than no ladder,
		// because the sort would look deliberate while being wrong.
		Game.MAGIC, Game.POKEMON, Game.ONE_PIECE -> emptyList()
	}

	/**
	 * Where [rarity] sits in [game]'s ladder.
	 *
	 * Matched case-insensitively, because a provider's capitalisation is its own business. Returns
	 * [Int.MAX_VALUE] for anything unrecognised, including `null`, which sorts it last.
	 */
	fun rankOf(game: Game, rarity: String?): Int {
		if (rarity == null) return Int.MAX_VALUE
		val vIndex = forGame(game).indexOfFirst { it.equals(rarity, ignoreCase = true) }
		return if (vIndex >= 0) vIndex else Int.MAX_VALUE
	}

	/**
	 * Orders rarity names by the ladder, unknown ones alphabetically at the end.
	 *
	 * Used for the filter chips as well as the sort, so the two agree about what "ascending rarity"
	 * means.
	 */
	fun sorted(game: Game, rarities: Collection<String>): List<String> =
		rarities.sortedWith(compareBy({ rankOf(game, it) }, { it }))

	/** Orders printings by rarity, then by collector number to keep ties stable. */
	fun comparatorFor(game: Game): Comparator<CardPrinting> =
		compareBy<CardPrinting> { rankOf(game, it.classification.rarity) }
			.thenBy { it.classification.rarity ?: "" }
			.then(CollectorNumberComparator)
}
