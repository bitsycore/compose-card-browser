package com.bitsycore.cardbrowser.ui.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.data.cache.CacheUsage
import com.bitsycore.cardbrowser.data.settings.BulkImportRecord
import com.bitsycore.cardbrowser.ui.preview.PreviewFrame
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import com.bitsycore.lib.pulse.compose.collectEffect
import org.koin.compose.viewmodel.koinViewModel

/**
 * What the app is storing, and what can be removed.
 *
 * Reached from settings, which keeps the *limits*. The two were one section until a bulk import
 * made the difference matter: a limit bounds what browsing may accumulate, and an imported
 * catalogue is not bounded by it and never will be, because nothing evicts a thing the user asked
 * for. So the only way it leaves the device is from here.
 */
@Composable
fun StorageScreen(
	onBack: () -> Unit,
	viewModel: StorageViewModel = koinViewModel(),
) {
	val vSnackbar = remember { SnackbarHostState() }
	viewModel.collectEffect { vEffect ->
		when (vEffect) {
			is StorageContract.Effect.Deleted -> vSnackbar.showSnackbar(
				if (vEffect.sets == 1) {
					"Deleted 1 set of ${vEffect.game}."
				} else {
					"Deleted ${vEffect.sets} sets of ${vEffect.game}."
				},
			)
		}
	}
	val vState by viewModel.collectAsStateWithLifecycle()

	StorageContent(
		state = vState,
		dispatch = viewModel::dispatch,
		onBack = onBack,
		snackbarHostState = vSnackbar,
	)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageContent(
	state: StorageContract.UiState,
	dispatch: (StorageContract.Intent) -> Unit,
	onBack: () -> Unit = {},
	snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("Storage") },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
					}
				},
			)
		},
		snackbarHost = { SnackbarHost(snackbarHostState) },
	) { vPadding ->
		Column(
			Modifier
				.padding(vPadding)
				.fillMaxSize()
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 16.dp),
		) {
			if (state.isLoading && state.usage == null) {
				Spacer(Modifier.height(16.dp))
				LinearProgressIndicator(Modifier.fillMaxWidth())
			}

			state.usage?.let { vUsage ->
				Spacer(Modifier.height(8.dp))
				SectionHeading("Kept on this device")
				Text(
					// The sentence the whole screen is for.
					text = "Sets you downloaded and catalogues you imported. These are not " +
						"limited by the card-data setting and are never removed automatically, " +
						"because you asked for them. Deleting one here is the only thing that " +
						"removes it.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Spacer(Modifier.height(8.dp))

				if (!state.hasKept && state.unattributedKeptBytes == 0L) {
					Text(
						text = "Nothing kept yet. Downloading a set or importing a catalogue " +
							"will put it here.",
						style = MaterialTheme.typography.bodyMedium,
					)
				}

				state.kept.forEach { vGame ->
					KeptGameRow(
						game = vGame,
						isBusy = state.isDeleting,
						onDelete = { dispatch(StorageContract.Intent.DeleteRequested(vGame)) },
					)
					Spacer(Modifier.height(8.dp))
				}

				if (state.unattributedKeptBytes > 0) {
					Text(
						// Shown rather than folded into a game's row, so the parts add up to the
						// total. See `UiState.unattributedKeptBytes`.
						text = "Other kept records: ${formatBytes(state.unattributedKeptBytes)}. " +
							"These belong to a game whose set list is no longer cached, so they " +
							"cannot be attributed. Clearing browsing data and reopening the game " +
							"will name them.",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Spacer(Modifier.height(8.dp))
				}

				Spacer(Modifier.height(8.dp))
				HorizontalDivider()
				Spacer(Modifier.height(12.dp))

				SectionHeading("Browsing data")
				UsageLine(
					used = vUsage.metadataBrowsingBytes,
					limit = vUsage.metadataLimitBytes,
					note = "Card records left behind by browsing. The card-data limit in " +
						"settings governs this, and the oldest is dropped when it is reached.",
				)
				Spacer(Modifier.height(8.dp))
				OutlinedButton(
					onClick = { dispatch(StorageContract.Intent.ClearBrowsingData) },
					enabled = vUsage.metadataBrowsingBytes > 0 && !state.isLoading,
				) { Text("Clear browsing data") }

				Spacer(Modifier.height(16.dp))
				SectionHeading("Images")
				UsageLine(
					used = vUsage.imageBytes,
					limit = vUsage.imageLimitBytes,
					note = "Card pictures, for every game. Downloaded art is here too and is " +
						"re-fetched when it is looked at again.",
				)
				Spacer(Modifier.height(8.dp))
				OutlinedButton(
					onClick = { dispatch(StorageContract.Intent.ClearImages) },
					enabled = vUsage.imageBytes > 0 && !state.isLoading,
				) { Text("Clear images") }

				Spacer(Modifier.height(24.dp))
			}
		}
	}

	state.pendingDelete?.let { vGame ->
		AlertDialog(
			onDismissRequest = { dispatch(StorageContract.Intent.DeleteRequested(null)) },
			title = { Text("Delete ${vGame.displayName} card data?") },
			text = {
				Text(
					// Says what it costs to undo, because that is the decision being made.
					"This removes ${vGame.sets} downloaded " +
						"${if (vGame.sets == 1) "set" else "sets"} and frees " +
						"${formatBytes(vGame.bytes)}. Browsing ${vGame.displayName} will " +
						"download what it needs again." +
						if (vGame.importedVariant != null) {
							" The imported catalogue would have to be imported again."
						} else {
							""
						},
				)
			},
			confirmButton = {
				TextButton(onClick = { dispatch(StorageContract.Intent.DeleteConfirmed) }) {
					Text("Delete")
				}
			},
			dismissButton = {
				TextButton(onClick = { dispatch(StorageContract.Intent.DeleteRequested(null)) }) {
					Text("Cancel")
				}
			},
		)
	}
}

@Composable
private fun SectionHeading(text: String) {
	Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
	Spacer(Modifier.height(4.dp))
}

/** One game's kept records, with the delete that is the only way to remove them. */
@Composable
private fun KeptGameRow(
	game: StorageContract.KeptGame,
	isBusy: Boolean,
	onDelete: () -> Unit,
) {
	Card(colors = CardDefaults.cardColors(), modifier = Modifier.fillMaxWidth()) {
		Row(
			modifier = Modifier.padding(16.dp).fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(Modifier.weight(1f)) {
				Text(game.displayName, style = MaterialTheme.typography.titleMedium)
				Spacer(Modifier.height(2.dp))
				Text(
					text = buildList {
						add("${game.sets} ${if (game.sets == 1) "set" else "sets"}")
						add(formatBytes(game.bytes))
						game.importedVariant?.let { add("imported ${it.updatedAt.take(10)}") }
					}.joinToString(" · "),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			IconButton(onClick = onDelete, enabled = !isBusy) {
				Icon(
					imageVector = Icons.Outlined.Delete,
					contentDescription = "Delete ${game.displayName} card data",
				)
			}
		}
	}
}

/** "18.4 MB of 1.0 GB", with the bar that makes a ratio readable at a glance. */
@Composable
private fun UsageLine(used: Long, limit: Long, note: String) {
	Text(
		text = if (limit > 0) "${formatBytes(used)} of ${formatBytes(limit)}" else formatBytes(used),
		style = MaterialTheme.typography.bodyLarge,
	)
	if (limit > 0) {
		Spacer(Modifier.height(4.dp))
		LinearProgressIndicator(
			progress = { (used.toFloat() / limit).coerceIn(0f, 1f) },
			modifier = Modifier.fillMaxWidth(),
		)
	}
	Spacer(Modifier.height(4.dp))
	Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Bytes as a person reads them.
 *
 * Decimal units, matching what a phone's own storage screen says, so the two do not disagree.
 */
internal fun formatBytes(bytes: Long): String = when {
	bytes >= 1_000_000_000 -> "${round(bytes / 1_000_000_000.0)} GB"
	bytes >= 1_000_000 -> "${round(bytes / 1_000_000.0)} MB"
	bytes >= 1_000 -> "${round(bytes / 1_000.0)} kB"
	else -> "$bytes B"
}

/** One decimal place, without pulling in a formatter that differs per platform. */
private fun round(value: Double): String {
	val vTenths = (value * 10).toLong()
	return "${vTenths / 10}.${vTenths % 10}"
}

// ==================
// MARK: Previews
// ==================

private fun previewUsage() = CacheUsage(
	metadataBytes = 486_000_000,
	metadataEntries = 1_240,
	metadataLimitBytes = 1_000_000_000,
	metadataKeptBytes = 462_000_000,
	imageBytes = 184_000_000,
	imageLimitBytes = 1_000_000_000,
)

@Preview
@Composable
private fun StoragePreview() = PreviewFrame {
	StorageContent(
		state = StorageContract.UiState(
			usage = previewUsage(),
			kept = listOf(
				StorageContract.KeptGame(
					game = GameId("magic"),
					displayName = "Magic: The Gathering",
					sets = 988,
					bytes = 441_000_000,
					importedVariant = BulkImportRecord("default_cards", "2026-09-10T09:14:00Z"),
				),
				StorageContract.KeptGame(
					game = GameId("riftbound"),
					displayName = "Riftbound",
					sets = 2,
					bytes = 21_000_000,
				),
			),
			isLoading = false,
		),
		dispatch = {},
	)
}

@Preview
@Composable
private fun StorageEmptyPreview() = PreviewFrame(isDark = false) {
	StorageContent(
		state = StorageContract.UiState(
			usage = previewUsage().copy(metadataKeptBytes = 0, metadataBytes = 24_000_000),
			kept = emptyList(),
			isLoading = false,
		),
		dispatch = {},
	)
}
