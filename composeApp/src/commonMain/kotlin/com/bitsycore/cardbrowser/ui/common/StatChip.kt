package com.bitsycore.cardbrowser.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.core.game.GameProfile

/**
 * A plain fact chip, or a coloured one when the fact carries a colour.
 *
 * Only a domain does. The colour is the game's rule -- see `GameDomain` -- and a `null` means the
 * game has never heard of the value, which is drawn in the ordinary chip colours rather than hidden.
 *
 * Shared by the card detail screen and the card list, so a rarity reads the same in both.
 *
 * @param dense the card list's size: the same chip with the smaller type, because a row gives it
 *   one line rather than a paragraph's width
 */
@Composable
fun StatChip(label: String, colour: Color? = null, dense: Boolean = false) {
	// Perceived brightness rather than a plain average: the eye weights green far above blue, and
	// an unweighted mean calls Magic's blue light enough for black text.
	val vIsLight = colour != null &&
		(0.299f * colour.red + 0.587f * colour.green + 0.114f * colour.blue) > 0.6f
	Surface(
		color = colour ?: MaterialTheme.colorScheme.secondaryContainer,
		shape = RoundedCornerShape(20.dp),
	) {
		Text(
			text = label,
			style = if (dense) {
				MaterialTheme.typography.labelSmall
			} else {
				MaterialTheme.typography.labelMedium
			},
			color = when {
				colour == null -> MaterialTheme.colorScheme.onSecondaryContainer
				vIsLight -> Color.Black
				else -> Color.White
			},
			modifier = if (dense) {
				Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
			} else {
				Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
			},
		)
	}
}

/**
 * A domain chip: the game's own label and colour rather than the raw key the filter is keyed on.
 *
 * Riftbound's `fury` is "Fury" in its own red; a game that has never heard of the key gets the key
 * and the ordinary colours, which is the honest rendering of "this came from the provider and the
 * profile does not describe it".
 */
@Composable
fun DomainChip(key: String, game: GameProfile?, dense: Boolean = false) {
	val vDomain = game?.domainFor(key)
	StatChip(
		label = vDomain?.label ?: key,
		colour = vDomain?.let { Color(it.colourArgb.toInt()) },
		dense = dense,
	)
}
