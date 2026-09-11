package com.bitsycore.cardbrowser.ui.cards

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
import com.bitsycore.cardbrowser.ui.cards.CardGridContract.toggle
import com.bitsycore.cardbrowser.ui.common.AppIcons

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
		Section("Sort by") {
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

		if (CardFilterField.DOMAIN in state.supportedFilters && state.facets.domains.isNotEmpty()) {
			// The word for this axis is the game's, not the app's: Riftbound has domains, Magic
			// has colours, Altered has factions. `GameVocabulary` is the one place that decides.
			Section(vVocabulary.domain ?: "Domain") {
				// In the game's own order, not the order the facets happened to come out in. WUBRG
				// is the point: no alphabetical sort produces it, and a Magic player reads any
				// other order as wrong.
				state.facets.domains.sortedBy { vKey ->
					val vIndex = state.game?.domains?.indexOfFirst { it.key.equals(vKey, true) } ?: -1
					// A domain the game does not declare sorts after the ones it does, rather than
					// being dropped: a source inventing a value must still be filterable.
					if (vIndex >= 0) vIndex else Int.MAX_VALUE
				}.forEach { vKey ->
					val vDomain = state.game?.domainFor(vKey)
					DomainChip(
						label = vDomain?.label ?: vKey,
						colour = vDomain?.let { Color(it.colourArgb.toInt()) },
						isSelected = vKey in state.query.domains,
						onClick = {
							onQueryChanged(state.query.copy(domains = state.query.domains.toggle(vKey)))
						},
					)
				}
			}
		}

		if (CardFilterField.CARD_TYPE in state.supportedFilters && state.facets.cardTypes.isNotEmpty()) {
			Section(vVocabulary.cardType) {
				state.facets.cardTypes.forEach { vType ->
					FilterChip(
						selected = vType in state.query.cardTypes,
						onClick = { onQueryChanged(state.query.copy(cardTypes = state.query.cardTypes.toggle(vType))) },
						label = { Text(vType) },
					)
				}
			}
		}

		if (CardFilterField.RARITY in state.supportedFilters && state.facets.rarities.isNotEmpty()) {
			Section("Rarity") {
				state.facets.rarities.forEach { vRarity ->
					FilterChip(
						selected = vRarity in state.query.rarities,
						onClick = { onQueryChanged(state.query.copy(rarities = state.query.rarities.toggle(vRarity))) },
						label = { Text(vRarity) },
					)
				}
			}
		}

		if (CardFilterField.COST in state.supportedFilters && state.facets.costs.isNotEmpty()) {
			Section(vVocabulary.cost ?: "Cost") {
				state.facets.costs.forEach { vCost ->
					FilterChip(
						selected = vCost in state.query.costs,
						onClick = { onQueryChanged(state.query.copy(costs = state.query.costs.toggle(vCost))) },
						label = { Text("$vCost") },
					)
				}
			}
		}

		if (CardFilterField.ARTWORK_TREATMENT in state.supportedFilters && state.facets.treatments.size > 1) {
			Section("Artwork") {
				state.facets.treatments.forEach { vTreatment ->
					FilterChip(
						selected = vTreatment in state.query.treatments,
						onClick = { onQueryChanged(state.query.copy(treatments = state.query.treatments.toggle(vTreatment))) },
						label = { Text(CardGridContract.treatmentLabel(vTreatment)) },
					)
				}
			}
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

		if (state.facets.isEmpty && !state.isCompleteSet) {
			Spacer(Modifier.height(16.dp))
			Text(
				text = "Filters appear once the whole set is downloaded.",
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

/** A titled row of chips that wraps. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, content: @Composable FlowRowScope.() -> Unit) {
	Spacer(Modifier.height(16.dp))
	Text(title, style = MaterialTheme.typography.titleSmall)
	Spacer(Modifier.height(6.dp))
	FlowRow(
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
		content = content,
	)
}

private typealias FlowRowScope = androidx.compose.foundation.layout.FlowRowScope

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
			RemovableChip(state.game?.domainFor(vValue)?.label ?: vValue) {
				onQueryChanged(vQuery.copy(domains = vQuery.domains - vValue))
			}
		}
		vQuery.cardTypes.forEach { vValue ->
			RemovableChip(vValue) { onQueryChanged(vQuery.copy(cardTypes = vQuery.cardTypes - vValue)) }
		}
		vQuery.rarities.forEach { vValue ->
			RemovableChip(vValue) { onQueryChanged(vQuery.copy(rarities = vQuery.rarities - vValue)) }
		}
		vQuery.costs.forEach { vValue ->
			RemovableChip("$vValue energy") {
				onQueryChanged(vQuery.copy(costs = vQuery.costs - vValue))
			}
		}
		vQuery.treatments.forEach { vValue ->
			RemovableChip(vValue.displayName) {
				onQueryChanged(vQuery.copy(treatments = vQuery.treatments - vValue))
			}
		}
		if (!vQuery.text.isNullOrBlank()) {
			RemovableChip("\"${vQuery.text}\"") { onQueryChanged(vQuery.copy(text = null)) }
		}
		TextButton(onClick = onClearAll) { Text("Clear all") }
	}
}

/** A chip with an x on it. */
@Composable
private fun RemovableChip(label: String, onRemove: () -> Unit) {
	InputChip(
		selected = true,
		onClick = onRemove,
		label = { Text(label) },
		trailingIcon = {
			Icon(
				imageVector = AppIcons.Close,
				contentDescription = "Remove $label filter",
				modifier = Modifier.size(16.dp),
			)
		},
	)
}

/**
 * A domain chip, in that domain's own colour.
 *
 * The colour is a *rule*, declared by the game module -- Magic's red is red whoever supplied the
 * data -- and it arrives here already resolved. `null` means the game has never heard of this
 * value, which happens when a provider invents one; the chip then draws in the theme's own colours
 * rather than being hidden, because a value a card really has must stay filterable.
 *
 * Selected fills with the colour and picks black or white text from its luminance, so a pale chip
 * (Magic white, Pokémon colourless) does not end up white-on-white. Unselected keeps a tinted
 * outline: seven saturated fills in a row read as a paint chart rather than as a list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DomainChip(
	label: String,
	colour: Color?,
	isSelected: Boolean,
	onClick: () -> Unit,
) {
	val vColour = colour ?: MaterialTheme.colorScheme.primary
	// Perceived brightness rather than a plain average: the eye weights green far above blue, and
	// an unweighted mean calls Magic's blue light enough for black text.
	val vIsLight = (0.299f * vColour.red + 0.587f * vColour.green + 0.114f * vColour.blue) > 0.6f
	FilterChip(
		selected = isSelected,
		onClick = onClick,
		label = { Text(label) },
		colors = FilterChipDefaults.filterChipColors(
			selectedContainerColor = vColour,
			selectedLabelColor = if (vIsLight) Color.Black else Color.White,
			labelColor = MaterialTheme.colorScheme.onSurface,
		),
		border = FilterChipDefaults.filterChipBorder(
			enabled = true,
			selected = isSelected,
			borderColor = vColour.copy(alpha = 0.55f),
			selectedBorderColor = vColour,
		),
	)
}
