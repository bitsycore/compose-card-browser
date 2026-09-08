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

	/** Magic, as Scryfall spells it. `special` and `bonus` are Scryfall's own trailing tiers. */
	private val MAGIC = listOf("common", "uncommon", "rare", "mythic", "special", "bonus")

	/**
	 * One Piece, expanded from the API's abbreviations by the adapter before it gets here.
	 *
	 * Leader and Promo are deliberately absent. They are not points on the rarity ladder -- a
	 * Leader is a card role and a Promo is how a card was distributed -- so they sort after the
	 * tiers rather than being given a rank they do not have.
	 */
	private val ONE_PIECE = listOf("Common", "Uncommon", "Rare", "Super Rare", "Secret Rare")

	/**
	 * Altered.
	 *
	 * Only Common and Rare occur in a printed set; Unique cards are generated per player and are
	 * the top tier when the mirror carries any.
	 */
	private val ALTERED = listOf("Common", "Rare", "Unique")

	/**
	 * Yu-Gi-Oh!, the tiers that occur across most sets.
	 *
	 * YGOPRODeck reports dozens of set-specific rarities -- "Starlight Rare", "Quarter Century
	 * Secret Rare", "Prismatic Ultimate Rare" and so on. Ordering those against each other would be
	 * an opinion rather than a fact, so only the widely agreed ladder is named and everything else
	 * sorts after it alphabetically.
	 */
	private val YU_GI_OH = listOf(
		"Common",
		"Rare",
		"Super Rare",
		"Ultra Rare",
		"Ultimate Rare",
		"Secret Rare",
	)

	/**
	 * Wuthering Waves, whose API states rarity as a run of stars.
	 *
	 * Collection sits at the top: it is the premium treatment tier rather than a sixth star.
	 */
	private val WUTHERING_WAVES = listOf("★", "★★", "★★★", "★★★★", "★★★★★", "Collection")

	/**
	 * The ladder for [game], or an empty list when this app does not know one.
	 *
	 * Pokémon has none on purpose. TCGdex reports well over a hundred distinct rarity strings that
	 * differ per era and per locale -- "Holo Rare V", "Double rare", "Illustration rare",
	 * "ダブルレア" -- and no single ordering of them is a fact about the game. An invented ladder
	 * would look deliberate while being wrong, which is worse than sorting them alphabetically and
	 * saying nothing.
	 */
	fun forGame(game: Game): List<String> = when (game) {
		Game.RIFTBOUND -> RIFTBOUND
		Game.MAGIC -> MAGIC
		Game.ONE_PIECE -> ONE_PIECE
		Game.ALTERED -> ALTERED
		Game.YU_GI_OH -> YU_GI_OH
		Game.WUTHERING_WAVES -> WUTHERING_WAVES
		Game.POKEMON -> emptyList()
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
