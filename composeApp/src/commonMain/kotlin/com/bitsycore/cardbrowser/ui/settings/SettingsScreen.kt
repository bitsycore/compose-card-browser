package com.bitsycore.cardbrowser.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.data.settings.BrowsingPreferences
import com.bitsycore.cardbrowser.data.settings.ThemeMode
import com.bitsycore.cardbrowser.ui.common.FinePrint
import com.bitsycore.cardbrowser.ui.preview.PreviewFrame
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

/**
 * Cache usage, cache clearing, and the card-language preference order.
 *
 * The two caches are reported and cleared separately because they behave differently: metadata is
 * what makes offline browsing work and is cheap to refetch, images are most of the bytes.
 */
@Composable
fun SettingsScreen(
	onBack: () -> Unit,
	onOpenStorage: () -> Unit = {},
	onOpenDownloads: () -> Unit = {},
	viewModel: SettingsViewModel = koinViewModel(),
) {
	val vState by viewModel.collectAsStateWithLifecycle()

	SettingsContent(
		state = vState,
		dispatch = viewModel::dispatch,
		onBack = onBack,
		onOpenStorage = onOpenStorage,
		onOpenDownloads = onOpenDownloads,
	)
}

/** The settings screen, given a state and somewhere to send intents. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
	state: SettingsContract.UiState,
	dispatch: (SettingsContract.Intent) -> Unit,
	onBack: () -> Unit,
	onOpenStorage: () -> Unit = {},
	onOpenDownloads: () -> Unit = {},
) {
	val vState = state
	var vMenuOpen by remember { mutableStateOf(false) }

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("Settings") },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
					}
				},
				actions = {
					// The screens settings *leads to*, rather than buttons buried among the
					// switches. Neither is a preference, and both manage something that lives
					// elsewhere, so neither belongs in the list below.
					IconButton(onClick = { vMenuOpen = true }) {
						Icon(Icons.Outlined.MoreVert, contentDescription = "More")
					}
					DropdownMenu(expanded = vMenuOpen, onDismissRequest = { vMenuOpen = false }) {
						DropdownMenuItem(
							text = { Text("Manage storage") },
							onClick = {
								vMenuOpen = false
								onOpenStorage()
							},
						)
						DropdownMenuItem(
							text = { Text("Downloads") },
							onClick = {
								vMenuOpen = false
								onOpenDownloads()
							},
						)
					}
				},
			)
		},
	) { vPadding ->
		Column(
			modifier = Modifier
				.padding(vPadding)
				.fillMaxSize()
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 20.dp, vertical = 8.dp),
		) {
			// ============
			//  General

			SettingsSection("General", isFirst = true)

			ChoiceRow(
				label = "Theme",
				note = "System follows the device.",
				options = ThemeMode.entries,
				selected = vState.themeMode,
				render = { it.label },
				onSelect = { dispatch(SettingsContract.Intent.ThemeModeChosen(it)) },
			)

			Spacer(Modifier.height(12.dp))
			Text("Card language", style = MaterialTheme.typography.bodyMedium)
			Text(
				text = "A preference, not a guarantee. Each card says which language it is.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(Modifier.height(8.dp))
			FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				vState.preferredLanguages.forEachIndexed { vIndex, vLanguage ->
					AssistChip(
						onClick = { dispatch(SettingsContract.Intent.PromoteLanguage(vLanguage)) },
						label = { Text("${vIndex + 1}. ${vLanguage.displayName}") },
					)
				}
			}
			Spacer(Modifier.height(4.dp))
			Text(
				text = "Tap to move to the front.",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)

			// ============
			//  Browsing

			SettingsSection("Browsing")

			SwitchRow(
				label = "Hide sets with no cards",
				note = "Sets a source states are empty.",
				checked = vState.hideEmptySets,
				onCheckedChange = { dispatch(SettingsContract.Intent.HideEmptySetsChanged(it)) },
			)

			Spacer(Modifier.height(12.dp))
			SwitchRow(
				label = "Check for new sets on launch",
				note = "One small request. The saved list shows first either way.",
				checked = vState.revalidateSetsOnLaunch,
				onCheckedChange = {
					dispatch(SettingsContract.Intent.RevalidateOnLaunchChanged(it))
				},
			)

			Spacer(Modifier.height(12.dp))
			ChoiceRow(
				label = "Cards fetched ahead",
				note = "Art fetched either side of the open card. About 180 KB each.",
				options = BrowsingPreferences.PREFETCH_CHOICES,
				selected = vState.prefetchRadius,
				render = { if (it == 0) "Off" else "$it" },
				onSelect = { dispatch(SettingsContract.Intent.PrefetchRadiusChosen(it)) },
			)

			// ============
			//  Storage

			SettingsSection("Storage")

			Text(
				// What is *used* is on the storage screen, which can also act on it. Reporting the
				// same figures here left a reader looking at numbers with no button beside them.
				text = "Limits apply to cached data. Downloads are kept until deleted.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(Modifier.height(8.dp))

			ChoiceRow(
				label = "Image cache limit",
				note = "Applies on next launch.",
				options = BrowsingPreferences.IMAGE_CACHE_CHOICES,
				selected = vState.imageLimitBytes,
				render = ::formatBytes,
				onSelect = { dispatch(SettingsContract.Intent.ImageCacheLimitChosen(it)) },
			)

			ChoiceRow(
				label = "Card data limit",
				note = "Applies now. A whole set is a few megabytes.",
				options = BrowsingPreferences.METADATA_CACHE_CHOICES,
				selected = vState.metadataLimitBytes,
				render = ::formatBytes,
				onSelect = { dispatch(SettingsContract.Intent.MetadataCacheLimitChosen(it)) },
			)

			// ============
			//  Network

			SettingsSection("Network", action = {
				if (vState.apiCalls.isNotEmpty()) {
					TextButton(onClick = { dispatch(SettingsContract.Intent.ResetApiCalls) }) {
						Text("Reset")
					}
				}
			})

			Text(
				text = "Requests since launch. Reopening a cached set should not move these.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(Modifier.height(8.dp))

			if (vState.apiCalls.isEmpty()) {
				Text(
					// The honest reading of zero: everything on screen came off the disk.
					text = "No requests yet.",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			} else {
				vState.apiCalls.forEach { (vHost, vCount) ->
					Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
						Text(
							text = vHost,
							style = MaterialTheme.typography.bodyMedium,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.weight(1f),
						)
						Spacer(Modifier.width(12.dp))
						Text(
							text = vCount.toString(),
							style = MaterialTheme.typography.bodyMedium,
							fontWeight = FontWeight.Medium,
						)
					}
				}
				Spacer(Modifier.height(6.dp))
				Text(
					text = "${vState.apiCallTotal} in total.",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}

			// Last on the screen and the quietest thing on it. Required, not worth reading twice.
			if (vState.attributions.isNotEmpty()) {
				Spacer(Modifier.height(20.dp))
				HorizontalDivider()
				Spacer(Modifier.height(12.dp))
				FinePrint(vState.attributions.joinToString("\n") { "${it.source} — ${it.text}" })
			}
			Spacer(Modifier.height(24.dp))
		}
	}
}

/**
 * A section rule and its heading, with an optional action on the right.
 *
 * The first section needs no rule above it, which is the only reason this takes a flag rather than
 * being four lines repeated five times.
 */
@Composable
private fun SettingsSection(
	title: String,
	isFirst: Boolean = false,
	action: @Composable (() -> Unit)? = null,
) {
	if (!isFirst) {
		Spacer(Modifier.height(20.dp))
		HorizontalDivider()
		Spacer(Modifier.height(16.dp))
	}
	if (action == null) {
		Text(title, style = MaterialTheme.typography.titleSmall)
	} else {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				text = title,
				style = MaterialTheme.typography.titleSmall,
				modifier = Modifier.weight(1f),
			)
			action()
		}
	}
	Spacer(Modifier.height(8.dp))
}

/** A switch with its label and one line of explanation. */
@Composable
private fun SwitchRow(
	label: String,
	note: String,
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Column(Modifier.weight(1f)) {
			Text(label, style = MaterialTheme.typography.bodyMedium)
			Text(
				text = note,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		Spacer(Modifier.size(12.dp))
		Switch(checked = checked, onCheckedChange = onCheckedChange)
	}
}

/**
 * A labelled row of mutually exclusive choices.
 *
 * Preset chips rather than a slider: every one of these is a value the user might want to state
 * exactly, and none of them is worth the imprecision of dragging. The note underneath says when the
 * choice takes effect, because for one of them the answer is "next launch" and hiding that would be
 * the sort of small dishonesty that makes a settings screen untrustworthy.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(
	label: String,
	note: String,
	options: List<T>,
	selected: T,
	render: (T) -> String,
	onSelect: (T) -> Unit,
) {
	Spacer(Modifier.height(16.dp))
	Text(label, style = MaterialTheme.typography.bodyMedium)
	Spacer(Modifier.height(2.dp))
	Text(
		text = note,
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
	Spacer(Modifier.height(8.dp))
	FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		options.forEach { vOption ->
			FilterChip(
				selected = vOption == selected,
				onClick = { onSelect(vOption) },
				label = { Text(render(vOption)) },
			)
		}
	}
}


/**
 * Bytes as something a person reads.
 *
 * Binary units with decimal names, which is what every desktop OS shows and therefore what a user
 * will compare this against.
 */
internal fun formatBytes(bytes: Long): String = when {
	bytes < 1024 -> "$bytes B"
	bytes < 1024 * 1024 -> "${bytes / 1024} KB"
	bytes < 1024L * 1024 * 1024 -> "${(bytes * 10 / (1024 * 1024)) / 10.0} MB"
	else -> "${(bytes * 10 / (1024L * 1024 * 1024)) / 10.0} GB"
}

// ==================
// MARK: Previews
// ==================

@Preview
@Composable
private fun SettingsPreview() = PreviewFrame {
	SettingsContent(
		state = SettingsContract.UiState(
			metadataLimitBytes = BrowsingPreferences.DEFAULT_METADATA_CACHE_LIMIT_BYTES,
			imageLimitBytes = BrowsingPreferences.DEFAULT_IMAGE_CACHE_LIMIT_BYTES,
			attributions = listOf(
				SettingsContract.ProviderCredit(
					source = "Riftcodex",
					text = "Card data from Riftcodex, an unofficial fan project not affiliated " +
						"with Riot Games.",
				),
				SettingsContract.ProviderCredit(
					source = "TCGdex",
					text = "Pokémon card data from TCGdex, a community project not affiliated " +
						"with Nintendo, Creatures or GAME FREAK.",
				),
			),
		),
		dispatch = {},
		onBack = {},
	)
}

@Preview
@Composable
private fun SettingsLightPreview() = PreviewFrame(isDark = false) {
	SettingsContent(
		state = SettingsContract.UiState(
			metadataLimitBytes = 256L * 1024 * 1024,
			imageLimitBytes = 128L * 1024 * 1024,
			prefetchRadius = 0,
			revalidateSetsOnLaunch = false,
		),
		dispatch = {},
		onBack = {},
	)
}
