package com.bitsycore.cardbrowser.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.data.download.DownloadJob
import com.bitsycore.cardbrowser.data.download.DownloadKind
import com.bitsycore.cardbrowser.data.download.DownloadRequest
import com.bitsycore.cardbrowser.data.download.DownloadStatus
import com.bitsycore.cardbrowser.ui.preview.PreviewFrame

// ==================
// MARK: Choosing what to download
// ==================

/**
 * Asks which parts of a set to put on disk.
 *
 * Two checkboxes rather than one button, because the two cost very different amounts and the
 * dialog says so in the same breath as offering them. Card records are a handful of small requests;
 * images are one per card against a CDN, which for a large set is hundreds of them.
 *
 * Card info is pre-selected and images are not: the cheap, useful half is the default, and the
 * expensive half is opted into.
 */
@Composable
fun DownloadKindDialog(
	setName: String,
	cardCount: Int?,
	onDismiss: () -> Unit,
	onConfirm: (Set<DownloadKind>) -> Unit,
) {
	var vInfo by remember { mutableStateOf(true) }
	var vImages by remember { mutableStateOf(false) }

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("Download $setName") },
		text = {
			Column {
				KindRow(
					checked = vInfo,
					onCheckedChange = { vInfo = it },
					title = "Card info",
					// Deliberately not "small": the honest thing is to say what it is, since a set
					// with an unknown card count cannot be sized at all.
					detail = cardCount?.let { "$it cards. Names, numbers, rarities and rules text." }
						?: "Names, numbers, rarities and rules text.",
				)
				Spacer(Modifier.height(8.dp))
				KindRow(
					checked = vImages,
					onCheckedChange = { vImages = it },
					title = "Card images",
					detail = cardCount
						?.let { "Two images per card, about ${estimateMegabytes(it)} MB in total." }
						?: "Two images per card: the grid thumbnail and the full-size art.",
				)
				Spacer(Modifier.height(12.dp))
				Text(
					text = "Downloads run one set at a time, to stay within what these free APIs " +
						"allow. You can keep browsing while one runs.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		},
		confirmButton = {
			TextButton(
				// Nothing ticked is not a download, so the button is not offered as one.
				enabled = vInfo || vImages,
				onClick = {
					onConfirm(
						buildSet {
							if (vInfo) add(DownloadKind.CARD_INFO)
							if (vImages) add(DownloadKind.CARD_IMAGES)
						},
					)
				},
			) { Text("Download") }
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
	)
}

@Composable
private fun KindRow(
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
	title: String,
	detail: String,
) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Checkbox(checked = checked, onCheckedChange = onCheckedChange)
		Spacer(Modifier.width(4.dp))
		Column {
			Text(title, style = MaterialTheme.typography.bodyLarge)
			Text(
				text = detail,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

/**
 * A rough size for a set's art, so the choice is informed rather than blind.
 *
 * Deliberately approximate and labelled "about". Measured across the providers here, a thumbnail
 * runs about 20 KB and a full-size render about 180 KB, so a card costs roughly 200 KB of the two.
 * Sets vary and some sources serve one image at full size for both, which this will under-count --
 * it is an order of magnitude, not a promise.
 */
private fun estimateMegabytes(cardCount: Int): Int =
	((cardCount * APPROX_BYTES_PER_CARD) / 1_000_000).coerceAtLeast(1)

private const val APPROX_BYTES_PER_CARD = 200_000

// ==================
// MARK: The queue
// ==================

/**
 * The top-bar button, badged with how many downloads are outstanding.
 *
 * Hidden entirely when nothing has ever been queued: a control that does nothing is worse than no
 * control, and this screen has two buttons already.
 */
@Composable
fun DownloadsButton(jobs: List<DownloadJob>, onClick: () -> Unit) {
	if (jobs.isEmpty()) return
	val vActive = jobs.count { it.isActive }

	BadgedBox(
		badge = { if (vActive > 0) Badge { Text(vActive.toString()) } },
	) {
		IconButton(onClick = onClick) {
			Icon(
				imageVector = if (vActive > 0) Icons.Outlined.Download else Icons.Outlined.DownloadDone,
				contentDescription = if (vActive > 0) "$vActive downloads in progress" else "Downloads",
			)
		}
	}
}

/** The queue, as a dialog: what is running, what is waiting, and what went wrong. */
@Composable
fun DownloadsDialog(
	jobs: List<DownloadJob>,
	onCancel: (String) -> Unit,
	onCancelAll: () -> Unit,
	onClearFinished: () -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("Downloads") },
		text = {
			if (jobs.isEmpty()) {
				Text("Nothing queued.")
			} else {
				LazyColumn(
					// Bounded, or a long queue pushes the buttons off a phone screen.
					modifier = Modifier.heightIn(max = 320.dp),
					verticalArrangement = Arrangement.spacedBy(10.dp),
				) {
					items(jobs, key = { it.id }) { vJob -> DownloadRow(vJob, onCancel) }
				}
			}
		},
		confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
		dismissButton = {
			Row {
				if (jobs.any { !it.isActive }) {
					TextButton(onClick = onClearFinished) { Text("Clear finished") }
				}
				if (jobs.any { it.isActive }) {
					TextButton(onClick = onCancelAll) { Text("Stop all") }
				}
			}
		},
	)
}

/** One queue row: what it is, how far it has got, and a way to stop it. */
@Composable
private fun DownloadRow(job: DownloadJob, onCancel: (String) -> Unit) {
	Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
		Column(Modifier.weight(1f)) {
			Text(
				text = job.request.setName,
				style = MaterialTheme.typography.bodyLarge,
				fontWeight = FontWeight.Medium,
			)
			Text(
				text = describe(job),
				style = MaterialTheme.typography.bodySmall,
				color = if (job.status is DownloadStatus.Failed) {
					MaterialTheme.colorScheme.error
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
			)
			val vProgress = job.progress
			if (job.status is DownloadStatus.Running) {
				Spacer(Modifier.height(6.dp))
				if (vProgress == null) {
					// The image count is not known until the card list lands. An indeterminate bar
					// is the truthful shape; a determinate one at 0% would imply a known total.
					LinearProgressIndicator(Modifier.fillMaxWidth())
				} else {
					LinearProgressIndicator(progress = { vProgress }, modifier = Modifier.fillMaxWidth())
				}
			}
		}
		if (job.isActive) {
			Spacer(Modifier.width(8.dp))
			IconButton(onClick = { onCancel(job.id) }) {
				Icon(Icons.Outlined.Close, contentDescription = "Stop downloading ${job.request.setName}")
			}
		}
	}
}

/** One line saying exactly where a job stands. */
private fun describe(job: DownloadJob): String {
	val vWhat = job.request.kinds.sortedBy { it.name }.joinToString(" + ") {
		when (it) {
			DownloadKind.CARD_INFO -> "info"
			DownloadKind.CARD_IMAGES -> "images"
		}
	}
	return when (val vStatus = job.status) {
		is DownloadStatus.Queued -> "Waiting · $vWhat"
		is DownloadStatus.Running ->
			if (vStatus.total <= 0) {
				"Fetching card list · $vWhat"
			} else {
				"${vStatus.completed} of ${vStatus.total} images · $vWhat"
			}
		is DownloadStatus.Completed -> buildString {
			append("${vStatus.cards} cards")
			if (vStatus.imagesFetched > 0) append(", ${vStatus.imagesFetched} images")
			// Never rounded up to "done". A set that is four images short is not complete, and the
			// user should find that out here rather than offline.
			if (vStatus.imagesFailed > 0) append(" · ${vStatus.imagesFailed} failed")
		}
		is DownloadStatus.Failed -> vStatus.reason
		DownloadStatus.Cancelled -> "Stopped"
	}
}

// ==================
// MARK: Previews
// ==================

private fun previewJob(
	id: String,
	name: String,
	status: DownloadStatus,
	kinds: Set<DownloadKind> = setOf(DownloadKind.CARD_INFO, DownloadKind.CARD_IMAGES),
) = DownloadJob(
	id = id,
	request = DownloadRequest(
		setId = SourceId(ProviderId("riftcodex"), id),
		game = GameId("riftbound"),
		setName = name,
		kinds = kinds,
	),
	status = status,
)

@Preview
@Composable
private fun DownloadsDialogPreview() = PreviewFrame {
	DownloadsDialog(
		jobs = listOf(
			previewJob("OGN", "Origins", DownloadStatus.Running(completed = 214, total = 704)),
			previewJob("VEN", "Vendetta", DownloadStatus.Queued),
			// The state the row exists to be honest about.
			previewJob("SFD", "Spiritforged", DownloadStatus.Completed(288, 570, 6)),
			previewJob("UNL", "Unleashed", DownloadStatus.Failed("No network connection")),
		),
		onCancel = {},
		onCancelAll = {},
		onClearFinished = {},
		onDismiss = {},
	)
}

@Preview
@Composable
private fun DownloadsDialogStartingPreview() = PreviewFrame(isDark = false) {
	// Total unknown: an indeterminate bar rather than a determinate one stuck at zero.
	DownloadsDialog(
		jobs = listOf(previewJob("OGN", "Origins", DownloadStatus.Running(completed = 0, total = 0))),
		onCancel = {},
		onCancelAll = {},
		onClearFinished = {},
		onDismiss = {},
	)
}

@Preview
@Composable
private fun DownloadKindDialogPreview() = PreviewFrame {
	DownloadKindDialog(setName = "Origins", cardCount = 352, onDismiss = {}, onConfirm = {})
}

@Preview
@Composable
private fun DownloadKindDialogUnknownSizePreview() = PreviewFrame {
	// No card count, so no size estimate is offered rather than a made-up one.
	DownloadKindDialog(setName = "Promos", cardCount = null, onDismiss = {}, onConfirm = {})
}
