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
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
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
import com.bitsycore.cardbrowser.ui.common.reorderHandle
import com.bitsycore.cardbrowser.ui.common.rememberReorder
import com.bitsycore.cardbrowser.ui.common.ReorderState
import com.bitsycore.cardbrowser.ui.common.ReorderHandle
import com.bitsycore.cardbrowser.ui.common.DRAGGED_ROW_ELEVATION
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularWavyProgressIndicator
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
import org.koin.compose.koinInject
import androidx.compose.foundation.Image
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import com.bitsycore.cardbrowser.games.api.GameArt
import com.bitsycore.cardbrowser.ui.games.GameArtRegistry
import com.bitsycore.cardbrowser.ui.games.logoBackdropFor
import com.bitsycore.cardbrowser.ui.games.logoTintFor
import org.jetbrains.compose.resources.painterResource
import com.bitsycore.cardbrowser.data.settings.ImageDownloadRecord
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
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.data.repository.BulkImportProgress
import com.bitsycore.cardbrowser.data.repository.DataOrigin
import com.bitsycore.cardbrowser.ui.common.EmptyState
import com.bitsycore.cardbrowser.ui.common.FastScroller
import com.bitsycore.cardbrowser.ui.common.ErrorState
import com.bitsycore.cardbrowser.ui.common.LoadingState
import com.bitsycore.cardbrowser.ui.common.NoticeBanner
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.ui.common.sharedSetContainer
import com.bitsycore.cardbrowser.ui.preview.PreviewData
import com.bitsycore.cardbrowser.ui.preview.PreviewFrame
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import com.bitsycore.lib.pulse.compose.collectEffect
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import com.bitsycore.cardbrowser.ui.common.AppIcons
import com.bitsycore.cardbrowser.ui.common.AppOverflowMenu

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
	onOpenStorage: () -> Unit,
	onOpenSearch: (GameProfile) -> Unit,
	onOpenDownloads: () -> Unit,
	viewModel: SetListViewModel = koinViewModel { parametersOf(SetListArgs(game)) },
) {
	// The only part of this screen that knows a back stack exists. Everything below dispatches.
	viewModel.collectEffect { vEffect ->
		when (vEffect) {
			is SetListContract.Effect.OpenSet -> onOpenSet(vEffect.set)
			is SetListContract.Effect.OpenSearch -> onOpenSearch(vEffect.game)
			SetListContract.Effect.NavigateBack -> onBack()
			SetListContract.Effect.OpenSettings -> onOpenSettings()
			SetListContract.Effect.OpenStorage -> onOpenStorage()
			SetListContract.Effect.OpenDownloads -> onOpenDownloads()
		}
	}
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

	// What the routed source will *actually* answer in, not what the user would prefer.
	//
	// The two differ more often than they look like they should. Riftcodex serves English only, so
	// a French-preferring user downloading Riftbound queued a job labelled "French" for records
	// that come back English -- the repository normalises the language before it builds a cache
	// key, so the file was right and only the screen was lying. `resolveLanguage` is the same
	// function the repository uses, asked one layer earlier so the queue can say the truth.
	val vRegistry = koinInject<ProviderRegistry>()
	val vProvider = vState.game?.let(vRegistry::resolve)
	val vPreferred = vPreferences.preferences.value.primaryLanguage
	val vDownloadLanguage = vState.game?.let { vRegistry.effectiveLanguage(it.id, vPreferred) } ?: vPreferred

	SetListContent(
		state = vState,
		dispatch = viewModel::dispatch,
		downloads = vJobs,
		onDownload = { vSet, vKinds, vLanguages ->
			// One job per language, because everything downstream is per language: a cache key
			// embeds it and so does an image download record. Splitting here is what makes "card
			// info in every language, thumbnails in the two you read" a thing the queue can
			// express.
			//
			// The two halves are treated differently on purpose. Card records are small and the
			// whole point of having them is being able to switch language on a card you already
			// hold, so those are fetched in every language the set states. Thumbnails are a
			// request per card per language, so they go only where they were asked for.
			// The source's answer for the user's preference, not the preference itself. See
			// `vDownloadLanguage` above: a set that states no languages used to be queued under
			// whatever the user preferred, which for an English-only source was a job labelled
			// with a language it would never return.
			val vPrimary = vDownloadLanguage
			val vStated = vSet.languages.toList().ifEmpty { listOf(vPrimary) }
			val vForArt = vLanguages.ifEmpty { setOf(vPrimary) }.filter { it in vStated || vSet.languages.isEmpty() }

			val vInfoKinds = vKinds.filterNot { it.isImagery }.toSet()
			val vArtKinds = vKinds.filter { it.isImagery }.toSet()

			for (vLanguage in vStated) {
				if (vInfoKinds.isEmpty()) break
				vDownloads.enqueue(
					DownloadRequest(
						setId = vSet.id,
						game = vSet.game,
						setName = vSet.name,
						kinds = vInfoKinds,
						// The user's preferred language, which is what every other caller passes --
						// not `null`.
						//
						// A cache key embeds the language, so a download written under `null` and a
						// grid or a search reading under `fr` are different files: the set would
						// come down, and then not be found by the search that was the reason for
						// downloading it. `CardRepository` normalises this once against what the
						// provider can really answer in, so passing a language is correct even for
						// a source that cannot serve it.
						//
						// This is the same mistake that once stopped any set ever showing as saved;
						// see the note in `CardGridViewModel.startLoad`.
						language = vLanguage,
					),
				)
			}
			for (vLanguage in vForArt) {
				if (vArtKinds.isEmpty()) break
				vDownloads.enqueue(
					DownloadRequest(
						setId = vSet.id,
						game = vSet.game,
						setName = vSet.name,
						kinds = vArtKinds,
						language = vLanguage,
					),
				)
			}
		},
		onCancelDownload = vDownloads::cancel,
		onCancelAllDownloads = vDownloads::cancelAll,
		onClearFinishedDownloads = vDownloads::clearFinished,
		gameArt = vArt,
		preferredLanguage = vDownloadLanguage,
		isCardDataBundled = vProvider?.capabilities?.data?.bundledCardData == true,
		hasThumbnails = vProvider?.capabilities?.data?.thumbnailImages != false,
	)
}

/**
 * The set list, given a state and somewhere to send intents.
 *
 * No view model, no Koin, no coroutines: everything it needs arrives as arguments, which is what
 * makes it previewable and what keeps the screen's layout separable from how its data is obtained.
 * Navigation is dispatched, not called: see `SetListContract.Effect`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetListContent(
	state: SetListContract.UiState,
	dispatch: (SetListContract.Intent) -> Unit,
	downloads: List<DownloadJob> = emptyList(),
	onDownload: (CardSet, Set<DownloadKind>, Set<CardLanguage>) -> Unit = { _, _, _ -> },
	/**
	 * The user's preferred language, ticked by default in the download dialog.
	 *
	 * Passed in rather than read here, because this composable stays free of Koin so it can be
	 * previewed. `null` leaves the set's own first stated language ticked instead.
	 */
	preferredLanguage: CardLanguage? = null,
	/** True when this game's records ship with the app -- `DataCapabilities.bundledCardData`. */
	isCardDataBundled: Boolean = false,
	/** True when the source publishes a small rendition -- `DataCapabilities.thumbnailImages`. */
	hasThumbnails: Boolean = true,
	onCancelDownload: (String) -> Unit = {},
	onCancelAllDownloads: () -> Unit = {},
	onClearFinishedDownloads: () -> Unit = {},
	gameArt: GameArt? = null,
) {
	val vState = state

	// Which set's download dialog is open, and whether the queue is showing. Local because neither
	// is worth a trip through the state machine: nothing outside this screen cares.
	var vPendingSet by remember { mutableStateOf<CardSet?>(null) }
	var vPendingAll by remember { mutableStateOf(false) }

	Scaffold(
		topBar = {
			TopAppBar(
				navigationIcon = {
					// The game picker is a real screen above this one now, so this is a genuine
					// back rather than a decoration.
					IconButton(onClick = { dispatch(SetListContract.Intent.BackPressed) }) {
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
						val vBackdrop = logoBackdropFor(gameArt)
						if (vBackdrop == null) {
							vLogoImage()
						} else {
							Box(
								modifier = Modifier
									.clip(RoundedCornerShape(8.dp))
									.background(vBackdrop)
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
								AppIcons.CloudDownload,
								contentDescription = "Download all ${vState.visibleSets.size} sets shown",
							)
						}
					}
					IconButton(onClick = { dispatch(SetListContract.Intent.SearchRequested) }) {
						Icon(
							AppIcons.TravelExplore,
							contentDescription = "Search cards across all sets",
						)
					}
					AppOverflowMenu(
						onOpenSettings = { dispatch(SetListContract.Intent.SettingsRequested) },
						onOpenStorage = { dispatch(SetListContract.Intent.StorageRequested) },
						onOpenDownloads = { dispatch(SetListContract.Intent.DownloadsRequested) },
						activeDownloads = downloads.count { it.isActive },
					)
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
						error = vState.error,
						onRetry = { dispatch(SetListContract.Intent.Refresh) },
					)

					vState.isEmptySearch -> EmptyState(emptyMessage(vState))

					else -> {
						val vFavourites = vState.favouriteSets
						val vListState = rememberLazyListState()
						val vReorder = rememberReorder(vListState)
						val vFavouriteKeys = vFavourites.map { it.id.qualified }
						// Indexed against the *stored* favourites, not the visible ones, because
						// that is the list being reordered. They are the same list whenever a drag
						// is allowed at all -- see `UiState.canReorderFavourites`.
						val vOnMove: (String, Int) -> Unit = { vKey, vTo ->
							dispatch(SetListContract.Intent.FavouriteMovedTo(vKey, vTo))
						}

						LazyColumn(
							state = vListState,
							contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
							verticalArrangement = Arrangement.spacedBy(8.dp),
						) {
							if (vFavourites.isNotEmpty()) {
								item(key = "favourites-heading") {
									SectionHeading(
										text = if (vFavourites.size == 1) {
											"1 favourite"
										} else {
											"${vFavourites.size} favourites"
										},
										// Said once, where the handles would otherwise have been,
										// rather than leaving the user to wonder where they went.
										note = if (vState.canReorderFavourites) {
											null
										} else if (vFavourites.size > 1) {
											"Clear the search to reorder"
										} else {
											null
										},
									)
								}
							}

							setRows(
								sets = vFavourites,
								state = vState,
								dispatch = dispatch,
								downloads = downloads,
								onDownload = { vPendingSet = it },
								reorder = vReorder.takeIf { vState.canReorderFavourites },
								reorderKeys = vFavouriteKeys,
								onMove = vOnMove,
							)

							if (vFavourites.isNotEmpty() && vState.otherSets.isNotEmpty()) {
								item(key = "all-sets-heading") { SectionHeading("All sets") }
							}

							setRows(
								sets = vState.otherSets,
								state = vState,
								dispatch = dispatch,
								downloads = downloads,
								onDownload = { vPendingSet = it },
								reorder = null,
								reorderKeys = emptyList(),
								onMove = vOnMove,
							)

							// A tally and nothing else. The option that changes it lives in
							// settings, where options live; this is the end of a list and the
							// only thing wanted here is the answer to "is that all of them?".
							item(key = "set-count-footer") {
								Text(
									text = vState.countsLine,
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
								)
							}
						}

						// Labelled in the same order the list is drawn in, headings included, so an
						// index from the strip lands on the row the label names rather than a few
						// off. A heading is labelled with the section it opens.
						FastScroller(
							listState = vListState,
							labels = buildList {
								if (vFavourites.isNotEmpty()) add("Favourites")
								vFavourites.forEach { add(it.code.ifBlank { it.name }) }
								if (vFavourites.isNotEmpty() && vState.otherSets.isNotEmpty()) {
									add("All sets")
								}
								vState.otherSets.forEach { add(it.code.ifBlank { it.name }) }
							},
						)
					}
				}
			}
		}
	}

	// One import at a time per game, and nothing per-set that would write the same records while
	// it runs. Read off the queue rather than tracked here, so it stays true if the import was
	// started from another screen -- which it can be, now that it survives leaving this one.
	val vIsImportingGame = downloads.any {
		it.isActive && it.request.isWholeGameImport && it.request.game == state.game?.id
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
			isImportingGame = vIsImportingGame,
			languages = vSet.languages.toList(),
			defaultLanguage = preferredLanguage,
			isCardDataBundled = isCardDataBundled,
			hasThumbnails = hasThumbnails,
			infoLanguages = vState.savedLanguages[vSet.id.qualified].orEmpty(),
			// One set, so no dump is involved and there is nothing to choose: a 78 MB file to
			// fill one set is far worse than the request it would replace. See `BulkCatalogue`.
			onConfirm = { vKinds, vLanguages, _ ->
				onDownload(vSet, vKinds, vLanguages)
				vPendingSet = null
				// Straight to the queue, so the download is visibly a thing that now exists rather
				// than a dialog that closed and apparently did nothing.
				dispatch(SetListContract.Intent.DownloadsRequested)
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
			bulkVariants = vState.bulkVariants,
			// Every language any of these sets states. A language only some of them have is still
			// worth offering -- the enqueue skips it for the sets that were never printed in it.
			languages = vSets.flatMap { it.languages }.distinct(),
			defaultLanguage = preferredLanguage,
			isCardDataBundled = isCardDataBundled,
			hasThumbnails = hasThumbnails,
			// Only what *every* shown set already holds, for the same reason `alreadyHave` is an
			// intersection: a language half the list is missing must stay fetchable.
			infoLanguages = vSets
				.map { vState.savedLanguages[it.id.qualified].orEmpty() }
				.reduceOrNull { vAcc, vNext -> vAcc intersect vNext }
				.orEmpty(),
			onDismiss = { vPendingAll = false },
			isImportingGame = vIsImportingGame,
			importedVariantIds = state.importedVariantIds,
			onConfirm = { vKinds, vLanguages, vVariantId ->
				// Where the source publishes a dump, the whole game's records come from it and
				// there is no path here that fetches them a set at a time. That is not a
				// preference: the file exists so clients stop walking somebody else's API, and
				// choosing 988 requests over one download is not a choice worth offering.
				//
				// Art is unaffected. It is not in the file, it comes from a CDN rather than the
				// API, and it is still fetched per set.
				val vBulkHandlesInfo = vState.bulkVariants.isNotEmpty()
				if (vBulkHandlesInfo && DownloadKind.CARD_INFO in vKinds) {
					dispatch(SetListContract.Intent.BulkImportRequested(vVariantId))
				}
				val vPerSet = if (vBulkHandlesInfo) {
					vKinds - DownloadKind.CARD_INFO
				} else {
					vKinds
				}
				if (vPerSet.isNotEmpty()) {
					vSets.forEach { vSet -> onDownload(vSet, vPerSet, vLanguages) }
				}
				vPendingAll = false
				dispatch(SetListContract.Intent.DownloadsRequested)
			},
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

/**
 * Emits one section's worth of set rows.
 *
 * A `LazyListScope` function rather than a composable, so both sections are items of the *same*
 * `LazyColumn` -- which is what lets a favourite keep its identity, and its `animateItem`, when it
 * is unpinned and moves down into the list below. Two nested lists would make that a disappearance
 * and a reappearance.
 *
 * @param reorder non-null only where dragging is allowed, which is the favourites section with no
 *   search running. Passing null is what removes the handles
 */
private fun LazyListScope.setRows(
	sets: List<CardSet>,
	state: SetListContract.UiState,
	dispatch: (SetListContract.Intent) -> Unit,
	downloads: List<DownloadJob>,
	onDownload: (CardSet) -> Unit,
	reorder: ReorderState?,
	reorderKeys: List<String>,
	onMove: (String, Int) -> Unit,
) {
	items(sets, key = { it.id.qualified }) { vSet ->
		val vId = vSet.id.qualified
		val vIsDragging = reorder?.draggedKey == vId
		SetRow(
			set = vSet,
			// Badged only while every line is on screen: with one line selected the badge would
			// repeat the chip on every single row.
			region = if (state.region != null) null else state.game?.regionFor(vSet.region),
			isLastOpened = vId == state.lastOpenedSetId,
			isSaved = vId in state.savedSetIds,
			onClick = { dispatch(SetListContract.Intent.SetOpened(vSet)) },
			// Absent until the set has been fetched in the language it opens in, and the row then
			// falls back to the figure the source states. See `UiState.confirmedCardCounts`.
			confirmedCardCount = state.confirmedCardCounts[vId],
			availableLanguages = state.availableLanguages[vId].orEmpty(),
			downloadStatus = downloads.firstOrNull { it.request.setId == vSet.id },
			images = state.imageDownloads[vId],
			onDownload = { onDownload(vSet) },
			isFavourite = vId in state.favouriteIds,
			onToggleFavourite = { dispatch(SetListContract.Intent.FavouriteToggled(vId)) },
			// The dragged row follows the finger, so it must not also be animated into place.
			itemModifier = if (vIsDragging) Modifier else Modifier.animateItem(),
			isDragging = vIsDragging,
			// Drawn above its neighbours while it travels, whether by finger or by being starred.
			// Without this a row promoted into the favourites slides up *behind* the rows it
			// passes, because a `LazyColumn` draws in index order and its new index is above
			// theirs. Separate from `isDragging`, which also raises the card off the page: the
			// last-starred row should not keep a drag shadow once it has landed.
			isLifted = vIsDragging || vId == state.recentlyMovedId,
			dragOffsetY = if (vIsDragging) reorder.offsetY else 0f,
			handleModifier = reorder?.let { Modifier.reorderHandle(it, vId, reorderKeys, onMove) },
		)
	}
}

/** A quiet label between two groups of rows. */
@Composable
private fun SectionHeading(text: String, note: String? = null) {
	Column(Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp)) {
		Text(
			text = text,
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		if (note != null) {
			Text(
				text = note,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
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
	confirmedCardCount: Int? = null,
	availableLanguages: Set<CardLanguage> = emptySet(),
	downloadStatus: DownloadJob? = null,
	images: SetImageStatus? = null,
	onDownload: () -> Unit = {},
	isFavourite: Boolean = false,
	onToggleFavourite: () -> Unit = {},
	itemModifier: Modifier = Modifier,
	isDragging: Boolean = false,
	isLifted: Boolean = false,
	dragOffsetY: Float = 0f,
	handleModifier: Modifier? = null,
) {
	Card(
		onClick = onClick,
		modifier = itemModifier
			.fillMaxWidth()
			// Rides above its neighbours while they slide underneath it.
			.zIndex(if (isLifted) 1f else 0f)
			.graphicsLayer { translationY = dragOffsetY }
			// The row is one half of the container transform into the card grid; the grid screen's
			// root is the other. See `Modifier.sharedSetContainer`.
			.sharedSetContainer(set.id.qualified),
		elevation = CardDefaults.cardElevation(
			defaultElevation = if (isDragging) DRAGGED_ROW_ELEVATION else 0.dp,
		),
		colors = if (isLastOpened) {
			CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
		} else {
			CardDefaults.cardColors()
		},
	) {
		Row(
			modifier = Modifier
				.padding(start = if (handleModifier == null) 16.dp else 4.dp, top = 16.dp, end = 16.dp, bottom = 16.dp)
				.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (handleModifier != null) {
				ReorderHandle(handleModifier)
			}
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
					LanguagePin(availableLanguages)
					Text(
						text = setSubtitle(set, isLastOpened, confirmedCardCount),
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
					// Wavy, to agree with the Downloads screen. The reason is recorded there and
					// applies just as much here: a long download that is progressing looks
					// identical to a stalled one under a static indicator, and the wave moves on
					// its own. This is the app's other live-download surface, so it should not
					// read differently.
					Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
						if (vProgress == null) {
							CircularWavyProgressIndicator(Modifier.size(24.dp))
						} else {
							CircularWavyProgressIndicator(
								progress = { vProgress },
								modifier = Modifier.size(24.dp),
							)
						}
					}
				}

				else -> {
					Spacer(Modifier.size(4.dp))
					IconButton(onClick = onToggleFavourite, modifier = Modifier.size(32.dp)) {
						Icon(
							imageVector = if (isFavourite) Icons.Filled.Star else AppIcons.StarBorder,
							contentDescription = if (isFavourite) {
								"Remove ${set.name} from favourites"
							} else {
								"Add ${set.name} to favourites"
							},
							// Filled and coloured when on, outlined and quiet when off, so a column
							// of rows reads as "these few" rather than as a row of identical stars.
							tint = if (isFavourite) {
								MaterialTheme.colorScheme.primary
							} else {
								MaterialTheme.colorScheme.onSurfaceVariant
							},
							modifier = Modifier.size(20.dp),
						)
					}
					IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
						Icon(
							imageVector = AppIcons.Download,
							contentDescription = "Download ${set.name}",
							tint = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.size(20.dp),
						)
					}
				}
			}
			// Two marks, because the two halves of a download are separately true: a set can have
			// its records and none of its thumbnails, which is the common case after browsing it
			// once. Full-size art has no mark because it is never bulk-downloaded and so has no
			// state to report -- see `DownloadKind`.
			if (isSaved || images?.isEmpty == false) {
				Spacer(Modifier.size(6.dp))
				Row(verticalAlignment = Alignment.CenterVertically) {
					if (isSaved) {
						Icon(
							imageVector = AppIcons.Description,
							// "Saved", not "complete". A set interrupted part-way through leaves a
							// file behind too, and the mark must not promise more than that.
							contentDescription = "Card info saved on this device",
							tint = MaterialTheme.colorScheme.primary,
							modifier = Modifier.size(18.dp),
						)
					}
					ImageMark(
						record = images?.thumbnails,
						icon = AppIcons.GridView,
						label = "Thumbnails",
						leadingSpace = isSaved,
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
 * Three of the sources supply real artwork -- Scryfall a symbol for all 988 of its paper sets,
 * TCGdex a logo for 157 of its 218, YGOPRODeck box art for many of its. The rest publish nothing at
 * all, and so do the sets those three skip, which is why the coloured monogram below is a permanent
 * fallback rather than a temporary one.
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
 * The fallback for every source and every set that publishes no symbol of its own -- see
 * [SetMark], which prefers a real one where it exists. Without this the alternative is a column of
 * identical grey tiles that are genuinely hard to tell apart when scrolling a catalogue of several
 * hundred Magic sets.
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
 * The languages a set is known to exist in, as a small run of codes.
 *
 * ## When it appears, and when it deliberately does not
 *
 * Only where the languages are actually **known**, which is a narrower thing than it sounds. Two
 * sources state them with the catalogue -- TCGdex, which serves eleven locales and whose Japanese
 * line does not exist in any of the western ones, and the Wuthering Waves snapshot. Everywhere
 * else the answer only arrives once the set has been opened and `CardRepository.languagesFor`
 * has probed for it, and until then this is empty and nothing is drawn.
 *
 * That silence is the point. Most sources say nothing per set, and a pin that filled the gap from
 * the provider's capability list would announce eleven languages for a set printed in one -- which
 * is the exact claim `LanguageCoverage` exists to prevent.
 *
 * A single language that is not the one being browsed is worth showing on its own: that is the
 * Japan-only set, and it is why a French user opens it in Japanese rather than onto nothing.
 */
@Composable
private fun LanguagePin(languages: Set<CardLanguage>) {
	if (languages.isEmpty()) return
	// Ordered by the app's own preference rather than by whatever a set happened to list, so the
	// same set reads the same way in every row it appears in.
	val vCodes = CardLanguage.PREFERENCE_ORDER
		.filter { it in languages }
		.plus(languages.filterNot { it in CardLanguage.PREFERENCE_ORDER })
		.map { it.code.uppercase() }
	Row(
		modifier = Modifier
			.padding(end = 6.dp)
			.clip(RoundedCornerShape(4.dp))
			.background(MaterialTheme.colorScheme.surfaceVariant)
			.padding(horizontal = 5.dp, vertical = 1.dp),
	) {
		Text(
			// Capped, because eleven locales in a subtitle is not a pin any more. The overflow
			// count still says there are more rather than silently hiding them.
			text = vCodes.take(LANGUAGE_PIN_MAX).joinToString(" ") +
				if (vCodes.size > LANGUAGE_PIN_MAX) " +${vCodes.size - LANGUAGE_PIN_MAX}" else "",
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
		)
	}
}

/** Three codes and a count reads at a glance; five does not. */
private const val LANGUAGE_PIN_MAX = 3

/**
 * "OGN · 352 cards · Oct 2025", with each part dropped when the provider does not supply it.
 *
 * A missing release date shows nothing rather than "Unknown date": the row is not the place to
 * discuss what the provider does not know.
 *
 * [confirmedCardCount] wins over the set's own figure when there is one, because it is the count
 * for the language this row will actually open in. A source states one size per set and it is the
 * English printing's: YGOPRODeck says Magnificent Maestros is 24 cards, and serves 4 of them in
 * French. The row said 24 and the grid showed 4, which is the app claiming a number it never
 * checked. The language is not named here -- a subtitle is not the place to explain translation
 * coverage, and the number simply being right is what was wanted.
 */
private fun setSubtitle(
	set: CardSet,
	isLastOpened: Boolean = false,
	confirmedCardCount: Int? = null,
): String = buildList {
	add(set.code)
	(confirmedCardCount ?: set.cardCount)?.let { add(if (it == 1) "1 card" else "$it cards") }
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
	)
}

@Preview
@Composable
private fun SetListLoadingPreview() = PreviewFrame {
	SetListContent(
		state = SetListContract.UiState(isLoading = true),
		dispatch = {},
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
				// Finished.
				PreviewData.SETS[1].id.qualified to SetImageStatus(
					thumbnails = ImageDownloadRecord(fetched = 280, total = 280),
				),
				// Interrupted, which the mark shows as a percentage rather than a tick.
				PreviewData.SETS[2].id.qualified to SetImageStatus(
					thumbnails = ImageDownloadRecord(fetched = 206, total = 288),
				),
			),
		),
		dispatch = {},
	)
}

