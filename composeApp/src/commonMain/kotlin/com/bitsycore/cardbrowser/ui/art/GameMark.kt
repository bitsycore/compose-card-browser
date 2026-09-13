package com.bitsycore.cardbrowser.ui.art

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.games.api.GameArt
import com.bitsycore.cardbrowser.ui.component.AppIcons
import com.bitsycore.cardbrowser.ui.theme.isDarkTheme
import org.jetbrains.compose.resources.painterResource

/**
 * A game's mark: the logo, the tile it sits on, and how each is painted.
 *
 * Here rather than on the game picker because three screens draw one -- the picker, the set list's
 * title bar and the first-launch setup -- and two of them drawing it their own way is how three
 * logos ended up invisible on one screen and fine on the other.
 *
 * [GameArtRegistry] beside this answers *which* art a game has; this answers how to draw it.
 */

/**
 * How to paint a game's mark, or `null` to leave the artwork alone.
 *
 * Three cases, and the middle one is why this is a function rather than a line at each call site:
 *
 * - full-colour artwork is never recoloured, because tinting flattens it to a silhouette;
 * - a single-colour mark with a brand colour is painted in it on dark and left as drawn on light;
 * - any other single-colour mark follows the theme's own foreground, so it inverts with the theme.
 */
@Composable
internal fun logoTintFor(art: GameArt): ColorFilter? {
	if (!art.tintLogo) return null
	val vDarkTint = art.logoTintDarkArgb
	return if (vDarkTint != null && isDarkTheme()) {
		ColorFilter.tint(Color(vDarkTint.toInt()))
	} else {
		ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
	}
}

/**
 * A game's mark in a tinted tile.
 *
 * `null` art means the game's module ships no logo -- see [GameArtRegistry] for why that is a
 * missing logo rather than a missing game.
 *
 * Internal, and shared with the first-launch setup. Two surfaces drawing a logo their own way is
 * how three of them ended up invisible on one screen and fine on the other -- see
 * [logoBackdropFor], which exists because that already happened once.
 */
@Composable
internal fun GameMark(art: GameArt?, width: Dp = 72.dp, height: Dp = 48.dp) {
	Box(
		modifier = Modifier
			// Wider than it is tall, because most of these are wordmarks. A square tile squeezes a
			// 960x275 logo into a smear; the icon rows simply centre their glyph in the space.
			.size(width = width, height = height)
			.clip(RoundedCornerShape(12.dp))
			.background(backdropFor(art)),
		contentAlignment = Alignment.Center,
	) {
		val vLogo = art?.logo
		if (vLogo == null) {
			GameMarkFallback(art)
		} else {
			// `Fit` rather than `Crop`: these are wordmarks of every aspect ratio -- the Magic one
			// is 960x275 -- and cropping one is far worse than letterboxing it.
			Image(
				painter = painterResource(vLogo),
				contentDescription = null,
				contentScale = ContentScale.Fit,
				modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp),
				// A monochrome wordmark is painted rather than left as drawn -- see [logoTintFor].
				// Deliberately never the row accent: a teal Wuthering Waves logo is not its logo,
				// and colour artwork is not tinted at all.
				colorFilter = logoTintFor(art),
			)
		}
	}
}

/**
 * The tile a game's mark sits on.
 *zzzzzzzzzzzzzzzz
 * A logo drawn for a particular background states one, and keeps it on both themes rather than
 * washing out against a pale tile. That is usually [GameArt.DARK_BACKDROP], and for Cyberpunk it is
 * the brand's own yellow with a black wordmark on it -- which is exactly why `backdropArgb` is a
 * colour rather than the "prefers dark" boolean it replaced.
 *
 * Everything else gets its accent, heavily tinted rather than saturated, so ten of these in a
 * column read as one list rather than as a paint chart.
 */
@Composable
internal fun backdropFor(art: GameArt?): Color =
	art?.let { logoBackdropFor(it) }
		?: (art?.accent ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.18f)

/**
 * The plate this mark states for the theme in force, or `null` if it states none.
 *
 * Shared with the set list's title bar so the two surfaces cannot drift -- which they did once
 * already, when the picker had a tile and the title bar did not and three logos were invisible on
 * one screen and fine on the other.
 */
@Composable
internal fun logoBackdropFor(art: GameArt): Color? {
	val vArgb = if (isDarkTheme()) art.backdropDarkArgb else art.backdropArgb
	return vArgb?.let { Color(it.toInt()) }
}

/**
 * The fallback for a game whose module ships no logo.
 *
 * A generic symbol rather than one chosen per game. Every shipped game has a logo, so this is only
 * reached by a game added without one -- and picking a Material glyph for it would have to happen
 * here, in the UI, which is the sort of per-game table this refactor removed.
 */
@Composable
private fun GameMarkFallback(art: GameArt?) {
	Icon(
		imageVector = AppIcons.Style,
		contentDescription = null,
		tint = art?.accent ?: MaterialTheme.colorScheme.primary,
		modifier = Modifier.size(26.dp),
	)
}
