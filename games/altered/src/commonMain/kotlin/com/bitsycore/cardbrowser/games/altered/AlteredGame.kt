package com.bitsycore.cardbrowser.games.altered

import com.bitsycore.cardbrowser.core.game.GameDomain
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.games.api.GameArt
import com.bitsycore.cardbrowser.games.altered.resources.Res
import com.bitsycore.cardbrowser.games.altered.resources.game_logo_altered
import org.jetbrains.compose.resources.DrawableResource

/**
 * Altered.
 */
object AlteredGame : GameProfile {

	override val id: GameId = GameId("altered")

	override val displayName: String = "Altered"

	override val shortName: String = "Altered"

	override val vocabulary: GameVocabulary = GameVocabulary(
		domain = "Faction",
		cost = "Hand cost",
		// Altered's second number is its reserve cost, which is not a "stat" in the sense the
		// other games use the slot for. The slot is generic; this is what to call it here.
		primaryStat = "Reserve cost",
	)

	/**
	 * Only Common and Rare occur in a printed set.
	 *
	 * Unique cards are generated per player and are the top tier when the mirror carries any.
	 */
	override val rarityLadder: List<String> = listOf("Common", "Rare", "Unique")

	/**
	 * Altered is not sold on Cardmarket.
	 *
	 * Checked: the site has no section for the game. `null` suppresses the marketplace link
	 * entirely, which is the difference between "we do not know the path" and "there is no page" --
	 * both end in no button, but only one of them is worth someone re-checking later.
	 */
	override val cardmarketSlug: String? = null

	/**
	 * The six factions, keyed by the two-letter reference the API itself uses.
	 *
	 * `mainFaction.reference` is canonical where `mainFaction.name` is localised, so the reference
	 * is the key and this supplies the English label -- which is why an Altered filter keeps
	 * working when the source is answering in French.
	 */
	override val domains: List<GameDomain> = listOf(
		GameDomain("AX", "Axiom", 0xFFB0743A),
		GameDomain("BR", "Bravos", 0xFFD8453C),
		GameDomain("LY", "Lyra", 0xFFE07AA8),
		GameDomain("MU", "Muna", 0xFF3F9A62),
		GameDomain("OR", "Ordis", 0xFF3B7FC4),
		GameDomain("YZ", "Yzmir", 0xFF8E5CC0),
	)

}

/** Altered's mark. See [GameArt] for where each logo came from and what may be done with it. */
object AlteredArt : GameArt {

	override val game: GameProfile = AlteredGame

	override val logo: DrawableResource = Res.drawable.game_logo_altered

	override val accentArgb: Long = 0xFF4FA97C

	// A near-white wordmark: 49% of its visible pixels fall below a 2:1 contrast ratio against a
	// light tile.
	override val backdropArgb: Long = GameArt.DARK_BACKDROP
}
