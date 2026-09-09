package com.bitsycore.cardbrowser.games.riftbound

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


	/**
	 * The one game whose Cardmarket segment is confirmed.
	 *
	 * Checked against real pages, including a working scoped search URL. Every other game here
	 * declares none, because Cardmarket answers 403 to every scripted request and a plausible
	 * segment that lands on a 404 is worse than no button.
	 */
	override val cardmarketSlug: String = "Riftbound"
}

/** Riftbound's mark. See [GameArt] for where each logo came from and what may be done with it. */
object RiftboundArt : GameArt {

	override val game: GameProfile = RiftboundGame

	override val logo: DrawableResource = Res.drawable.game_logo_riftbound

	override val accentArgb: Long = 0xFF7C6BF5

	// The white "LEAGUE OF LEGENDS" subtitle disappears entirely on a light tile.
	override val prefersDarkBackdrop: Boolean = true
}
