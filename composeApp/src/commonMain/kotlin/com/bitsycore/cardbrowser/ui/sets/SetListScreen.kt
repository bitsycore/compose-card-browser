package com.bitsycore.cardbrowser.ui.sets

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bitsycore.cardbrowser.data.download.DownloadJob
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.cardbrowser.data.download.DownloadKind
import com.bitsycore.cardbrowser.data.download.DownloadManager
import com.bitsycore.cardbrowser.data.download.DownloadRequest
import com.bitsycore.cardbrowser.ui.downloads.DownloadKindDialog
import com.bitsycore.cardbrowser.ui.downloads.DownloadsButton
import com.bitsycore.cardbrowser.ui.downloads.DownloadsDialog
import org.koin.compose.koinInject
import androidx.compose.foundation.Image
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import com.bitsycore.cardbrowser.games.api.GameArt
import com.bitsycore.cardbrowser.ui.games.GameArtRegistry
import com.bitsycore.cardbrowser.ui.games.logoTintFor
import org.jetbrains.compose.resources.painterResource
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Description
import com.bitsycore.cardbrowser.data.settings.ImageDownloadRecord
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameRegion
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.data.repository.DataOrigin
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import com.bitsycore.cardbrowser.ui.common.EmptyState
import com.bitsycore.cardbrowser.ui.common.ErrorState
import com.bitsycore.cardbrowser.ui.common.LoadingState
import com.bitsycore.cardbrowser.ui.common.NoticeBanner
import com.bitsycore.cardbrowser.ui.preview.PreviewData
import com.bitsycore.cardbrowser.ui.preview.PreviewFrame
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The Riftbound set list: the app's first screen.
 *
 * Newest first, searchable by name or code, and honest about whether what is on screen came off the
 * network or off the disk.
 */
@Composable
fun SetListScreen(
	game: GameId,
	onBack: () -> Unit,
	onOpenSet: (CardSet) -> Unit,
	onOpenSettings: () -> Unit,
	onOpenSearch: (GameProfile) -> Unit,
	viewModel: SetListViewModel = koinViewModel { parametersOf(SetListArgs(game)) },
) {
	val vState by viewModel.collectAsStateWithLifecycle()

	// The queue is application-scoped rather than this screen's, so it is read here and handed down
	// as plain state -- `SetListContent` stays free of Koin and therefore previewable.
	val vDownloads = koinInject<DownloadManager>()
	val vJobs by vDownloads.jobs.collectAsState()
	val vPreferences = koinInject<PreferencesStore>()
	// Resolved here rather than in `SetListContent`, so the content stays free of Koin and keeps
	// previewing. `null` for a game whose module ships no logo, which the title falls back for.
	val vArtRegistry = koinInject<GameArtRegistry>()
	val vArt = vState.game?.let(vArtRegistry::forGame)

	SetListContent(
		state = vState,
		dispatch = viewModel::dispatch,
		onBack = onBack,
		onOpenSet = onOpenSet,
		onOpenSettings = onOpenSettings,
		onOpenSearch = onOpenSearch,
		downloads = vJobs,
		onDownload = { vSet, vKinds ->
			vDownloads.enqueue(
				DownloadRequest(
					setId = vSet.id,
					game = vSet.game,
					setName = vSet.name,
					kinds = vKinds,
					// The user's preferred language, which is what every other caller passes --
					// not `null`.
					//
					// A cache key embeds the language, so a download written under `null` and a
					// grid or a search reading under `fr` are different files: the set would come
					// down, and then not be found by the search that was the reason for
					// downloading it. `CardRepository` normalises this once against what the
					// provider can really answer in, so passing the preference is correct even for
					// a source that cannot serve it.
					//
					// This is the same mistake that once stopped any set ever showing as saved;
					// see the note in `CardGridViewModel.startLoad`.
					language = vPreferences.preferences.value.primaryLanguage,
				),
			)
		},
		onCancelDownload = vDownloads::cancel,
		onCancelAllDownloads = vDownloads::cancelAll,
		onClearFinishedDownloads = vDownloads::clearFinished,
		gameArt = vArt,
	)
}

/**
 * The set list, given a state and somewhere to send intents.
 *
 * No view model, no Koin, no coroutines: everything it needs arrives as arguments, which is what
 * makes it previewable and what keeps the screen's layout separable from how its data is obtained.
 * Navigation stays as callbacks rather than intents -- where the app goes next is the caller's
 * business, not this screen's state machine's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetListContent(
	state: SetListContract.UiState,
	dispatch: (SetListContract.Intent) -> Unit,
	onBack: () -> Unit = {},
	onOpenSet: (CardSet) -> Unit,
	onOpenSettings: () -> Unit,
	onOpenSearch: (GameProfile) -> Unit = {},
	downloads: List<DownloadJob> = emptyList(),
	onDownload: (CardSet, Set<DownloadKind>) -> Unit = { _, _ -> },
	onCancelDownload: (String) -> Unit = {},
	onCancelAllDownloads: () -> Unit = {},
	onClearFinishedDownloads: () -> Unit = {},
	gameArt: GameArt? = null,
) {
	val vState = state

	// Which set's download dialog is open, and whether the queue is showing. Local because neither
	// is worth a trip through the state machine: nothing outside this screen cares.
	var vPendingSet by remember { mutableStateOf<CardSet?>(null) }
	var vShowQueue by remember { mutableStateOf(false) }
	var vPendingAll by remember { mutableStateOf(false) }

	Scaffold(
		topBar = {
			TopAppBar(
				navigationIcon = {
					// The game picker is a real screen above this one now, so this is a genuine
					// back rather than a decoration.
					IconButton(onClick = onBack) {
						Icon(
							Icons.AutoMirrored.Outlined.ArrowBack,
							contentDescription = "Back to games",
						)
					}
				},
				title = {
					// The game's own logo rather than its name in text. The chip row that used to
					// sit under this bar said which game you were in; with the picker above, that
					// row was a second way to do one thing, and the logo says it in the space the
					// title already occupies.
					val vLogo = gameArt?.logo
					if (vLogo == null) {
						// A game whose module ships no logo, and the state before one loads.
						Text(vState.game?.shortName.orEmpty())
					} else {
						val vLogoImage = @Composable {
							Image(
								painter = painterResource(vLogo),
								// The title *is* the game name, so this carries it for a screen
								// reader rather than being decorative.
								contentDescription = vState.game?.displayName,
								contentScale = ContentScale.Fit,
								// Bounded both ways. Height is what normally binds, but these are
								// wordmarks of wildly different aspect -- One Piece is 149 dp wide
								// at 30 dp tall against Pokémon's 59 -- and without a width cap the
								// widest of them crowds the three action buttons on a narrow phone.
								// `Fit` then scales by whichever limit binds first.
								modifier = Modifier.heightIn(max = 30.dp).widthIn(max = 132.dp),
								// Exactly the picker's rule, from the same function.
								colorFilter = logoTintFor(gameArt),
							)
						}

						// Artwork that declares a plate gets it here too, not only in the picker.
						// Without it such a mark is drawn straight onto the app bar, which is the
						// one background it was never designed for: Altered's near-white wordmark,
						// Riftbound's white subtitle and Lorcana's gold all vanish on the light
						// theme. The picker had a tile and this did not, so those three were
						// correct on one screen and invisible on the other.
						//
						// A mark that can be *painted* never gets here, and should not: a plate
						// behind a recolourable wordmark is a saturated block in the app bar for no
						// reason. Cyberpunk is the case that proved it -- it wore a yellow pill
						// until it was pointed out that the mark alone, painted its own yellow, is
						// what belongs on a dark bar.
						val vBackdrop = gameArt.backdropArgb
						if (vBackdrop == null) {
							vLogoImage()
						} else {
							Box(
								modifier = Modifier
									.clip(RoundedCornerShape(8.dp))
									.background(Color(vBackdrop.toInt()))
									.padding(horizontal = 8.dp, vertical = 4.dp),
								contentAlignment = Alignment.Center,
							) {
								vLogoImage()
							}
						}
					}
				},
				actions = {
					// Whatever the list is currently showing, which is the useful scope: with a
					// region chip or a search active, "all" means all of *those*, not all 988.
					if (vState.visibleSets.isNotEmpty()) {
						IconButton(onClick = { vPendingAll = true }) {
							Icon(
								Icons.Outlined.CloudDownload,
								contentDescription = "Download all ${vState.visibleSets.size} sets shown",
							)
						}
					}
					DownloadsButton(jobs = downloads, onClick = { vShowQueue = true })
					IconButton(onClick = { vState.game?.let(onOpenSearch) }) {
						Icon(
							Icons.Outlined.TravelExplore,
							contentDescription = "Search cards across all sets",
						)
					}
					IconButton(onClick = onOpenSettings) {
						Icon(Icons.Outlined.Settings, contentDescription = "Settings")
					}
				},
			)
		},
	) { vPadding ->
		Column(Modifier.padding(vPadding).fillMaxSize()) {

			// Only for a game that really ships more than one line. See `GameProfile.regions`.
			if (vState.regionOptions.isNotEmpty()) {
				RegionFilter(
					regions = vState.regionOptions,
					selected = vState.region,
					onSelect = { dispatch(SetListContract.Intent.RegionSelected(it)) },
				)
			}

			OutlinedTextField(
				value = vState.search,
				onValueChange = { dispatch(SetListContract.Intent.SearchChanged(it)) },
				label = { Text("Search sets") },
				leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
				singleLine = true,
				modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
			)

			// The honesty strip. Shown whenever what is on screen is not a fresh network result.
			when {
				vState.error != null && vState.sets.isNotEmpty() -> NoticeBanner(
					text = "Showing saved sets. Refresh failed.",
					onAction = { dispatch(SetListContract.Intent.Refresh) },
				)
				vState.origin == DataOrigin.CACHE && vState.isStale -> NoticeBanner(
					text = "Saved copy, refreshing…",
					onAction = null,
				)
			}

			Box(Modifier.weight(1f)) {
				when {
					vState.isInitialLoad -> LoadingState()

					vState.sets.isEmpty() && vState.error != null -> ErrorState(
						error = vState.error!!,
						onRetry = { dispatch(SetListContract.Intent.Refresh) },
					)

					vState.isEmptySearch -> EmptyState(emptyMessage(vState))

					else -> LazyColumn(
						contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp),
					) {
						items(vState.visibleSets, key = { it.id.qualified }) { vSet ->
							SetRow(
								set = vSet,
								// Badged only while every line is on screen: with one line selected
								// the badge would repeat the chip on every single row.
								region = if (vState.region != null) {
									null
								} else {
									vState.game?.regionFor(vSet.region)
								},
								isLastOpened = vSet.id.qualified == vState.lastOpenedSetId,
								isSaved = vSet.id.qualified in vState.savedSetIds,
								onClick = {
									dispatch(SetListContract.Intent.SetOpened(vSet.id.qualified))
									onOpenSet(vSet)
								},
								downloadStatus = downloads.firstOrNull { it.request.setId == vSet.id },
								images = vState.imageDownloads[vSet.id.qualified],
								onDownload = { vPendingSet = vSet },
							)
						}
					}
				}
			}
		}
	}

	vPendingSet?.let { vSet ->
		DownloadKindDialog(
			setName = vSet.name,
			cardCount = vSet.cardCount,
			alreadyHave = alreadyDownloaded(
				isSaved = vSet.id.qualified in vState.savedSetIds,
				images = vState.imageDownloads[vSet.id.qualified],
			),
			onDismiss = { vPendingSet = null },
			onConfirm = { vKinds ->
				onDownload(vSet, vKinds)
				vPendingSet = null
				// Straight to the queue, so the download is visibly a thing that now exists rather
				// than a dialog that closed and apparently did nothing.
				vShowQueue = true
			},
		)
	}

	if (vPendingAll) {
		val vSets = vState.visibleSets
		DownloadKindDialog(
			setName = "",
			setCount = vSets.size,
			// Summed only when every set states one. A partial sum would understate the job by
			// however many sets stayed silent, which is worse than offering no number.
			cardCount = vSets.mapNotNull { it.cardCount }
				.takeIf { it.size == vSets.size }
				?.sum(),
			// Only what *every* shown set already has. Offering a kind as done when half the
			// list is missing it would stop the user downloading the half that needs it.
			alreadyHave = vSets
				.map { vSet ->
					alreadyDownloaded(
						isSaved = vSet.id.qualified in vState.savedSetIds,
						images = vState.imageDownloads[vSet.id.qualified],
					)
				}
				.reduceOrNull { vAcc, vNext -> vAcc intersect vNext }
				.orEmpty(),
			onDismiss = { vPendingAll = false },
			onConfirm = { vKinds ->
				vSets.forEach { vSet -> onDownload(vSet, vKinds) }
				vPendingAll = false
				vShowQueue = true
			},
		)
	}

	if (vShowQueue) {
		DownloadsDialog(
			jobs = downloads,
			onCancel = onCancelDownload,
			onCancelAll = onCancelAllDownloads,
			onClearFinished = onClearFinishedDownloads,
			onDismiss = { vShowQueue = false },
		)
	}
}


/**
 * A game's product lines, as chips, with "All" first.
 *
 * Pokemon's Japanese and international catalogues are different products on different schedules,
 * not translations of each other -- 486 sets between them sharing 4 ids -- so they belong in one
 * list that can be narrowed rather than in one list that silently shows whichever the language
 * preference happened to select.
 */
@Composable
private fun RegionFilter(
	regions: List<GameRegion>,
	selected: String?,
	onSelect: (String?) -> Unit,
) {
	val vScroll = rememberScrollState()
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.horizontalScroll(vScroll)
			.padding(horizontal = 16.dp, vertical = 4.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		FilterChip(
			selected = selected == null,
			onClick = { onSelect(null) },
			label = { Text("All") },
		)
		regions.forEach { vRegion ->
			FilterChip(
				selected = vRegion.key == selected,
				onClick = { onSelect(vRegion.key) },
				label = { Text(vRegion.label) },
			)
		}
	}
}

/**
 * Why the list is empty, which depends on which filter emptied it.
 *
 * Blaming the search text for a region chip's doing would send the user to fix the wrong control.
 */
private fun emptyMessage(state: SetListContract.UiState): String {
	val vRegion = state.game?.regionFor(state.region)?.label
	val vSearch = state.search.trim()
	return when {
		vSearch.isNotEmpty() && vRegion != null -> "No $vRegion set matches \"$vSearch\"."
		vSearch.isNotEmpty() -> "No set matches \"$vSearch\"."
		vRegion != null -> "No $vRegion sets."
		else -> "No sets."
	}
}

/** One set: name, code, card count and release date, plus a mark for where you left off. */
@Composable
private fun SetRow(
	set: CardSet,
	region: GameRegion?,
	isLastOpened: Boolean,
	isSaved: Boolean,
	onClick: () -> Unit,
	downloadStatus: DownloadJob? = null,
	images: SetImageStatus? = null,
	onDownload: () -> Unit = {},
) {
	Card(
		onClick = onClick,
		modifier = Modifier.fillMaxWidth(),
		colors = if (isLastOpened) {
			CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
		} else {
			CardDefaults.cardColors()
		},
	) {
		Row(
			modifier = Modifier.padding(16.dp).fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			SetMark(set, isHighlighted = isLastOpened)
			Spacer(Modifier.size(12.dp))
			Column(Modifier.weight(1f)) {
				Text(
					text = set.name,
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.Medium,
				)
				Spacer(Modifier.height(2.dp))
				// The badge sits on the subtitle line rather than at the end of the row. As a
				// trailing sibling of a weighted column it took its width from the set name, and
				// on a row that also says "Last opened" that left the name about one character
				// wide. Here it competes with nothing: it is provenance, like the code and the
				// date it sits next to.
				Row(verticalAlignment = Alignment.CenterVertically) {
					if (region != null) {
						RegionBadge(region)
						Spacer(Modifier.size(6.dp))
					}
					Text(
						text = setSubtitle(set, isLastOpened),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
			// A running download replaces the button with its own progress, so the row shows one
			// state rather than a button next to a spinner describing the same thing.
			when {
				downloadStatus?.isActive == true -> {
					Spacer(Modifier.size(8.dp))
					val vProgress = downloadStatus.progress
					Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
						if (vProgress == null) {
							CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
						} else {
							CircularProgressIndicator(
								progress = { vProgress },
								modifier = Modifier.size(20.dp),
								strokeWidth = 2.dp,
							)
						}
					}
				}

				else -> {
					Spacer(Modifier.size(4.dp))
					IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
						Icon(
							imageVector = Icons.Outlined.Download,
							contentDescription = "Download ${set.name}",
							tint = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.size(20.dp),
						)
					}
				}
			}
			// Two marks, because the two halves of a download are separately true: a set can have
			// its records and none of its art, which is the common case after browsing it once.
			// Three marks, because the three halves of a download are separately true: a set can
			// have its records and no art, or thumbnails and no full art, and those are genuinely
			// different states -- browsable offline versus readable offline.
			if (isSaved || images?.isEmpty == false) {
				Spacer(Modifier.size(6.dp))
				Row(verticalAlignment = Alignment.CenterVertically) {
					if (isSaved) {
						Icon(
							imageVector = Icons.Outlined.Description,
							// "Saved", not "complete". A set interrupted part-way through leaves a
							// file behind too, and the mark must not promise more than that.
							contentDescription = "Card info saved on this device",
							tint = MaterialTheme.colorScheme.primary,
							modifier = Modifier.size(18.dp),
						)
					}
					ImageMark(
						record = images?.thumbnails,
						icon = Icons.Outlined.GridView,
						label = "Grid thumbnails",
						leadingSpace = isSaved,
					)
					ImageMark(
						record = images?.art,
						icon = Icons.Outlined.Photo,
						label = "Full card art",
						leadingSpace = isSaved || images?.thumbnails != null,
					)
				}
			}
			// "Last opened" is on the metadata line rather than out here. As an unweighted
			// trailing sibling it was measured at its full intrinsic width before the weighted
			// column got any, so on a row with a long set name the name was squeezed to about one
			// character per line. The row's own tint is the primary signal anyway; this is the
			// label that explains it.
		}
	}
}

/**
 * Which product line a set belongs to, as a small outlined tag.
 *
 * The short form, because it sits beside a set name that needs the width: "INTL", "JP". Outlined
 * and in the muted colour rather than filled -- it is provenance, not a call to action, and it
 * appears on every row.
 */
@Composable
private fun RegionBadge(region: GameRegion) {
	Text(
		text = region.badge,
		style = MaterialTheme.typography.labelSmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier
			.border(
				width = 1.dp,
				color = MaterialTheme.colorScheme.outlineVariant,
				shape = RoundedCornerShape(4.dp),
			)
			.padding(horizontal = 6.dp, vertical = 2.dp),
	)
}

/**
 * Which download kinds a set already holds.
 *
 * Complete ones only. A part-finished art download is still worth offering, and a record of
 * 206 of 288 images is precisely the case where re-running it is the right thing to do.
 */
private fun alreadyDownloaded(isSaved: Boolean, images: SetImageStatus?): Set<DownloadKind> =
	buildSet {
		if (isSaved) add(DownloadKind.CARD_INFO)
		if (images?.thumbnails?.isComplete == true) add(DownloadKind.GRID_THUMBNAILS)
		if (images?.art?.isComplete == true) add(DownloadKind.FULL_ART)
	}

/**
 * One rendition's mark, with a percentage when the download did not finish.
 *
 * Draws nothing at all when no download was recorded. Absent is not the same as zero: art arrives
 * by browsing too and that is not tracked, so a missing mark means "never downloaded", never "not
 * present".
 */
@Composable
private fun ImageMark(
	record: ImageDownloadRecord?,
	icon: androidx.compose.ui.graphics.vector.ImageVector,
	label: String,
	leadingSpace: Boolean,
) {
	if (record == null) return
	if (leadingSpace) Spacer(Modifier.size(6.dp))
	Icon(
		imageVector = icon,
		// "Downloaded", not "available": the image cache is an LRU and the OS may purge it, so
		// this records what came down rather than promising what is still there.
		contentDescription = if (record.isComplete) {
			"$label downloaded"
		} else {
			"${record.percent}% of $label downloaded"
		},
		tint = if (record.isComplete) {
			MaterialTheme.colorScheme.primary
		} else {
			// A partial download is not a tick. The muted metadata colour, so it reads as a
			// qualification rather than a win.
			MaterialTheme.colorScheme.onSurfaceVariant
		},
		modifier = Modifier.size(18.dp),
	)
	// The number only when it says something the icon does not.
	if (!record.isComplete) {
		Spacer(Modifier.size(2.dp))
		Text(
			text = "${record.percent}%",
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

/**
 * A set's own symbol where its provider publishes one, and its code where none exists.
 *
 * Three of the seven sources supply real artwork -- Scryfall a symbol for all 988 of its paper
 * sets, TCGdex a logo for 157 of its 218, YGOPRODeck box art for many of its. The other four
 * publish nothing at all, and so do the sets those three skip, which is why the coloured monogram
 * below is a permanent fallback rather than a temporary one.
 */
@Composable
private fun SetMark(set: CardSet, isHighlighted: Boolean) {
	val vSymbol = set.symbol
	if (vSymbol == null) {
		SetMonogram(set.code, isHighlighted = isHighlighted)
		return
	}

	Box(modifier = Modifier.size(SET_MARK_WIDTH, SET_MARK_HEIGHT), contentAlignment = Alignment.Center) {
		SubcomposeAsyncImage(
			model = vSymbol.url,
			contentDescription = null,
			contentScale = ContentScale.Fit,
			modifier = Modifier.fillMaxSize().padding(2.dp),
			// A monochrome glyph has no colour of its own -- Scryfall's SVGs carry no `fill` and
			// default to black, invisible against the dark theme -- so it is drawn in the theme's
			// foreground. Full-colour artwork is never recoloured.
			colorFilter = if (vSymbol.isMonochrome) {
				ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
			} else {
				null
			},
			// The monogram, not a spinner and not a gap: a symbol that is slow or missing leaves a
			// row that still identifies its set.
			loading = { SetMonogram(set.code, isHighlighted = isHighlighted) },
			error = { SetMonogram(set.code, isHighlighted = isHighlighted) },
		)
	}
}

/**
 * A set's code in a tile, standing in for the set symbol the provider does not have.
 *
 * Riftcodex publishes no icon, logo or symbol for a set -- the only image anywhere in its schema is
 * a card's own art. Rather than leave the row as an undifferentiated wall of text, or invent a
 * symbol and pass it off as the game's, this shows the set's real short code, which is what players
 * call it anyway and what is printed on the cards.
 */
@Composable
private fun SetMonogram(code: String, isHighlighted: Boolean) {
	val vTint = setColour(code)
	Box(
		modifier = Modifier
			.size(SET_MARK_WIDTH, SET_MARK_HEIGHT)
			.clip(RoundedCornerShape(10.dp))
			.background(
				if (isHighlighted) {
					MaterialTheme.colorScheme.primary
				} else {
					// Tinted rather than saturated, so a screen of these reads as a list rather
					// than as a paint chart, and the code stays legible on top of it.
					vTint.copy(alpha = 0.22f)
				},
			),
		contentAlignment = Alignment.Center,
	) {
		Text(
			// Long enough for the codes that actually occur. One Piece's are five characters --
			// `OP-14`, `ST-21`, `EB-02` -- and a four-character cap truncated every one of them to
			// `OP-1`, which is a different set. Six covers those plus Pokémon's `sv08.5`.
			text = code.take(6),
			style = MaterialTheme.typography.labelLarge,
			fontWeight = FontWeight.Medium,
			maxLines = 1,
			softWrap = false,
			color = if (isHighlighted) MaterialTheme.colorScheme.onPrimary else vTint,
		)
	}
}

/**
 * A stable colour for a set, derived from its code.
 *
 * Placeholder work, and deliberately so: none of the seven providers publishes a set symbol -- the
 * only image in any of their schemas is a card's own art -- so until one does, the alternative is a
 * column of identical grey tiles that are genuinely hard to tell apart when scrolling a catalogue
 * of several hundred Magic sets.
 *
 * Derived rather than random. The same set is the same colour on every launch and on every device,
 * because a mark that changes each time you look at it is worse than no mark: it teaches you
 * nothing and it makes the list look unstable.
 *
 * Only the hue varies; saturation and lightness are fixed, so every colour this can produce is
 * legible against both themes and none is louder than the others.
 */
private fun setColour(code: String): Color {
	// FNV-1a: a few lines, no platform APIs, and it scatters short similar strings well -- "OGN"
	// and "OGS" must not land on neighbouring hues.
	var vHash = FNV_OFFSET_BASIS
	for (vChar in code) {
		vHash = vHash xor vChar.code.toLong()
		vHash = (vHash * FNV_PRIME) and 0xFFFFFFFFL
	}
	val vHue = (vHash % 360L).toFloat()
	return Color.hsl(hue = vHue, saturation = MONOGRAM_SATURATION, lightness = MONOGRAM_LIGHTNESS)
}

private const val FNV_OFFSET_BASIS = 2166136261L

private const val FNV_PRIME = 16777619L

/** Muted enough that no set shouts, strong enough to tell two of them apart. */
private const val MONOGRAM_SATURATION = 0.55f

/** Mid-lightness, so the same colour works as text on a light theme and on a dark one. */
private const val MONOGRAM_LIGHTNESS = 0.62f

/**
 * "OGN · 352 cards · Oct 2025", with each part dropped when the provider does not supply it.
 *
 * A missing release date shows nothing rather than "Unknown date": the row is not the place to
 * discuss what the provider does not know.
 */
private fun setSubtitle(set: CardSet, isLastOpened: Boolean = false): String = buildList {
	add(set.code)
	set.cardCount?.let { add("$it cards") }
	set.releaseDate?.let { add("${monthName(it.month.ordinal)} ${it.year}") }
	if (isLastOpened) add("last opened")
}.joinToString(" · ")

/** Zero-based, matching `Month.ordinal`, so January is 0. */
private fun monthName(monthOrdinal: Int): String =
	MONTH_NAMES.getOrElse(monthOrdinal) { "" }

private val MONTH_NAMES = listOf(
	"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

// ==================
// MARK: Previews
// ==================

@Preview
@Composable
private fun SetListLoadedPreview() = PreviewFrame {
	SetListContent(
		state = SetListContract.UiState(
			sets = PreviewData.SETS,
			isLoading = false,
			lastOpenedSetId = PreviewData.ORIGINS.id.qualified,
		),
		dispatch = {},
		onOpenSet = {},
		onOpenSettings = {},
	)
}

@Preview
@Composable
private fun SetListLoadingPreview() = PreviewFrame {
	SetListContent(
		state = SetListContract.UiState(isLoading = true),
		dispatch = {},
		onOpenSet = {},
		onOpenSettings = {},
	)
}

@Preview
@Composable
private fun SetListOfflinePreview() = PreviewFrame {
	// The state that matters most and is hardest to reach by hand: cached data on screen with a
	// failed refresh behind it.
	SetListContent(
		state = SetListContract.UiState(
			sets = PreviewData.SETS,
			isLoading = false,
			origin = DataOrigin.CACHE,
			isStale = true,
			error = ProviderError.Offline(),
		),
		dispatch = {},
		onOpenSet = {},
		onOpenSettings = {},
	)
}

@Preview
@Composable
private fun SetListEmptySearchPreview() = PreviewFrame(isDark = false) {
	SetListContent(
		state = SetListContract.UiState(
			sets = PreviewData.SETS,
			search = "nothing matches this",
			isLoading = false,
		),
		dispatch = {},
		onOpenSet = {},
		onOpenSettings = {},
	)
}


@Preview
@Composable
private fun SetListSavedPreview() = PreviewFrame {
	// Two sets already on disk, and the per-set colours that stand in for symbols nobody publishes.
	SetListContent(
		state = SetListContract.UiState(
			sets = PreviewData.SETS,
			isLoading = false,
			savedSetIds = setOf(
				PreviewData.ORIGINS.id.qualified,
				PreviewData.SETS.first().id.qualified,
			),
			lastOpenedSetId = PreviewData.ORIGINS.id.qualified,
		),
		dispatch = {},
		onOpenSet = {},
		onOpenSettings = {},
	)
}

/**
 * The set mark's box: wider than it is tall, and both larger than they were.
 *
 * A 44 dp square could not hold a five-character set code -- every One Piece code is five, so all
 * of them were truncated to something that named a different set -- and it squeezed the wordmark-
 * shaped set logos that TCGdex and Scryfall publish into a smear. Landscape suits both: a code sits
 * comfortably on one line, and a logo letterboxes instead of cropping.
 */
private val SET_MARK_WIDTH = 68.dp

private val SET_MARK_HEIGHT = 44.dp

@Preview
@Composable
private fun SetListDownloadMarksPreview() = PreviewFrame {
	// The four states the two marks exist to tell apart: nothing, records only, records plus a
	// complete image download, and records plus a partial one.
	SetListContent(
		state = SetListContract.UiState(
			sets = PreviewData.SETS,
			isLoading = false,
			savedSetIds = PreviewData.SETS.drop(1).map { it.id.qualified }.toSet(),
			imageDownloads = mapOf(
				// Thumbnails only: browsable offline, not readable offline.
				PreviewData.SETS[1].id.qualified to SetImageStatus(
					thumbnails = ImageDownloadRecord(fetched = 280, total = 280),
				),
				// Both, with the art download interrupted.
				PreviewData.SETS[2].id.qualified to SetImageStatus(
					thumbnails = ImageDownloadRecord(fetched = 288, total = 288),
					art = ImageDownloadRecord(fetched = 206, total = 288),
				),
			),
		),
		dispatch = {},
		onOpenSet = {},
		onOpenSettings = {},
	)
}
