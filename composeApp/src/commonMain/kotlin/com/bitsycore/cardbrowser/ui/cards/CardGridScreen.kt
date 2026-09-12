package com.bitsycore.cardbrowser.ui.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import com.bitsycore.cardbrowser.data.download.DownloadJob
import com.bitsycore.cardbrowser.data.download.DownloadManager
import com.bitsycore.cardbrowser.ui.downloads.DownloadsButton
import org.koin.compose.koinInject
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed as itemsIndexedInColumn
import androidx.compose.foundation.lazy.grid.itemsIndexed
import com.bitsycore.cardbrowser.ui.common.arrowSelection
import com.bitsycore.cardbrowser.data.settings.CardRowHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import com.bitsycore.cardbrowser.data.settings.CardViewMode
import androidx.compose.runtime.mutableStateOf
import com.bitsycore.cardbrowser.data.settings.CardTileSize
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.Composable
import com.bitsycore.cardbrowser.ui.common.sharedSetContainer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.core.filter.CardFilterEngine
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardOrientation
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.provider.CardFilterField
import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.ui.browse.BrowseSession
import com.bitsycore.cardbrowser.ui.common.CardImage
import com.bitsycore.cardbrowser.ui.common.EmptyState
import com.bitsycore.cardbrowser.ui.common.ErrorState
import com.bitsycore.cardbrowser.ui.common.ImageVariant
import com.bitsycore.cardbrowser.ui.common.LoadingState
import com.bitsycore.cardbrowser.data.repository.LanguageSubstitution
import com.bitsycore.cardbrowser.ui.common.LanguageMenu
import com.bitsycore.cardbrowser.ui.common.NoticeBanner
import com.bitsycore.cardbrowser.ui.common.DomainChip
import com.bitsycore.cardbrowser.ui.common.StatChip
import com.bitsycore.cardbrowser.ui.common.sharedCardArt
import com.bitsycore.cardbrowser.core.game.GameProfile
import androidx.compose.foundation.layout.FlowRow
import com.bitsycore.cardbrowser.ui.preview.PreviewData
import com.bitsycore.cardbrowser.ui.preview.PreviewFrame
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import com.bitsycore.lib.pulse.compose.collectEffect
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.viewmodel.koinViewModel
import com.bitsycore.cardbrowser.ui.common.AppIcons

/**
 * One set's cards, as a grid of readable card images.
 *
 * A tile per distinct printing, which for Riftcodex means a tile per artwork: alternate art,
 * overnumbered and signature versions each get their own, because each is a different picture. What
 * does *not* multiply tiles is finish or language -- those are choices inside the detail screen, and
 * duplicating a tile for them would triple a set with nothing new to look at.
 */
@Composable
fun CardGridScreen(
	setId: String,
	setName: String,
	setCode: String,
	onBack: () -> Unit,
	onOpenCard: (CardPrinting) -> Unit,
	onOpenDownloads: () -> Unit = {},
	viewModel: CardGridViewModel = koinViewModel(),
) {
	// Application-scoped, so a download started from the set list is still running while its set
	// is being browsed -- which is the common case, and was the one place it became invisible.
	val vJobs by koinInject<DownloadManager>().jobs.collectAsState()
	val vSnackbarHost = remember { SnackbarHostState() }

	// A language that turns out to have nothing for this set has to say so. Silence would read as
	// a tap that did not register, and the grid would still be showing the old edition.
	viewModel.collectEffect { vEffect ->
		when (vEffect) {
			is CardGridContract.Effect.LanguageUnavailable ->
				vSnackbarHost.showSnackbar(vEffect.reason)

			CardGridContract.Effect.NavigateBack -> onBack()

			is CardGridContract.Effect.OpenCard -> onOpenCard(vEffect.card)

			CardGridContract.Effect.OpenDownloads -> onOpenDownloads()
		}
	}
	val vState by viewModel.collectAsStateWithLifecycle()

	// Dispatched once per set. Keyed on setId so reusing this view model for a different set
	// re-runs it, and so returning from detail does not restart the load.
	LaunchedEffect(setId) {
		if (vState.setId != setId) {
			viewModel.dispatch(CardGridContract.Intent.SetSelected(setId, setName, setCode))
		}
	}

	// Read here rather than inside the content, which has to stay free of Koin so it can be
	// previewed. A preview has no Koin graph at all and `koinInject` throws outright.
	val vFocusedCardId by koinInject<BrowseSession>().focusedCardId.collectAsState()

	// The other half of the container transform out of the set list's row. Wrapped here rather than
	// applied inside `CardGridContent`, so the content stays a pure function of its arguments and
	// keeps previewing -- the modifier reads navigation composition locals that a preview has not
	// got, and this screen is the layer that already knows about navigation.
	// `expandsFromCorner` is the set row's own radius -- `MaterialTheme.shapes.medium`, which is what
	// a Material 3 `Card` uses. The container starts that round and squares off as it fills the
	// screen, so the row visibly becomes the screen rather than being swapped for it.
	Box(Modifier.fillMaxSize().sharedSetContainer(setId, expandsFromCorner = SET_ROW_CORNER)) {
		CardGridContent(
			snackbarHostState = vSnackbarHost,
			state = vState,
			dispatch = viewModel::dispatch,
			fallbackSetName = setName,
			focusedCardId = vFocusedCardId,
			downloads = vJobs,
		)
	}
}

/**
 * The card grid, given a state and somewhere to send intents.
 *
 * @param fallbackSetName shown until the loaded state carries a name of its own, so the bar is
 *   never briefly blank on the way in
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardGridContent(
	state: CardGridContract.UiState,
	dispatch: (CardGridContract.Intent) -> Unit,
	fallbackSetName: String = "",
	/** The card the detail screen last showed, so returning scrolls it back into view. */
	focusedCardId: String? = null,
	/** The queue, passed in rather than injected, so this composable and its previews need no Koin. */
	downloads: List<DownloadJob> = emptyList(),
	/** Hoisted so the screen can post to it from an effect. A preview passes a fresh, unused one. */
	snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
	val vState = state
	val setName = fallbackSetName

	val vGridState = rememberLazyGridState(initialFirstVisibleItemIndex = vState.firstVisibleIndex)
	val vListState = rememberLazyListState(initialFirstVisibleItemIndex = vState.firstVisibleIndex)

	// The keyboard's cursor, held here rather than inside either renderer: it is a position in the
	// *set*, and switching between the grid and the list should not lose your place in it.
	var vSelected by remember { mutableIntStateOf(0) }
	// See the game picker: no cursor until something navigates.
	var vCursorVisible by remember { mutableStateOf(false) }
	LaunchedEffect(vState.cards.size) {
		vSelected = vSelected.coerceIn(0, (vState.cards.size - 1).coerceAtLeast(0))
	}
	// Only while there *is* a cursor. Without the guard this ran on arrival with a selection of 0
	// and scrolled a restored grid back to the top, which is what every back navigation did.
	LaunchedEffect(vSelected, vState.viewMode, vCursorVisible) {
		if (!vCursorVisible) return@LaunchedEffect
		if (vState.viewMode == CardViewMode.LIST) {
			if (vListState.layoutInfo.visibleItemsInfo.none { it.index == vSelected }) {
				vListState.animateScrollToItem(vSelected)
			}
		} else if (vGridState.layoutInfo.visibleItemsInfo.none { it.index == vSelected }) {
			vGridState.animateScrollToItem(vSelected)
		}
	}

	// Coming back from a card that was swiped to rather than tapped, the grid may be nowhere near it.
	// Scrolling it into view is worth doing for its own sake, and the shared-element transition needs
	// it: a lazy grid only composes what is visible, so a tile that is not on screen is not there for
	// the artwork to fly back to.
	LaunchedEffect(focusedCardId, vState.cards, vState.viewMode) {
		val vTarget = vState.cards.indexOfFirst { it.id.qualified == focusedCardId }
		if (vTarget < 0) return@LaunchedEffect
		// Whichever renderer is on screen. The list used to be left out, so returning to it in list
		// mode left the row uncomposed and the artwork had nothing to fly back to.
		//
		// Not animated: this happens while the screen is off-screen or arriving, and a scroll
		// animation racing the transition is exactly the kind of thing that looks broken.
		if (vState.viewMode == CardViewMode.LIST) {
			if (vListState.layoutInfo.visibleItemsInfo.none { it.index == vTarget }) {
				vListState.scrollToItem(vTarget)
			}
		} else if (vGridState.layoutInfo.visibleItemsInfo.none { it.index == vTarget }) {
			vGridState.scrollToItem(vTarget)
		}
	}

	// Scroll position is kept in the view model rather than only in the grid state, so it survives
	// the trip into card detail and back even though this composable leaves the composition.
	LaunchedEffect(vGridState) {
		snapshotFlow { vGridState.firstVisibleItemIndex }
			.distinctUntilChanged()
			.collect { dispatch(CardGridContract.Intent.ScrollPositionChanged(it)) }
	}

	// `enterAlways`, not `exitUntilCollapsed`.
	//
	// Both collapse on the way down, but `exitUntilCollapsed` only expands again once the list is
	// scrolled back to the very top -- so after a long scroll the bar stays shrunk however far up
	// you swipe, which does not feel like Material and does not feel responsive. `enterAlways`
	// brings it back on any upward scroll, which is the behaviour every Material app has.
	val vScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

	// Opening the search expands the bar it lives in.
	//
	// The field is drawn under the app bar and hidden once the bar has collapsed past halfway --
	// otherwise it would overlap the grid. That is right while scrolling and wrong the moment the
	// search button is pressed: after a long scroll the bar is collapsed, so tapping search set the
	// state and showed nothing at all, which reads as a dead button. Expanding the bar puts the
	// field where the press was asking for it.
	LaunchedEffect(vState.isSearchOpen) {
		if (vState.isSearchOpen) {
			vScrollBehavior.state.heightOffset = 0f
			vScrollBehavior.state.contentOffset = 0f
		}
	}

	Scaffold(
		modifier = Modifier.nestedScroll(vScrollBehavior.nestedScrollConnection),
		snackbarHost = { SnackbarHost(snackbarHostState) },
		topBar = {
			// A surface, not a bare Column. The app bar paints its own background but the controls
			// stacked under it do not, so the grid scrolling underneath showed straight through the
			// search field and the filter chips and made them unreadable.
			Surface(color = MaterialTheme.colorScheme.surface) {
			Column {
				MediumTopAppBar(
					title = {
						Column {
							Text(
								text = vState.setName.ifBlank { setName },
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
							Text(
								text = vState.countLabel,
								style = MaterialTheme.typography.labelSmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					},
					navigationIcon = {
						IconButton(onClick = { dispatch(CardGridContract.Intent.BackPressed) }) {
							Icon(AppIcons.ArrowBack, contentDescription = "Back to sets")
						}
					},
					actions = {
						DownloadsButton(
						jobs = downloads,
						onClick = { dispatch(CardGridContract.Intent.DownloadsRequested) },
					)
						// Only where there is a choice to make -- see `UiState.hasLanguageChoice`.
						if (vState.hasLanguageChoice) {
							LanguageMenu(
								options = vState.languageOptions,
								selected = vState.language,
								isBusy = vState.isChangingLanguage,
								isChecking = vState.isConfirmingLanguages,
								checkFailed = vState.languageCheckFailed,
								onSelect = {
									dispatch(CardGridContract.Intent.LanguageSelected(it))
								},
								onOpened = {
									dispatch(CardGridContract.Intent.LanguageOptionsRequested)
								},
							)
						}
						// Search is a button beside filters rather than a field permanently occupying a
						// strip of a screen whose whole job is showing pictures.
						IconButton(
							onClick = {
								dispatch(
									CardGridContract.Intent.SearchToggled(!vState.isSearchOpen),
								)
							},
						) {
							Icon(
								imageVector = if (vState.isSearchOpen) {
									AppIcons.SearchOff
								} else {
									AppIcons.Search
								},
								contentDescription = if (vState.isSearchOpen) "Hide search" else "Search",
								// Tinted while a search is active, so a hidden field is never a hidden filter.
								tint = if (!vState.query.text.isNullOrBlank()) {
									MaterialTheme.colorScheme.primary
								} else {
									LocalContentColor.current
								},
							)
						}
						BadgedBox(
							badge = {
								if (vState.activeFilterCount > 0) {
									Badge { Text("${vState.activeFilterCount}") }
								}
							},
						) {
							IconButton(
								onClick = {
									dispatch(CardGridContract.Intent.FilterSheetToggled(true))
								},
							) {
								Icon(AppIcons.FilterList, contentDescription = "Filters")
							}
						}
						ViewModeButton(
							mode = vState.viewMode,
							rowHeight = vState.rowHeight,
							tileSize = vState.tileSize,
							onModeChanged = {
								dispatch(CardGridContract.Intent.ViewModeChanged(it))
							},
							onRowHeightChanged = {
								dispatch(CardGridContract.Intent.RowHeightChanged(it))
							},
							onTileSizeChanged = {
								dispatch(CardGridContract.Intent.TileSizeChanged(it))
							},
						)
					},
					scrollBehavior = vScrollBehavior,
				)

				// Folds away with the bar. Scrolling down is a request for more grid, and a search
				// field that stays behind while the bar it belongs to collapses reads as a leftover.
				// The query itself is untouched -- it survives as a chip and the field returns on the
				// way back up.
				AnimatedVisibility(
					visible = vState.isSearchOpen && vScrollBehavior.state.collapsedFraction < 0.5f,
				) {
					SearchField(
						text = vState.query.text.orEmpty(),
						onTextChanged = { vText ->
							dispatch(
								CardGridContract.Intent.QueryChanged(
									vState.query.copy(text = vText.takeIf { it.isNotBlank() }),
								),
							)
						},
					)
				}

				// Controls, not content: these stay put while the grid scrolls underneath.
				ActiveFilterChips(
					state = vState,
					onQueryChanged = { dispatch(CardGridContract.Intent.QueryChanged(it)) },
					onClearAll = { dispatch(CardGridContract.Intent.ClearFilters) },
				)

				// Above the coverage notice, because it explains something about the whole
				// screen rather than about how much of the set is on it.
				//
				// Two substitutions, two sentences, and only one of them has anything to offer:
				// a language that is merely not downloaded can be fetched, and one the source
				// does not publish cannot. Saying "not downloaded" about a printing that does
				// not exist would send the user after a card nobody has.
				vState.languageSubstitutedFor?.let { vWanted ->
					val vShowing = vState.language?.displayName.orEmpty()
					when (vState.languageSubstitution) {
						LanguageSubstitution.NOT_DOWNLOADED -> NoticeBanner(
							text = "Showing $vShowing — ${vWanted.displayName} is not downloaded.",
							actionLabel = "Fetch ${vWanted.displayName}",
							// The same intent the language menu dispatches, so "fetch it after
							// all" and "choose it from the menu" are one code path rather than
							// two that could come to disagree about what switching means.
							onAction = {
								dispatch(CardGridContract.Intent.LanguageSelected(vWanted))
							},
						)

						LanguageSubstitution.NOT_PUBLISHED -> NoticeBanner(
							text = "No ${vWanted.displayName} edition of this set. Showing $vShowing.",
						)

						null -> Unit
					}
				}

				vState.coverageNotice?.let { vNotice ->
					NoticeBanner(
						text = vNotice,
						onAction = if (vState.noticeIsRetryable) {
							{ dispatch(CardGridContract.Intent.Load) }
						} else {
							null
						},
					)
				}
			}
			}
		},
	) { vPadding ->
		Box(Modifier.fillMaxSize()) {
			when {
				vState.isInitialLoad -> LoadingState(Modifier.padding(vPadding))

				vState.cards.isEmpty() && vState.error != null -> ErrorState(
					error = vState.error,
					onRetry = { dispatch(CardGridContract.Intent.Load) },
					modifier = Modifier.padding(vPadding),
				)

				vState.isEmptyAfterFilter -> EmptyState(
					message = if (vState.query.isEmpty) {
						"This set has no cards."
					} else {
						"No card matches these filters."
					},
					modifier = Modifier.padding(vPadding),
				)

				// The scaffold's insets go to the grid as *content padding* rather than as a margin
				// around it, which is what lets cards scroll up underneath the bar rather than
				// stopping dead at its edge.
				vState.viewMode == CardViewMode.LIST -> CardList(
					cards = vState.cards,
					listState = vListState,
					rowHeight = vState.rowHeight,
					// The denominator for "4/352". Null where the set's size is unknown, which the
					// row prints as a bare number rather than inventing one.
					knownSetSize = vState.knownSetSize,
					game = vState.game,
					onOpenCard = { dispatch(CardGridContract.Intent.CardOpened(it)) },
					contentPadding = vPadding,
					selected = vSelected,
					isCursorVisible = vCursorVisible,
					onSelect = { vSelected = it },
					// The cursor appears where the list already is, not at the top.
					onKeyboardUsed = {
						if (!vCursorVisible) {
							vSelected = vListState.firstVisibleItemIndex
							vCursorVisible = true
						}
					},
					canTakeFocus = !vState.isSearchOpen,
				)

				else -> CardGrid(
					cards = vState.cards,
					gridState = vGridState,
					tileSize = vState.tileSize,
					onOpenCard = { dispatch(CardGridContract.Intent.CardOpened(it)) },
					contentPadding = vPadding,
					selected = vSelected,
					isCursorVisible = vCursorVisible,
					onSelect = { vSelected = it },
					onKeyboardUsed = {
						if (!vCursorVisible) {
							vSelected = vGridState.firstVisibleItemIndex
							vCursorVisible = true
						}
					},
					canTakeFocus = !vState.isSearchOpen,
				)
			}
		}
	}

	if (vState.isFilterSheetOpen) {
		// `rememberBottomSheetState`, not the deprecated `rememberModalBottomSheetState`. The
		// states the sheet may take are now listed rather than expressed as a skip flag, and
		// omitting `PartiallyExpanded` is what `skipPartiallyExpanded = true` used to say: this
		// sheet is a filter panel, and a half-height filter panel is a worse filter panel.
		// The second argument is the set of states the sheet may take, and leaving
		// `PartiallyExpanded` out of it is what `skipPartiallyExpanded = true` used to say. Passed
		// positionally because the parameter is unnamed in the published API.
		val vSheetState = rememberBottomSheetState(
			SheetValue.Hidden,
			setOf(SheetValue.Hidden, SheetValue.Expanded),
		)
		ModalBottomSheet(
			onDismissRequest = { dispatch(CardGridContract.Intent.FilterSheetToggled(false)) },
			sheetState = vSheetState,
		) {
			FilterSheet(
				state = vState,
				onQueryChanged = { dispatch(CardGridContract.Intent.QueryChanged(it)) },
				onClearAll = { dispatch(CardGridContract.Intent.ClearFilters) },
			)
		}
	}
}

/**
 * The grid itself.
 *
 * Columns are chosen from the available width rather than fixed, so a phone gets two or three and a
 * desktop window gets as many as fit. `Adaptive` with a minimum tile width is what keeps a card
 * image readable at every size, which is the whole job of this screen.
 */
@Composable
private fun CardGrid(
	cards: List<CardPrinting>,
	gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
	onOpenCard: (CardPrinting) -> Unit,
	contentPadding: PaddingValues,
	/**
	 * Whether the grid may take the focus.
	 *
	 * False while the search field is open, which has a better claim on it: the field focuses
	 * itself as it appears, and a grid that grabbed the focus back would eat the first letter typed.
	 */
	tileSize: CardTileSize,
	selected: Int,
	isCursorVisible: Boolean,
	onSelect: (Int) -> Unit,
	onKeyboardUsed: () -> Unit = {},
	canTakeFocus: Boolean = true,
) {
	// How many tiles are on a row, read off the grid rather than recomputed from the window.
	//
	// The columns are `Adaptive`, so the number depends on the width the grid actually got --
	// which only the grid knows. Counting the items laid out at the same vertical offset is that
	// number. One until the first layout, which is the right answer for an empty grid too.
	val vColumns = remember(gridState.layoutInfo) {
		gridState.layoutInfo.visibleItemsInfo
			.groupBy { it.offset.y }
			.maxOfOrNull { it.value.size }
			?: 1
	}

	LazyVerticalGrid(
		columns = GridCells.Adaptive(minSize = tileSize.minWidth),
		state = gridState,
		contentPadding = PaddingValues(
			start = TILE_GAP,
			end = TILE_GAP,
			top = contentPadding.calculateTopPadding() + TILE_GAP,
			bottom = contentPadding.calculateBottomPadding() + TILE_GAP,
		),
		horizontalArrangement = Arrangement.spacedBy(10.dp),
		verticalArrangement = Arrangement.spacedBy(14.dp),
		modifier = Modifier.arrowSelection(
			count = cards.size,
			selected = selected,
			isCursorVisible = isCursorVisible,
			onSelect = onSelect,
			// A grid, so up and down move by a row and left and right by one tile.
			columns = vColumns,
			onActivate = { cards.getOrNull(selected)?.let(onOpenCard) },
			onKeyboardUsed = onKeyboardUsed,
			takeFocus = canTakeFocus,
		),
	) {
		itemsIndexed(cards, key = { _, vCard -> vCard.id.qualified }) { vIndex, vCard ->
			CardTile(
				card = vCard,
				isSelected = isCursorVisible && vIndex == selected,
				onClick = { onOpenCard(vCard) },
			)
		}
	}
}

/**
 * The set as a list: a thumbnail, what the card is called, and the facts worth scanning for.
 *
 * A different job from the grid rather than a smaller version of it. The grid is for looking at
 * art; this is for finding a card you can already name, or reading a set in collector order -- so
 * it says the number, the rarity and the domain, which the grid can only show by being opened.
 *
 * Everything except the thumbnail is text, so a taller row buys a bigger picture and nothing else.
 * That is why [CardRowHeight] scales the image and leaves the type scale alone: making the words
 * bigger too would be three sizes of the same list rather than three amounts of card.
 */
@Composable
private fun CardList(
	cards: List<CardPrinting>,
	listState: androidx.compose.foundation.lazy.LazyListState,
	rowHeight: CardRowHeight,
	knownSetSize: Int?,
	game: GameProfile?,
	onOpenCard: (CardPrinting) -> Unit,
	contentPadding: PaddingValues,
	selected: Int,
	isCursorVisible: Boolean,
	onSelect: (Int) -> Unit,
	onKeyboardUsed: () -> Unit,
	canTakeFocus: Boolean,
) {
	LazyColumn(
		state = listState,
		contentPadding = PaddingValues(
			start = TILE_GAP,
			end = TILE_GAP,
			top = contentPadding.calculateTopPadding() + TILE_GAP,
			bottom = contentPadding.calculateBottomPadding() + TILE_GAP,
		),
		verticalArrangement = Arrangement.spacedBy(6.dp),
		modifier = Modifier.arrowSelection(
			count = cards.size,
			selected = selected,
			isCursorVisible = isCursorVisible,
			onSelect = onSelect,
			onActivate = { cards.getOrNull(selected)?.let(onOpenCard) },
			onKeyboardUsed = onKeyboardUsed,
			takeFocus = canTakeFocus,
		),
	) {
		itemsIndexedInColumn(cards, key = { _, vCard -> vCard.id.qualified }) { vIndex, vCard ->
			CardRow(
				card = vCard,
				rowHeight = rowHeight,
				knownSetSize = knownSetSize,
				isSelected = isCursorVisible && vIndex == selected,
				game = game,
				onClick = { onOpenCard(vCard) },
			)
		}
	}
}

/** One card in [CardList]. */
@Composable
private fun CardRow(
	card: CardPrinting,
	rowHeight: CardRowHeight,
	knownSetSize: Int?,
	isSelected: Boolean,
	/** For a domain's own label and colour; `null` draws the raw key in the plain chip. */
	game: GameProfile?,
	onClick: () -> Unit,
) {
	val vImageHeight = when (rowHeight) {
		CardRowHeight.COMPACT -> 40.dp
		CardRowHeight.REGULAR -> 64.dp
		CardRowHeight.TALL -> 96.dp
	}
	Card(
		onClick = onClick,
		modifier = Modifier
			.fillMaxWidth()
			.then(
				if (isSelected) {
					Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CardDefaults.shape)
				} else {
					Modifier
				},
			),
	) {
		Row(
			modifier = Modifier.padding(8.dp).fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			CardImage(
				artwork = card.artwork,
				contentDescription = null,
				variant = ImageVariant.THUMBNAIL,
				contentScale = ContentScale.Crop,
				modifier = Modifier
					.height(vImageHeight)
					// The card's own ratio, so a landscape card is a landscape thumbnail and the
					// row does not crop it to a portrait window.
					.aspectRatio(
						if (card.orientation == CardOrientation.LANDSCAPE) {
							1039f / 744f
						} else {
							744f / 1039f
						},
					)
					// The other half of the detail screen's picture, exactly as the tile is:
					// without this, opening a card from the list had nothing to grow from.
					.sharedCardArt(card.id.qualified)
					.clip(RoundedCornerShape(4.dp)),
			)
			Spacer(Modifier.width(12.dp))
			Column(Modifier.weight(1f)) {
				Text(
					text = card.displayName,
					style = MaterialTheme.typography.bodyLarge,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				// The number as text, the rest as the same chips the detail screen draws: a
				// domain is a game's word with a game's colour, and "fury" printed raw was
				// neither. Dense, because a row is one line rather than a paragraph's width.
				Spacer(Modifier.height(2.dp))
				FlowRow(
					horizontalArrangement = Arrangement.spacedBy(4.dp),
					verticalArrangement = Arrangement.spacedBy(2.dp),
					// A compact row is one line of detail by definition.
					maxLines = if (rowHeight == CardRowHeight.COMPACT) 1 else 2,
				) {
					Text(
						text = collectorLabel(card, knownSetSize),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						// On the pills' centre line rather than on their top edge: it is the same
						// row of facts, and text sitting high against a chip reads as misaligned.
						modifier = Modifier.align(Alignment.CenterVertically),
					)
					card.classification.rarity?.takeIf { it.isNotBlank() }?.let {
						StatChip(it, dense = true)
					}
					card.classification.domains.forEach { DomainChip(it, game, dense = true) }
					// Type and supertype, as the detail screen draws them: "Unit", "Legend". On a
					// compact row they are what the single line drops first, which is the right
					// order to lose things in -- the name and the number matter more.
					card.classification.type?.takeIf { it.isNotBlank() }?.let {
						StatChip(it, dense = true)
					}
					card.classification.supertype?.takeIf { it.isNotBlank() }?.let {
						StatChip(it, dense = true)
					}
				}
			}
		}
	}
}

/**
 * The card's number, over the set's size where that is known.
 *
 * `4/352` and not `4` -- a collector number means little without the denominator. The denominator
 * is omitted rather than guessed when the set size is unknown, which is the same rule the grid's
 * count label follows: two numbers either side of a slash must count the same population.
 */
private fun collectorLabel(card: CardPrinting, knownSetSize: Int?): String =
	if (knownSetSize != null && knownSetSize > 0) {
		"${card.collectorNumber}/$knownSetSize"
	} else {
		card.collectorNumber
	}

/** Between the facts on a row's second line. */
private const val SEPARATOR = "  ·  "

/**
 * The search field, shown only while the search button is on.
 *
 * Focused as it appears, because a field that opens and then waits to be tapped costs the user the
 * gesture they already made.
 */
@Composable
private fun SearchField(text: String, onTextChanged: (String) -> Unit) {
	val vFocusRequester = remember { FocusRequester() }
	LaunchedEffect(Unit) { vFocusRequester.requestFocus() }

	OutlinedTextField(
		value = text,
		onValueChange = onTextChanged,
		label = { Text("Name or collector number") },
		leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
		trailingIcon = {
			if (text.isNotEmpty()) {
				IconButton(onClick = { onTextChanged("") }) {
					Icon(AppIcons.Close, contentDescription = "Clear search")
				}
			}
		},
		singleLine = true,
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 8.dp)
			.focusRequester(vFocusRequester),
	)
}

/** One tile: the art, then the name, collector number and whatever label distinguishes it. */
@Composable
private fun CardTile(card: CardPrinting, onClick: () -> Unit, isSelected: Boolean = false) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			// The keyboard's cursor. Drawn around the tile rather than over the art, so it never
			// obscures the card it is pointing at.
			.then(
				if (isSelected) {
					Modifier.border(
						width = 2.dp,
						color = MaterialTheme.colorScheme.primary,
						shape = RoundedCornerShape(10.dp),
					)
				} else {
					Modifier
				},
			)
			.clickable(onClick = onClick),
	) {
		CardImage(
			artwork = card.artwork,
			contentDescription = card.displayName,
			variant = ImageVariant.THUMBNAIL,
			contentScale = ContentScale.Crop,
			modifier = Modifier
				.fillMaxWidth()
				// Riftbound cards are 744x1039, and a landscape card is that turned over. Using the
				// real ratio means the grid does not jump as images resolve.
				.aspectRatio(if (card.orientation == CardOrientation.LANDSCAPE) 1039f / 744f else 744f / 1039f)
				// The other half of this is the large image on the detail screen: tapping the tile
				// grows this exact picture into that one.
				.sharedCardArt(card.id.qualified)
				.clip(RoundedCornerShape(6.dp))
				.background(MaterialTheme.colorScheme.surfaceVariant),
		)
		Spacer(Modifier.height(4.dp))
		Text(
			text = card.displayName,
			style = MaterialTheme.typography.labelMedium,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		Text(
			text = tileSubtitle(card),
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

/** "042 · Epic · Alternate art", with anything the provider did not state left out. */
private fun tileSubtitle(card: CardPrinting): String = buildList {
	add(card.collectorNumber)
	card.classification.rarity?.let { add(it) }
	if (card.artwork.treatment != ArtworkTreatment.STANDARD) add(card.artwork.treatment.displayName)
}.joinToString(" · ")

/** Wide enough that a card's name and art stay legible on a phone. */
/**
 * The narrowest a tile may be, per size step.
 *
 * A *minimum* rather than a count, because the grid's columns are adaptive: 108dp gives three
 * across on a phone and seven in a desktop window, and the same setting keeps meaning the same
 * thing on both. 108 was the single value before there were three, so it is the middle one.
 */
private val CardTileSize.minWidth: Dp
	get() = when (this) {
		CardTileSize.SMALL -> 76.dp
		CardTileSize.MEDIUM -> 108.dp
		CardTileSize.LARGE -> 164.dp
	}

/** The gap between tiles, and the margin around the grid. */
private val TILE_GAP = 12.dp

// ==================
// MARK: Previews
// ==================

private fun previewGridState(
	cards: List<CardPrinting> = PreviewData.CARDS,
	query: CardQuery = CardQuery(),
	isLoading: Boolean = false,
	isCompleteSet: Boolean = true,
	cachedCardCount: Int = PreviewData.CARDS.size,
	knownSetSize: Int? = PreviewData.CARDS.size,
	error: ProviderError? = null,
) = CardGridContract.UiState(
	setId = "riftcodex:OGN",
	setName = "Origins",
	setCode = "OGN",
	cards = cards,
	query = query,
	isLoading = isLoading,
	isCompleteSet = isCompleteSet,
	cachedCardCount = cachedCardCount,
	knownSetSize = knownSetSize,
	error = error,
	supportedFilters = setOf(
		CardFilterField.TEXT,
		CardFilterField.DOMAIN,
		CardFilterField.CARD_TYPE,
		CardFilterField.RARITY,
		CardFilterField.COST,
	),
	facets = CardFilterEngine.facetsOf(PreviewData.CARDS),
)

@Preview
@Composable
private fun CardGridPreview() = PreviewFrame {
	CardGridContent(state = previewGridState(), dispatch = {})
}

@Preview
@Composable
private fun CardGridFilteredPreview() = PreviewFrame {
	// Filters on, and only part of the set downloaded -- the state where the screen has to be
	// explicit that the results are not exhaustive.
	CardGridContent(
		state = previewGridState(
			cards = PreviewData.CARDS.take(3),
			query = CardQuery(text = "annie", rarities = setOf("Epic"), domains = setOf("Fury")),
			isCompleteSet = false,
			cachedCardCount = 200,
			knownSetSize = 352,
		),
		dispatch = {},
	)
}

@Preview
@Composable
private fun CardGridEmptyPreview() = PreviewFrame {
	CardGridContent(
		state = previewGridState(
			cards = emptyList(),
			query = CardQuery(rarities = setOf("Showcase")),
			cachedCardCount = 352,
			knownSetSize = 352,
		),
		dispatch = {},
	)
}

@Preview
@Composable
private fun CardGridOfflinePreview() = PreviewFrame(isDark = false) {
	CardGridContent(
		state = previewGridState(error = ProviderError.Offline()),
		dispatch = {},
	)
}

@Preview
@Composable
private fun CardGridLoadingPreview() = PreviewFrame {
	CardGridContent(
		state = previewGridState(cards = emptyList(), isLoading = true, knownSetSize = null),
		dispatch = {},
	)
}

/** The corner radius a set row is drawn with, which the container transform grows out of. */
private val SET_ROW_CORNER = 12.dp

/**
 * Switches between the grid and the list, and picks the list's row height.
 *
 * One control rather than two. The height only means anything in list mode, so a separate button
 * for it would be disabled most of the time -- and a menu that opens on the mode it is *not* in is
 * how a toggle turns into a thing to think about.
 */
@Composable
private fun ViewModeButton(
	mode: CardViewMode,
	rowHeight: CardRowHeight,
	tileSize: CardTileSize,
	onModeChanged: (CardViewMode) -> Unit,
	onRowHeightChanged: (CardRowHeight) -> Unit,
	onTileSizeChanged: (CardTileSize) -> Unit,
) {
	var vIsOpen by remember { mutableStateOf(false) }
	Box {
		IconButton(onClick = { vIsOpen = true }) {
			Icon(
				imageVector = if (mode == CardViewMode.LIST) {
					AppIcons.FormatListBulleted
				} else {
					AppIcons.GridView
				},
				contentDescription = "How cards are shown",
			)
		}
		DropdownMenu(expanded = vIsOpen, onDismissRequest = { vIsOpen = false }) {
			CardViewMode.entries.forEach { vMode ->
				DropdownMenuItem(
					text = { Text(vMode.label) },
					onClick = {
						onModeChanged(vMode)
						vIsOpen = false
					},
					trailingIcon = {
						if (vMode == mode) Icon(AppIcons.Check, contentDescription = null)
					},
				)
			}
			// The size steps for whichever mode is showing. Both modes have three, and only the one
			// in force is offered -- a menu that lists the other mode's sizes is a menu with
			// entries that do nothing.
			HorizontalDivider()
			if (mode == CardViewMode.LIST) {
				CardRowHeight.entries.forEach { vHeight ->
					DropdownMenuItem(
						text = { Text(vHeight.label) },
						onClick = {
							onRowHeightChanged(vHeight)
							vIsOpen = false
						},
						trailingIcon = {
							if (vHeight == rowHeight) {
								Icon(AppIcons.Check, contentDescription = null)
							}
						},
					)
				}
			} else {
				CardTileSize.entries.forEach { vSize ->
					DropdownMenuItem(
						text = { Text(vSize.label) },
						onClick = {
							onTileSizeChanged(vSize)
							vIsOpen = false
						},
						trailingIcon = {
							if (vSize == tileSize) {
								Icon(AppIcons.Check, contentDescription = null)
							}
						},
					)
				}
			}
		}
	}
}

