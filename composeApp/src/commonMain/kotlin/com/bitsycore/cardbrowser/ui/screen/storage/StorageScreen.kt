package com.bitsycore.cardbrowser.ui.screen.storage

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.bitsycore.cardbrowser.ui.component.AppIcons
import com.bitsycore.cardbrowser.ui.component.arrowScroll
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
	onOpenGame: (GameId) -> Unit = {},
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

			is StorageContract.Effect.OpenGame -> onOpenGame(vEffect.game)
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
					// One line, not a paragraph. The distinction between the sections is the whole
					// content of this screen, and it survives being said briefly.
					subtitle = "Tap a game to see what it holds.",
					trailing = formatBytes(state.keptBytes + state.unattributedKeptBytes),
				)

				if (!state.hasKept && state.unattributedKeptBytes == 0L) {
					Text(
						text = "No cards stored yet.",
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}

				state.kept.forEach { vGame ->
					KeptGameRow(
						game = vGame,
						isBusy = state.isDeleting,
						onDelete = { dispatch(StorageContract.Intent.DeleteRequested(vGame)) },
						onOpen = { dispatch(StorageContract.Intent.GameOpened(vGame.game)) },
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
				//  Image cache

				// The only cache left. Card data is one database and nothing evicts any of it, so
				// a set you opened sits in the section above beside one you downloaded -- the
				// difference between them is how much of the game is there, which the rows say.
				// Images are different in kind: most of the bytes, a ceiling, and the platform may
				// purge them whenever it likes.
				Spacer(Modifier.height(16.dp))
				SectionHeading(
					title = "Image cache",
					subtitle = "Kept within this limit, and dropped as needed.",
					trailing = formatBytes(vUsage.imageBytes),
				)

				CacheLine(
					used = vUsage.imageBytes,
					limit = vUsage.imageLimitBytes,
					onClear = { dispatch(StorageContract.Intent.ClearImages) },
					enabled = vUsage.imageBytes > 0 && !state.isLoading,
				)

				Spacer(Modifier.height(12.dp))
				// Beside the bar, because seeing a cache at its ceiling is the moment anyone wants
				// to change the ceiling -- and the limit lives in settings, not here.
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
				TextButton(onClick = { dispatch(StorageContract.Intent.DeleteConfirmed(vGame)) }) {
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
	used: Long,
	limit: Long,
	onClear: () -> Unit,
	enabled: Boolean,
) {
	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Column(Modifier.weight(1f)) {
			Text(
				text = if (limit > 0) "${formatBytes(used)} of ${formatBytes(limit)}" else
                    formatBytes(used),
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

/**
 * One game's kept records: the headline, a way in, and the delete that removes all of it.
 *
 * The row itself opens the breakdown. The trash icon is still here because "remove this game
 * entirely" is the common case and should not need two screens -- but it is the only thing this
 * row can offer, and a reader who wants one language back needs [onOpen].
 */
@Composable
private fun KeptGameRow(
	game: StorageContract.KeptGame,
	isBusy: Boolean,
	onDelete: () -> Unit,
	onOpen: () -> Unit = {},
) {
	Card(
		onClick = onOpen,
		colors = CardDefaults.cardColors(),
		modifier = Modifier.fillMaxWidth(),
	) {
		Row(
			modifier = Modifier.padding(16.dp).fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(Modifier.weight(1f)) {
				Text(game.displayName, style = MaterialTheme.typography.titleMedium)
				Spacer(Modifier.height(2.dp))
				Text(
					text = buildList {
						add(game.summary)
						add(formatBytes(game.bytes))
						game.originNote?.let { add(it) }
						game.importedVariant?.let { add("imported ${it.updatedAt.take(10)}") }
					}.joinToString(" · "),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				// Two measures, each naming its own unit. Cards are a fraction of the game's
				// cards; pictures are counted in sets, because that is what an image download
				// records. Printing them as two bare percentages would put two differently
				// counted numbers side by side, which is this project's oldest mistake.
				game.completion?.let { vCompletion ->
					Spacer(Modifier.height(8.dp))
					MeasureLine(
						label = "Card info",
						value = vCompletion.label,
						fraction = vCompletion.fraction,
					)
				}
				game.artSummary?.let { vArt ->
					Spacer(Modifier.height(6.dp))
					MeasureLine(label = "Pictures", value = vArt, fraction = game.artFraction)
				}
			}
			Spacer(Modifier.size(4.dp))
			IconButton(onClick = onDelete, enabled = !isBusy) {
				Icon(
					imageVector = AppIcons.Delete,
					contentDescription = "Delete ${game.displayName} card data",
				)
			}
		}
	}
}

/** One labelled measure and its bar. The label is the unit, so two of these cannot be confused. */
@Composable
private fun MeasureLine(label: String, value: String, fraction: Float?) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.width(72.dp),
		)
		if (fraction != null) {
			LinearProgressIndicator(
				progress = { fraction },
				modifier = Modifier.weight(1f),
			)
			Spacer(Modifier.size(10.dp))
		} else {
			Spacer(Modifier.weight(1f))
		}
		Text(
			text = value,
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.primary,
		)
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
