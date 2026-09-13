package com.bitsycore.cardbrowser.ui.screen.cards

import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import com.bitsycore.cardbrowser.core.game.RarityLadder
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.provider.CardFilterField
import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.provider.SortDirection
import com.bitsycore.cardbrowser.ui.screen.cards.CardGridContract.toggle
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bitsycore.cardbrowser.ui.component.AppIcons
import com.bitsycore.cardbrowser.ui.component.FilterSection
import com.bitsycore.cardbrowser.ui.component.FilterValueChip
import com.bitsycore.cardbrowser.ui.component.domainColourOf
import com.bitsycore.cardbrowser.ui.component.rarityColourOf
import com.bitsycore.cardbrowser.ui.component.RemovableFilterChip

/**
 * The filter sheet.
 *
 * Two rules shape what appears here:
 *
 * 1. **Only what the provider supports.** A section is drawn only if its field is in
 *    [CardGridContract.UiState.supportedFilters]. For Riftcodex that means no finish and no language
 *    section at all, because it carries neither -- offering them would be offering a control that
 *    cannot work.
 * 2. **Only values that exist in this set.** The chips come from the facets of the cards actually
 *    downloaded, not from a global index, so every chip can match something.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
	state: CardGridContract.UiState,
	onQueryChanged: (CardQuery) -> Unit,
	onSetsChanged: (Set<String>) -> Unit,
	onClearAll: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.verticalScroll(rememberScrollState())
			.padding(horizontal = 20.dp)
			.padding(bottom = 32.dp),
	) {
		val vVocabulary = (state.game?.vocabulary ?: GameVocabulary())

		Row(verticalAlignment = Alignment.CenterVertically) {
			Text("Filters", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
			if (state.activeFilterCount > 0) {
				TextButton(onClick = onClearAll) { Text("Clear all") }
			}
		}

		// Sorting is always available: it is applied locally over whatever is on screen.
		//
		// Direction is folded into the field chips rather than sitting beside them as its own
		// "Descending" toggle. As a separate chip it read as a fifth sort field and went unnoticed;
		// tapping the already-selected field to flip it, with the arrow saying which way it is
		// pointing, is the pattern every table header in the world uses.
		FilterSection("Sort by") {
			CardGridContract.sortOptions(state.game).forEach { (vField, vLabel) ->
				val vIsSelected = state.query.sortBy == vField
				val vIsDescending = state.query.sortDirection == SortDirection.DESCENDING
				FilterChip(
					selected = vIsSelected,
					onClick = {
						onQueryChanged(
							if (vIsSelected) {
								// Already sorting by this: the tap means "the other way round".
								state.query.copy(
									sortDirection = if (vIsDescending) {
										SortDirection.ASCENDING
									} else {
										SortDirection.DESCENDING
									},
								)
							} else {
								// A new field starts ascending, which is the reading order for
								// collector numbers, names and the rarity ladder alike.
								state.query.copy(sortBy = vField, sortDirection = SortDirection.ASCENDING)
							},
						)
					},
					label = { Text(vLabel) },
					trailingIcon = if (vIsSelected) {
						{
							Icon(
								imageVector = if (vIsDescending) {
									AppIcons.ArrowDownward
								} else {
									AppIcons.ArrowUpward
								},
								contentDescription = if (vIsDescending) "Descending" else "Ascending",
								modifier = Modifier.size(16.dp),
							)
						}
					} else {
						null
					},
				)
			}
		}
		Spacer(Modifier.height(4.dp))
		Text(
			text = "Tap the selected sort again to reverse it.",
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)

		// Which sets this list covers. One is the set you opened; none is everything downloaded,
		// which is what the separate search screen used to be. A menu rather than the chips every
		// other axis uses, because a game has five rarities and Pokemon has 486 sets.
		if (state.setOptions.isNotEmpty()) {
			// The same control as every other axis, and it has to be: Magic downloads 988 sets, so
			// this is the longest list in the sheet and had its own menu that composed all of them.
			FilterValues(
				title = "Sets",
				options = state.setOptions.map { FilterOption(it.id, it.name) },
				selected = state.setIds,
				anyLabel = "Everything downloaded",
				onToggle = { vId -> onSetsChanged(state.setIds.toggle(vId)) },
				onClear = { onSetsChanged(emptySet()) },
			)
		}

		if (CardFilterField.DOMAIN in state.supportedFilters && state.facets.domains.isNotEmpty()) {
			// The word for this axis is the game's, not the app's: Riftbound has domains, Magic
			// has colours, Altered has factions. `GameVocabulary` is the one place that decides.
			FilterValues(
				title = vVocabulary.domain ?: "Domain",
				// In the game's own order, not the order the facets happened to come out in. WUBRG
				// is the point: no alphabetical sort produces it, and a Magic player reads any
				// other order as wrong.
				options = state.facets.domains.sortedBy { vKey ->
					val vIndex = state.game?.domains?.indexOfFirst { it.key.equals(vKey, true) } ?: -1
					// A domain the game does not declare sorts after the ones it does, rather than
					// being dropped: a source inventing a value must still be filterable.
					if (vIndex >= 0) vIndex else Int.MAX_VALUE
				}.map { vKey ->
					FilterOption(
						key = vKey,
						label = state.game?.domainFor(vKey)?.label ?: vKey,
						colour = domainColourOf(state.game, vKey),
					)
				},
				selected = state.query.domains,
				onToggle = { vKey ->
					onQueryChanged(state.query.copy(domains = state.query.domains.toggle(vKey)))
				},
				onClear = { onQueryChanged(state.query.copy(domains = emptySet())) },
			)
		}

		if (CardFilterField.CARD_TYPE in state.supportedFilters && state.facets.cardTypes.isNotEmpty()) {
			FilterValues(
				title = vVocabulary.cardType,
				options = state.facets.cardTypes.map { FilterOption(it, it) },
				selected = state.query.cardTypes,
				onToggle = { vType ->
					onQueryChanged(state.query.copy(cardTypes = state.query.cardTypes.toggle(vType)))
				},
				onClear = { onQueryChanged(state.query.copy(cardTypes = emptySet())) },
			)
		}

		if (CardFilterField.RARITY in state.supportedFilters && state.facets.rarities.isNotEmpty()) {
			FilterValues(
				title = "Rarity",
				// In the game's own colours where it prints them, like the domains above and like
				// the pill on the card itself.
				// The key is the source's own string, because that is what the query matches on;
				// only the label is tidied. See `RarityLadder.display`.
				options = state.facets.rarities.map {
					FilterOption(it, RarityLadder.display(it), rarityColourOf(state.game, it))
				},
				selected = state.query.rarities,
				onToggle = { vRarity ->
					onQueryChanged(state.query.copy(rarities = state.query.rarities.toggle(vRarity)))
				},
				onClear = { onQueryChanged(state.query.copy(rarities = emptySet())) },
			)
		}

		if (CardFilterField.COST in state.supportedFilters && state.facets.costs.isNotEmpty()) {
			FilterValues(
				title = vVocabulary.cost ?: "Cost",
				options = state.facets.costs.map { FilterOption("$it", "$it") },
				selected = state.query.costs.mapTo(mutableSetOf()) { "$it" },
				onToggle = { vKey ->
					val vCost = vKey.toIntOrNull() ?: return@FilterValues
					onQueryChanged(state.query.copy(costs = state.query.costs.toggle(vCost)))
				},
				onClear = { onQueryChanged(state.query.copy(costs = emptySet())) },
			)
		}

		if (CardFilterField.ARTWORK_TREATMENT in state.supportedFilters && state.facets.treatments.size > 1) {
			FilterValues(
				title = "Artwork",
				options = state.facets.treatments.map {
					FilterOption(it.name, CardGridContract.treatmentLabel(it))
				},
				selected = state.query.treatments.mapTo(mutableSetOf()) { it.name },
				onToggle = { vKey ->
					val vTreatment = state.facets.treatments.firstOrNull { it.name == vKey }
						?: return@FilterValues
					onQueryChanged(
						state.query.copy(treatments = state.query.treatments.toggle(vTreatment)),
					)
				},
				onClear = { onQueryChanged(state.query.copy(treatments = emptySet())) },
			)
		}

		// Why finish and language are missing, said once, where someone would look for them.
		if (CardFilterField.FINISH !in state.supportedFilters ||
			CardFilterField.LANGUAGE !in state.supportedFilters
		) {
			Spacer(Modifier.height(16.dp))
			Text(
				text = unsupportedFilterNote(state.supportedFilters),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}

		// Two different silences, said differently, because they are opposite facts.
		//
		// The second one had no message at all: a complete set whose records carry no type, rarity
		// or category drew an empty sheet that looked identical to a sheet still loading. It is
		// reachable and not rare -- records saved before this app started asking a source for
		// those fields have none, so one set filters and the set below it does not, which is
		// exactly how it was reported.
		if (state.facets.isEmpty) {
			Spacer(Modifier.height(16.dp))
			Text(
				text = if (!state.isCompleteSet) {
					"Filters appear once the whole set is downloaded."
				} else {
					"Nothing to filter on: the saved cards carry no type, rarity or category. " +
						"If they were downloaded a while ago, downloading the set again may " +
						"fill them in."
				},
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

/** Explains an absent filter section rather than leaving a silent gap. */
private fun unsupportedFilterNote(supported: Set<CardFilterField>): String {
	val vMissing = buildList {
		if (CardFilterField.FINISH !in supported) add("finish")
		if (CardFilterField.LANGUAGE !in supported) add("card language")
	}
	return "This source does not record ${vMissing.joinToString(" or ")}."
}

/**
 * The active filters, above the grid, each removable in one tap.
 *
 * Separate from the sheet on purpose: a filter the user cannot see is a filter they will blame the
 * app for. These are always visible while they are on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActiveFilterChips(
	state: CardGridContract.UiState,
	onQueryChanged: (CardQuery) -> Unit,
	onClearAll: () -> Unit,
) {
	if (state.activeFilterCount == 0) return
	val vQuery = state.query

	FlowRow(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
		horizontalArrangement = Arrangement.spacedBy(6.dp),
		verticalArrangement = Arrangement.spacedBy(0.dp),
	) {
		vQuery.domains.forEach { vValue ->
			// The game's label, so the summary row reads "White" rather than the `W` the filter
			// is actually keyed on.
			RemovableFilterChip(state.game?.domainFor(vValue)?.label ?: vValue) {
				onQueryChanged(vQuery.copy(domains = vQuery.domains - vValue))
			}
		}
		vQuery.cardTypes.forEach { vValue ->
			RemovableFilterChip(vValue) { onQueryChanged(vQuery.copy(cardTypes = vQuery.cardTypes - vValue)) }
		}
		vQuery.rarities.forEach { vValue ->
			RemovableFilterChip(vValue) { onQueryChanged(vQuery.copy(rarities = vQuery.rarities - vValue)) }
		}
		vQuery.costs.forEach { vValue ->
			RemovableFilterChip("$vValue energy") {
				onQueryChanged(vQuery.copy(costs = vQuery.costs - vValue))
			}
		}
		vQuery.treatments.forEach { vValue ->
			RemovableFilterChip(vValue.displayName) {
				onQueryChanged(vQuery.copy(treatments = vQuery.treatments - vValue))
			}
		}
		if (!vQuery.text.isNullOrBlank()) {
			RemovableFilterChip("\"${vQuery.text}\"") { onQueryChanged(vQuery.copy(text = null)) }
		}
		TextButton(onClick = onClearAll) { Text("Clear all") }
	}
}

/**
 * One value of a filter axis: what it is called, what to toggle, and the game's colour for it.
 *
 * A string key even for the axes that are not strings -- costs are numbers, treatments are an enum
 * -- because the control below does not care what an axis is made of, and one control is the point.
 * The call sites convert back, which is three lines and is where the type is known.
 */
internal data class FilterOption(
	val key: String,
	val label: String,
	val colour: Color? = null,
)

/**
 * One filter axis, drawn as chips or as a menu depending on how many values it has.
 *
 * Chips show every value at once, which is what makes them worth the space: five rarities are read
 * in a glance. Past about ten they stop being a glance and become five wrapped rows that push
 * everything below them off a phone screen, and a sheet that has to be scrolled past one axis to
 * reach the next is worse at both jobs. Pokemon reaches nineteen rarities, and a cost axis on a
 * game that goes to twelve is the same shape.
 *
 * The menu is the sets menu's, because it is the same question -- several of many, multi-select --
 * and two controls for one question is two things to learn. It keeps the colour as a dot, so a
 * Magic player still picks red by its colour rather than by reading five names.
 */
@Composable
private fun FilterValues(
	title: String,
	options: List<FilterOption>,
	selected: Set<String>,
	onToggle: (String) -> Unit,
	onClear: () -> Unit,
	/** What "nothing chosen" means on this axis. "Any" for a value, "everything" for a scope. */
	anyLabel: String = "Any",
) {
	if (options.size <= CHIP_LIMIT) {
		FilterSection(title) {
			options.forEach { vOption ->
				FilterValueChip(
					label = vOption.label,
					isSelected = vOption.key in selected,
					colour = vOption.colour,
					onToggle = { onToggle(vOption.key) },
				)
			}
		}
		return
	}

	Spacer(Modifier.height(16.dp))
	var vIsOpen by remember { mutableStateOf(false) }
	Column(Modifier.fillMaxWidth()) {
		Text(title, style = MaterialTheme.typography.titleSmall)
		Spacer(Modifier.height(6.dp))
		Row(verticalAlignment = Alignment.CenterVertically) {
			OutlinedButton(onClick = { vIsOpen = true }) {
				Text(
					when (selected.size) {
						0 -> anyLabel
						1 -> options.firstOrNull { it.key in selected }?.label ?: "1 chosen"
						else -> "${selected.size} chosen"
					},
				)
				Icon(AppIcons.ArrowDropDown, contentDescription = null)
			}
			if (selected.isNotEmpty()) {
				TextButton(onClick = onClear) { Text(anyLabel) }
			}
		}
	}
	if (vIsOpen) {
		FilterValueDialog(
			title = title,
			options = options,
			selected = selected,
			onToggle = onToggle,
			onDismiss = { vIsOpen = false },
		)
	}
}

/**
 * The long-axis picker: a dialog, not a menu.
 *
 * It was a `DropdownMenu` with a lazy list inside, and that is two problems stacked. A menu puts its
 * content in a column that is already vertically scrollable, so a lazy list inside it is a
 * scrollable in a scrollable -- which crashed. And a popup anchored to a chip is the wrong shape for
 * two thousand values on a phone whatever its scrolling does: it opens over the control that
 * summoned it, sized by its content.
 *
 * A dialog is a separate window, so nothing is nested, the list is an ordinary `LazyColumn`, and
 * there is room for a title, a box to narrow by, and a Done button. That last matters more than it
 * sounds: this is multi-select, and a menu that dismisses on the first tap makes choosing three
 * values three round trips.
 */
@Composable
private fun FilterValueDialog(
	title: String,
	options: List<FilterOption>,
	selected: Set<String>,
	onToggle: (String) -> Unit,
	onDismiss: () -> Unit,
) {
	var vNarrow by remember { mutableStateOf("") }
	val vShown = if (vNarrow.isBlank()) {
		options
	} else {
		options.filter { it.label.contains(vNarrow, ignoreCase = true) }
	}
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
		text = {
			Column {
				if (options.size > SEARCHABLE_MENU) {
					OutlinedTextField(
						value = vNarrow,
						onValueChange = { vNarrow = it },
						singleLine = true,
						label = { Text("Narrow") },
						modifier = Modifier.fillMaxWidth(),
					)
					Spacer(Modifier.height(8.dp))
				}
				// Lazy, because Magic's card type is the printed type line -- "Legendary Creature
				// -- Human Wizard" -- and a downloaded catalogue has thousands of distinct ones. A
				// column that composes them all freezes the screen before the dialog appears.
				//
				// `distinctBy` is not paranoia: `LazyColumn` throws on a duplicate key rather than
				// degrading, and these keys come from whatever a provider wrote.
				LazyColumn(modifier = Modifier.heightIn(max = LIST_HEIGHT)) {
					items(vShown.distinctBy { it.key }, key = { it.key }) { vOption ->
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable { onToggle(vOption.key) }
								.padding(vertical = 10.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							Checkbox(
								checked = vOption.key in selected,
								onCheckedChange = { onToggle(vOption.key) },
							)
							vOption.colour?.let { vColour ->
								Box(
									Modifier
										.size(14.dp)
										.clip(CircleShape)
										.background(vColour),
								)
								Spacer(Modifier.size(8.dp))
							}
							Text(vOption.label)
						}
					}
					if (vShown.isEmpty()) {
						item {
							Text(
								text = "Nothing matches \u201c$vNarrow\u201d.",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.padding(vertical = 12.dp),
							)
						}
					}
				}
			}
		},
	)
}

/** How tall the list inside a filter dialog may get before it scrolls. */
private val LIST_HEIGHT = 400.dp

/** Past this many values a dialog grows a box to narrow itself with. */
private const val SEARCHABLE_MENU = 24

/**
 * Past this many values an axis is a dialog rather than a row of chips.
 *
 * Chips show everything at once, which is what makes them worth the space: five rarities are read
 * in a glance. Past about ten they become five wrapped rows that push the next axis off the screen.
 */
private const val CHIP_LIMIT = 10

