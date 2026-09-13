package com.bitsycore.toploader.ui.screen.storagedetail

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
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
import com.bitsycore.toploader.core.model.GameId
import com.bitsycore.toploader.data.repository.KeptSet
import com.bitsycore.toploader.ui.component.AppIcons
import com.bitsycore.toploader.ui.component.arrowScroll
import com.bitsycore.toploader.ui.preview.PreviewFrame
import com.bitsycore.toploader.ui.screen.storage.formatBytes
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import com.bitsycore.lib.pulse.compose.collectEffect
import org.koin.compose.viewmodel.koinViewModel

/**
 * One game's downloads, listed so that part of them can be removed.
 *
 * Reached by tapping a game on the storage screen, which can only offer all-or-nothing. Here a
 * language or a single set goes on its own.
 */
@Composable
fun StorageDetailScreen(
	onBack: () -> Unit,
	viewModel: StorageDetailViewModel = koinViewModel(),
) {
	val vSnackbar = remember { SnackbarHostState() }
	viewModel.collectEffect { vEffect ->
		when (vEffect) {
			is StorageDetailContract.Effect.Deleted -> vSnackbar.showSnackbar(
				if (vEffect.sets == 1) {
					"Deleted 1 set."
				} else {
					"Deleted ${vEffect.sets} sets."
				},
			)

			StorageDetailContract.Effect.NavigateBack -> onBack()

			StorageDetailContract.Effect.NavigateBackEmpty -> onBack()
		}
	}
	val vState by viewModel.collectAsStateWithLifecycle()

	StorageDetailContent(
		state = vState,
		dispatch = viewModel::dispatch,
		snackbarHostState = vSnackbar,
	)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageDetailContent(
	state: StorageDetailContract.UiState,
	dispatch: (StorageDetailContract.Intent) -> Unit,
	snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(state.displayName.ifBlank { "Storage" }) },
				navigationIcon = {
					IconButton(onClick = { dispatch(StorageDetailContract.Intent.BackPressed) }) {
						Icon(AppIcons.ArrowBack, contentDescription = "Back")
					}
				},
			)
		},
		snackbarHost = { SnackbarHost(snackbarHostState) },
	) { vPadding ->
		val vScroll = rememberScrollState()
		Column(
			Modifier
				.padding(vPadding)
				.fillMaxSize()
				.verticalScroll(vScroll)
				.arrowScroll(vScroll)
				.padding(horizontal = 16.dp),
		) {
			Spacer(Modifier.height(8.dp))
			Text(
				text = "${state.summary} · ${formatBytes(state.totalBytes)}",
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(Modifier.height(16.dp))

			for (vGroup in state.byLanguage) {
				LanguageSection(
					group = vGroup,
					// Offered only where there is a second language to keep. Deleting "every
					// English set" of a game held only in English is deleting the game, and that
					// is the storage screen's button, said plainly there.
					canDeleteWhole = state.hasSeveralLanguages,
					// With one group its totals are the screen's totals, printed just above.
					showTotals = state.hasSeveralLanguages,
					isBusy = state.isDeleting,
					onDeleteLanguage = {
						dispatch(
							StorageDetailContract.Intent.DeleteRequested(
								StorageDetailContract.Target.WholeLanguage(vGroup),
							),
						)
					},
					onDeleteSet = { vSet ->
						dispatch(
							StorageDetailContract.Intent.DeleteRequested(
								StorageDetailContract.Target.OneSet(vSet),
							),
						)
					},
				)
				Spacer(Modifier.height(20.dp))
			}
			Spacer(Modifier.height(16.dp))
		}
	}

	state.pendingDelete?.let { vTarget ->
		AlertDialog(
			onDismissRequest = {
				dispatch(StorageDetailContract.Intent.DeleteRequested(null))
			},
			title = { Text("Delete ${vTarget.label}?") },
			text = {
				Text(
					"${formatBytes(vTarget.bytes)} will be removed from this device. " +
						"It can be downloaded again.",
				)
			},
			confirmButton = {
				TextButton(
					onClick = {
						dispatch(StorageDetailContract.Intent.DeleteConfirmed(vTarget))
					},
				) { Text("Delete") }
			},
			dismissButton = {
				TextButton(onClick = { dispatch(StorageDetailContract.Intent.DeleteRequested(null)) }) {
					Text("Cancel")
				}
			},
		)
	}
}

/** One language, its total, and every set held in it. */
@Composable
private fun LanguageSection(
	group: StorageDetailContract.LanguageGroup,
	canDeleteWhole: Boolean,
	showTotals: Boolean,
	isBusy: Boolean,
	onDeleteLanguage: () -> Unit,
	onDeleteSet: (KeptSet) -> Unit,
) {
	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Column(Modifier.weight(1f)) {
			Text(
				text = group.displayName,
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.Medium,
			)
			if (showTotals) {
				Text(
					text = "${group.summary} · ${formatBytes(group.bytes)}",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		if (canDeleteWhole) {
			TextButton(onClick = onDeleteLanguage, enabled = !isBusy) { Text("Delete all") }
		}
	}
	Spacer(Modifier.height(8.dp))

	Card(colors = CardDefaults.cardColors()) {
		Column(Modifier.padding(vertical = 4.dp)) {
			for (vSet in group.sets) {
				SetRow(set = vSet, isBusy = isBusy, onDelete = { onDeleteSet(vSet) })
			}
		}
	}
}

/** One downloaded set, and the only control that removes it. */
@Composable
private fun SetRow(set: KeptSet, isBusy: Boolean, onDelete: () -> Unit) {
	Row(
		Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Column(Modifier.weight(1f)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				// The code first, because it is how a set is actually referred to -- "OP-16", not
				// "Beginning of the New Era". Fixed-width-ish and dimmed so the name still leads
				// the eye.
				set.code?.let { vCode ->
					Text(
						text = vCode,
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Spacer(Modifier.size(8.dp))
				}
				Text(
					text = set.label,
					style = MaterialTheme.typography.bodyMedium,
					modifier = Modifier.weight(1f, fill = false),
				)
				// Only where there is a denominator. A source that states no set sizes gets a card
				// count and no percentage, rather than a percentage of a number nobody supplied.
				set.completion?.let { vCompletion ->
					Spacer(Modifier.size(8.dp))
					Text(
						text = vCompletion.label,
						style = MaterialTheme.typography.bodyMedium,
						color = if (vCompletion.percent == 100) {
							MaterialTheme.colorScheme.onSurfaceVariant
						} else {
							MaterialTheme.colorScheme.primary
						},
					)
				}
			}
			Text(
				// Marked rather than hidden. A bulk import stores sets the catalogue does not
				// offer, and they take up real space -- a screen that cannot show them is one
				// whose total does not add up.
				text = buildString {
					val vKnown = set.knownCardCount
					if (vKnown != null && !set.isComplete) {
						append("${set.cardCount} of $vKnown cards")
					} else {
						append("${set.cardCount} cards")
					}
					append(" · ${formatBytes(set.bytes)}")
					if (!set.isDownloaded) append(" · browsed")
					if (!set.isInCatalogue) append(" · not in the set list")
				},
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		Spacer(Modifier.size(8.dp))
		IconButton(onClick = onDelete, enabled = !isBusy) {
			Icon(AppIcons.Delete, contentDescription = "Delete ${set.label}")
		}
	}
}

@Preview
@Composable
private fun StorageDetailPreview() {
	PreviewFrame {
		StorageDetailContent(
			state = StorageDetailContract.UiState(
				game = GameId("pokemon"),
				displayName = "Pokémon",
				isLoading = false,
				sets = listOf(
					KeptSet("tcgdex", "tcgdex:sv08", "en", "Surging Sparks", "SV08", 252, 1_400_000),
					KeptSet("tcgdex", "tcgdex:sv07", "en", "Stellar Crown", "SV07", 175, 980_000),
					KeptSet("tcgdex", "tcgdex:sv08", "fr", "Étincelles Déferlantes", "SV08", 252, 1_410_000),
				),
			),
			dispatch = {},
		)
	}
}

@Preview
@Composable
private fun StorageDetailSingleLanguagePreview() {
	PreviewFrame {
		StorageDetailContent(
			state = StorageDetailContract.UiState(
				game = GameId("onepiece"),
				displayName = "One Piece",
				isLoading = false,
				sets = listOf(
					// OPTCG states no language, which is a group of its own and says so.
					KeptSet("optcg", "optcg:OP-01", "-", "Romance Dawn", "OP-01", 154, 620_000),
					KeptSet("optcg", "optcg:OP-02", "-", "Paramount War", "OP-02", 154, 615_000),
				),
			),
			dispatch = {},
		)
	}
}
