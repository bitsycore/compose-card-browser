package com.bitsycore.cardbrowser.games.riftbound

import com.bitsycore.cardbrowser.core.game.GameDomain
import com.bitsycore.cardbrowser.core.game.GameRarityColour
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.games.api.GameArt
import com.bitsycore.cardbrowser.games.riftbound.resources.Res
import com.bitsycore.cardbrowser.games.riftbound.resources.game_logo_riftbound
import org.jetbrains.compose.resources.DrawableResource

/**
 * Riftbound, the League of Legends card game.
 *
 * Domains are its colour-like axis, energy its single play cost, and might and power its two stat
 * slots -- the names the shared card model's three numeric slots carry for this game.
 */
object RiftboundGame : GameProfile {

	override val id: GameId = GameId("riftbound")

	override val displayName: String = "Riftbound"

	override val shortName: String = "Riftbound"

	override val vocabulary: GameVocabulary = GameVocabulary(
		domain = "Domain",
		cost = "Energy",
		primaryStat = "Might",
		secondaryStat = "Power",
	)

	/**
	 * Lowest to highest.
	 *
	 * Showcase sits above Epic: it is the premium treatment tier rather than another power tier.
	 */
	override val rarityLadder: List<String> =
		listOf("Common", "Uncommon", "Rare", "Epic", "Showcase")

	/** The five the cards themselves print: white, blue, purple, orange, yellow. */
	override val rarityColours: List<GameRarityColour> = listOf(
		GameRarityColour("Common", 0xFFE8E8EC),
		GameRarityColour("Uncommon", 0xFF3E8FD0),
		GameRarityColour("Rare", 0xFF6F5BD0),
		GameRarityColour("Epic", 0xFFD98A2B),
		GameRarityColour("Showcase", 0xFFD9C24A),
	)


	/**
	 * The one game whose Cardmarket segment is confirmed.
	 *
	 * Checked against real pages, including a working scoped search URL. Every other game here
	 * declares none, because Cardmarket answers 403 to every scripted request and a plausible
	 * segment that lands on a 404 is worse than no button.
	 */
	override val cardmarketSlug: String = "Riftbound"

	/** Read off a real search URL for this game. See [GameProfile.cardmarketCategoryId]. */
	override val cardmarketCategoryId: Int = 1655
	/**
	 * The six domains plus colourless, in Riftcodex's own order of appearance.
	 *
	 * Measured: OGN reports exactly `Body`, `Calm`, `Chaos`, `Colorless`, `Fury`, `Mind` and
	 * `Order`. The colours are the ones the game prints on its own cards.
	 */
	override val domains: List<GameDomain> = listOf(
		GameDomain("fury", "Fury", 0xFFD9453B),
		GameDomain("calm", "Calm", 0xFF3E8FD0),
		GameDomain("mind", "Mind", 0xFF6F5BD0),
		GameDomain("body", "Body", 0xFFD98A2B),
		GameDomain("chaos", "Chaos", 0xFF8E3FA8),
		GameDomain("order", "Order", 0xFFD9C24A),
		GameDomain("colorless", "Colourless", 0xFF9A9AA5),
	)

}

/** Riftbound's mark. See [GameArt] for where each logo came from and what may be done with it. */
object RiftboundArt : GameArt {

	override val game: GameProfile = RiftboundGame

	override val logo: DrawableResource = Res.drawable.game_logo_riftbound

	override val accentArgb: Long = 0xFF7C6BF5

	// The white "LEAGUE OF LEGENDS" subtitle disappears entirely on a light tile.
	override val backdropArgb: Long = GameArt.DARK_BACKDROP
}
