package com.bitsycore.tcgexplorer.ui.screen.sets

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import com.bitsycore.tcgexplorer.ui.component.arrowSelection
import com.bitsycore.tcgexplorer.ui.component.readableColumn
import com.bitsycore.tcgexplorer.ui.component.readablePadding
import com.bitsycore.tcgexplorer.ui.component.reorderHandle
import com.bitsycore.tcgexplorer.ui.component.rememberReorder
import com.bitsycore.tcgexplorer.ui.component.ReorderState
import com.bitsycore.tcgexplorer.ui.component.ReorderHandle
import com.bitsycore.tcgexplorer.ui.component.DRAGGED_ROW_ELEVATION
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bitsycore.tcgexplorer.data.download.DownloadJob
import com.bitsycore.tcgexplorer.data.download.DownloadKind
import com.bitsycore.tcgexplorer.ui.screen.downloads.DownloadKindDialog
import org.koin.compose.koinInject
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import com.bitsycore.tcgexplorer.games.api.GameArt
import com.bitsycore.tcgexplorer.ui.art.GameArtRegistry
import com.bitsycore.tcgexplorer.ui.art.backdropFor
import com.bitsycore.tcgexplorer.ui.art.logoTintFor
import org.jetbrains.compose.resources.painterResource
import com.bitsycore.tcgexplorer.data.settings.ImageDownloadRecord
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.bitsycore.tcgexplorer.core.game.GameProfile
import com.bitsycore.tcgexplorer.core.game.GameRegion
import com.bitsycore.tcgexplorer.core.model.CardLanguage
import com.bitsycore.tcgexplorer.core.model.CardSet
import com.bitsycore.tcgexplorer.core.model.GameId
import com.bitsycore.tcgexplorer.core.provider.ProviderError
import com.bitsycore.tcgexplorer.data.repository.DataOrigin
import com.bitsycore.tcgexplorer.ui.component.EmptyState
import com.bitsycore.tcgexplorer.ui.component.FastScroller
import com.bitsycore.tcgexplorer.ui.component.ErrorState
import com.bitsycore.tcgexplorer.ui.component.LoadingState
import com.bitsycore.tcgexplorer.ui.component.LanguageMenu
import com.bitsycore.tcgexplorer.ui.component.NoticeBanner
import com.bitsycore.tcgexplorer.ui.component.fadesWithSharedContainer
import com.bitsycore.tcgexplorer.ui.component.sharedSetContainer
import com.bitsycore.tcgexplorer.ui.preview.PreviewData
import com.bitsycore.tcgexplorer.ui.preview.PreviewFrame
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import com.bitsycore.lib.pulse.compose.collectEffect
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import com.bitsycore.tcgexplorer.ui.component.AppIcons
import com.bitsycore.tcgexplorer.ui.component.withoutBottom
import com.bitsycore.tcgexplorer.ui.component.Coverage
import com.bitsycore.tcgexplorer.ui.component.CoverageRings
import com.bitsycore.tcgexplorer.ui.component.AppOverflowMenu

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

	// The only thing still read here, and it is a Compose resource rather than data: a game's logo
	// is a painter, which is not a thing to put in a view model's state. `null` for a game whose
	// module ships no logo, which the title falls back for.
	val vArt = vState.game?.let(koinInject<GameArtRegistry>()::forGame)

	SetListContent(
		state = vState,
		dispatch = viewModel::dispatch,
		gameArt = vArt,
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
	gameArt: GameArt? = null,
) {

	// Everything this screen needs now arrives in the state. It used to take the queue, the
	// preferred language, three capability flags and four callbacks as parameters, all assembled
	// in the binder above from Koin -- which meant the dialog could only ever be previewed in its
	// default shape, and the rules that built the jobs sat in a lambda no test could reach.
	val vDownloads = state.downloads
	val vPreferredLanguage = state.browsingLanguage

	// Which set's download dialog is open, and whether the queue is showing. Local because neither
	// is worth a trip through the state machine: nothing outside this screen cares.
	var vPendingSet by remember { mutableStateOf<CardSet?>(null) }
	var vPendingAll by remember { mutableStateOf(false) }

	// `enterAlways`, exactly the card grid's, and for the reason recorded there: both collapse on
	// the way down, but `exitUntilCollapsed` only expands again at the very top of the list.
	val vScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

	Scaffold(
		modifier = Modifier.nestedScroll(vScrollBehavior.nestedScrollConnection),
		topBar = {
			// A surface, not a bare Column: the app bar paints its own background and the controls
			// stacked under it do not, so the list scrolling underneath would show straight
			// through the search field. The grid learned this first.
			Surface(color = MaterialTheme.colorScheme.surface) {
			Column {
			TopAppBar(
				navigationIcon = {
					// The game picker is a real screen above this one now, so this is a genuine
					// back rather than a decoration.
					IconButton(onClick = { dispatch(SetListContract.Intent.BackPressed) }) {
						Icon(
							AppIcons.ArrowBack,
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
						Text(state.game?.shortName.orEmpty())
					} else {
						// The mark always sits on a tile, exactly as it does in the picker, and from
						// the same function so the two cannot drift. This screen used to give one
						// only to artwork that declared a plate, which meant most marks were drawn
						// straight onto the app bar -- the one background none of them was designed
						// for. Altered's near-white wordmark, Riftbound's white subtitle and
						// Lorcana's gold all vanish there on the light theme.
						//
						// `backdropFor` answers with the artwork's own plate where it states one
						// -- Cyberpunk's yellow -- and otherwise a wash of the game's accent, which
						// is dark enough on the light theme to hold a white mark and pale enough on
						// the dark one to disappear into the bar.
						Box(
							modifier = Modifier
								.clip(RoundedCornerShape(8.dp))
								.background(backdropFor(gameArt))
								.padding(horizontal = 8.dp, vertical = 4.dp),
							contentAlignment = Alignment.Center,
						) {
							Image(
								painter = painterResource(vLogo),
								// The title *is* the game name, so this carries it for a screen
								// reader rather than being decorative.
								contentDescription = state.game?.displayName,
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
					}
				},
				actions = {
					// What every set below will open in, and what a download will fetch. First in
					// the row because it qualifies the whole list rather than acting on it.
					if (state.browsingLanguageOptions.size > 1) {
						LanguageMenu(
							options = CardLanguage.PREFERENCE_ORDER
								.filter { it in state.browsingLanguageOptions },
							selected = state.browsingLanguage,
							// A preference, said plainly, because the menu lists what the *source*
							// serves and not what each set is published in -- the rows answer that
							// one, per set, and the grid confirms it when a set is opened.
							header = "Browsing language",
							onSelect = {
								dispatch(SetListContract.Intent.BrowsingLanguageSelected(it))
							},
						)
					}
					// Whatever the list is currently showing, which is the useful scope: with a
					// region chip or a search active, "all" means all of *those*, not all 988.
					if (state.visibleSets.isNotEmpty()) {
						IconButton(onClick = { vPendingAll = true }) {
							Icon(
								AppIcons.CloudDownload,
								contentDescription = "Download all ${state.visibleSets.size} sets shown",
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
						activeDownloads = vDownloads.count { it.isActive },
					)
				},
				scrollBehavior = vScrollBehavior,
			)

			// Only for a game that really ships more than one line. See `GameProfile.regions`.
			if (state.regionOptions.isNotEmpty()) {
				RegionFilter(
					regions = state.regionOptions,
					selected = state.region,
					onSelect = { dispatch(SetListContract.Intent.RegionSelected(it)) },
				)
			}

			// Folds away with the bar, like the grid's. Scrolling down is a request for more list,
			// and a search field left behind while the bar it belongs to collapses reads as a
			// leftover. The query is untouched -- the field comes back on the way up with whatever
			// was typed in it, and the list stays filtered meanwhile.
			//
			// Seeded transition state rather than `visible = …`, for the reason recorded on the
			// grid: `AnimatedVisibility` animates to its target on first composition, so a screen
			// that is re-composed on every back would replay the box opening under a bar that had
			// not moved.
			val vSearchShown = vScrollBehavior.state.collapsedFraction < 0.5f
			val vSearchTransition = remember { MutableTransitionState(vSearchShown) }
			vSearchTransition.targetState = vSearchShown
			AnimatedVisibility(visibleState = vSearchTransition) {
				// Arranging sits beside the search rather than in the app bar, which is where it
				// used to be. It acts on the rows below it, while the bar above holds what acts on
				// the whole game -- the language, the bulk download, the search across sets. Next
				// to the field it is in the same band as the list it rearranges.
				// The inset above depends on what is above it, because a chip row is not an app
				// bar: it hands down 8dp of touch-target padding that cannot be seen, so 4dp on
				// top of that is the same gap the bar needs 12dp to produce. Every visible edge on
				// this screen ends up 12dp from the next one.
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(
							start = 16.dp,
							end = 16.dp,
							top = if (state.regionOptions.isEmpty()) 12.dp else 4.dp,
							bottom = 8.dp,
						)
						// Same column as the rows below, or the field would run the width of a
						// tablet while the list it searches sat in the middle.
						.readableColumn(),
					verticalAlignment = Alignment.CenterVertically,
				) {
					OutlinedTextField(
						value = state.search,
						onValueChange = { dispatch(SetListContract.Intent.SearchChanged(it)) },
						// A placeholder, not a label. A floating label reserves 8dp above the
						// border for itself whether or not it has floated, which is invisible
						// space that every inset around this field then had to be written to
						// cancel -- and it was got wrong three times running. A search box that
						// says what it is until you type in it loses nothing by it.
						placeholder = { Text("Search sets") },
						leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
						singleLine = true,
						modifier = Modifier.weight(1f),
					)
					Spacer(Modifier.size(4.dp))
					IconButton(onClick = { dispatch(SetListContract.Intent.EditingToggled) }) {
						Icon(
							imageVector = if (state.isEditing) AppIcons.Check else AppIcons.Tune,
							contentDescription = if (state.isEditing) {
								"Done arranging favourites"
							} else {
								"Arrange favourites"
							},
						)
					}
				}
			}
			}
			}
		},
	) { vPadding ->
		Column(Modifier.padding(vPadding.withoutBottom()).fillMaxSize()) {

			// The honesty strip. Shown whenever what is on screen is not a fresh network result.
			when {
				state.error != null && state.sets.isNotEmpty() -> NoticeBanner(
					text = "Showing saved sets. Refresh failed.",
					onAction = { dispatch(SetListContract.Intent.Refresh) },
				)
				state.origin == DataOrigin.CACHE && state.isStale -> NoticeBanner(
					text = "Saved copy, refreshing…",
					onAction = null,
				)
			}

			// Knows its own width, which is what the rows are centred within on a tablet.
			BoxWithConstraints(Modifier.weight(1f)) {
				when {
					state.isInitialLoad -> LoadingState()

					state.sets.isEmpty() && state.error != null -> ErrorState(
						error = state.error,
						onRetry = { dispatch(SetListContract.Intent.Refresh) },
					)

					state.isEmptySearch -> EmptyState(emptyMessage(state))

					else -> {
						val vFavourites = state.favouriteSets
						val vListState = rememberLazyListState()
						val vReorder = rememberReorder(vListState)
						val vFavouriteKeys = vFavourites.map { it.id.qualified }
						// Indexed against the *stored* favourites, not the visible ones, because
						// that is the list being reordered. They are the same list whenever a drag
						// is allowed at all -- see `UiState.canReorderFavourites`.
						val vOnMove: (String, Int) -> Unit = { vKey, vTo ->
							dispatch(SetListContract.Intent.FavouriteMovedTo(vKey, vTo))
						}

						// The keyboard's cursor, over the two groups as one run of sets.
						val vSelectable = vFavourites + state.otherSets
						var vSelected by remember { mutableIntStateOf(0) }
						// See the game picker: no cursor until something navigates.
						var vCursorVisible by remember { mutableStateOf(false) }
						LaunchedEffect(vSelectable.size) {
							vSelected = vSelected.coerceIn(0, (vSelectable.size - 1).coerceAtLeast(0))
						}
						// The selection counts sets; the list counts *rows*, and the headings are
						// rows too. This is the one place the two are converted between.
						val vFavHeading = if (vFavourites.isNotEmpty()) 1 else 0
						val vAllHeading =
							if (vFavourites.isNotEmpty() && state.otherSets.isNotEmpty()) 1 else 0
						val vRowOf: (Int) -> Int = { vIndex ->
							if (vIndex < vFavourites.size) {
								vFavHeading + vIndex
							} else {
								vFavHeading + vFavourites.size + vAllHeading +
									(vIndex - vFavourites.size)
							}
						}
						// Only while there *is* a cursor. Without the guard this ran on arrival with a
						// selection of 0 and scrolled a restored list back to the top, which is what
						// every back navigation did.
						LaunchedEffect(vSelected, vCursorVisible) {
							if (!vCursorVisible) return@LaunchedEffect
							val vRow = vRowOf(vSelected)
							if (vListState.layoutInfo.visibleItemsInfo.none { it.index == vRow }) {
								vListState.animateScrollToItem(vRow)
							}
						}

						LazyColumn(
							state = vListState,
							// 4dp at the top so the first row sits the same distance below the search field
							// as the field sits below the bar. The field contributes 8dp of its own.
							//
							// The sides centre the rows in a readable column on a tablet, as padding
							// rather than a narrower list so a drag anywhere still scrolls.
							contentPadding = PaddingValues(
								start = readablePadding(maxWidth, 16.dp),
								end = readablePadding(maxWidth, 16.dp),
								top = 4.dp,
								// The bottom inset as content rather than margin, so rows pass
								// under the home indicator and the last one still clears it.
								bottom = 8.dp + vPadding.calculateBottomPadding(),
							),
							verticalArrangement = Arrangement.spacedBy(8.dp),
							modifier = Modifier.arrowSelection(
								count = vSelectable.size,
								selected = vSelected,
								onSelect = { vSelected = it },
								isCursorVisible = vCursorVisible,
								// The cursor appears where the list already is, not at the top.
								onKeyboardUsed = {
									if (!vCursorVisible) {
										val vFirstRow = vListState.firstVisibleItemIndex
										vSelected = vSelectable.indices
											.firstOrNull { vRowOf(it) >= vFirstRow } ?: 0
										vCursorVisible = true
									}
								},
								onActivate = {
									vSelectable.getOrNull(vSelected)?.let {
										dispatch(SetListContract.Intent.SetOpened(it))
									}
								},
							),
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
										// Only while arranging: outside that mode there are no
										// handles to be missing, so explaining their absence
										// answers a question nobody asked.
										note = if (!state.isEditing || state.canReorderFavourites) {
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
								selectedId = vSelectable.getOrNull(vSelected)
									?.takeIf { vCursorVisible }?.id?.qualified,
								sets = vFavourites,
								state = state,
								dispatch = dispatch,
								downloads = vDownloads,
								onDownload = { vPendingSet = it },
								reorder = vReorder.takeIf { state.canReorderFavourites },
								reorderKeys = vFavouriteKeys,
								onMove = vOnMove,
							)

							if (vFavourites.isNotEmpty() && state.otherSets.isNotEmpty()) {
								item(key = "all-sets-heading") { SectionHeading("All sets") }
							}

							setRows(
								selectedId = vSelectable.getOrNull(vSelected)
									?.takeIf { vCursorVisible }?.id?.qualified,
								sets = state.otherSets,
								state = state,
								dispatch = dispatch,
								downloads = vDownloads,
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
									text = state.countsLine,
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
								if (vFavourites.isNotEmpty() && state.otherSets.isNotEmpty()) {
									add("All sets")
								}
								state.otherSets.forEach { add(it.code.ifBlank { it.name }) }
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
	val vIsImportingGame = vDownloads.any {
		it.isActive && it.request.isWholeGameImport && it.request.game == state.game?.id
	}

	vPendingSet?.let { vSet ->
		DownloadKindDialog(
			setName = vSet.name,
			cardCount = vSet.cardCount,
			alreadyHave = alreadyDownloaded(
				isSaved = vSet.id.qualified in state.savedSetIds,
				images = state.imageDownloads[vSet.id.qualified],
			),
			onDismiss = { vPendingSet = null },
			isImportingGame = vIsImportingGame,
			// This set's editions where the source states them, otherwise the menu of what the
			// source serves at all -- see `DownloadKindDialog.languagesAreClaimed`. Most sources
			// state nothing, and offering no choice at all there meant a game with six languages
			// could only ever be downloaded in one.
			languages = vSet.languages.toList().ifEmpty { state.browsingLanguageOptions.toList() },
			languagesAreClaimed = vSet.languages.isEmpty(),
			defaultLanguage = vPreferredLanguage,
			isCardDataBundled = state.isCardDataBundled,
			isCardInfoBulkOnly = state.isCardInfoBulkOnly,
			hasThumbnails = state.hasThumbnails,
			// Chips only, so an edition held under no language has none to light up. That it is
			// held at all is carried by `savedSetIds`, which is what locks the card-info row.
			infoLanguages = state.savedLanguages[vSet.id.qualified]
				.orEmpty()
				.filterNotNullTo(mutableSetOf()),
			// One set, so no dump is involved and there is nothing to choose: a 78 MB file to
			// fill one set is far worse than the request it would replace. See `BulkCatalogue`.
			onConfirm = { vKinds, vInfoLanguages, vArtLanguages, _ ->
				dispatch(
					SetListContract.Intent.DownloadRequested(
						set = vSet,
						kinds = vKinds,
						infoLanguages = vInfoLanguages,
						artLanguages = vArtLanguages,
					),
				)
				vPendingSet = null
				// Straight to the queue, so the download is visibly a thing that now exists rather
				// than a dialog that closed and apparently did nothing.
				dispatch(SetListContract.Intent.DownloadsRequested)
			},
		)
	}

	if (vPendingAll) {
		val vSets = state.visibleSets
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
						isSaved = vSet.id.qualified in state.savedSetIds,
						images = state.imageDownloads[vSet.id.qualified],
					)
				}
				.reduceOrNull { vAcc, vNext -> vAcc intersect vNext }
				.orEmpty(),
			bulkVariants = state.bulkVariants,
			// Every language any of these sets states. A language only some of them have is still
			// worth offering -- the enqueue skips it for the sets that were never printed in it.
			languages = vSets.flatMap { it.languages }.distinct()
				.ifEmpty { state.browsingLanguageOptions.toList() },
			languagesAreClaimed = vSets.none { it.languages.isNotEmpty() },
			defaultLanguage = vPreferredLanguage,
			isCardDataBundled = state.isCardDataBundled,
			hasThumbnails = state.hasThumbnails,
			// Only what *every* shown set already holds, for the same reason `alreadyHave` is an
			// intersection: a language half the list is missing must stay fetchable.
			infoLanguages = vSets
				.map { state.savedLanguages[it.id.qualified].orEmpty() }
				.reduceOrNull { vAcc, vNext -> vAcc intersect vNext }
				.orEmpty()
				// Chips only. See the single-set dialog above.
				.filterNotNullTo(mutableSetOf()),
			onDismiss = { vPendingAll = false },
			isImportingGame = vIsImportingGame,
			importedVariantIds = state.importedVariantIds,
			isCheckingForUpdate = state.isCheckingBulkUpdate,
			onCheckForUpdate = {
				dispatch(SetListContract.Intent.BulkUpdateCheckRequested)
			},
			onConfirm = { vKinds, vInfoLanguages, vArtLanguages, vVariantId ->
				// Where the source publishes a dump, the whole game's records come from it and
				// there is no path here that fetches them a set at a time. That is not a
				// preference: the file exists so clients stop walking somebody else's API, and
				// choosing 988 requests over one download is not a choice worth offering.
				//
				// Art is unaffected. It is not in the file, it comes from a CDN rather than the
				// API, and it is still fetched per set.
				val vBulkHandlesInfo = state.bulkVariants.isNotEmpty()
				if (vBulkHandlesInfo && DownloadKind.CARD_INFO in vKinds) {
					dispatch(SetListContract.Intent.BulkImportRequested(vVariantId))
				}
				val vPerSet = if (vBulkHandlesInfo) {
					vKinds - DownloadKind.CARD_INFO
				} else {
					vKinds
				}
				if (vPerSet.isNotEmpty()) {
					vSets.forEach { vSet ->
						dispatch(
							SetListContract.Intent.DownloadRequested(
								set = vSet,
								kinds = vPerSet,
								infoLanguages = vInfoLanguages,
								artLanguages = vArtLanguages,
							),
						)
					}
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
			// 4dp above and none below. A Material chip pads itself out to the 48dp touch target,
			// so it already carries 8dp of invisible space on each side -- adding 4dp below as
			// well as above made the gap under this row half as big again as the one over it.
			.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 0.dp),
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
	/** The set the keyboard is pointing at, if it is in this group. */
	selectedId: String?,
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
			isSelected = vId == selectedId,
			// Badged only while every line is on screen: with one line selected the badge would
			// repeat the chip on every single row.
			region = if (state.region != null) null else state.game?.regionFor(vSet.region),
			isSaved = vId in state.savedSetIds,
			// Only once the scan has answered, for the same reason `canOfferDownload` waits: before
			// then nothing is in `completeSetIds`, and every saved row would flash an error mark.
			isIncomplete = state.isDownloadStateKnown &&
				vId in state.savedSetIds &&
				vId !in state.completeSetIds,
			isEditing = state.isEditing,
			// Both halves, because a download is both: the records and the pictures. A set with
			// its cards and none of its art still has something to fetch. Full-size art is not
			// counted -- it is never bulk-downloaded, so it has no completed state to be in.
			// Only once the scan has answered. Until then the row knows nothing about this set,
			// and a button that offers to fetch it is a claim: it appeared on every row of a
			// fully downloaded game and disappeared a frame later, moving the row as it went.
			canOfferDownload = state.isDownloadStateKnown &&
				!(
					vId in state.completeSetIds &&
						state.imageDownloads[vId]?.thumbnails?.isComplete == true
					),
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

/**
 * One set: name, code, card count and release date.
 *
 * It used to also mark where you left off -- a filled monogram, a tinted card and "last opened" in
 * the subtitle. Removed on the project owner's call: a hover-like highlight means nothing on a
 * touch screen, and the line was one more thing to read on every row for something the user had
 * just done and already knew.
 */
@Composable
private fun SetRow(
	set: CardSet,
	/** Outlined, because the keyboard is pointing at it. */
	isSelected: Boolean = false,
	region: GameRegion?,
	isSaved: Boolean,
	/**
	 * On disk, and the fetch that put it there did not finish.
	 *
	 * Drawn instead of the saved mark rather than beside it, so the row's width does not move --
	 * see the arranging note on `ArrangingMotionTest`. The two are mutually exclusive anyway: a
	 * download is either finished or it is not.
	 *
	 * This is the state a batch download leaves behind when the network goes, and without a mark
	 * the row is indistinguishable from a set the source really has nothing for.
	 */
	isIncomplete: Boolean = false,
	/**
	 * Whether this set has anything left to fetch *and* the app has looked.
	 *
	 * Not the inverse of "downloaded": before the scan lands nothing is known, and nothing is
	 * offered. See `UiState.isDownloadStateKnown`.
	 */
	canOfferDownload: Boolean = false,
	/** Whether the list is being arranged. Off, the star is a mark rather than a button. */
	isEditing: Boolean = false,
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
	val vTint = setColour(set.code)
	// The same tint reads louder over a light surface than a dark one, so it is drawn weaker
	// there. Measured by rendering both: at one strength the light theme was stripes with a row
	// behind them.
	val vHatchStrength = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) LIGHT_HATCH_SCALE else 1f
	// The tab and the card travel together, so everything that moves the row -- the lazy list's own
	// item animation, the drag offset, the lift -- sits out here rather than on the card.
	//
	// A box rather than a column: the tab overlaps the card's top edge instead of sitting flush
	// above it, so the card keeps all four of its corners and the tab is simply a rounded thing
	// laid over one of them.
	Box(
		modifier = itemModifier
			.fillMaxWidth()
			// Rides above its neighbours while they slide underneath it.
			.zIndex(if (isLifted) 1f else 0f)
			.graphicsLayer { translationY = dragOffsetY },
	) {
		Card(
			// Not clickable while arranging, for the reason the game picker is not: the row's job there
			// is to be dragged, and opening a set from under a press aimed at its handle is the obvious
			// way to get that wrong.
			//
			// Absent rather than disabled, and one `Card` rather than one per mode. A disabled `Card`
			// greys everything inside it -- the set's name reads as unavailable when it is merely not
			// tappable -- and announces itself to a screen reader as a button that does nothing. Two
			// call sites would be worse still: that is a composition identity, and toggling the mode
			// would throw away everything inside, which is the bug the game picker's rows had.
			modifier = Modifier
				.fillMaxWidth()
				// Room for the half of the tab that stands above the card.
				.padding(top = SET_TAB_OVERHANG)
				// The keyboard's cursor. An outline, so it reads as pointing at a row rather than as
				// the row being in some other state.
				.then(
					if (isSelected) {
						Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CardDefaults.shape)
					} else {
						Modifier
					},
				)
				// The row is one half of the container transform into the card grid; the grid screen's
				// root is the other. See `Modifier.sharedSetContainer`.
				.sharedSetContainer(set.id.qualified)
				// Before the click, so the ripple keeps to the card's corners.
				.clip(CardDefaults.shape)
				.then(
					if (isEditing) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick),
				),
			elevation = CardDefaults.cardElevation(
				defaultElevation = if (isDragging) DRAGGED_ROW_ELEVATION else 0.dp,
			),
		) {
			Row(
				// Constant, in both modes, for the reason recorded on the game picker's row.
				//
				// It used to drop from 16dp to 4dp to claw back room for the handle, and being a plain
				// `if` rather than an animation it did not ease at all: 12dp of inset vanished in one
				// frame while the handle was still springing open beside it. That step against a spring
				// is the boing. One animation drives this corner now -- the handle's -- and the room it
				// needs is the room it makes.
				modifier = Modifier
					.fillMaxWidth()
					// Behind the content and outside the padding, so it covers the whole card and is
					// clipped to its corners.
					.drawBehind { drawSetHatch(vTint, vHatchStrength) }
					.padding(16.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				// The defaults, for the reason recorded on the game picker's row: `AnimatedVisibility`
				// in a `Row` is `fadeIn() + expandHorizontally()` on one critically damped spring, and
				// `expandHorizontally` already anchors its content to the end -- so the handle slides
				// in from outside the row without help. Adding a slide on top was a second animation
				// moving the same object a different distance, and it chattered.
				AnimatedVisibility(visible = handleModifier != null) {
					ReorderHandle(handleModifier ?: Modifier)
				}
				SetMark(set)
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
					// A flow rather than a row, so a line that runs out of width breaks *between*
					// these facts and never inside one. As a row, the date was the element with
					// slack at the end and wrapped itself: "Nov" with "2024" on the line below,
					// which is a date cut in half and reads as a bug. Each child is one line and
					// does not wrap, so the whole date drops to the next line or none of it does.
					FlowRow(
						horizontalArrangement = Arrangement.spacedBy(8.dp),
						verticalArrangement = Arrangement.spacedBy(2.dp),
						// Three: a set in several languages can take the first line with its badge
						// and pin alone, leaving the count and the date a line each.
						maxLines = 3,
					) {
						if (region != null) {
							RegionBadge(region)
						}
						LanguagePin(availableLanguages)
						Text(
							text = setSubtitle(set, confirmedCardCount),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							maxLines = 1,
							softWrap = false,
							overflow = TextOverflow.Ellipsis,
						)
						set.releaseDate?.let {
							Text(
								text = "${monthName(it.month.ordinal)} ${it.year}",
								style = MaterialTheme.typography.labelSmall,
								fontWeight = FontWeight.Light,
								maxLines = 1,
								softWrap = false,
							)
						}
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

					// The star is a control, and only while arranging. Browsing, it said nothing the
					// list was not already saying: a favourite is already under the "N favourites"
					// heading, so a star beside it repeats that heading once per row.
					//
					// The slot stays 32dp wide either way. Collapsing it would be a second layout
					// change on the corner the handle is already animating, which is exactly the
					// bounce that took three attempts to find the first time.
					else -> {
						Spacer(Modifier.size(4.dp))
						FavouriteStar(
							name = set.name,
							isFavourite = isFavourite,
							isEditing = isEditing,
							onToggle = onToggleFavourite,
						)
						// Offered only while there is something left to fetch, and only while browsing.
						// A button that starts a download of nothing is worse than no button: it
						// invites a tap, does the work of checking, and reports that everything was
						// already there. Arranging is a different job, and a row being dragged should
						// not also be a row that starts a download.
						//
						// The marks below still say what is held -- this removes the *offer*, not the
						// statement. Which is the right way round: "you have this" is information, and
						// "get this" is an action that has nothing to act on.
						AnimatedVisibility(visible = canOfferDownload && !isEditing) {
							DownloadButton(name = set.name, onDownload = onDownload)
						}
					}
				}
				// Two rings, because the two halves of a download are separately true: a set can have
				// its records and none of its thumbnails, which is the common case after browsing it
				// once. Full-size art has no ring because it is never bulk-downloaded and so has no
				// state to report -- see `DownloadKind`.
				//
				// Browsing only. They are things to read, and arranging is not reading: leaving them
				// on put the star of a downloaded set two notches left of an undownloaded one's, and a
				// column of controls that do not line up reads as a bug.
				AnimatedVisibility(visible = !isEditing && (isSaved || images?.isEmpty == false)) {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Spacer(Modifier.size(6.dp))
						CoverageRings(
							// Card info keeps no per-set fraction, so an unfinished one is drawn as
							// interrupted rather than as a made-up sweep. "Saved", not "complete":
							// a fetch stopped part-way leaves a file behind too.
							info = when {
								!isSaved -> Coverage.Unknown
								isIncomplete -> Coverage.Interrupted
								else -> Coverage.Complete
							},
							infoIcon = AppIcons.Description,
							thumbnails = images?.thumbnails.toCoverage(),
							thumbnailIcon = AppIcons.GridView,
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
		// After the card, so it lies over it. It carries no click of its own, so the tap still
		// reaches the card underneath and opens the set.
		SetTab(set.code, Modifier.align(Alignment.TopStart).fadesWithSharedContainer())
	}
}

/**
 * The favourite mark, in a slot that never moves.
 *
 * A favourite's star is the *same* star in both modes, so it is simply drawn and nothing about it
 * transitions -- morphing it into itself is motion that says something changed when nothing did.
 * Only the hollow "add" star comes and goes, and only on a row that is not a favourite yet: an
 * outline on every row while browsing is a column of targets to miss on the way to opening one.
 *
 * The slot is a fixed 32dp either way, which is what keeps the rest of the row still.
 *
 * Its own composable rather than a block inside the row because `AnimatedVisibility` resolves to
 * the `RowScope` overload wherever a `Row` receiver is in scope, and the one wanted here is the
 * plain one.
 */
@Composable
private fun FavouriteStar(
	name: String,
	isFavourite: Boolean,
	isEditing: Boolean,
	onToggle: () -> Unit,
) {
	Box(
		modifier = Modifier
			.size(32.dp)
			.clip(CircleShape)
			.then(
				if (isEditing) {
					Modifier.clickable(role = Role.Button, onClick = onToggle)
				} else {
					Modifier
				},
			),
		contentAlignment = Alignment.Center,
	) {
		AnimatedVisibility(
			visible = isEditing && isFavourite,
			enter = fadeIn() + scaleIn(initialScale = 0.6f),
			exit = fadeOut() + scaleOut(targetScale = 0.6f),
		) {
			Icon(
				imageVector = AppIcons.StarFilled,
				contentDescription = "Remove $name from favourites",
				tint = MaterialTheme.colorScheme.primary,
				modifier = Modifier.size(20.dp),
			)
		}
		AnimatedVisibility(
			visible = isEditing && !isFavourite,
			enter = fadeIn() + scaleIn(initialScale = 0.6f),
			exit = fadeOut() + scaleOut(targetScale = 0.6f),
		) {
			Icon(
				imageVector = AppIcons.StarBorder,
				contentDescription = "Add $name to favourites",
				// Outlined and quiet when off, filled and coloured when on, so a column of rows
				// reads as "these few" rather than as a row of identical stars.
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(20.dp),
			)
		}
	}
}

/**
 * The download offer.
 *
 * It leaves entirely while arranging, width and all, rather than fading in place: arranging is for
 * the star, and a row that keeps an invisible slot beyond it puts the stars of downloadable sets
 * one notch left of the rest. A column of controls that do not line up reads as a bug.
 *
 * The slot's width once bore the blame for the arranging toggle's bounce and was innocent -- that
 * was a start padding stepping 16dp to 4dp in a single frame. See `ArrangingMotionTest`.
 */
@Composable
private fun DownloadButton(name: String, onDownload: () -> Unit) {
	IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
		Icon(
			imageVector = AppIcons.Download,
			contentDescription = "Download $name",
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.size(20.dp),
		)
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
 * One rendition's download record as a ring's worth of knowledge.
 *
 * A null record is [Coverage.Unknown] and draws nothing. Absent is not the same as zero: art
 * arrives by browsing too and that is not tracked, so this under-claims rather than over-claims.
 *
 * "Downloaded", not "available" -- the image cache is an LRU and the OS may purge it, so the record
 * says what came down rather than promising what is still there.
 */
private fun ImageDownloadRecord?.toCoverage(): Coverage = when {
	this == null -> Coverage.Unknown
	isComplete -> Coverage.Complete
	else -> Coverage.Partial(percent)
}

/**
 * The set's code, on a rounded tab lying over the top-left of its row.
 *
 * The code is identity, and putting it up here takes it off the line below, which then carries the
 * languages, the size and the date without running out of room on a phone. The tab's colour is the
 * set's own, so a scan down the left edge of the list reads as codes rather than as decoration --
 * see [setColour].
 *
 * Overlapping rather than flush: the card keeps all four of its own corners and this is a second
 * rounded shape laid over one of them, which is the arrangement the project owner drew.
 */
@Composable
private fun SetTab(code: String, modifier: Modifier = Modifier) {
	val vTint = setColour(code)
	Box(
		modifier = modifier
			.padding(start = SET_TAB_INSET)
			.height(SET_TAB_HEIGHT)
			.clip(RoundedCornerShape(SET_TAB_RADIUS))
			.background(vTint)
			.padding(horizontal = 10.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(
			// Six, for the reason recorded on `SetMonogram`: One Piece's codes are five characters
			// and Pokemon has `sv08.5`, and a shorter cap silently renames a set.
			text = code.take(6),
			style = MaterialTheme.typography.labelSmall,
			fontWeight = FontWeight.Bold,
			maxLines = 1,
			softWrap = false,
			// Against the set's own colour, which is fixed at mid-lightness by `setColour` so that
			// this one foreground works for every hue it can produce.
			color = Color.Black.copy(alpha = 0.82f),
		)
	}
}

/** The tab starts a little in from the edge, the way a divider's does. */
private val SET_TAB_INSET = 12.dp

private val SET_TAB_HEIGHT = 21.dp

private val SET_TAB_RADIUS = 7.dp

/**
 * How far the card is pushed down to make room for the tab.
 *
 * Less than the tab's height, and the difference is the overlap: the tab stands
 * `SET_TAB_HEIGHT - SET_TAB_OVERHANG` deep into the card.
 */
private val SET_TAB_OVERHANG = 13.dp

/**
 * A set's own symbol, where its provider publishes one.
 *
 * Three of the sources supply real artwork -- Scryfall a symbol for all 988 of its paper sets,
 * TCGdex a logo for 157 of its 218, YGOPRODeck box art for many of its. The rest publish nothing at
 * all, and so do the sets those three skip.
 *
 * Nothing is drawn for those, not even an empty tile: a slot reserved for a picture that does not
 * exist is a column of holes down the list. The set's colour is on the row itself now -- see the
 * hatch in [SetRow] -- so a row without a symbol is still not an undifferentiated grey block.
 *
 * The slot *is* held while a symbol that really exists is loading or has failed, because there the
 * space belongs to something.
 */
@Composable
private fun SetMark(set: CardSet) {
	val vSymbol = set.symbol ?: return

	Box(
		// Fixed height, width from the picture. The sources' marks are not one shape: Scryfall's
		// set symbols are square glyphs and TCGdex's logos are wide wordmarks, and a square slot
		// letterboxed the second kind down to a fraction of the space it was given. The slot is at
		// least square, so a square symbol is drawn at full size and a row whose symbol has not
		// loaded yet does not start its name against the edge.
		//
		// The gap to the name travels with the mark, so a row without one closes up rather than
		// starting its name 12dp in from nothing.
		modifier = Modifier
			.padding(end = 12.dp)
			.height(SET_MARK_HEIGHT)
			.widthIn(min = SET_MARK_HEIGHT, max = SET_MARK_MAX_WIDTH),
		contentAlignment = Alignment.Center,
	) {
		SubcomposeAsyncImage(
			model = vSymbol.url,
			contentDescription = null,
			contentScale = ContentScale.Fit,
			// Height only: the width is left to the painter's own ratio, capped by the box.
			modifier = Modifier.fillMaxHeight().padding(2.dp),
			// A monochrome glyph has no colour of its own -- Scryfall's SVGs carry no `fill` and
			// default to black, invisible against the dark theme -- so it is drawn in the theme's
			// foreground. Full-colour artwork is never recoloured.
			colorFilter = if (vSymbol.isMonochrome) {
				ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
			} else {
				null
			},
			// Nothing while it loads or after it fails. The slot is the right size either way, and
			// the row already identifies its set by its tab and its hatch.
			loading = {},
			error = {},
		)
	}
}

/**
 * The set's own colour, brushed diagonally across its row.
 *
 * This is where the set's colour lives now that there is no monogram tile. It does the job the tile
 * did -- a scrolled list of several hundred Magic sets is not a wall of identical grey rectangles --
 * without spending 56dp of a phone's width on a decoration, and without reserving a slot for a
 * picture most providers do not publish.
 *
 * Faint on purpose. It sits behind the set's name, and a texture that competes with the text it is
 * under has stopped being a background.
 */
private fun DrawScope.drawSetHatch(tint: Color, strength: Float) {
	val vStep = ROW_HATCH_STEP.toPx()
	drawRect(color = tint.copy(alpha = ROW_TINT_ALPHA * strength))
	// From a row's height to the left of it, so the first stripe crosses the leading edge rather
	// than starting inside it.
	var vX = -size.height
	while (vX < size.width) {
		drawLine(
			color = tint.copy(alpha = ROW_HATCH_ALPHA * strength),
			start = Offset(vX, size.height),
			end = Offset(vX + size.height, 0f),
			strokeWidth = vStep / 3f,
		)
		vX += vStep
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
 * "352 cards · Oct 2025", with each part dropped when the provider does not supply it.
 *
 * The code used to lead this line and is now on the row's tab -- see [SetTab]. One place, not two:
 * printing it here as well put the same six characters twice on every row.
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
	confirmedCardCount: Int? = null,
): String = buildList {
	(confirmedCardCount ?: set.cardCount)?.let { add(if (it == 1) "1 card" else "$it cards") }
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
			// Scanned, so the rows may offer a download -- see `UiState.isDownloadStateKnown`.
			isDownloadStateKnown = true,
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
/**
 * How wide a set's mark may get before it is scaled down.
 *
 * The slot is at least [SET_MARK_HEIGHT] square and grows with the picture's own ratio up to this,
 * so TCGdex's wide wordmarks are drawn wide and Scryfall's square symbols are drawn square. Capped,
 * because a very wide logo would otherwise take the name's width.
 */
private val SET_MARK_MAX_WIDTH = 76.dp

private val SET_MARK_HEIGHT = 44.dp

/** Close enough to read as a texture, far enough apart to read as stripes. */
private val ROW_HATCH_STEP = 11.dp

/** Faint. It is under the set's name, and a background that competes with text is not one. */
private const val ROW_TINT_ALPHA = 0.07f

private const val ROW_HATCH_ALPHA = 0.08f

/** How much of the hatch survives on the light theme. */
private const val LIGHT_HATCH_SCALE = 0.55f

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

