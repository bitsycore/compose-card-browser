package com.bitsycore.cardbrowser.games.wutheringwaves

import com.bitsycore.cardbrowser.core.game.GameDomain
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.games.api.GameArt
import com.bitsycore.cardbrowser.games.wutheringwaves.resources.Res
import com.bitsycore.cardbrowser.games.wutheringwaves.resources.game_logo_wutheringwaves
import org.jetbrains.compose.resources.DrawableResource

/**
 * Wuthering Waves TCG.
 */
object WutheringWavesGame : GameProfile {

	override val id: GameId = GameId("wuwa")

	override val displayName: String = "Wuthering Waves TCG"

	override val shortName: String = "Wuthering Waves"

	override val vocabulary: GameVocabulary = GameVocabulary(
		domain = "Attribute",
		cost = "Cost",
		primaryStat = "Damage",
		secondaryStat = "Speed",
	)

	/**
	 * Stated by the source as a run of stars, and carried through as one.
	 *
	 * Collection sits at the top: it is the premium treatment tier rather than a sixth star.
	 */
	override val rarityLadder: List<String> =
		listOf("★", "★★", "★★★", "★★★★", "★★★★★", "Collection")


	/**
	 * None, and not for the usual reason.
	 *
	 * Cardmarket has no section for this game at all -- it is Japan-only so far -- so there is no
	 * segment to confirm rather than one that merely has not been.
	 */
	override val cardmarketSlug: String? = null
	/**
	 * The six elements, keyed by the numeric ids UCP's own filter options publish.
	 *
	 * Those ids are the same in all three locales -- attribute 2 is 焦熱, 热熔 and 용융 -- which is
	 * what makes them usable as the key. The labels here are English because the app's UI is.
	 */
	override val domains: List<GameDomain> = listOf(
		GameDomain("1", "Aero", 0xFF5FAE85),
		GameDomain("2", "Fusion", 0xFFD8503C),
		GameDomain("3", "Electro", 0xFF8E5CC0),
		GameDomain("4", "Havoc", 0xFF8E3F6A),
		GameDomain("5", "Glacio", 0xFF3FA9E0),
		GameDomain("6", "Spectro", 0xFFE8C13A),
	)

}

/** WutheringWaves's mark. See [GameArt] for where each logo came from and what may be done with it. */
object WutheringWavesArt : GameArt {

	override val game: GameProfile = WutheringWavesGame

	override val logo: DrawableResource = Res.drawable.game_logo_wutheringwaves

	override val accentArgb: Long = 0xFF2FA8A0

	// A solid black wordmark: both the mean luminance and the mean saturation of its visible
	// pixels measure 0, so drawn as-is on the dark theme it is a black shape on a near-black tile.
	override val tintLogo: Boolean = true
}
