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
 * ### Three from Wikimedia Commons, licence-checked
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
 * | Wuthering Waves | Public domain | Below the threshold of originality. Trademarked. |
 *
 * Yu-Gi-Oh!, One Piece and Cyberpunk were in this table until the project owner supplied each
 * publisher's own current mark, which are better likenesses of the games as they are sold today.
 * All three moved to the group below, and the CC BY credit Yu-Gi-Oh!'s Commons file required moved
 * out of the app with it. Cyberpunk's case was the weakest of the three from the start: the Commons
 * file was the *Cyberpunk 2077* wordmark standing in for a card game that had no free mark, so it
 * identified the setting rather than the game.
 *
 * "Public domain, trademarked" is the ordinary state of a wordmark: no one holds a *copyright* in
 * it, so redistributing the file is fine, while the *trademark* still belongs to its owner. Using
 * it to identify that owner's game -- the only thing the app does with it -- is what trademarks are
 * for. The app remains unaffiliated with every publisher named, and says so on the same screen.
 *
 * ### Seven supplied by the project owner
 *
 * Riftbound, Altered, Disney Lorcana, the WoW TCG, Yu-Gi-Oh!, One Piece and Cyberpunk are **not**
 * in that table and are not equivalent to it. The first four exist on neither Commons nor English
 * Wikipedia under any name searched, the only Wikipedia files being covers and card backs under
 * non-free fair-use rationales. The last three are the odd ones: a freely-licensed mark *was*
 * available for each and was replaced on request with the publisher's own current one, trading a
 * verified licence for a better likeness.
 *
 * All seven were chosen and supplied by the owner of this project from third-party sites -- a card
 * shop's CDN, a retailer's blog, a community wiki, a storefront CDN, and Konami's, Bandai's and the
 * Cyberpunk TCG's own image hosts -- and were downloaded and resized on request.
 *
 * So: no licence was verified for those seven, because there is none to verify. They are the
 * publishers' trademarks used to identify the publishers' own games, which is the ordinary
 * nominative use every card database relies on, but anyone redistributing this app should make
 * their own decision about them rather than assume they carry the same clearance as the three above.
 *
 * ## Artwork drawn for dark backgrounds
 *
 * Three of these logos have no dark outline: Altered is a near-white wordmark, Riftbound sets a
 * white "LEAGUE OF LEGENDS" under its orange title, and Lorcana's name is gold filigree. On the
 * light theme they wash out -- measured as the share of visible pixels falling below a 2:1 contrast
 * ratio against a light tile, Altered loses 49%. Riftbound's overall figure is a milder 31% and
 * Lorcana's 33%, but both are *concentrated* rather than spread: Riftbound's is entirely in the
 * subtitle, and Lorcana's runs 0/0/36/48/2% by fifths of the image, the middle bands being the word
 * LORCANA itself. In each case what vanishes is the part that names the game.
 *
 * Those three set [backdropArgb] to [DARK_BACKDROP], so the artwork sits on something like what it
 * was drawn for. All three are full-colour artwork, which is the reason they need a plate at all:
 * a mark that cannot be recoloured has to have its background changed instead.
 *
 * ## Artwork that can simply be painted
 *
 * A single-colour mark needs no plate, because it can be drawn in whatever colour suits the
 * background. Wuthering Waves and One Piece are black wordmarks and follow the theme's foreground.
 * Cyberpunk is the interesting one: its mark is published both yellow-on-black and black-on-yellow,
 * so it is drawn black on the light theme and in its own `0xFFFEEC00` on the dark one, via
 * [logoTintDarkArgb]. Following the plain foreground would make it white and lose the brand.
 *
 * It wore a yellow plate for one commit. That worked and was worse: a saturated block in the app
 * bar beside the back arrow, where the mark alone reads perfectly once painted correctly.
 *
 * Magic, Pokémon, Yu-Gi-Oh and the WoW TCG are *not* flagged despite comparable raw numbers -- the
 * WoW mark measures 18% spread evenly at 19/14/29/14/9% by fifths, and the Yu-Gi-Oh one a startling
 * 44% -- because their dark outlines carry the shape: they read correctly against a pale tile where
 * an unoutlined wordmark does not. Yu-Gi-Oh is the clearest case that the number is evidence and
 * not the rule; its 44% is counting the white *interiors* of outlined letters. That is a judgement
 * from looking at all of them on both themes, with the measurement as supporting evidence.
 *
 * @property game the profile this art belongs to, so a registry can key on it without a table
 * @property logo the game's wordmark, bundled by the game's own module
 * @property accentArgb the tile colour, as `0xAARRGGBB`. A `Long` rather than a Compose `Color` so
 *   this interface needs no graphics dependency; the UI converts it once
 * @property logoTintDarkArgb what to paint a [tintLogo] mark on the dark theme, instead of the
 *   theme's own foreground. For a brand whose single-colour mark has a colour: Cyberpunk's is drawn
 *   black on light and in its own yellow on dark, which is how its owner presents it, and neither of
 *   those is the theme's foreground. `null` means follow the foreground, which is right for a mark
 *   that is simply black-or-white
 * @property tintLogo true only for a single-colour wordmark, which is then drawn in the theme's
 *   foreground colour so it stays legible on both themes. Never true for colour artwork -- tinting
 *   a full-colour logo flattens it to a silhouette, and a teal Wuthering Waves mark is not its mark
 * @property backdropArgb the plate this artwork was drawn for, as `0xAARRGGBB`, or `null` to tint
 *   the accent as usual. It used to be a `prefersDarkBackdrop` boolean, which could only say "put
 *   it on the dark one" -- fine while every flagged logo wanted the same dark grey, and useless the
 *   moment a mark wanted its own brand colour behind it. A colour says everything the boolean did
 *   and one thing more, so [DARK_BACKDROP] is now a value rather than a hidden default
 */
interface GameArt {

	val game: GameProfile

	val logo: DrawableResource

	val accentArgb: Long

	val tintLogo: Boolean get() = false

	val logoTintDarkArgb: Long? get() = null

	val backdropArgb: Long? get() = null

	companion object {

		/**
		 * The plate for artwork drawn for dark backgrounds.
		 *
		 * Close to the dark theme's own surface, so on that theme it is nearly invisible and only
		 * the light theme sees a change. A fixed value rather than a theme colour, because the whole
		 * point is that it does *not* follow the theme.
		 */
		const val DARK_BACKDROP: Long = 0xFF201E26

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
