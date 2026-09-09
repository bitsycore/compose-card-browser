package com.bitsycore.cardbrowser.games.api

import com.bitsycore.cardbrowser.core.game.GameProfile
import org.jetbrains.compose.resources.DrawableResource

/**
 * A game's mark: its logo and the accent colour the app draws around it.
 *
 * Separate from [GameProfile] because it is not a rule. A profile says what a game calls its cost
 * axis; this says what its wordmark looks like. Keeping them apart is what lets `:core` declare the
 * profile while staying free of Compose -- a `DrawableResource` would drag the resources runtime,
 * and with it the Compose runtime, into the domain module.
 *
 * Each `:games:*` module declares one of these next to its profile and bundles its own logo, so the
 * file lives with the game that owns it rather than in a shared drawable folder the app has to keep
 * in step with a table. The UI reads them from the registry and never enumerates games itself.
 *
 * ## Where the logos come from
 *
 * Two different provenances, and the difference matters enough to keep straight.
 *
 * ### Five from Wikimedia Commons, licence-checked
 *
 * Commons accepts only freely-licensed media, which is what makes these bundleable. A logo merely
 * *shown* on Wikipedia usually lives on Wikipedia itself under a non-free fair-use rationale that
 * does not permit redistribution; none of those are used. Each licence was checked individually
 * through the Commons API rather than assumed:
 *
 * | Game | Licence | Note |
 * | --- | --- | --- |
 * | Pokémon | Public domain | Below the threshold of originality. Trademarked. |
 * | Magic | Public domain | Below the threshold of originality. Trademarked. |
 * | One Piece | Public domain | Below the threshold of originality. Trademarked. |
 * | Wuthering Waves | Public domain | Below the threshold of originality. Trademarked. |
 * | Cyberpunk | Public domain | Below the threshold of originality. Trademarked. |
 *
 * Yu-Gi-Oh! was in this table until the project owner supplied the official mark from Konami's
 * own site, which is a better likeness of the game as it is sold today. It moved to the group
 * below, and the CC BY credit it required moved out of the app with it.
 *
 * "Public domain, trademarked" is the ordinary state of a wordmark: no one holds a *copyright* in
 * it, so redistributing the file is fine, while the *trademark* still belongs to its owner. Using
 * it to identify that owner's game -- the only thing the app does with it -- is what trademarks are
 * for. The app remains unaffiliated with every publisher named, and says so on the same screen.
 *
 * ### Five supplied by the project owner
 *
 * Riftbound, Altered, Disney Lorcana, the WoW TCG and Yu-Gi-Oh! are **not** in that table and are
 * not equivalent to it. The first four exist on neither Commons nor English Wikipedia under any
 * name searched, the only Wikipedia files being covers and card backs under non-free fair-use
 * rationales. Yu-Gi-Oh! is the odd one: a freely-licensed mark *was* available and was replaced on
 * request with the publisher's own current one, trading a verified licence for a better likeness.
 *
 * All five were chosen and supplied by the owner of this project from third-party sites -- a card
 * shop's CDN, a retailer's blog, a community wiki, a storefront CDN and Konami's own image host --
 * and were downloaded and resized on request.
 *
 * So: no licence was verified for those five, because there is none to verify. They are the
 * publishers' trademarks used to identify the publishers' own games, which is the ordinary
 * nominative use every card database relies on, but anyone redistributing this app should make
 * their own decision about them rather than assume they carry the same clearance as the five above.
 *
 * ## Artwork drawn for dark backgrounds
 *
 * Four of these logos have no dark outline: Altered is a near-white wordmark, One Piece is flat
 * yellow, Riftbound sets a white "LEAGUE OF LEGENDS" under its orange title, and Lorcana's name is
 * gold filigree. On the light theme they wash out -- measured as the share of visible pixels
 * falling below a 2:1 contrast ratio against a light tile, Altered loses 49% and One Piece 76%.
 * Riftbound's overall figure is a milder 31% and Lorcana's 33%, but both are *concentrated* rather
 * than spread: Riftbound's is entirely in the subtitle, and Lorcana's runs 0/0/36/48/2% by fifths
 * of the image, the middle bands being the word LORCANA itself. In each case what vanishes is the
 * part that names the game.
 *
 * Those four set [prefersDarkBackdrop], so the artwork sits on what it was drawn for.
 *
 * Magic, Pokémon, Yu-Gi-Oh and the WoW TCG are *not* flagged despite comparable raw numbers -- the
 * WoW mark measures 18%, spread evenly at 19/14/29/14/9% by fifths -- because their dark outlines
 * carry the shape: they read correctly against a pale tile where an unoutlined wordmark does not.
 * That is a judgement from looking at all of them on both themes, with the measurement as
 * supporting evidence rather than as the rule.
 *
 * @property game the profile this art belongs to, so a registry can key on it without a table
 * @property logo the game's wordmark, bundled by the game's own module
 * @property accentArgb the tile colour, as `0xAARRGGBB`. A `Long` rather than a Compose `Color` so
 *   this interface needs no graphics dependency; the UI converts it once
 * @property tintLogo true only for a single-colour wordmark, which is then drawn in the theme's
 *   foreground colour so it stays legible on both themes. Never true for colour artwork -- tinting
 *   a full-colour logo flattens it to a silhouette, and a teal Wuthering Waves mark is not its mark
 * @property prefersDarkBackdrop true for artwork drawn for dark backgrounds, which then keeps a
 *   dark tile on the light theme too
 */
interface GameArt {

	val game: GameProfile

	val logo: DrawableResource

	val accentArgb: Long

	val tintLogo: Boolean get() = false

	val prefersDarkBackdrop: Boolean get() = false

	companion object {

		/**
		 * Where the bundled logos come from.
		 *
		 * This used to carry a CC BY 3.0 credit to Kazuki Takahashi, which the Commons Yu-Gi-Oh!
		 * file required. That file is no longer bundled -- the project owner supplied the official
		 * one from Konami's site instead -- so the credit went with it. Removing it is the honest
		 * move rather than the risky one: crediting an author whose work the app no longer ships
		 * would be a false statement, and no other bundled file requires attribution.
		 *
		 * It is shown on the game picker rather than buried in a settings page, because an
		 * attribution nobody sees is not an attribution.
		 */
		const val LOGO_ATTRIBUTION: String =
			"Game logos are their publishers' trademarks, used to identify their own games."
	}
}
