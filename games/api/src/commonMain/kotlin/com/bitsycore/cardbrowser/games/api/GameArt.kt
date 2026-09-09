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
 * ### Six from Wikimedia Commons, licence-checked
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
 * | Yu-Gi-Oh! | **CC BY 3.0** | Attribution required -- Kazuki Takahashi. Shown in the app. |
 * | One Piece | Public domain | Below the threshold of originality. Trademarked. |
 * | Wuthering Waves | Public domain | Below the threshold of originality. Trademarked. |
 * | Cyberpunk | Public domain | Below the threshold of originality. Trademarked. |
 *
 * "Public domain, trademarked" is the ordinary state of a wordmark: no one holds a *copyright* in
 * it, so redistributing the file is fine, while the *trademark* still belongs to its owner. Using
 * it to identify that owner's game -- the only thing the app does with it -- is what trademarks are
 * for. The app remains unaffiliated with every publisher named, and says so on the same screen.
 *
 * ### Four supplied by the project owner
 *
 * Riftbound, Altered, Disney Lorcana and the WoW TCG are **not** in that table and are not
 * equivalent to it. None exists on Commons, and the only English Wikipedia files are covers and
 * card backs under non-free fair-use rationales. The four bundled here were chosen and supplied by
 * the owner of this project from third-party sites -- a card shop's CDN, a retailer's blog, a
 * community wiki and a storefront CDN -- and were downloaded and resized on request.
 *
 * So: no licence was verified for those four, because there is none to verify. They are the
 * publishers' trademarks used to identify the publishers' own games, which is the ordinary
 * nominative use every card database relies on, but anyone redistributing this app should make
 * their own decision about them rather than assume they carry the same clearance as the six above.
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
		 * The credit the Yu-Gi-Oh! logo's CC BY 3.0 licence requires.
		 *
		 * Only the Commons files carry a licence at all, and of those only Yu-Gi-Oh! requires a
		 * credit. It is shown on the game picker rather than buried in a settings page, because an
		 * attribution nobody sees is not an attribution.
		 */
		const val LOGO_ATTRIBUTION: String =
			"Game logos from Wikimedia Commons. The Yu-Gi-Oh! logo is by Kazuki Takahashi, CC BY 3.0."
	}
}
