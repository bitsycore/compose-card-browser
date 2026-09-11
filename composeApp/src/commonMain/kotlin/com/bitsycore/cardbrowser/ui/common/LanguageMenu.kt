package com.bitsycore.cardbrowser.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.core.model.CardLanguage

/**
 * Which language is being read, and a menu to change it.
 *
 * A two-letter code rather than an icon, because there is no glyph for "Japanese" that anyone reads
 * as one, and rather than the full name because a bar already holds a title and two or three
 * actions. The code is the language's own tag, upper-cased -- `EN`, `JA`, `ZH-CN` -- which is what a
 * card database shows and what the menu then spells out in full.
 *
 * Shared by the set list and the card grid on purpose, and they ask subtly different questions:
 *
 * - the **set list** asks which language you browse in, which is a preference and always answerable;
 * - the **card grid** asks which edition of *this set* to show, which is a fact about the set and
 *   may have to be established first.
 *
 * That difference is why [isChecking] and [checkFailed] exist. A menu must never list a source's
 * claim as though it were the set's editions: the grid's used to open on the claim and collapse to
 * the confirmed list under the user's finger -- eleven entries becoming four while they read them.
 * It now lists what is known and says, in a row of its own, that it is still asking.
 *
 * @param header a line above the options, for where the list needs qualifying. The set list uses it
 *   to say the choice is a preference rather than a promise about any particular set
 * @param isBusy disables the button while a switch is in flight, so a second tap cannot start a
 *   third load and leave the state describing an edition nobody asked for
 * @param onOpened called when the menu opens, so the options can be confirmed against the source.
 *   Confirming is a request per candidate -- eleven for a Magic set -- so it is paid by someone
 *   actually looking at the menu rather than by everyone who opens a set
 */
@Composable
fun LanguageMenu(
	options: List<CardLanguage>,
	selected: CardLanguage?,
	onSelect: (CardLanguage) -> Unit,
	isBusy: Boolean = false,
	isChecking: Boolean = false,
	checkFailed: Boolean = false,
	header: String? = null,
	onOpened: () -> Unit = {},
) {
	var vIsOpen by remember { mutableStateOf(false) }

	Box {
		TextButton(
			onClick = { vIsOpen = true; onOpened() },
			enabled = !isBusy,
		) {
			Text(
				text = selected?.code?.uppercase() ?: "--",
				style = MaterialTheme.typography.labelLarge,
			)
			Icon(
				imageVector = AppIcons.ArrowDropDown,
				contentDescription = "Change language",
				modifier = Modifier.size(18.dp),
			)
		}

		DropdownMenu(expanded = vIsOpen, onDismissRequest = { vIsOpen = false }) {
			if (header != null) {
				Text(
					text = header,
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
				)
				HorizontalDivider()
			}
			options.forEach { vLanguage ->
				DropdownMenuItem(
					text = { Text(vLanguage.displayName) },
					onClick = {
						vIsOpen = false
						onSelect(vLanguage)
					},
					trailingIcon = {
						// A tick on the current one rather than a highlight: the menu is short and
						// the point is which one you are reading, not which row is hovered.
						if (vLanguage == selected) {
							Icon(AppIcons.Check, contentDescription = "Showing")
						}
					},
				)
			}

			// The state of knowledge, as a row. Not a list of guesses.
			if (isChecking) {
				DropdownMenuItem(
					text = {
						Text(
							text = "Checking for other editions…",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					},
					leadingIcon = {
						CircularProgressIndicator(
							modifier = Modifier.size(14.dp),
							strokeWidth = 2.dp,
						)
					},
					enabled = false,
					onClick = {},
				)
			} else if (checkFailed) {
				DropdownMenuItem(
					text = {
						Text(
							// Which is not the same as "there are none", and the difference is the
							// whole reason this row exists.
							text = "Could not check for other editions.",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					},
					enabled = false,
					onClick = {},
				)
			}
		}
	}
}
