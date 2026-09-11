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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.bitsycore.lib.pulse.compose.collectEffect
import org.koin.compose.viewmodel.koinViewModel

/**
 * Preferences: theme, languages, browsing, the cache ceilings, and what the network did.
 *
 * Limits only, never usage. What is *stored* and what can be deleted is the storage screen's job,
 * and it links back here -- one direction only, so the two cannot be walked in a circle.
 */
@Composable
fun SettingsScreen(
	onBack: () -> Unit,
	viewModel: SettingsViewModel = koinViewModel(),
) {
	// Where navigation is turned back into navigation. The body below dispatches an intent and knows
	// nothing about a back stack; this is the only part that does, and it is not the part that draws.
	viewModel.collectEffect { vEffect ->
		when (vEffect) {
			SettingsContract.Effect.NavigateBack -> onBack()
		}
	}
	val vState by viewModel.collectAsStateWithLifecycle()

	SettingsContent(state = vState, dispatch = viewModel::dispatch)
}

/** The settings screen, given a state and somewhere to send intents. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
	state: SettingsContract.UiState,
	dispatch: (SettingsContract.Intent) -> Unit,
) {
	val vState = state

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("Settings") },
				navigationIcon = {
					IconButton(onClick = { dispatch(SettingsContract.Intent.BackPressed) }) {
						Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
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

			Spacer(Modifier.height(16.dp))
			OutlinedButton(onClick = { dispatch(SettingsContract.Intent.RerunSetup) }) {
				Text("Run first-time setup again")
			}
			Text(
				// What it does and, as importantly, what it does not: nothing is cleared until
				// the flow itself finishes, so opening it to look is free.
				text = "Pick games and a card language again. Nothing downloaded is removed.",
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
			//  Cache

			// "Cache", not "Storage": every control under it bounds what browsing may accumulate,
			// and nothing under it can touch a download. What is *stored* -- and what can be
			// deleted -- is the storage screen's, which is reached from the bar menu.
			SettingsSection("Cache")

			Text(
				text = "Limits apply to cached data. Downloads are kept until deleted.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(Modifier.height(8.dp))

			LimitRow(
				label = "Image cache limit",
				note = "Applies on next launch. About 22 KB a thumbnail.",
				selected = vState.imageLimitBytes,
				onSelect = { dispatch(SettingsContract.Intent.ImageCacheLimitChosen(it)) },
			)

			LimitRow(
				label = "Card data limit",
				note = "Applies now. A whole set is a few megabytes.",
				selected = vState.metadataLimitBytes,
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
 * A cache ceiling: the presets, plus whatever the user would rather type.
 *
 * The "Custom" chip is not a sixth preset -- it reads as selected whenever the stored value is off
 * the list, and then carries that value as its label, so a limit set by hand is still legible at a
 * glance rather than leaving every chip unselected and the real figure nowhere on screen. That also
 * covers a value a previous build wrote and this one no longer offers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LimitRow(
	label: String,
	note: String,
	selected: Long,
	onSelect: (Long) -> Unit,
) {
	var vEditing by remember { mutableStateOf(false) }
	val vIsPreset = selected in BrowsingPreferences.CACHE_LIMIT_CHOICES

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
		BrowsingPreferences.CACHE_LIMIT_CHOICES.forEach { vOption ->
			FilterChip(
				selected = vOption == selected,
				onClick = { onSelect(vOption) },
				label = { Text(formatBytes(vOption)) },
			)
		}
		FilterChip(
			selected = !vIsPreset,
			onClick = { vEditing = true },
			label = { Text(if (vIsPreset) "Custom" else formatBytes(selected)) },
		)
	}

	if (vEditing) {
		CustomLimitDialog(
			label = label,
			current = selected,
			onDismiss = { vEditing = false },
			onConfirm = {
				vEditing = false
				onSelect(it)
			},
		)
	}
}

/**
 * Types a limit in megabytes.
 *
 * Megabytes rather than bytes because nobody wants to count zeroes, and the confirm stays disabled
 * on anything unparseable or out of range instead of silently rounding it into one -- a settings
 * screen that quietly changed what you typed would be the same dishonesty as one that misreports a
 * figure.
 */
@Composable
private fun CustomLimitDialog(
	label: String,
	current: Long,
	onDismiss: () -> Unit,
	onConfirm: (Long) -> Unit,
) {
	val vMinMb = BrowsingPreferences.MIN_CACHE_LIMIT_BYTES / MB
	val vMaxMb = BrowsingPreferences.MAX_CACHE_LIMIT_BYTES / MB
	var vText by remember { mutableStateOf((current / MB).toString()) }
	val vMegabytes = vText.trim().toLongOrNull()
	val vIsValid = vMegabytes != null && vMegabytes in vMinMb..vMaxMb

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(label) },
		text = {
			Column {
				OutlinedTextField(
					value = vText,
					onValueChange = { vText = it.filter { vChar -> vChar.isDigit() } },
					singleLine = true,
					label = { Text("Megabytes") },
					isError = vText.isNotBlank() && !vIsValid,
				)
				Spacer(Modifier.height(8.dp))
				Text(
					text = "$vMinMb to $vMaxMb MB.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		},
		confirmButton = {
			TextButton(
				onClick = { vMegabytes?.let { onConfirm(it * MB) } },
				enabled = vIsValid,
			) { Text("Set") }
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
	)
}

/** One binary megabyte, the unit the custom-limit field is in. */
private const val MB: Long = 1024L * 1024


/**
 * Bytes as something a person reads.
 *
 * Binary units with decimal names, which is what every desktop OS shows and therefore what a user
 * will compare this against.
 */
internal fun formatBytes(bytes: Long): String = when {
	bytes < 1024 -> "$bytes B"
	bytes < 1024 * 1024 -> "${bytes / 1024} KB"
	bytes < 1024L * 1024 * 1024 -> "${trimTenth((bytes * 10 / (1024 * 1024)) / 10.0)} MB"
	else -> "${trimTenth((bytes * 10 / (1024L * 1024 * 1024)) / 10.0)} GB"
}

/** Drops a trailing `.0`, so a row of chips reads "256 MB" rather than "256.0 MB". */
private fun trimTenth(value: Double): String =
	value.toString().removeSuffix(".0")

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
	)
}
