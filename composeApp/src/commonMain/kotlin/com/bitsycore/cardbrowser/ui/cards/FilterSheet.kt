package com.bitsycore.cardbrowser.ui.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.core.provider.CardFilterField
import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.provider.SortDirection
import com.bitsycore.cardbrowser.ui.cards.CardGridContract.toggle

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
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text("Filters", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
			if (state.activeFilterCount > 0) {
				TextButton(onClick = onClearAll) { Text("Clear all") }
			}
		}

		// Sorting is always available: it is applied locally over whatever is on screen.
		Section("Sort by") {
			CardGridContract.SORT_OPTIONS.forEach { (vField, vLabel) ->
				FilterChip(
					selected = state.query.sortBy == vField,
					onClick = { onQueryChanged(state.query.copy(sortBy = vField)) },
					label = { Text(vLabel) },
				)
			}
			FilterChip(
				selected = state.query.sortDirection == SortDirection.DESCENDING,
				onClick = {
					onQueryChanged(
						state.query.copy(
							sortDirection = if (state.query.sortDirection == SortDirection.DESCENDING) {
								SortDirection.ASCENDING
							} else {
								SortDirection.DESCENDING
							},
						),
					)
				},
				label = { Text("Descending") },
			)
		}

		if (CardFilterField.DOMAIN in state.supportedFilters && state.facets.domains.isNotEmpty()) {
			Section("Domain") {
				state.facets.domains.forEach { vDomain ->
					FilterChip(
						selected = vDomain in state.query.domains,
						onClick = { onQueryChanged(state.query.copy(domains = state.query.domains.toggle(vDomain))) },
						label = { Text(vDomain) },
					)
				}
			}
		}

		if (CardFilterField.CARD_TYPE in state.supportedFilters && state.facets.cardTypes.isNotEmpty()) {
			Section("Card type") {
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

		if (CardFilterField.ENERGY_COST in state.supportedFilters && state.facets.energyCosts.isNotEmpty()) {
			Section("Energy cost") {
				state.facets.energyCosts.forEach { vCost ->
					FilterChip(
						selected = vCost in state.query.energyCosts,
						onClick = { onQueryChanged(state.query.copy(energyCosts = state.query.energyCosts.toggle(vCost))) },
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
				text = "Filters appear once the whole set has been downloaded. " +
					"Filtering part of a set would hide cards that exist.",
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
	return "This card database does not record ${vMissing.joinToString(" or ")}, " +
		"so there is nothing to filter on. It is not that these do not exist."
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
			RemovableChip(vValue) { onQueryChanged(vQuery.copy(domains = vQuery.domains - vValue)) }
		}
		vQuery.cardTypes.forEach { vValue ->
			RemovableChip(vValue) { onQueryChanged(vQuery.copy(cardTypes = vQuery.cardTypes - vValue)) }
		}
		vQuery.rarities.forEach { vValue ->
			RemovableChip(vValue) { onQueryChanged(vQuery.copy(rarities = vQuery.rarities - vValue)) }
		}
		vQuery.energyCosts.forEach { vValue ->
			RemovableChip("$vValue energy") {
				onQueryChanged(vQuery.copy(energyCosts = vQuery.energyCosts - vValue))
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
				imageVector = Icons.Outlined.Close,
				contentDescription = "Remove $label filter",
				modifier = Modifier.size(16.dp),
			)
		},
	)
}
