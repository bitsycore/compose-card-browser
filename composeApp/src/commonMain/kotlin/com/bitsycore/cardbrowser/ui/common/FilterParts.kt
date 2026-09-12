package com.bitsycore.cardbrowser.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.bitsycore.cardbrowser.core.game.GameProfile
import androidx.compose.ui.unit.dp

/**
 * The pieces both filter surfaces are built from.
 *
 * The card grid's sheet and the search's sheet ask different questions -- the grid filters what is
 * on screen, the search asks the store -- but they are the same control to a user, and they used to
 * look nothing alike: chips in a bottom sheet on one side, a column of dropdown buttons on the
 * other. These are the parts that make them one thing.
 */

/** A titled group of chips that wraps rather than scrolls sideways. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterSection(title: String, content: @Composable FlowRowScope.() -> Unit) {
	Spacer(Modifier.height(16.dp))
	Text(title, style = MaterialTheme.typography.titleSmall)
	Spacer(Modifier.height(6.dp))
	FlowRow(
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
		content = content,
	)
}

/**
 * One value in a section: on or off, and off is the absence of that constraint.
 *
 * A chip rather than a menu item, because a filter's job is to show what it is doing while it is
 * doing it. A dropdown says "Rarity" whether or not one is chosen, and needs opening to answer.
 *
 * @param colour the game's own colour for this value, where it states one -- a domain's, a rarity's.
 *   The chip then fills with it when selected and outlines in it when not, so the filter reads in
 *   the same colours as the cards it is filtering. `null` uses the theme, which is what a value the
 *   game says nothing about gets.
 */
@Composable
fun FilterValueChip(
	label: String,
	isSelected: Boolean,
	colour: Color? = null,
	onToggle: () -> Unit,
) {
	if (colour == null) {
		FilterChip(selected = isSelected, onClick = onToggle, label = { Text(label) })
		return
	}
	// Perceived brightness rather than a plain average: the eye weights green far above blue, and
	// an unweighted mean calls Magic's blue light enough for black text.
	val vIsLight = (0.299f * colour.red + 0.587f * colour.green + 0.114f * colour.blue) > 0.6f
	FilterChip(
		selected = isSelected,
		onClick = onToggle,
		label = { Text(label) },
		colors = FilterChipDefaults.filterChipColors(
			selectedContainerColor = colour,
			selectedLabelColor = if (vIsLight) Color.Black else Color.White,
			labelColor = MaterialTheme.colorScheme.onSurface,
		),
		border = FilterChipDefaults.filterChipBorder(
			enabled = true,
			selected = isSelected,
			borderColor = colour.copy(alpha = 0.55f),
			selectedBorderColor = colour,
		),
	)
}

/**
 * The colour a game states for one of its domains, or `null` where it states none.
 *
 * Here rather than at each call site because both filter sheets ask the same question of the same
 * profile, and a second copy of it is a second place for "Riftbound's Fury is red" to be wrong.
 */
@Composable
fun domainColourOf(game: GameProfile?, key: String): Color? =
	game?.domainFor(key)?.let { Color(it.colourArgb.toInt()) }

/** The colour a game states for one of its rarities, or `null`. */
@Composable
fun rarityColourOf(game: GameProfile?, rarity: String): Color? =
	game?.rarityColourFor(rarity)?.let { Color(it.toInt()) }

/** A chip with an x on it: an active filter, removable where it is shown. */
@Composable
fun RemovableFilterChip(label: String, onRemove: () -> Unit) {
	InputChip(
		selected = true,
		onClick = onRemove,
		label = { Text(label) },
		trailingIcon = {
			Icon(
				imageVector = AppIcons.Close,
				contentDescription = "Remove $label",
				modifier = Modifier.size(16.dp),
			)
		},
	)
}
