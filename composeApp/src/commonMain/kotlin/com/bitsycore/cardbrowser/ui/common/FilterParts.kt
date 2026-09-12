package com.bitsycore.cardbrowser.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 */
@Composable
fun FilterValueChip(label: String, isSelected: Boolean, onToggle: () -> Unit) {
	FilterChip(selected = isSelected, onClick = onToggle, label = { Text(label) })
}

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
