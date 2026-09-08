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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

/**
 * Cache usage, cache clearing, and the card-language preference order.
 *
 * The two caches are reported and cleared separately because they behave differently: metadata is
 * what makes offline browsing work and is cheap to refetch, images are most of the bytes.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
	onBack: () -> Unit,
	viewModel: SettingsViewModel = koinViewModel(),
) {
	val vState by viewModel.collectAsStateWithLifecycle()

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("Settings") },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
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

			Text("Card language preference", style = MaterialTheme.typography.titleSmall)
			Spacer(Modifier.height(4.dp))
			Text(
				text = "The order cards are preferred in. This is a preference, not a guarantee: " +
					"each card database supports only some languages, and a card's own page says " +
					"which one you are actually looking at.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(Modifier.height(8.dp))
			FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				vState.preferredLanguages.forEachIndexed { vIndex, vLanguage ->
					AssistChip(
						onClick = { viewModel.dispatch(SettingsContract.Intent.PromoteLanguage(vLanguage)) },
						label = { Text("${vIndex + 1}. ${vLanguage.displayName}") },
					)
				}
			}
			Spacer(Modifier.height(4.dp))
			Text(
				text = "Tap a language to move it to the front.",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)

			Spacer(Modifier.height(20.dp))
			HorizontalDivider()
			Spacer(Modifier.height(16.dp))

			Text("Storage", style = MaterialTheme.typography.titleSmall)
			Spacer(Modifier.height(8.dp))

			CacheRow(
				label = "Card data",
				detail = "${vState.metadataEntries} records",
				usedBytes = vState.metadataBytes,
				limitBytes = vState.metadataLimitBytes,
			)
			Spacer(Modifier.height(12.dp))
			CacheRow(
				label = "Images",
				detail = "Downloaded as you browse",
				usedBytes = vState.imageBytes,
				limitBytes = vState.imageLimitBytes,
			)

			Spacer(Modifier.height(12.dp))
			Text(
				text = "Both caches evict the least recently used items when they reach their " +
					"limit. Clearing them frees space and costs a re-download; your preferences " +
					"and the set you were reading are kept separately and are not affected.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)

			Spacer(Modifier.height(12.dp))
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				OutlinedButton(
					onClick = { viewModel.dispatch(SettingsContract.Intent.ClearMetadata) },
					modifier = Modifier.weight(1f),
				) { Text("Clear card data") }
				OutlinedButton(
					onClick = { viewModel.dispatch(SettingsContract.Intent.ClearImages) },
					modifier = Modifier.weight(1f),
				) { Text("Clear images") }
			}

			vState.providerAttribution?.let { vAttribution ->
				Spacer(Modifier.height(20.dp))
				HorizontalDivider()
				Spacer(Modifier.height(12.dp))
				Text("Data source", style = MaterialTheme.typography.titleSmall)
				Spacer(Modifier.height(4.dp))
				Text(
					text = vAttribution,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

/** One cache, with a bar showing how close it is to its ceiling. */
@Composable
private fun CacheRow(label: String, detail: String, usedBytes: Long, limitBytes: Long) {
	Column(Modifier.fillMaxWidth()) {
		Row(Modifier.fillMaxWidth()) {
			Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
			Text(
				text = "${formatBytes(usedBytes)} / ${formatBytes(limitBytes)}",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		Spacer(Modifier.height(4.dp))
		LinearProgressIndicator(
			progress = {
				if (limitBytes <= 0) 0f else (usedBytes.toFloat() / limitBytes.toFloat()).coerceIn(0f, 1f)
			},
			modifier = Modifier.fillMaxWidth(),
		)
		Spacer(Modifier.height(2.dp))
		Text(
			text = detail,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
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
