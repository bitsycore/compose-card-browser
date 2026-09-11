package com.bitsycore.cardbrowser.ui.storage

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.bitsycore.cardbrowser.ui.common.AppIcons
import com.bitsycore.cardbrowser.ui.common.arrowScroll
import com.bitsycore.cardbrowser.ui.preview.PreviewFrame
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import com.bitsycore.lib.pulse.compose.collectEffect
import org.koin.compose.viewmodel.koinViewModel

/**
 * What the app is storing, and what can be removed.
 *
 * Settings keeps the *limits*, and there is a button here to reach them. The two were one section
 * until a bulk import made the difference matter: a limit bounds what browsing may accumulate, and
 * an imported catalogue is not bounded by it and never will be, because nothing evicts a thing the
 * user asked for. So the only way it leaves the device is from here.
 */
@Composable
fun StorageScreen(
	onBack: () -> Unit,
	onOpenCacheSettings: () -> Unit = {},
	viewModel: StorageViewModel = koinViewModel(),
) {
	val vSnackbar = remember { SnackbarHostState() }
	// Everything that is not drawing: a message to show, and two places to go. The body below only
	// ever dispatches, so it can be previewed and rendered with no host and no back stack.
	viewModel.collectEffect { vEffect ->
		when (vEffect) {
			is StorageContract.Effect.Deleted -> vSnackbar.showSnackbar(
				if (vEffect.sets == 1) {
					"Deleted 1 set of ${vEffect.game}."
				} else {
					"Deleted ${vEffect.sets} sets of ${vEffect.game}."
				},
			)

			StorageContract.Effect.NavigateBack -> onBack()

			StorageContract.Effect.OpenCacheSettings -> onOpenCacheSettings()
		}
	}
	val vState by viewModel.collectAsStateWithLifecycle()

	StorageContent(
		state = vState,
		dispatch = viewModel::dispatch,
		snackbarHostState = vSnackbar,
	)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageContent(
	state: StorageContract.UiState,
	dispatch: (StorageContract.Intent) -> Unit,
	snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("Storage") },
				navigationIcon = {
					IconButton(onClick = { dispatch(StorageContract.Intent.BackPressed) }) {
						Icon(AppIcons.ArrowBack, contentDescription = "Back")
					}
				},
			)
		},
		snackbarHost = { SnackbarHost(snackbarHostState) },
	) { vPadding ->
		// See the settings screen: a column of figures and prose has little to traverse between.
		val vScroll = rememberScrollState()
		Column(
			Modifier
				.padding(vPadding)
				.fillMaxSize()
				.verticalScroll(vScroll)
				.arrowScroll(vScroll)
				.padding(horizontal = 16.dp),
		) {
			if (state.isLoading && state.usage == null) {
				Spacer(Modifier.height(16.dp))
				LinearProgressIndicator(Modifier.fillMaxWidth())
			}

			state.usage?.let { vUsage ->
				// ============
				//  Downloaded

				Spacer(Modifier.height(8.dp))
				SectionHeading(
					title = "Downloaded",
					// One line, not a paragraph. The distinction between the two sections is the
					// whole content of this screen, and it survives being said briefly.
					subtitle = "Yours until you delete it.",
					trailing = formatBytes(state.keptBytes + state.unattributedKeptBytes),
				)

				if (!state.hasKept && state.unattributedKeptBytes == 0L) {
					Text(
						text = "Nothing downloaded yet.",
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
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
						// Shown rather than folded into a row, so the parts add up to the heading.
						text = "Other · ${formatBytes(state.unattributedKeptBytes)}",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Spacer(Modifier.height(8.dp))
				}

				// ============
				//  Cached

				Spacer(Modifier.height(16.dp))
				SectionHeading(
					title = "Cached",
					subtitle = "Kept within these limits, and dropped as needed.",
					trailing = formatBytes(vUsage.metadataBrowsingBytes + vUsage.imageBytes),
				)

				CacheLine(
					label = "Card data",
					used = vUsage.metadataBrowsingBytes,
					limit = vUsage.metadataLimitBytes,
					onClear = { dispatch(StorageContract.Intent.ClearBrowsingData) },
					enabled = vUsage.metadataBrowsingBytes > 0 && !state.isLoading,
				)
				Spacer(Modifier.height(12.dp))
				CacheLine(
					label = "Images",
					used = vUsage.imageBytes,
					limit = vUsage.imageLimitBytes,
					onClear = { dispatch(StorageContract.Intent.ClearImages) },
					enabled = vUsage.imageBytes > 0 && !state.isLoading,
				)

				Spacer(Modifier.height(12.dp))
				// Beside the bars, because seeing a cache at its ceiling is the moment anyone
				// wants to change the ceiling -- and the limits live in settings, not here.
				OutlinedButton(
					onClick = { dispatch(StorageContract.Intent.CacheSettingsRequested) },
					modifier = Modifier.fillMaxWidth(),
				) {
					Text("Cache settings")
				}

				Spacer(Modifier.height(24.dp))
			}
		}
	}

	state.pendingDelete?.let { vGame ->
		AlertDialog(
			onDismissRequest = { dispatch(StorageContract.Intent.DeleteRequested(null)) },
			title = { Text("Delete ${vGame.displayName}?") },
			text = {
				// The row has one line and says the headline; this has room for the parts that
				// would not fit, and they are the parts a person about to delete wants: every
				// language, with how much of the game each covers, and the sets that are on disk
				// but not in the set list.
				Text(
					buildString {
						append(vGame.summary)
						append(" · ")
						append(formatBytes(vGame.bytes))
						append(".")
						if (vGame.languagesByCoverage.size > 1) {
							append("\n")
							append(
								vGame.languagesByCoverage.joinToString(", ") { (vLanguage, vSets) ->
									"${vLanguage.displayName} $vSets"
								},
							)
							append(" sets.")
						}
						if (vGame.extraSets > 0) {
							append("\n${vGame.extraSets} more the set list does not show.")
						}
						if (vGame.importedVariant != null) {
							append(" The import would have to be run again.")
						}
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

/** A heading with its total on the right, so each section can be read in one glance. */
@Composable
private fun SectionHeading(title: String, subtitle: String, trailing: String) {
	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Text(
			text = title,
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.Medium,
			modifier = Modifier.weight(1f),
		)
		Text(trailing, style = MaterialTheme.typography.titleMedium)
	}
	Text(
		text = subtitle,
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
	Spacer(Modifier.height(10.dp))
}

/** One cache, its share of its limit, and the button that empties it. */
@Composable
private fun CacheLine(
	label: String,
	used: Long,
	limit: Long,
	onClear: () -> Unit,
	enabled: Boolean,
) {
	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Column(Modifier.weight(1f)) {
			Text(
				text = if (limit > 0) "$label · ${formatBytes(used)} of ${formatBytes(limit)}" else
					"$label · ${formatBytes(used)}",
				style = MaterialTheme.typography.bodyMedium,
			)
			if (limit > 0) {
				Spacer(Modifier.height(4.dp))
				LinearProgressIndicator(
					progress = { (used.toFloat() / limit).coerceIn(0f, 1f) },
					modifier = Modifier.fillMaxWidth(),
				)
			}
		}
		Spacer(Modifier.size(12.dp))
		TextButton(onClick = onClear, enabled = enabled) { Text("Clear") }
	}
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
					// What was downloaded and how much of it -- "Card info 2/8 · Thumbnails 2".
					// A row that said only "2 sets · 21.0 MB" left the two questions a person
					// actually has unanswered: how much of the game, and of what.
					text = buildList {
						add(game.summary)
						add(formatBytes(game.bytes))
						game.importedVariant?.let { add("imported ${it.updatedAt.take(10)}") }
					}.joinToString(" · "),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			IconButton(onClick = onDelete, enabled = !isBusy) {
				Icon(
					imageVector = AppIcons.Delete,
					contentDescription = "Delete ${game.displayName} card data",
				)
			}
		}
	}
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
					knownSets = 988,
					importedVariant = BulkImportRecord("default_cards", "2026-09-10T09:14:00Z"),
				),
				StorageContract.KeptGame(
					game = GameId("riftbound"),
					displayName = "Riftbound",
					sets = 2,
					bytes = 21_000_000,
					knownSets = 8,
					thumbnailSets = 2,
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
