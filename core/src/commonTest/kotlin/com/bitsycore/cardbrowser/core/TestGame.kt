package com.bitsycore.cardbrowser.core

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.GameId

/**
 * A game invented for these tests.
 *
 * Core's tests deliberately do **not** use a real `:games:*` module. Core sits below those, so
 * depending on one would invert the layering -- and a test that only passes because `RiftboundGame`
 * happens to declare the right ladder would not be testing core's mechanisms at all. Its values
 * mirror Riftbound's because that is what the fixtures in `TestCards` describe.
 */
object TestGame : GameProfile {

	override val id: GameId = GameId("test-game")

	override val displayName: String = "Test Game"

	override val shortName: String = "Test"

	override val vocabulary: GameVocabulary = GameVocabulary(
		domain = "Domain",
		cost = "Energy",
		primaryStat = "Might",
		secondaryStat = "Power",
	)

	override val rarityLadder: List<String> =
		listOf("Common", "Uncommon", "Rare", "Epic", "Showcase")

	override val cardmarketSlug: String = "Riftbound"
}

/** A game that states no Cardmarket segment, which must suppress the link entirely. */
object TestGameWithoutMarketplace : GameProfile {

	override val id: GameId = GameId("test-game-no-market")

	override val displayName: String = "Unlisted Game"

	override val vocabulary: GameVocabulary = GameVocabulary()

	override val cardmarketSlug: String? = null
}
