package com.bitsycore.cardbrowser.games.pokemon

import com.bitsycore.cardbrowser.core.game.GameProfile
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

}

/** Pokemon's mark. See [GameArt] for where each logo came from and what may be done with it. */
object PokemonArt : GameArt {

	override val game: GameProfile = PokemonGame

	override val logo: DrawableResource = Res.drawable.game_logo_pokemon

	override val accentArgb: Long = 0xFFE4573D
}
