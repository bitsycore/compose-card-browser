package com.bitsycore.cardbrowser.games.onepiece

import com.bitsycore.cardbrowser.core.game.GameDomain
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.games.api.GameArt
import com.bitsycore.cardbrowser.games.onepiece.resources.Res
import com.bitsycore.cardbrowser.games.onepiece.resources.game_logo_onepiece
import org.jetbrains.compose.resources.DrawableResource

/**
 * One Piece Card Game.
 */
object OnePieceGame : GameProfile {

	override val id: GameId = GameId("onepiece")

	override val displayName: String = "One Piece Card Game"

	override val shortName: String = "One Piece"

	override val vocabulary: GameVocabulary = GameVocabulary(
		domain = "Colour",
		cost = "Cost",
		primaryStat = "Power",
		secondaryStat = "Life",
	)

	/**
	 * Expanded from the API's abbreviations by the adapter before it gets here.
	 *
	 * Leader and Promo are deliberately absent. They are not points on the rarity ladder -- a
	 * Leader is a card role and a Promo is how a card was distributed -- so they sort after the
	 * tiers rather than being given a rank they do not have.
	 */
	override val rarityLadder: List<String> =
		listOf("Common", "Uncommon", "Rare", "Super Rare", "Secret Rare")

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
	override val cardmarketSlug: String = "OnePiece"

	/**
	 * The six colours.
	 *
	 * Measured from OP-01, which reports `Blue`, `Green`, `Purple`, `Red` and the *dual* values
	 * `Blue Purple` and `Green Red`. A dual-colour card is both of its colours rather than a
	 * seventh thing, so the adapter splits those and each half lands here -- which is what makes
	 * filtering by Blue match a Blue/Purple leader.
	 */
	override val domains: List<GameDomain> = listOf(
		GameDomain("red", "Red", 0xFFD8453C),
		GameDomain("green", "Green", 0xFF3F9A62),
		GameDomain("blue", "Blue", 0xFF3B7FC4),
		GameDomain("purple", "Purple", 0xFF8E5CC0),
		GameDomain("black", "Black", 0xFF4A4351),
		GameDomain("yellow", "Yellow", 0xFFE8C13A),
	)

}

/** OnePiece's mark. See [GameArt] for where each logo came from and what may be done with it. */
object OnePieceArt : GameArt {

	override val game: GameProfile = OnePieceGame

	override val logo: DrawableResource = Res.drawable.game_logo_onepiece

	override val accentArgb: Long = 0xFF3E8FD0

	// A flat yellow wordmark with no dark outline: 76% of its visible pixels fall below a 2:1
	// contrast ratio against a light tile.
	override val prefersDarkBackdrop: Boolean = true
}
