package com.bitsycore.cardbrowser.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import com.bitsycore.cardbrowser.ui.common.arrowKeys
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.core.cardmarket.CardmarketLink
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardOrientation
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.cardbrowser.ui.common.CardImage
import com.bitsycore.cardbrowser.ui.common.DomainChip
import com.bitsycore.cardbrowser.ui.common.RarityChip
import com.bitsycore.cardbrowser.ui.common.StatChip
import com.bitsycore.cardbrowser.ui.common.ErrorState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.bitsycore.cardbrowser.ui.common.FullscreenCardViewer
import com.bitsycore.cardbrowser.ui.common.ImageVariant
import com.bitsycore.cardbrowser.ui.common.LoadingState
import com.bitsycore.cardbrowser.ui.common.PrefetchCardArt
import com.bitsycore.cardbrowser.ui.common.sharedCardArt
import com.bitsycore.cardbrowser.ui.preview.PreviewData
import com.bitsycore.cardbrowser.ui.preview.PreviewFrame
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import com.bitsycore.lib.pulse.compose.collectEffect
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import com.bitsycore.cardbrowser.ui.common.AppIcons

/**
 * One printing, in full, with the rest of the set a swipe away.
 *
 * Three pieces: a preview strip that stays put, a pager holding one card per page, and the details
 * under each card.
 *
 * Every section below the card is drawn only when it has something in it. That is a change of mind:
 * the sections used to be unconditional, each with a paragraph explaining what the source had not
 * said -- a Finish heading over "this card database does not record finishes", an Other-artwork
 * heading over a note that no artworks could be listed, and a language list in which most entries
 * were greyed out and annotated "-- unknown". The reasoning was that silence about Korean is not the
 * same as there being no Korean printing, which is true and is still what the model holds. But the
 * place to be careful about that is the model, not four paragraphs on every card in the app. What
 * the screen shows now is what is known; absence is expressed by the section being absent.
 */
@Composable
fun CardDetailScreen(
	cardId: String,
	setId: String?,
	onBack: () -> Unit,
	/** The list this card was opened from, when it is not the card's set: a search. */
	browseKey: String? = null,
	/** Leaves for the card's own set. See `CardDetailContract.Effect.OpenSet`. */
	onOpenSet: (setId: String, setName: String, setCode: String) -> Unit = { _, _, _ -> },
	// The arguments go in at construction so the view model can seed its state from the browse
	// session before the first frame, rather than being told to load after one has already been drawn.
	viewModel: CardDetailViewModel = koinViewModel {
		parametersOf(CardDetailArgs(cardId, setId, browseKey))
	},
) {
	val vState by viewModel.collectAsStateWithLifecycle()
	val vSnackbarHost = remember { SnackbarHostState() }

	// Effects belong to the screen, not to the content: they need the view model, and a preview has
	// none.
	viewModel.collectEffect { vEffect ->
		when (vEffect) {
			is CardDetailContract.Effect.LinkFailed ->
				vSnackbarHost.showSnackbar("Could not open a browser for that link.")

			is CardDetailContract.Effect.LanguageUnavailable ->
				vSnackbarHost.showSnackbar(vEffect.reason)

			CardDetailContract.Effect.NavigateBack -> onBack()

			is CardDetailContract.Effect.OpenSet ->
				onOpenSet(vEffect.setId, vEffect.setName, vEffect.setCode)
		}
	}

	// Read here, not in the content: a preview has no Koin graph and `koinInject` throws.
	val vPreferences by koinInject<PreferencesStore>().preferences.collectAsState()

	CardDetailContent(
		state = vState,
		dispatch = viewModel::dispatch,
		prefetchRadius = vPreferences.prefetchRadius,
		snackbarHostState = vSnackbarHost,
	)
}

/**
 * The card detail screen, given a state and somewhere to send intents.
 *
 * @param snackbarHostState hoisted so the screen can post to it from an effect. A preview passes a
 *   fresh one and never uses it
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailContent(
	state: CardDetailContract.UiState,
	dispatch: (CardDetailContract.Intent) -> Unit,
	/** Cards either side of the open one to fetch ahead. Zero, as in a preview, fetches nothing. */
	prefetchRadius: Int = 0,
	snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
	val vState = state
	val vSnackbarHost = snackbarHostState

	// Escape closes the viewer, which is what back means on a desktop -- `BackHandler` carries the
	// platform back gesture and answers no keyboard, which `FullscreenBackTest` was written to
	// check and duly caught. A *preview* handler because the pager below owns the focus: an
	// ancestor sees the key on the way down, so there is no focus to win.
	Box(
		modifier = Modifier
			.fillMaxSize()
			.onPreviewKeyEvent { vEvent ->
				val vIsEscape = vEvent.type == KeyEventType.KeyDown && vEvent.key == Key.Escape
				if (vIsEscape && vState.isFullscreen) {
					dispatch(CardDetailContract.Intent.FullscreenToggled(false))
					true
				} else {
					false
				}
			},
	) {
	// `enterAlways` rather than `exitUntilCollapsed`: this bar is one line of title and a position
	// counter, so there is no larger form to shrink from -- it either takes the space or it does not.
	// Reading a card's rules is the one thing this screen is for, so scrolling down gives that space
	// back and the smallest scroll up returns the bar.
	val vScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

	Scaffold(
		modifier = Modifier.nestedScroll(vScrollBehavior.nestedScrollConnection),
		topBar = {
			TopAppBar(
				title = {
					Column {
						Text(
							text = vState.card?.displayName ?: "Card",
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						vState.positionLabel?.let { vLabel ->
							Text(
								text = vLabel,
								style = MaterialTheme.typography.labelSmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
				},
				navigationIcon = {
					IconButton(onClick = { dispatch(CardDetailContract.Intent.BackPressed) }) {
						Icon(AppIcons.ArrowBack, contentDescription = "Back to cards")
					}
				},
				actions = {
					// Only on a card reached from a search, where the set it belongs to is
					// somewhere the user has not been. Inside a set it would go where they
					// already are.
					if (vState.isOutsideItsSet) {
						IconButton(
							onClick = { dispatch(CardDetailContract.Intent.GoToSetPressed) },
						) {
							Icon(
								imageVector = AppIcons.Style,
								contentDescription = vState.card
									?.let { "Go to ${it.setName}" }
									?: "Go to the set",
							)
						}
					}
				},
				scrollBehavior = vScrollBehavior,
			)
		},
		snackbarHost = { SnackbarHost(vSnackbarHost) },
	) { vPadding ->
		when {
			vState.cards.isEmpty() && vState.isLoading -> LoadingState(Modifier.padding(vPadding))

			vState.cards.isEmpty() -> ErrorState(
				error = vState.error ?: ProviderError.Unknown("Card not found"),
				onRetry = null,
				modifier = Modifier.padding(vPadding),
			)

			// The insets go *into* the scrolling card rather than around the pager, so the card
			// travels under the bar and the strip instead of stopping at them. Which is the point of
			// having a bar that collapses.
			else -> CardPager(
				contentPadding = vPadding,
				prefetchRadius = prefetchRadius,
				state = vState,
				onPageChanged = { dispatch(CardDetailContract.Intent.PageChanged(it)) },
				onLanguageSelected = { dispatch(CardDetailContract.Intent.LanguageSelected(it)) },
				onOpenCardmarket = { dispatch(CardDetailContract.Intent.OpenCardmarket(it)) },
				onOpenTcgplayer = { dispatch(CardDetailContract.Intent.OpenTcgplayer(it)) },
				onOpenFullscreen = { dispatch(CardDetailContract.Intent.FullscreenToggled(true)) },
			)
		}
	}

	// Back closes the viewer before it closes the card. Two things are open, and the top one goes
	// first -- anything else loses the card as well, which is not what "back" meant.
	//
	// `NavigationEvent` rather than `BackHandler`, which Compose 1.12 deprecates in its favour.
	// This carries no info of its own: the state is what a predictive gesture would draw, and a
	// dismissable overlay has nothing to show behind itself.
	NavigationBackHandler(
		state = rememberNavigationEventState(NavigationEventInfo.None),
		isBackEnabled = vState.isFullscreen,
		onBackCompleted = { dispatch(CardDetailContract.Intent.FullscreenToggled(false)) },
	)

	// Outside the Scaffold so it covers the app bar as well; a card is worth the whole screen.
	vState.card?.let { vCard ->
		FullscreenCardViewer(
			artwork = vCard.artwork,
			contentDescription = vCard.artwork.accessibilityText ?: vCard.displayName,
			isVisible = vState.isFullscreen,
			onDismiss = { dispatch(CardDetailContract.Intent.FullscreenToggled(false)) },
		)
	}
	}
}

// ==================
// MARK: Pager
// ==================

/**
 * The swipeable stack of cards, with the preview strip pinned above it.
 *
 * The strip is deliberately outside the pager: it is a map of where you are, and a map that slides
 * away with the thing it is describing is no use.
 */
@Composable
private fun CardPager(
	contentPadding: PaddingValues,
	prefetchRadius: Int,
	state: CardDetailContract.UiState,
	onPageChanged: (Int) -> Unit,
	onLanguageSelected: (CardLanguage) -> Unit,
	onOpenCardmarket: (String) -> Unit,
	onOpenTcgplayer: (String) -> Unit,
	onOpenFullscreen: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val vPagerState = rememberPagerState(
		initialPage = state.currentIndex,
		pageCount = { state.cards.size },
	)
	val vScope = rememberCoroutineScope()

	// The last page the pager itself reported.
	//
	// This is what tells the effect below whether a change to `currentIndex` came from the pager or
	// from somewhere else, which the state alone cannot say -- and without it the two effects form a
	// loop. See the comment on that effect.
	var vLastReportedByPager by remember { mutableIntStateOf(-1) }

	// A swipe only counts once it has settled.
	//
	// `targetPage` updates the instant a drag looks like it is heading somewhere, so a half-hearted
	// swipe that snaps back still selected the next card and then undid itself -- the strip and the
	// title flickered to a card the user never arrived at. A tap does not need this, because a tap
	// has no "maybe": it selects immediately in the strip's own handler and this merely confirms it.
	LaunchedEffect(vPagerState) {
		snapshotFlow { vPagerState.settledPage }.collect { vPage ->
			vLastReportedByPager = vPage
			onPageChanged(vPage)
		}
	}

	// The other direction: something outside the pager moved the selection, so the pager follows.
	//
	// The guard against `vLastReportedByPager` is what stops the two effects fighting. A settled
	// swipe reports its page, which becomes `currentIndex`, which lands back here -- and if the user
	// has already begun the next swipe by then, `currentIndex` no longer matches `targetPage` and
	// this would animate *back* to the page just left, cancelling the swipe in progress. Which is
	// exactly what a run of quick swipes felt like. An echo of what the pager itself just said is
	// not a request to move.
	//
	// The first move is a *jump*, not an animation. Cards load after the screen opens, so the pager
	// starts at page 0 and only then learns it should be on, say, 297 -- and animating there scrolls
	// through every page between, composing and discarding them as fast as the device can manage.
	// Afterwards, moves are real navigation between neighbours and are worth animating.
	var vHasPositioned by remember { mutableStateOf(false) }
	LaunchedEffect(state.currentIndex, state.cards.size) {
		if (state.cards.isEmpty()) return@LaunchedEffect
		if (!vHasPositioned) {
			vPagerState.scrollToPage(state.currentIndex)
			vLastReportedByPager = state.currentIndex
			vHasPositioned = true
			return@LaunchedEffect
		}
		if (state.currentIndex == vLastReportedByPager) return@LaunchedEffect
		if (state.currentIndex != vPagerState.targetPage) {
			vPagerState.animateScrollToPage(state.currentIndex)
		}
	}

	// The cards either side, fetched before they are asked for. Combined with the thumbnail showing
	// underneath a loading image, a swipe lands on finished art rather than on a spinner.
	PrefetchCardArt(
		artworks = if (prefetchRadius <= 0) {
			emptyList()
		} else {
			state.cards
				.slice(
					(state.currentIndex - prefetchRadius).coerceAtLeast(0)..
						(state.currentIndex + prefetchRadius).coerceAtMost(state.cards.lastIndex),
				)
				.map { it.artwork }
		},
	)

	val vTopInset = contentPadding.calculateTopPadding()
	// What the card has to clear at rest: the app bar, then the strip if there is one.
	val vHeaderHeight = vTopInset + if (state.canSwipe) PREVIEW_ROW_HEIGHT + STRIP_TO_CARD_GAP else 0.dp

	// Left and right change the card, which is what a swipe does -- so they do it *through* the
	// pager rather than by dispatching a page change. The pager already reports where it settles
	// and the state already follows it; going the other way would be a second route to the same
	// place, and the two effects above exist because that is hard to keep straight even once.
	//
	// Counted from where the pager is *heading*, not from `currentIndex`.
	//
	// `currentIndex` only moves when a page settles -- that is what makes a half-hearted swipe not
	// count -- so during an animation it still names the card being left. Two quick presses of the
	// same arrow both measured from there and asked for the same page twice, so the second did
	// nothing. `targetPage` is where the pager has already committed to going, which makes the
	// second press land one further on, as holding an arrow down should.
	// The scroll of whichever page is on screen, so up and down can move it. Held here because the
	// state belongs to a page and the keys arrive outside every page.
	var vCurrentScroll by remember { mutableStateOf<ScrollState?>(null) }
	val vScrollStep = with(LocalDensity.current) { DETAIL_SCROLL_STEP.toPx() }
	val vScrollBy: (Float) -> (() -> Unit)? = { vAmount ->
		vCurrentScroll?.let { vState -> { vScope.launch { vState.animateScrollBy(vAmount) } } }
	}

	val vStep: (Int) -> (() -> Unit)? = { vDelta ->
		val vTarget = vPagerState.targetPage + vDelta
		if (vTarget !in state.cards.indices) {
			null
		} else {
			{ vScope.launch { vPagerState.animateScrollToPage(vTarget) } }
		}
	}

	Box(
		modifier
			.fillMaxSize()
			// No handler at either end of the list, so the key is left unconsumed there rather
			// than swallowed -- nothing else wants it today, but a swallowed key is invisible and
			// a passed-on one is not.
			//
			// `takeFocus = false` because the page on screen takes it instead, which is what lets
			// up and down scroll that page. Left and right still arrive here: a key bubbles from
			// the focused node up through its ancestors, and this is one.
			.arrowKeys(
				onLeft = vStep(-1),
				onRight = vStep(1),
				// A card's detail is a long column -- the art, then the stats, then the other
				// printings -- and before this the keyboard could change card but not read one.
				onUp = vScrollBy(-vScrollStep),
				onDown = vScrollBy(vScrollStep),
			),
	) {
		HorizontalPager(
			state = vPagerState,
			// One page either side stays composed, so the neighbour is already laid out and drawn
			// when a swipe starts. Three either side are *prefetched* above, which costs no
			// composition -- keeping seven full detail pages alive would.
			beyondViewportPageCount = 1,
			modifier = Modifier.fillMaxSize(),
		) { vPage ->
			val vCard = state.cards[vPage]
			CardDetailPage(
				state = state,
				card = vCard,
				// Only the page actually on screen takes part in the shared transition.
				//
				// The pager keeps its neighbours composed so a swipe is instant, and each of them was
				// claiming the shared key for its own card. Going back then matched three pairs at once
				// and flew three cards across the screen from wherever the off-screen pages happened to
				// be laid out -- which is the mess that made the back animation look broken.
				isSharedElement = vPage == state.currentIndex,
				// Only the page on screen may hold the focus. Its neighbours are composed so a
				// swipe is instant, and three pages each asking for focus would be three answers
				// to a question with one.
				isCurrentPage = vPage == vPagerState.currentPage,
				onScrollStateReady = { vCurrentScroll = it },
				onLanguageSelected = onLanguageSelected,
				onOpenCardmarket = { onOpenCardmarket(vCard.id.qualified) },
				onOpenTcgplayer = { onOpenTcgplayer(vCard.id.qualified) },
				onOpenFullscreen = onOpenFullscreen,
				// Tapping another artwork of the same card is a jump within the list already loaded,
				// so it is the same move the preview strip makes rather than a new screen.
				onSelectPrinting = { vTarget ->
					val vIndex = state.cards.indexOfFirst { it.id == vTarget.id }
					if (vIndex >= 0) {
						onPageChanged(vIndex)
						vScope.launch { vPagerState.animateScrollToPage(vIndex) }
					}
				},
				headerHeight = vHeaderHeight,
				bottomPadding = contentPadding.calculateBottomPadding(),
			)
		}

		// Drawn over the pager rather than above it, on an opaque surface, so the card slides
		// underneath instead of being clipped by a layout boundary.
		if (state.canSwipe) {
			Surface(
				color = MaterialTheme.colorScheme.surface,
				modifier = Modifier.align(Alignment.TopStart).padding(top = vTopInset),
			) {
				Column {
					PreviewStrip(
						cards = state.cards,
						currentIndex = state.currentIndex,
						// Selected on the tap rather than when the scroll finishes: a tap is
						// unambiguous, so making the strip wait out the animation looks unresponsive.
						onSelect = { vIndex ->
							onPageChanged(vIndex)
							vScope.launch { vPagerState.animateScrollToPage(vIndex) }
						},
					)
					HorizontalDivider()
				}
			}
		}
	}
}

/**
 * A row of thumbnails showing where you are and what is either side.
 *
 * Scrolls itself to keep the current card centred, so a swipe moves the strip too and the next card
 * is always already visible in it. Tapping one jumps there.
 */
@Composable
private fun PreviewStrip(
	cards: List<CardPrinting>,
	currentIndex: Int,
	onSelect: (Int) -> Unit,
) {
	val vListState = rememberLazyListState()
	// Same reasoning as the pager: the first positioning is a jump. Animating from card 1 to card 297
	// drags the whole strip past 296 thumbnails, each of which is a real image request.
	var vHasPositioned by remember { mutableStateOf(false) }

	LaunchedEffect(currentIndex) {
		// Centred rather than merely visible: the point of the strip is seeing both directions.
		val vViewport = vListState.layoutInfo.viewportEndOffset - vListState.layoutInfo.viewportStartOffset
		val vItemWidth = vListState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
		val vOffset = -(vViewport / 2 - vItemWidth / 2).coerceAtLeast(0)
		if (vHasPositioned) {
			vListState.animateScrollToItem(index = currentIndex, scrollOffset = vOffset)
		} else {
			vListState.scrollToItem(index = currentIndex, scrollOffset = vOffset)
			vHasPositioned = true
		}
	}

	LazyRow(
		state = vListState,
		// Pinned, so the strip is a fixed band and never resizes the screen under it.
		modifier = Modifier.fillMaxWidth().height(PREVIEW_ROW_HEIGHT),
		contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
		horizontalArrangement = Arrangement.spacedBy(6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		itemsIndexed(cards, key = { _, vCard -> vCard.id.qualified }) { vIndex, vCard ->
			val vIsCurrent = vIndex == currentIndex
			Column(horizontalAlignment = Alignment.CenterHorizontally) {
				CardImage(
					artwork = vCard.artwork,
					contentDescription = vCard.displayName,
					variant = ImageVariant.THUMBNAIL,
					contentScale = ContentScale.Crop,
					modifier = Modifier
						// Every thumbnail is the same *height* and takes its width from its shape,
						// rather than the reverse. Sizing by width made the row as tall as whatever
						// happened to be in it: a portrait card is 1.4x taller than it is wide, a
						// landscape battlefield is shorter than it is wide, and the selected one was
						// bigger again -- so the strip changed height on every swipe and shoved the
						// card below it up and down.
						.height(PREVIEW_HEIGHT)
						.aspectRatio(
							ratio = if (vCard.orientation == CardOrientation.LANDSCAPE) {
								1039f / 744f
							} else {
								744f / 1039f
							},
							// Width follows height. Without this the ratio is satisfied against the
							// row's very wide max width first and every thumbnail comes out huge.
							matchHeightConstraintsFirst = true,
						)
						.clip(RoundedCornerShape(4.dp))
						.background(MaterialTheme.colorScheme.surfaceVariant)
						.then(
							if (vIsCurrent) {
								Modifier.border(
									width = 2.dp,
									color = MaterialTheme.colorScheme.primary,
									shape = RoundedCornerShape(4.dp),
								)
							} else {
								Modifier
							},
						)
						.clickable { onSelect(vIndex) },
				)
				Text(
					text = vCard.collectorNumber,
					style = MaterialTheme.typography.labelSmall,
					color = if (vIsCurrent) {
						MaterialTheme.colorScheme.primary
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
					maxLines = 1,
				)
			}
		}
	}
}

// ==================
// MARK: One card
// ==================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardDetailPage(
	state: CardDetailContract.UiState,
	card: CardPrinting,
	onLanguageSelected: (CardLanguage) -> Unit,
	onOpenCardmarket: () -> Unit,
	onOpenTcgplayer: () -> Unit,
	onOpenFullscreen: () -> Unit,
	onSelectPrinting: (CardPrinting) -> Unit,
	isSharedElement: Boolean,
	isCurrentPage: Boolean,
	/** Hands this page's scroll state up, while it is the page on screen. See the call site. */
	onScrollStateReady: (ScrollState) -> Unit,
	headerHeight: Dp = 0.dp,
	bottomPadding: Dp = 0.dp,
	modifier: Modifier = Modifier,
) {
	// Measured outside the scroll on purpose. A vertically scrolling Column hands its children an
	// infinite height constraint, so an `aspectRatio` inside one derives its height from the width
	// and grows as tall as the ratio demands -- which is how a portrait card ended up taller than
	// the window with its own name scrolled off the bottom. This is the last place that still knows
	// how much room there actually is.
	BoxWithConstraints(modifier.fillMaxSize()) {
		// Measured against what is actually free below the header, or the card would be sized for a
		// screen it does not get all of and hang past the bottom.
		val vMaxImageHeight = (maxHeight - headerHeight) * IMAGE_HEIGHT_FRACTION

		// Reported upward rather than driven here, so the arrow keys can scroll it.
		//
		// A focusable *inside* the pager was the first attempt and it was worse than not working:
		// taking focus within a page makes Compose bring that page into view, which scrolls the
		// pager itself -- so opening a card jumped to a different one before a key was touched.
		// One focus target, on the Box outside, is what avoids that.
		val vScroll = rememberScrollState()
		LaunchedEffect(vScroll, isCurrentPage) {
			if (isCurrentPage) onScrollStateReady(vScroll)
		}

		Column(
			modifier = Modifier
				.fillMaxSize()
				.verticalScroll(vScroll)
				.padding(horizontal = 20.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			// Inside the scroll, not on it: padding on a scrolling container clips at its edge, while
			// a spacer within simply moves with the content -- which is what lets the card disappear
			// under the bar rather than stop at it.
			Spacer(Modifier.height(headerHeight))

			CardDetailImage(
				card = card,
				maxImageHeight = vMaxImageHeight,
				onOpenFullscreen = onOpenFullscreen,
				isSharedElement = isSharedElement,
			)

			Spacer(Modifier.height(16.dp))

			Text(card.displayName, style = MaterialTheme.typography.headlineSmall)
			Text(
				text = "${card.setName} · ${card.collectorNumber}",
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)

			Spacer(Modifier.height(12.dp))

			// The glance: what someone reads before the rules text. Everything the provider stated
			// is in the details table further down, so a chip here is a repeat only of the handful
			// worth seeing without scrolling. Each is omitted when the provider did not state it.
			val vWords = state.game?.vocabulary ?: GameVocabulary()
			FlowRow(
				horizontalArrangement = Arrangement.spacedBy(6.dp),
				verticalArrangement = Arrangement.spacedBy(2.dp),
			) {
				card.classification.rarity?.let { RarityChip(it, state.game) }
				card.classification.type?.let { StatChip(it) }
				card.classification.supertype?.let { StatChip(it) }
				// The game's label and colour, not the raw key the filter is keyed on.
				card.classification.domains.forEach { vKey ->
					DomainChip(vKey, state.game)
				}
				// Labelled with the game's own word: "Mana value 3" for Magic, "Level 4" for
				// Yu-Gi-Oh. "3 energy" was Riftbound's word applied to all seven games.
				card.attributes.cost?.let { vCost ->
					StatChip(vWords.cost?.let { "$it $vCost" } ?: "$vCost")
				}
				card.attributes.primary?.let { vStat ->
					StatChip(vWords.primaryStat?.let { "$it $vStat" } ?: "$vStat")
				}
				card.attributes.secondary?.let { vStat ->
					StatChip(vWords.secondaryStat?.let { "$it $vStat" } ?: "$vStat")
				}
				if (card.artwork.treatment != ArtworkTreatment.STANDARD) {
					StatChip(card.artwork.treatment.displayName)
				}
			}

			card.text.rules?.let { vRules ->
				Spacer(Modifier.height(16.dp))
				Text(vRules, style = MaterialTheme.typography.bodyMedium)
			}

			card.text.flavour?.let { vFlavour ->
				Spacer(Modifier.height(10.dp))
				Text(
					text = vFlavour,
					style = MaterialTheme.typography.bodySmall,
					fontStyle = FontStyle.Italic,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}

			// ============
			//  Language

			val vLanguages = state.languageOptionsFor(card)
			if (vLanguages.isNotEmpty()) {
				SectionDivider()
				SectionTitle("Language")
				Spacer(Modifier.height(8.dp))
				FlowRow(
					horizontalArrangement = Arrangement.spacedBy(6.dp),
					verticalArrangement = Arrangement.spacedBy(4.dp),
					modifier = Modifier.fillMaxWidth(),
				) {
					vLanguages.forEach { vOption ->
						AvailabilityChip(
							label = vOption.language.displayName,
							isSelected = vOption.isSelected,
							onClick = if (vOption.isSelectable && !state.isChangingLanguage) {
								{ onLanguageSelected(vOption.language) }
							} else {
								null
							},
						)
					}
				}
			}

			// Finish has no section of its own: it is a row in Details. Every finish of a card is
			// the same picture -- no source here publishes a scan per finish -- so chips offered a
			// choice that changed nothing on screen. See `UiState.finishesFor`.

			// ============
			//  Other artwork

			// Same rule: a real list or nothing at all.
			val vOtherArtworks = state.otherArtworksFor(card)
			if (vOtherArtworks.isNotEmpty()) {
				SectionDivider()
				SectionTitle("Other artwork")
				Spacer(Modifier.height(8.dp))
				OtherArtworkRow(printings = vOtherArtworks, onSelect = onSelectPrinting)
			}

			// ============
			//  Details

			val vFacts = state.factsFor(card)
			if (vFacts.isNotEmpty()) {
				SectionDivider()
				SectionTitle("Details")
				Spacer(Modifier.height(8.dp))
				vFacts.forEach { vFact -> FactRow(vFact) }
			}

			// ============
			//  Where to buy it

			val vTcgplayer = state.tcgplayerLinkFor(card)
			state.cardmarketLinkFor(card)?.let { vLink ->
				SectionDivider()
				Spacer(Modifier.height(4.dp))
				OutlinedButton(onClick = onOpenCardmarket, modifier = Modifier.fillMaxWidth()) {
					Icon(
						imageVector = AppIcons.OpenInNew,
						contentDescription = null,
						modifier = Modifier.size(16.dp),
					)
					Spacer(Modifier.size(8.dp))
					Text(vLink.label)
				}
				// One line, and only for the case a reader would otherwise be misled by: a search
				// can land on the wrong product, a direct link cannot. That a link does not buy
				// anything is not worth a sentence under every card.
				if (vLink is CardmarketLink.CardSearch) {
					FinePrint(
						"A search for \"${vLink.terms}\" in ${vLink.expansion} — this database does " +
							"not map cards to Cardmarket products.",
					)
				}
			}

			// Its own block rather than an `else`: the two marketplaces are independent, and a
			// card can have an id for one and not the other. Four of the eight sources publish a
			// TCGplayer id, so this appears for Magic, Pokemon, Riftbound and the three TCGCSV
			// games, and simply does not for the rest.
			vTcgplayer?.let { vLink ->
				if (state.cardmarketLinkFor(card) == null) {
					SectionDivider()
					Spacer(Modifier.height(4.dp))
				} else {
					Spacer(Modifier.height(8.dp))
				}
				OutlinedButton(onClick = onOpenTcgplayer, modifier = Modifier.fillMaxWidth()) {
					Icon(
						imageVector = AppIcons.OpenInNew,
						contentDescription = null,
						modifier = Modifier.size(16.dp),
					)
					Spacer(Modifier.size(8.dp))
					Text(vLink.label)
				}
			}

			// The trademark notice. Deliberately the quietest thing on the screen: it has to be
			// here, and it is never what anyone opened the card to read.
			state.attribution?.let { vAttribution ->
				Spacer(Modifier.height(24.dp))
				FinePrint(vAttribution)
			}

			Spacer(Modifier.height(bottomPadding + 32.dp))
		}

		// Hints, on the edges, only while there is somewhere to go.
		if (state.canSwipe) {
			SwipeHint(AppIcons.ChevronLeft, Alignment.CenterStart, state.currentIndex > 0)
			SwipeHint(
				icon = AppIcons.ChevronRight,
				alignment = Alignment.CenterEnd,
				isVisible = state.currentIndex < state.cards.size - 1,
			)
		}
	}
}

/** A faint chevron at the edge, so the swipe is discoverable without a tutorial. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SwipeHint(
	icon: androidx.compose.ui.graphics.vector.ImageVector,
	alignment: Alignment,
	isVisible: Boolean,
) {
	if (!isVisible) return
	Icon(
		imageVector = icon,
		contentDescription = null,
		tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
		modifier = Modifier.align(alignment).size(28.dp),
	)
}

// ==================
// MARK: Image
// ==================

/**
 * The large image. Tapping it opens the fullscreen viewer, which is where zooming lives.
 *
 * It used to pinch and pan in place. That put two magnifiable views of the same card in the app,
 * one of them inside a pager that had to be disabled while it was magnified -- so a pan could
 * silently turn into a page swipe, and the state saying "this page is zoomed" had to be threaded
 * through three composables and the contract to stop it.
 *
 * Loads the full-resolution URL rather than the grid's thumbnail: this is the one place where the
 * full image is worth its bytes, and loading it here rather than in the grid is what keeps
 * scrolling a set cheap.
 *
 * @param maxImageHeight how tall the image may be, measured from the window rather than derived
 *   from the width. Without it a portrait card fills the width, takes its height from the aspect
 *   ratio, and pushes its own name off the bottom of the screen
 */
@Composable
private fun CardDetailImage(
	card: CardPrinting,
	maxImageHeight: Dp,
	onOpenFullscreen: () -> Unit,
	isSharedElement: Boolean,
) {
	Box(
		modifier = Modifier
			// Both bounds are maxima, and neither is `fillMaxWidth`. `aspectRatio` satisfies the
			// width bound first and falls back to the height bound when the result would overflow
			// it, so a tall window gives a 420 dp-wide card and a short or wide one gives a card
			// sized by the height instead. `fillMaxWidth` here would pin the width and defeat both.
			.widthIn(max = MAX_IMAGE_WIDTH)
			.heightIn(max = maxImageHeight)
			.aspectRatio(
				if (card.orientation == CardOrientation.LANDSCAPE) 1039f / 744f else 744f / 1039f,
			)
			// Pairs with the grid tile of the same printing, and only while this is the page on
			// screen -- see `isSharedElement`.
			.then(if (isSharedElement) Modifier.sharedCardArt(card.id.qualified) else Modifier)
			.clip(RoundedCornerShape(12.dp))
			.background(MaterialTheme.colorScheme.surfaceVariant)
			.clickable(onClick = onOpenFullscreen),
	) {
		CardImage(
			artwork = card.artwork,
			contentDescription = card.artwork.accessibilityText ?: card.displayName,
			variant = ImageVariant.DISPLAY,
			// Real pixels rather than a bitmap sized to this box: the fullscreen viewer grows this
			// exact picture, and a box-sized bitmap arrives there already soft.
			decodeAtSourceResolution = true,
			contentScale = ContentScale.Fit,
			modifier = Modifier.fillMaxSize(),
		)
	}
}

// ==================
// MARK: Small parts
// ==================

/**
 * A chip for one of a card's variants -- a language, a finish -- filled when it is the one showing.
 *
 * The label is the name and nothing else. It used to append "-- unknown" or "-- not printed" to
 * explain each greyed-out chip, which meant every card carried a row of disclaimers about languages
 * its database had simply never mentioned. What cannot be chosen is now not listed at all, so a chip
 * here is either what you are reading or something you can switch to.
 */
@Composable
private fun AvailabilityChip(
	label: String,
	isSelected: Boolean,
	onClick: (() -> Unit)?,
) {
	AssistChip(
		onClick = onClick ?: {},
		enabled = onClick != null || isSelected,
		label = { Text(label) },
		colors = if (isSelected) {
			AssistChipDefaults.assistChipColors(
				containerColor = MaterialTheme.colorScheme.primaryContainer,
				labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
			)
		} else {
			AssistChipDefaults.assistChipColors()
		},
	)
}

@Composable
private fun SectionDivider() {
	Spacer(Modifier.height(20.dp))
	HorizontalDivider()
	Spacer(Modifier.height(12.dp))
}

@Composable
private fun SectionTitle(text: String) {
	Text(
		text = text,
		style = MaterialTheme.typography.titleSmall,
		modifier = Modifier.fillMaxWidth(),
	)
}

/**
 * The other artworks of the same card, as thumbnails that jump to them.
 *
 * Only ever drawn with something in it -- see `UiState.otherArtworksFor`. The treatment goes under
 * each one, because "which of these is the full-art?" is the question this row exists to answer.
 */
@Composable
private fun OtherArtworkRow(
	printings: List<CardPrinting>,
	onSelect: (CardPrinting) -> Unit,
) {
	LazyRow(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		items(printings, key = { it.id.qualified }) { vPrinting ->
			Column(
				horizontalAlignment = Alignment.CenterHorizontally,
				modifier = Modifier.width(OTHER_ARTWORK_WIDTH),
			) {
				CardImage(
					artwork = vPrinting.artwork,
					contentDescription = vPrinting.artwork.accessibilityText ?: vPrinting.displayName,
					variant = ImageVariant.THUMBNAIL,
					contentScale = ContentScale.Crop,
					modifier = Modifier
						.width(OTHER_ARTWORK_WIDTH)
						.aspectRatio(
							if (vPrinting.orientation == CardOrientation.LANDSCAPE) {
								1039f / 744f
							} else {
								744f / 1039f
							},
						)
						.clip(RoundedCornerShape(6.dp))
						.background(MaterialTheme.colorScheme.surfaceVariant)
						.clickable { onSelect(vPrinting) },
				)
				Spacer(Modifier.height(4.dp))
				Text(
					text = vPrinting.artwork.treatment.displayName,
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 2,
				)
			}
		}
	}
}

/**
 * One labelled fact, label left and value right.
 *
 * A table rather than more chips: these are answers to named questions, and a chip reading
 * "89631139" gives you a value while hiding which field it is the value of.
 */
@Composable
private fun FactRow(fact: CardDetailContract.CardFact) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
		verticalAlignment = Alignment.Top,
	) {
		Text(
			text = fact.label,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.width(FACT_LABEL_WIDTH),
		)
		Spacer(Modifier.width(8.dp))
		Text(
			text = fact.value,
			style = MaterialTheme.typography.bodySmall,
			modifier = Modifier.weight(1f),
		)
	}
}

/**
 * Text that has to be there and is not what anyone came for: a trademark notice, and the one caveat
 * a Cardmarket *search* needs that a direct product link does not.
 *
 * Smaller and dimmer than body text on purpose. Setting it like everything else is what made the
 * bottom of this screen read as content.
 */
@Composable
private fun FinePrint(text: String) {
	Spacer(Modifier.height(6.dp))
	Text(
		text = text,
		style = MaterialTheme.typography.labelSmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
		modifier = Modifier.fillMaxWidth(),
	)
}

/**
 * How much of the window the card image may occupy.
 *
 * Two thirds leaves the name, the collector number and the first row of stats visible without
 * scrolling, which is what someone opening a card is looking for. The rest is a scroll away.
 */
private const val IMAGE_HEIGHT_FRACTION = 0.66f

/**
 * The widest the image is allowed to get.
 *
 * A card blown up across a 1600 dp desktop window is not more readable, just further from the text
 * that describes it.
 */
private val MAX_IMAGE_WIDTH = 420.dp

/**
 * Preview thumbnails: small enough that several fit, large enough to recognise the art.
 *
 * A *height*, deliberately. Riftbound has portrait cards and landscape battlefields, and the current
 * card used to be drawn larger than its neighbours -- all three of which changed the row's height
 * when sized by width. The selected card is marked with a border instead, which costs no space.
 */
private val PREVIEW_HEIGHT = 52.dp

/** The thumbnail, its collector number underneath, and the row's own padding. */
private val PREVIEW_ROW_HEIGHT = 84.dp

/** Breathing room between the preview strip and the card it is describing. */
private val STRIP_TO_CARD_GAP = 8.dp

/** Wide enough to tell two artworks of one card apart, narrow enough that several fit in a row. */
private val OTHER_ARTWORK_WIDTH = 72.dp

/** Fixed, so every value in the details table starts at the same place and the column reads down. */
private val FACT_LABEL_WIDTH = 116.dp

// ==================
// MARK: Previews
// ==================

private fun previewDetailState(
	cards: List<CardPrinting> = PreviewData.CARDS,
	currentIndex: Int = 0,
	isLoading: Boolean = false,
	requestedLanguage: CardLanguage = CardLanguage.ENGLISH,
	// Riftcodex's real answer: English, and it says so. Which is why the language section of a
	// Riftbound card is a single filled chip rather than a list of things it cannot offer.
	providerLanguages: Set<CardLanguage> = setOf(CardLanguage.ENGLISH),
) = CardDetailContract.UiState(
	cards = cards,
	currentIndex = currentIndex,
	set = PreviewData.ORIGINS,
	isLoading = isLoading,
	requestedLanguage = requestedLanguage,
	providerLanguages = providerLanguages,
	providerDisplayName = "Riftcodex",
	game = RiftboundGame,
	attribution = "Card data from Riftcodex, an unofficial fan project not affiliated with Riot Games.",
)

@Preview
@Composable
private fun CardDetailPreview() = PreviewFrame {
	CardDetailContent(state = previewDetailState(), dispatch = {})
}

@Preview
@Composable
private fun CardDetailManyLanguagesPreview() = PreviewFrame {
	// A source that really does serve eleven languages, with French asked for and English the only
	// one confirmed for this printing. Every chip here is one the user can switch to; the section
	// exists to be switched in, not to explain itself.
	CardDetailContent(
		state = previewDetailState(
			currentIndex = 5,
			requestedLanguage = CardLanguage.FRENCH,
			providerLanguages = CardLanguage.entries.toSet(),
		),
		dispatch = {},
	)
}

@Preview
@Composable
private fun CardDetailSingleCardPreview() = PreviewFrame(isDark = false) {
	// One card, so no preview strip and no position counter.
	CardDetailContent(
		state = previewDetailState(cards = listOf(PreviewData.card())),
		dispatch = {},
	)
}

@Preview
@Composable
private fun CardDetailLoadingPreview() = PreviewFrame {
	CardDetailContent(
		state = previewDetailState(cards = emptyList(), isLoading = true),
		dispatch = {},
	)
}

/** How far one press of up or down moves a card's detail. About three lines. */
private val DETAIL_SCROLL_STEP = 64.dp
