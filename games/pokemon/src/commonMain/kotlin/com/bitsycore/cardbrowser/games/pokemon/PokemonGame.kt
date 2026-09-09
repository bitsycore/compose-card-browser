package com.bitsycore.cardbrowser.games.pokemon

import com.bitsycore.cardbrowser.core.game.GameDomain
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameRegion
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.games.api.GameArt
import com.bitsycore.cardbrowser.games.pokemon.resources.Res
import com.bitsycore.cardbrowser.games.pokemon.resources.game_logo_pokemon
import org.jetbrains.compose.resources.DrawableResource

/**
 * Pokémon.
 *
 * The one game here that declares no rarity ladder, and the reason that has to be allowed rather
 * than assumed -- see [rarityLadder].
 */
object PokemonGame : GameProfile {

	override val id: GameId = GameId("pokemon")

	override val displayName: String = "Pokémon"

	override val shortName: String = "Pokémon"

	override val vocabulary: GameVocabulary = GameVocabulary(
		domain = "Type",
		// No single play cost: a Pokémon card's cost is per attack, so there is no one number
		// to filter or sort on and the field stays empty rather than being faked from the first
		// attack's requirements.
		cost = null,
		cardType = "Category",
		primaryStat = "HP",
	)

	/**
	 * Deliberately empty.
	 *
	 * TCGdex reports well over a hundred distinct rarity strings that differ per era and per locale
	 * -- "Holo Rare V", "Double rare", "Illustration rare", "ダブルレア" -- and no single ordering of
	 * them is a fact about the game. An invented ladder would look deliberate while being wrong,
	 * which is worse than sorting them alphabetically and saying nothing.
	 */
	override val rarityLadder: List<String> = emptyList()

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
	override val cardmarketSlug: String = "Pokemon"

	/** Read off a real search URL for this game. See [GameProfile.cardmarketCategoryId]. */
	override val cardmarketCategoryId: Int = 51

	/**
	 * Region keys, named here so the adapter that tags sets and the game that labels them cannot
	 * drift apart on a spelling. They go into cache files, so they never change.
	 */
	const val REGION_INTERNATIONAL: String = "intl"
	const val REGION_JAPAN: String = "jp"
	const val REGION_TAIWAN: String = "tw"
	const val REGION_CHINA: String = "cn"

	/**
	 * Pokémon's four product lines, as measured across TCGdex's eleven locale catalogues.
	 *
	 * The set ids of these four barely overlap -- 4 shared ids out of 486 distinct sets -- because
	 * they are not translations of each other but separate release schedules with separate
	 * numbering. Japan gets `sv1a` "Triplet Beat", which was never printed internationally;
	 * Traditional Chinese has an exclusive `SC*` Sword & Shield line of its own.
	 *
	 * Korea is deliberately absent. All 95 Korean set ids are Japanese set ids, so Korea prints the
	 * Japan line and Korean is one of that line's languages -- listing it here would split one
	 * product line into two and show every Japanese set twice.
	 *
	 * The order is the order the chips appear in, largest line first.
	 */
	override val regions: List<GameRegion> = listOf(
		GameRegion(REGION_INTERNATIONAL, "International", "INTL"),
		GameRegion(REGION_JAPAN, "Japan", "JP"),
		GameRegion(REGION_TAIWAN, "Taiwan & HK", "TW"),
		GameRegion(REGION_CHINA, "China", "CN"),
	)

	/**
	 * The eleven types TCGdex serves, in the order the game itself lists them.
	 *
	 * Measured from `/v2/en/types`. The colours are the ones the cards use for their energy
	 * symbols.
	 */
	override val domains: List<GameDomain> = listOf(
		GameDomain("grass", "Grass", 0xFF6FBF5B),
		GameDomain("fire", "Fire", 0xFFE2542C),
		GameDomain("water", "Water", 0xFF3FA9E0),
		GameDomain("lightning", "Lightning", 0xFFF2C230),
		GameDomain("psychic", "Psychic", 0xFFB05FC0),
		GameDomain("fighting", "Fighting", 0xFFC1682F),
		GameDomain("darkness", "Darkness", 0xFF4A4B58),
		GameDomain("metal", "Metal", 0xFF8E9BA6),
		GameDomain("dragon", "Dragon", 0xFFC9A227),
		GameDomain("fairy", "Fairy", 0xFFE86FA8),
		GameDomain("colorless", "Colourless", 0xFFD8D3C6),
	)

}

/** Pokemon's mark. See [GameArt] for where each logo came from and what may be done with it. */
object PokemonArt : GameArt {

	override val game: GameProfile = PokemonGame

	override val logo: DrawableResource = Res.drawable.game_logo_pokemon

	override val accentArgb: Long = 0xFFE4573D
}
