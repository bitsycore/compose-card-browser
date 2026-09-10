package com.bitsycore.cardbrowser.ui.downloads

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.data.download.DownloadJob
import com.bitsycore.cardbrowser.data.download.DownloadKind
import com.bitsycore.cardbrowser.data.download.DownloadStatus
import com.bitsycore.cardbrowser.ui.common.EmptyState
import com.bitsycore.cardbrowser.ui.preview.PreviewFrame
import androidx.compose.ui.tooling.preview.Preview

/**
 * The download queue, as a screen.
 *
 * It was a dialog, and a dialog was the wrong container for it. A download of Magic runs for a long
 * time and the queue is the only place that says what is happening -- so it is the one thing in the
 * app a user comes *back* to, and a dialog is a thing you dismiss. It also has to hold a row per
 * set, which for "download all" is hundreds, inside a box that has to leave room for the screen
 * behind it.
 *
 * As a screen it can say considerably more per job: which language, which kinds, how far through,
 * and what a failure actually was. It also gets a summary, because "is it done yet" should be
 * answerable without reading a list of two hundred rows.
 *
 * ## On the progress bars
 *
 * Material 3 Expressive's `LinearWavyProgressIndicator`. Getting to it meant bumping `material3`
 * from 1.9.0 to 1.12.0-alpha03 -- the project was pinning material3 to 1.9.0 while every other
 * Compose artifact was already on 1.12.0, so this aligned a version that had drifted rather than
 * reaching for something new. The expressive components are behind
 * `ExperimentalMaterial3ExpressiveApi`, which is the honest status of them: they are alpha.
 *
 * The wave is not only decoration here. A long download that is genuinely progressing looks the
 * same as a stalled one under a static bar, and the wave animates on its own -- so a queue that is
 * moving is distinguishable at a glance from one that is stuck.
 *
 * An indeterminate bar where the total is unknown is not laziness. The image count cannot be known
 * until the card list has landed, and a determinate bar sitting at 0% would state a total that has
 * not been established.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadsScreen(
	jobs: List<DownloadJob>,
	onBack: () -> Unit,
	onCancel: (String) -> Unit,
	onCancelAll: () -> Unit,
	onClearFinished: () -> Unit,
) {
	val vActive = jobs.count { it.isActive }
	val vDone = jobs.count { it.status is DownloadStatus.Completed }
	val vFailed = jobs.count { it.status is DownloadStatus.Failed }

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("Downloads") },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
					}
				},
			)
		},
	) { vPadding ->
		Box(Modifier.padding(vPadding).fillMaxSize()) {
			if (jobs.isEmpty()) {
				EmptyState("Nothing queued. Downloads you start appear here.")
			} else {
				LazyColumn(
					contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
					verticalArrangement = Arrangement.spacedBy(10.dp),
				) {
					item(key = "summary") {
						QueueSummary(
							active = vActive,
							done = vDone,
							failed = vFailed,
							onCancelAll = onCancelAll.takeIf { vActive > 0 },
							onClearFinished = onClearFinished.takeIf { vDone + vFailed > 0 },
						)
					}
					items(jobs, key = { it.id }) { vJob ->
						DownloadCard(job = vJob, onCancel = onCancel)
					}
				}
			}
		}
	}
}

/** One line answering "is it done yet", so nobody has to count two hundred rows. */
@Composable
private fun QueueSummary(
	active: Int,
	done: Int,
	failed: Int,
	onCancelAll: (() -> Unit)?,
	onClearFinished: (() -> Unit)?,
) {
	Column(Modifier.padding(bottom = 4.dp)) {
		Text(
			text = when {
				active > 0 && failed > 0 -> "$active running, $done done, $failed failed"
				active > 0 -> "$active running, $done done"
				failed > 0 -> "$done done, $failed failed"
				else -> "$done done"
			},
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.Medium,
		)
		Text(
			// The pacing, stated where someone is watching a queue and wondering why it is slow.
			text = "One set at a time, to stay within what these free APIs allow.",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		if (onCancelAll != null || onClearFinished != null) {
			Spacer(Modifier.height(8.dp))
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				onCancelAll?.let { OutlinedButton(onClick = it) { Text("Stop all") } }
				onClearFinished?.let { OutlinedButton(onClick = it) { Text("Clear finished") } }
			}
		}
	}
}

/** One job, with everything known about it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadCard(job: DownloadJob, onCancel: (String) -> Unit) {
	Card(
		modifier = Modifier.fillMaxWidth(),
		colors = if (job.status is DownloadStatus.Failed) {
			CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
		} else {
			CardDefaults.cardColors()
		},
	) {
		Column(Modifier.padding(14.dp).fillMaxWidth()) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Column(Modifier.weight(1f)) {
					Text(
						text = job.request.setName,
						style = MaterialTheme.typography.titleSmall,
						fontWeight = FontWeight.Medium,
					)
					Text(
						text = describe(job),
						style = MaterialTheme.typography.bodySmall,
						color = if (job.status is DownloadStatus.Failed) {
							MaterialTheme.colorScheme.onErrorContainer
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
					)
				}
				StatusMark(job)
				if (job.isActive) {
					IconButton(onClick = { onCancel(job.id) }) {
						Icon(
							Icons.Outlined.Close,
							contentDescription = "Stop downloading ${job.request.setName}",
						)
					}
				}
			}

			// What this job is actually for, which the old row never said: a set can be queued
			// three times over for three different things in two languages, and three identical
			// rows reading only the set's name was genuinely confusing.
			Spacer(Modifier.height(8.dp))
			Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				// An import is the whole game in one file, which is a different shape of work
				// from a set fetch and reads as one wrong row unless it says so.
				if (job.request.isWholeGameImport) QueueChip("Whole game")
				job.request.language?.let { vLanguage ->
					QueueChip(vLanguage.displayName)
				}
				job.request.kinds.sortedBy { it.ordinal }.forEach { vKind ->
					QueueChip(kindLabel(vKind))
				}
			}

			if (job.status is DownloadStatus.Running) {
				Spacer(Modifier.height(10.dp))
				val vProgress = job.progress
				if (vProgress == null) {
					LinearWavyProgressIndicator(Modifier.fillMaxWidth())
				} else {
					LinearWavyProgressIndicator(
						progress = { vProgress },
						modifier = Modifier.fillMaxWidth(),
					)
				}
			}
		}
	}
}

/** A tick, a warning, or nothing while it is still going. */
@Composable
private fun StatusMark(job: DownloadJob) {
	when (job.status) {
		is DownloadStatus.Completed -> Icon(
			imageVector = Icons.Outlined.Check,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.primary,
			modifier = Modifier.size(20.dp),
		)
		is DownloadStatus.Failed -> Icon(
			imageVector = Icons.Outlined.ErrorOutline,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.error,
			modifier = Modifier.size(20.dp),
		)
		else -> Spacer(Modifier.width(0.dp))
	}
}

@Composable
private fun QueueChip(text: String) {
	AssistChip(
		onClick = {},
		enabled = false,
		label = { Text(text, style = MaterialTheme.typography.labelSmall) },
		colors = AssistChipDefaults.assistChipColors(
			disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
		),
	)
}

private fun kindLabel(kind: DownloadKind): String = when (kind) {
	DownloadKind.CARD_INFO -> "Card info"
	DownloadKind.GRID_THUMBNAILS -> "Thumbnails"
}

// ==================
// MARK: Previews
// ==================

@Preview
@Composable
private fun DownloadsScreenPreview() = PreviewFrame {
	DownloadsScreen(
		jobs = emptyList(),
		onBack = {},
		onCancel = {},
		onCancelAll = {},
		onClearFinished = {},
	)
}
