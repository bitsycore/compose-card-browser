package com.bitsycore.cardbrowser.ui.sets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.data.repository.DataOrigin
import com.bitsycore.cardbrowser.ui.common.EmptyState
import com.bitsycore.cardbrowser.ui.common.ErrorState
import com.bitsycore.cardbrowser.ui.common.LoadingState
import com.bitsycore.cardbrowser.ui.common.NoticeBanner
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Riftbound set list: the app's first screen.
 *
 * Newest first, searchable by name or code, and honest about whether what is on screen came off the
 * network or off the disk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetListScreen(
	onOpenSet: (CardSet) -> Unit,
	onOpenSettings: () -> Unit,
	viewModel: SetListViewModel = koinViewModel(),
) {
	val vState by viewModel.collectAsStateWithLifecycle()

	Scaffold(
		topBar = {
			TopAppBar(
				title = {
					Row(verticalAlignment = Alignment.CenterVertically) {
						// A neutral mark, not the game's logo: the provider ships no artwork for a
						// game either, and inventing something that looks official would be worse
						// than a plain one.
						Icon(
							imageVector = Icons.Outlined.Style,
							contentDescription = null,
							modifier = Modifier.size(22.dp),
							tint = MaterialTheme.colorScheme.primary,
						)
						Spacer(Modifier.size(10.dp))
						Text("Riftbound")
					}
				},
				actions = {
					IconButton(onClick = onOpenSettings) {
						Icon(Icons.Outlined.Settings, contentDescription = "Settings")
					}
				},
			)
		},
	) { vPadding ->
		Column(Modifier.padding(vPadding).fillMaxSize()) {

			OutlinedTextField(
				value = vState.search,
				onValueChange = { viewModel.dispatch(SetListContract.Intent.SearchChanged(it)) },
				label = { Text("Search sets") },
				leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
				singleLine = true,
				modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
			)

			// The honesty strip. Shown whenever what is on screen is not a fresh network result.
			when {
				vState.error != null && vState.sets.isNotEmpty() -> NoticeBanner(
					text = "Showing saved sets. Refresh failed.",
					onAction = { viewModel.dispatch(SetListContract.Intent.Refresh) },
				)
				vState.origin == DataOrigin.CACHE && vState.isStale -> NoticeBanner(
					text = "Saved copy, refreshing…",
					onAction = null,
				)
			}

			Box(Modifier.weight(1f)) {
				when {
					vState.isInitialLoad -> LoadingState()

					vState.sets.isEmpty() && vState.error != null -> ErrorState(
						error = vState.error!!,
						onRetry = { viewModel.dispatch(SetListContract.Intent.Refresh) },
					)

					vState.isEmptySearch -> EmptyState("No set matches \"${vState.search}\".")

					else -> LazyColumn(
						contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp),
					) {
						items(vState.visibleSets, key = { it.id.qualified }) { vSet ->
							SetRow(
								set = vSet,
								isLastOpened = vSet.id.qualified == vState.lastOpenedSetId,
								onClick = {
									viewModel.dispatch(SetListContract.Intent.SetOpened(vSet.id.qualified))
									onOpenSet(vSet)
								},
							)
						}
					}
				}
			}
		}
	}
}

/** One set: name, code, card count and release date, plus a mark for where you left off. */
@Composable
private fun SetRow(
	set: CardSet,
	isLastOpened: Boolean,
	onClick: () -> Unit,
) {
	Card(
		onClick = onClick,
		modifier = Modifier.fillMaxWidth(),
		colors = if (isLastOpened) {
			CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
		} else {
			CardDefaults.cardColors()
		},
	) {
		Row(
			modifier = Modifier.padding(16.dp).fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			SetMonogram(set.code, isHighlighted = isLastOpened)
			Spacer(Modifier.size(12.dp))
			Column(Modifier.weight(1f)) {
				Text(
					text = set.name,
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.Medium,
				)
				Spacer(Modifier.height(2.dp))
				Text(
					text = setSubtitle(set),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			if (isLastOpened) {
				Spacer(Modifier.size(8.dp))
				Text(
					text = "Last opened",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSecondaryContainer,
				)
			}
		}
	}
}

/**
 * A set's code in a tile, standing in for the set symbol the provider does not have.
 *
 * Riftcodex publishes no icon, logo or symbol for a set -- the only image anywhere in its schema is
 * a card's own art. Rather than leave the row as an undifferentiated wall of text, or invent a
 * symbol and pass it off as the game's, this shows the set's real short code, which is what players
 * call it anyway and what is printed on the cards.
 */
@Composable
private fun SetMonogram(code: String, isHighlighted: Boolean) {
	Box(
		modifier = Modifier
			.size(44.dp)
			.clip(RoundedCornerShape(10.dp))
			.background(
				if (isHighlighted) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.surfaceVariant
				},
			),
		contentAlignment = Alignment.Center,
	) {
		Text(
			// Promo codes are two characters, expansions three; anything longer is truncated
			// rather than shrunk to illegibility.
			text = code.take(4),
			style = MaterialTheme.typography.labelLarge,
			fontWeight = FontWeight.Medium,
			maxLines = 1,
			color = if (isHighlighted) {
				MaterialTheme.colorScheme.onPrimary
			} else {
				MaterialTheme.colorScheme.onSurfaceVariant
			},
		)
	}
}

/**
 * "OGN · 352 cards · Oct 2025", with each part dropped when the provider does not supply it.
 *
 * A missing release date shows nothing rather than "Unknown date": the row is not the place to
 * discuss what the provider does not know.
 */
private fun setSubtitle(set: CardSet): String = buildList {
	add(set.code)
	set.cardCount?.let { add("$it cards") }
	set.releaseDate?.let { add("${monthName(it.month.ordinal)} ${it.year}") }
}.joinToString(" · ")

/** Zero-based, matching `Month.ordinal`, so January is 0. */
private fun monthName(monthOrdinal: Int): String =
	MONTH_NAMES.getOrElse(monthOrdinal) { "" }

private val MONTH_NAMES = listOf(
	"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
