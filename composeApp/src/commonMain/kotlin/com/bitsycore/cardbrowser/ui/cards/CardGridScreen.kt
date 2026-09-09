package com.bitsycore.cardbrowser.ui.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import com.bitsycore.cardbrowser.ui.common.sharedSetContainer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
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
import com.bitsycore.cardbrowser.core.model.CardLanguage
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
import com.bitsycore.cardbrowser.ui.common.NoticeBanner
import com.bitsycore.cardbrowser.ui.common.sharedCardArt
import com.bitsycore.cardbrowser.ui.preview.PreviewData
import com.bitsycore.cardbrowser.ui.preview.PreviewFrame
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import com.bitsycore.lib.pulse.compose.collectEffect
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

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
	viewModel: CardGridViewModel = koinViewModel(),
) {
	val vSnackbarHost = remember { SnackbarHostState() }

	// A language that turns out to have nothing for this set has to say so. Silence would read as
	// a tap that did not register, and the grid would still be showing the old edition.
	viewModel.collectEffect { vEffect ->
		when (vEffect) {
			is CardGridContract.Effect.LanguageUnavailable ->
				vSnackbarHost.showSnackbar(vEffect.reason)
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
	Box(Modifier.fillMaxSize().sharedSetContainer(setId)) {
		CardGridContent(
			snackbarHostState = vSnackbarHost,
			state = vState,
			dispatch = viewModel::dispatch,
			fallbackSetName = setName,
			focusedCardId = vFocusedCardId,
			onBack = onBack,
			onOpenCard = onOpenCard,
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
	onBack: () -> Unit = {},
	onOpenCard: (CardPrinting) -> Unit = {},
	/** Hoisted so the screen can post to it from an effect. A preview passes a fresh, unused one. */
	snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
	val vState = state
	val setName = fallbackSetName

	val vGridState = rememberLazyGridState(initialFirstVisibleItemIndex = vState.firstVisibleIndex)

	// Coming back from a card that was swiped to rather than tapped, the grid may be nowhere near it.
	// Scrolling it into view is worth doing for its own sake, and the shared-element transition needs
	// it: a lazy grid only composes what is visible, so a tile that is not on screen is not there for
	// the artwork to fly back to.
	LaunchedEffect(focusedCardId, vState.cards) {
		val vTarget = vState.cards.indexOfFirst { it.id.qualified == focusedCardId }
		val vAlreadyVisible = vGridState.layoutInfo.visibleItemsInfo.any { it.index == vTarget }
		if (vTarget >= 0 && !vAlreadyVisible) {
			// Not animated: this happens while the screen is off-screen or arriving, and a scroll
			// animation racing the transition is exactly the kind of thing that looks broken.
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
						IconButton(onClick = onBack) {
							Icon(Icons.Outlined.ArrowBack, contentDescription = "Back to sets")
						}
					},
					actions = {
						// Only where there is a choice to make -- see `UiState.languageOptions`.
						if (vState.languageOptions.size > 1) {
							LanguageMenu(
								options = vState.languageOptions,
								selected = vState.language,
								isBusy = vState.isChangingLanguage,
								onSelect = {
									dispatch(CardGridContract.Intent.LanguageSelected(it))
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
									Icons.Outlined.SearchOff
								} else {
									Icons.Outlined.Search
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
								Icon(Icons.Outlined.FilterList, contentDescription = "Filters")
							}
						}
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
					error = vState.error!!,
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
				else -> CardGrid(
					cards = vState.cards,
					gridState = vGridState,
					onOpenCard = onOpenCard,
					contentPadding = vPadding,
				)
			}
		}
	}

	if (vState.isFilterSheetOpen) {
		val vSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
) {
	LazyVerticalGrid(
		columns = GridCells.Adaptive(minSize = MIN_TILE_WIDTH.dp),
		state = gridState,
		contentPadding = PaddingValues(
			start = TILE_GAP,
			end = TILE_GAP,
			top = contentPadding.calculateTopPadding() + TILE_GAP,
			bottom = contentPadding.calculateBottomPadding() + TILE_GAP,
		),
		horizontalArrangement = Arrangement.spacedBy(10.dp),
		verticalArrangement = Arrangement.spacedBy(14.dp),
		modifier = Modifier.fillMaxSize(),
	) {
		items(cards, key = { it.id.qualified }) { vCard ->
			CardTile(card = vCard, onClick = { onOpenCard(vCard) })
		}
	}
}

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
		leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
		trailingIcon = {
			if (text.isNotEmpty()) {
				IconButton(onClick = { onTextChanged("") }) {
					Icon(Icons.Outlined.Close, contentDescription = "Clear search")
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
private fun CardTile(card: CardPrinting, onClick: () -> Unit) {
	Column(
		modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
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
private const val MIN_TILE_WIDTH = 108

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

/**
 * Which edition of the set is on screen, and a menu to change it.
 *
 * A two-letter code rather than an icon, because there is no glyph for "Japanese" that anyone reads
 * as one, and rather than the full name because the bar already holds a title, a search button and
 * a filter badge. The code is the language's own tag, upper-cased -- `EN`, `JA`, `ZH-CN` -- which is
 * what a card database shows and what the menu then spells out in full.
 *
 * The detail screen has its own language chips. This exists because those were the *only* way to
 * change edition: browsing a whole set in another language meant changing the global preference in
 * Settings and navigating back in.
 */
@Composable
private fun LanguageMenu(
	options: List<CardLanguage>,
	selected: CardLanguage?,
	isBusy: Boolean,
	onSelect: (CardLanguage) -> Unit,
) {
	var vIsOpen by remember { mutableStateOf(false) }

	Box {
		TextButton(
			onClick = { vIsOpen = true },
			// Disabled while a switch is in flight, so a second tap cannot start a third load and
			// leave the state describing an edition nobody asked for.
			enabled = !isBusy,
		) {
			Text(
				text = selected?.code?.uppercase() ?: "--",
				style = MaterialTheme.typography.labelLarge,
			)
			Icon(
				imageVector = Icons.Outlined.ArrowDropDown,
				contentDescription = "Change language",
				modifier = Modifier.size(18.dp),
			)
		}

		DropdownMenu(expanded = vIsOpen, onDismissRequest = { vIsOpen = false }) {
			options.forEach { vLanguage ->
				DropdownMenuItem(
					text = { Text(vLanguage.displayName) },
					onClick = {
						vIsOpen = false
						onSelect(vLanguage)
					},
					trailingIcon = {
						// A tick on the current one rather than a highlight: the menu is short and
						// the point is which edition you are reading, not which row is hovered.
						if (vLanguage == selected) {
							Icon(Icons.Outlined.Check, contentDescription = "Showing")
						}
					},
				)
			}
		}
	}
}
