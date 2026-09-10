package com.bitsycore.cardbrowser.ui.downloads

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import com.bitsycore.cardbrowser.core.model.CardLanguage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
 * A dialog width that does not depend on what is inside it.
 *
 * Left to itself an `AlertDialog` is measured from its content, so the window it lives in is sized
 * after the content has composed -- and on the first frames that content is still settling, most
 * visibly where a `LazyColumn` is measuring its items. The dialog is laid out small, positioned,
 * then re-measured larger, which is seen as it growing out of its top-left corner.
 *
 * Stating the width removes the dependency: the dialog is the same size on the first frame as the
 * last, so there is nothing to animate. Capped rather than fixed, so it still looks right on a
 * phone and does not stretch across a desktop window.
 */
@Composable
private fun dialogWidth(): Modifier = Modifier
	.fillMaxWidth()
	.padding(horizontal = 24.dp)
	.widthIn(max = MAX_DIALOG_WIDTH)

/** Paired with [dialogWidth]: the platform's own width would override it. */
private val STABLE_DIALOG = DialogProperties(usePlatformDefaultWidth = false)

/** Wide enough for the longest of the three download descriptions, narrow enough to read. */
private val MAX_DIALOG_WIDTH = 420.dp

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
	onConfirm: (Set<DownloadKind>, Set<CardLanguage>) -> Unit,
	/**
	 * How many sets this covers. 1 for a single row; more for "download all".
	 *
	 * Above one the dialog stops pretending to be about a set and starts warning, because "download
	 * all" on Magic is 988 sets and tens of thousands of images.
	 */
	setCount: Int = 1,
	/**
	 * Kinds already on disk for this set.
	 *
	 * Offered as done rather than as a choice: re-downloading what you already have is almost never
	 * what the tap meant. A "Download again" button unlocks them, because a re-download is
	 * occasionally exactly what is wanted -- the image cache is an LRU and can be evicted from
	 * underneath a record that still says the images came down.
	 *
	 * Only *complete* kinds belong here. A part-finished download is still worth offering.
	 */
	alreadyHave: Set<DownloadKind> = emptySet(),
	/**
	 * The languages this set is actually published in, as its provider stated them.
	 *
	 * Empty for a source that says nothing about languages, which is most of them -- and then no
	 * choice is offered, because there is nothing honest to offer. Not the languages the *provider*
	 * can serve in general: that would put Korean on a set that was never printed in it.
	 */
	languages: List<CardLanguage> = emptyList(),
	/** Ticked when the dialog opens. The user's own preference, where the set has it. */
	defaultLanguage: CardLanguage? = null,
	/**
	 * Which languages already have their card records on disk.
	 *
	 * Named separately from [alreadyHave] because card info is not one purchase: it is fetched in
	 * every language a set states, so a set can be half held. Shown, so the answer to "what have I
	 * already got?" is a list of languages rather than a tick that means "some of them".
	 */
	infoLanguages: Set<CardLanguage> = emptySet(),
	/**
	 * The size of a one-file import of this game's card records, when its source offers one.
	 *
	 * Offered only on the whole-game dialog, because that is the only case it improves: the file
	 * is the entire catalogue, so using it to fetch a single set transfers far more than the
	 * request it would replace. `null` hides the option rather than showing a disabled one.
	 */
	bulkBytes: Long? = null,
	/**
	 * True while a whole-game import is running for this game.
	 *
	 * Card info is the one thing it collides with: the import is already writing exactly those
	 * records, so queueing them per set would fetch what is arriving anyway and race it to the
	 * same cache keys. Thumbnails are untouched -- an import carries no pictures -- and other
	 * games are untouched, since an import is scoped to one.
	 */
	isImportingGame: Boolean = false,
) {
	// Ticking is a fresh decision each time the dialog opens, so it is keyed on what is already
	// held: reopening after a download must not restore a tick for something now on disk.
	var vRedownload by remember(alreadyHave) { mutableStateOf(false) }
	val vInfoComplete = infoIsComplete(alreadyHave, languages, infoLanguages)
	val vLocked: (DownloadKind) -> Boolean = { vKind ->
		when {
			// Before the re-download escape hatch, because this one is not about what is held --
			// it is about what is in flight, and "Download again" must not start a second writer
			// against the records an import is in the middle of laying down.
			vKind == DownloadKind.CARD_INFO && isImportingGame -> true
			vRedownload -> false
			// Held in *every* language, not merely in one. See [infoIsComplete].
			vKind == DownloadKind.CARD_INFO -> vInfoComplete
			else -> vKind in alreadyHave
		}
	}

	val vInfoMissing = languages.filterNot { it in infoLanguages }
	var vInfo by remember(alreadyHave, infoLanguages) { mutableStateOf(!vInfoComplete) }
	// The only imagery on offer. Full-size art was here and was deliberately removed: bulk-fetching
	// every card's full rendition is roughly four times the bytes for pictures almost none of which
	// are looked at, against a CDN this project does not own. It arrives on demand instead, when a
	// card is opened. See `DownloadKind`. Not pre-ticked.
	var vThumbnails by remember(alreadyHave) { mutableStateOf(false) }

	// Which languages the thumbnails are wanted in. Card info is not part of this: text records
	// are small and a card is not much use in a language you cannot read *and* cannot switch to,
	// so info is fetched in every language the set states. Images are a request per card per
	// language against someone else's CDN, so those are chosen.
	val vChoosable = languages.size > 1
	var vLanguages by remember(languages, defaultLanguage) {
		mutableStateOf(
			setOfNotNull(defaultLanguage?.takeIf { it in languages } ?: languages.firstOrNull()),
		)
	}

	AlertDialog(
		onDismissRequest = onDismiss,
		modifier = dialogWidth(),
		properties = STABLE_DIALOG,
		title = { Text(if (setCount > 1) "Download $setCount sets" else "Download $setName") },
		text = {
			// Scrollable, because this content is not a fixed height and the dialog is not
			// resizable. With eleven languages -- Pokémon and Magic both reach that -- the chips
			// alone are five rows, and on a short viewport (landscape, a small handset, or a large
			// system font) the whole "Image languages" block was clipped away while the Download
			// button stayed enabled: images would be fetched in the default language with no way
			// to reach the control that changes it.
			Column(Modifier.verticalScroll(rememberScrollState())) {
				KindRow(
					checked = vInfo && !vLocked(DownloadKind.CARD_INFO),
					onCheckedChange = { vInfo = it },
					enabled = !vLocked(DownloadKind.CARD_INFO),
					// The mark means "nothing left to fetch", so it follows completeness rather
					// than presence -- otherwise a set held in one language of six looks finished.
					done = vInfoComplete,
					title = "Card info",
					// Said rather than left as an unexplained grey row: a disabled control with no
					// reason is indistinguishable from a broken one.
					note = "Already downloading for the whole game".takeIf { isImportingGame },
					// Deliberately not "small": the honest thing is to say what it is, since a set
					// with an unknown card count cannot be sized at all.
					detail = buildString {
						append(
							when {
								// A dump is one transfer, so it carries none of the per-set
								// pacing cost the row below has to warn about. Two rows, two
								// different costs, each said where it applies.
								bulkBytes != null && setCount > 1 ->
									"One file, about ${bulkBytes / 1_000_000} MB, in every " +
										"language it carries."
								setCount > 1 && cardCount != null ->
									"About $cardCount cards across $setCount sets, one set at a " +
										"time."
								cardCount != null -> "$cardCount cards."
								else -> "Names, numbers, rarities and rules text."
							},
						)
						// What is already here, by name. "We don't see what language is
						// downloaded" was the report; a tick cannot answer it and a list can.
						if (infoLanguages.isNotEmpty()) {
							append("\nAlready have: ")
							append(
								CardLanguage.PREFERENCE_ORDER
									.filter { it in infoLanguages }
									.joinToString(", ") { it.displayName },
							)
							append(".")
							if (vInfoMissing.isNotEmpty()) {
								append(" Missing ")
								append(vInfoMissing.joinToString(", ") { it.displayName })
								append(".")
							}
						}
					},
				)
				Spacer(Modifier.height(8.dp))
				KindRow(
					checked = vThumbnails && !vLocked(DownloadKind.GRID_THUMBNAILS),
					onCheckedChange = { vThumbnails = it },
					enabled = !vLocked(DownloadKind.GRID_THUMBNAILS),
					done = DownloadKind.GRID_THUMBNAILS in alreadyHave,
					title = "Thumbnails",
					detail = buildString {
						append(
							cardCount
								?.let { "About ${megabytes(it, THUMBNAIL_BYTES)} MB. Enough to browse offline." }
								?: "The small rendition the grid draws.",
						)
						// The pacing warning lives here rather than under the whole dialog,
						// because it is only true of this row: images are a request per card and
						// the queue runs one set at a time, while card info above may arrive as a
						// single file that costs none of that.
						if (setCount > 1) {
							append(" $setCount sets, one at a time -- this takes a while.")
						}
					},
				)
				if (vChoosable) {
					Spacer(Modifier.height(14.dp))
					HorizontalDivider()
					Spacer(Modifier.height(10.dp))
					Text(
						text = "Image languages",
						style = MaterialTheme.typography.labelLarge,
					)
					Text(
						// The asymmetry, in one line. Card info is cheap and switching language on
						// a card you already have is the point of downloading it; pictures are a
						// request per card per language.
						text = "Info comes in all ${languages.size}. Pick the thumbnail languages.",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Spacer(Modifier.height(8.dp))
					FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						for (vLanguage in languages) {
							FilterChip(
								selected = vLanguage in vLanguages,
								onClick = {
									vLanguages = if (vLanguage in vLanguages) {
										vLanguages - vLanguage
									} else {
										vLanguages + vLanguage
									}
								},
								// Only meaningful for the kinds that fetch pictures. Greyed rather
								// than hidden, so the choice does not appear and vanish as the
								// tick boxes above are used.
								enabled = vThumbnails,
								label = { Text(vLanguage.displayName) },
							)
						}
					}
					if (vThumbnails && vLanguages.isEmpty()) {
						Spacer(Modifier.height(6.dp))
						Text(
							text = "Choose at least one language for the thumbnails.",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.error,
						)
					}
				}
			}
		},
		confirmButton = {
			TextButton(
				// Nothing ticked is not a download, so the button is not offered as one -- and
				// neither is imagery in no language at all.
				enabled = (vInfo || vThumbnails) &&
					(!vThumbnails || !vChoosable || vLanguages.isNotEmpty()),
				onClick = {
					onConfirm(
						buildSet {
							if (vInfo) add(DownloadKind.CARD_INFO)
							if (vThumbnails) add(DownloadKind.GRID_THUMBNAILS)
						},
						vLanguages,
					)
				},
			) { Text("Download") }
		},
		dismissButton = {
			Row {
				// Only where it can do something. The image cache is an LRU and can be evicted
				// from under a record that still says the images came down, so re-downloading is
				// a real need rather than a theoretical one.
				if (alreadyHave.isNotEmpty() && !vRedownload) {
					TextButton(onClick = { vRedownload = true }) { Text("Download again") }
				}
				TextButton(onClick = onDismiss) { Text("Cancel") }
			}
		},
	)
}

@Composable
private fun KindRow(
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
	title: String,
	detail: String,
	/** Why this row is disabled, when the reason is not "you already have it". */
	note: String? = null,
	enabled: Boolean = true,
	done: Boolean = false,
) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		if (done && !enabled) {
			// A tick rather than a ticked checkbox: this is a statement about what is already
			// there, not a control that happens to be on.
			Icon(
				imageVector = Icons.Outlined.CheckCircle,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
				modifier = Modifier.size(24.dp).padding(2.dp),
			)
			Spacer(Modifier.width(18.dp))
		} else {
			Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
			Spacer(Modifier.width(4.dp))
		}
		Column {
			Text(
				text = title,
				style = MaterialTheme.typography.bodyLarge,
				color = if (enabled) {
					MaterialTheme.colorScheme.onSurface
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
			)
			Text(
				// A stated reason wins over both. What is already held says so instead of quoting
				// a size again -- the cost of a thing you already have is not the useful fact
				// about it -- and a row disabled for any other reason has to say which.
				text = note ?: if (done && !enabled) "Already downloaded" else detail,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

/** A rough size, so the choice is informed rather than blind. Always labelled "about". */
private fun megabytes(cardCount: Int, bytesPerCard: Int): Int =
	((cardCount.toLong() * bytesPerCard) / 1_000_000).toInt().coerceAtLeast(1)

/**
 * The per-card thumbnail size, measured rather than guessed.
 *
 * Sampled on 2026-09-09 across three providers: TCGdex 19.5 KB thumbnail against 63 KB full,
 * Scryfall 47 against 67, YGOPRODeck 28 against 153. The means are about 32 KB and 94 KB, so a
 * thumbnail is roughly a quarter of the full image -- which is why the thumbnail is the only
 * rendition bulk-fetched at all. See `DownloadKind`.
 *
 * The spread is wide, so this is an order of magnitude and not a promise: Scryfall's two
 * renditions barely differ, and a provider with no small rendition at all fetches nothing.
 */
private const val THUMBNAIL_BYTES = 32_000

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

/**
 * Whether there is any card info left to fetch for this set.
 *
 * The rule that was wrong, extracted so it can be tested. A download splits into one job per
 * language and fetches card info in *every* language a set states, so "already have card info" is
 * not a yes/no about the set -- it is a question about several editions. Locking the checkbox on
 * presence meant one finished language read as done and the other five could be reached only
 * through "Download again".
 *
 * [languages] empty means the source states none, and then presence is all there is to go on.
 */
internal fun infoIsComplete(
	alreadyHave: Set<DownloadKind>,
	languages: List<CardLanguage>,
	infoLanguages: Set<CardLanguage>,
): Boolean = DownloadKind.CARD_INFO in alreadyHave &&
	(languages.isEmpty() || languages.all { it in infoLanguages })

/**
 * One line saying exactly where a job stands, and which edition it is.
 *
 * The language is not decoration here. A download splits into one job per language -- card info in
 * every language a set states, art in the ones picked -- so a single tap on one set can put six
 * jobs in this queue whose set name is identical. Without the language, four of them read
 * "Base Set / Waiting · info" and there was no way to tell which was German and which Italian, nor
 * which of them the failed one was.
 *
 * Omitted when the source states no language at all, which is most of them: naming one there would
 * be inventing it.
 */
internal fun describe(job: DownloadJob): String {
	val vWhat = job.request.kinds.sortedBy { it.ordinal }.joinToString(" + ") {
		when (it) {
			DownloadKind.CARD_INFO -> "info"
			DownloadKind.GRID_THUMBNAILS -> "thumbnails"
		}
	}
	val vWhere = job.request.language?.displayName
	val vSuffix = if (vWhere == null) vWhat else "$vWhat · $vWhere"
	return when (val vStatus = job.status) {
		is DownloadStatus.Queued -> "Waiting · $vSuffix"
		is DownloadStatus.Running ->
			if (vStatus.total <= 0) {
				"Fetching card list · $vSuffix"
			} else {
				"${vStatus.completed} of ${vStatus.total} images · $vSuffix"
			}
		is DownloadStatus.Completed -> buildString {
			// Zero is a real answer, not a failure, and says which of the two it is. A source can
			// list a set and hold no singles for it -- a marketplace catalogue filing a booster box
			// under a set name is the common case -- and calling that "0 cards" beside a tick reads
			// as a mistake somewhere.
			if (vStatus.cards == 0) {
				append("No single cards in this set")
				return@buildString
			}
			append("${vStatus.cards} cards")
			if (vStatus.imagesFetched > 0) append(", ${vStatus.imagesFetched} images")
			// Never rounded up to "done". A set that is four images short is not complete, and the
			// user should find that out here rather than offline.
			if (vStatus.imagesFailed > 0) append(" · ${vStatus.imagesFailed} failed")
			if (vWhere != null) append(" · $vWhere")
		}
		// A failure needs it most: this is where the user finds out that the Italian art did not
		// come down while the French did.
		is DownloadStatus.Failed -> if (vWhere == null) vStatus.reason else "${vStatus.reason} · $vWhere"
		DownloadStatus.Cancelled -> if (vWhere == null) "Stopped" else "Stopped · $vWhere"
	}
}

// ==================
// MARK: Previews
// ==================

private fun previewJob(
	id: String,
	name: String,
	status: DownloadStatus,
	kinds: Set<DownloadKind> = setOf(DownloadKind.CARD_INFO, DownloadKind.GRID_THUMBNAILS),
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
private fun DownloadKindDialogPreview() = PreviewFrame {
	DownloadKindDialog(setName = "Origins", cardCount = 352, onDismiss = {}, onConfirm = { _, _ -> })
}

@Preview
@Composable
private fun DownloadKindDialogUnknownSizePreview() = PreviewFrame {
	// No card count, so no size estimate is offered rather than a made-up one.
	DownloadKindDialog(setName = "Promos", cardCount = null, onDismiss = {}, onConfirm = { _, _ -> })
}

@Preview
@Composable
private fun DownloadAllDialogPreview() = PreviewFrame {
	// The bulk case, which warns rather than reassures.
	DownloadKindDialog(
		setName = "",
		cardCount = 21_450,
		setCount = 88,
		onDismiss = {},
		onConfirm = { _, _ -> },
	)
}

@Preview
@Composable
private fun DownloadKindDialogPartlyHeldPreview() = PreviewFrame {
	// The state the `alreadyHave` parameter exists for: records are on disk and are shown as done
	// rather than offered again, leaving thumbnails as the one thing still worth asking for.
	DownloadKindDialog(
		setName = "Origins",
		cardCount = 352,
		alreadyHave = setOf(DownloadKind.CARD_INFO),
		onDismiss = {},
		onConfirm = { _, _ -> },
	)
}

@Preview
@Composable
private fun DownloadKindDialogPartlyTranslatedPreview() = PreviewFrame(isDark = false) {
	// Card info half held: French came down and the other two did not. The row has to say so and
	// stay tickable, because it used to read as finished and lock the rest away.
	DownloadKindDialog(
		setName = "Base Set",
		cardCount = 102,
		alreadyHave = setOf(DownloadKind.CARD_INFO),
		languages = listOf(CardLanguage.ENGLISH, CardLanguage.FRENCH, CardLanguage.GERMAN),
		defaultLanguage = CardLanguage.FRENCH,
		infoLanguages = setOf(CardLanguage.FRENCH),
		onDismiss = {},
		onConfirm = { _, _ -> },
	)
}
