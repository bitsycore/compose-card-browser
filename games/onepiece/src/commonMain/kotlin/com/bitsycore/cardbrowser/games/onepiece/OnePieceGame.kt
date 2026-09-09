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
	 * Cardmarket's path segment for this game.
	 *
	 * Confirmed against a real Cardmarket URL supplied by the project owner. It was a guess before
	 * that -- the site answers 403 to every scripted request, so a browser is the only oracle.
	 */
	override val cardmarketSlug: String = "OnePiece"

	/** Read off a real search URL for this game. See [GameProfile.cardmarketCategoryId]. */
	override val cardmarketCategoryId: Int = 1621

	/**
	 * One Piece reprints the same character across sets, so the name alone is not a card.
	 * See [GameProfile.cardmarketSearchIncludesCode].
	 */
	override val cardmarketSearchIncludesCode: Boolean = true

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

	/**
	 * Kept from the previous mark, because this one supplies no colour to sample.
	 *
	 * The wordmark is pure black -- measured, 0% of its visible pixels carry any saturation -- so
	 * there is nothing in the artwork to take an accent from. This blue is inherited rather than
	 * measured, which is the honest description of it.
	 */
	override val accentArgb: Long = 0xFF3E8FD0

	/**
	 * A single-colour wordmark, so it is drawn in the theme's own foreground.
	 *
	 * The mark this replaced was flat yellow with no outline and lost 76% of its visible pixels to
	 * a sub-2:1 contrast ratio on a light tile, which is why it used to ask for a dark backdrop.
	 * The publisher's own mark is black: 0% low contrast against a light tile and 0% saturation.
	 * That inverts the problem -- it is unreadable on the *dark* theme instead -- and tinting is
	 * the answer to that, not a dark plate. The two flags are mutually exclusive and `AppModuleTest`
	 * asserts it, so this had to change as a pair.
	 */
	override val tintLogo: Boolean = true
}
