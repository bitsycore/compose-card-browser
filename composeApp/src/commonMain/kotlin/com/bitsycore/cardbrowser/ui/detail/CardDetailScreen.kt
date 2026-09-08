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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.OpenInNew
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.core.cardmarket.CardmarketLink
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.Availability
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardOrientation
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.Finish
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.ui.common.CardImage
import com.bitsycore.cardbrowser.ui.common.ImageVariant
import com.bitsycore.cardbrowser.ui.common.ErrorState
import com.bitsycore.cardbrowser.ui.common.FullscreenCardViewer
import com.bitsycore.cardbrowser.ui.common.LoadingState
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import com.bitsycore.lib.pulse.compose.collectEffect
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * One printing, in full, with the rest of the set a swipe away.
 *
 * Three pieces: a preview strip that stays put, a pager holding one card per page, and the details
 * under each card. The sections of a card are ordered by how certain they are -- what the provider
 * stated, then what it stated partially, then what it did not state at all. The last group is still
 * shown: silence about Korean is not the same as there being no Korean printing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailScreen(
	cardId: String,
	setId: String?,
	onBack: () -> Unit,
	viewModel: CardDetailViewModel = koinViewModel(),
) {
	val vState by viewModel.collectAsStateWithLifecycle()
	val vSnackbarHost = remember { SnackbarHostState() }

	LaunchedEffect(cardId) {
		viewModel.dispatch(CardDetailContract.Intent.Load(cardId, setId))
	}

	viewModel.collectEffect { vEffect ->
		when (vEffect) {
			is CardDetailContract.Effect.LinkFailed ->
				vSnackbarHost.showSnackbar("Could not open a browser for that link.")
		}
	}

	Box(Modifier.fillMaxSize()) {
	Scaffold(
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
					IconButton(onClick = onBack) {
						Icon(Icons.Outlined.ArrowBack, contentDescription = "Back to cards")
					}
				},
			)
		},
		snackbarHost = { SnackbarHost(vSnackbarHost) },
	) { vPadding ->
		when {
			vState.isLoading -> LoadingState(Modifier.padding(vPadding))

			vState.cards.isEmpty() -> ErrorState(
				error = vState.error ?: ProviderError.Unknown("Card not found"),
				onRetry = null,
				modifier = Modifier.padding(vPadding),
			)

			else -> CardPager(
				state = vState,
				onPageChanged = { viewModel.dispatch(CardDetailContract.Intent.PageChanged(it)) },
				onZoomToggle = { viewModel.dispatch(CardDetailContract.Intent.ZoomToggled(it)) },
				onLanguageSelected = { viewModel.dispatch(CardDetailContract.Intent.LanguageSelected(it)) },
				onFinishSelected = { viewModel.dispatch(CardDetailContract.Intent.FinishSelected(it)) },
				onOpenCardmarket = { viewModel.dispatch(CardDetailContract.Intent.OpenCardmarket(it)) },
				onOpenFullscreen = { viewModel.dispatch(CardDetailContract.Intent.FullscreenToggled(true)) },
				modifier = Modifier.padding(vPadding),
			)
		}
	}

	// Outside the Scaffold so it covers the app bar as well; a card is worth the whole screen.
	vState.card?.let { vCard ->
		FullscreenCardViewer(
			artwork = vCard.artwork,
			contentDescription = vCard.artwork.accessibilityText ?: vCard.displayName,
			isVisible = vState.isFullscreen,
			onDismiss = { viewModel.dispatch(CardDetailContract.Intent.FullscreenToggled(false)) },
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
	state: CardDetailContract.UiState,
	onPageChanged: (Int) -> Unit,
	onZoomToggle: (Boolean) -> Unit,
	onLanguageSelected: (CardLanguage) -> Unit,
	onFinishSelected: (Finish) -> Unit,
	onOpenCardmarket: (String) -> Unit,
	onOpenFullscreen: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val vPagerState = rememberPagerState(
		initialPage = state.currentIndex,
		pageCount = { state.cards.size },
	)
	val vScope = rememberCoroutineScope()

	// Settled page, not the in-flight one: the title and the strip should follow a completed swipe
	// rather than flicker through every card a fast fling passes over.
	LaunchedEffect(vPagerState) {
		snapshotFlow { vPagerState.settledPage }.collect(onPageChanged)
	}

	// The other direction: tapping the strip, which moves the pager rather than the state.
	LaunchedEffect(state.currentIndex) {
		if (state.currentIndex != vPagerState.currentPage) {
			vPagerState.animateScrollToPage(state.currentIndex)
		}
	}

	Column(modifier.fillMaxSize()) {
		if (state.canSwipe) {
			PreviewStrip(
				cards = state.cards,
				currentIndex = state.currentIndex,
				onSelect = { vIndex -> vScope.launch { vPagerState.animateScrollToPage(vIndex) } },
			)
			HorizontalDivider()
		}

		HorizontalPager(
			state = vPagerState,
			// Off while zoomed, or a pan across a magnified card would flick to the next one.
			userScrollEnabled = !state.isZoomed,
			modifier = Modifier.fillMaxSize(),
		) { vPage ->
			val vCard = state.cards[vPage]
			CardDetailContent(
				state = state,
				card = vCard,
				onZoomToggle = onZoomToggle,
				onLanguageSelected = onLanguageSelected,
				onFinishSelected = onFinishSelected,
				onOpenCardmarket = { onOpenCardmarket(vCard.id.qualified) },
				onOpenFullscreen = onOpenFullscreen,
			)
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

	LaunchedEffect(currentIndex) {
		// Centred rather than merely visible: the point of the strip is seeing both directions.
		val vViewport = vListState.layoutInfo.viewportEndOffset - vListState.layoutInfo.viewportStartOffset
		val vItemWidth = vListState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
		vListState.animateScrollToItem(
			index = currentIndex,
			scrollOffset = -(vViewport / 2 - vItemWidth / 2).coerceAtLeast(0),
		)
	}

	LazyRow(
		state = vListState,
		modifier = Modifier.fillMaxWidth(),
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
						.width(if (vIsCurrent) PREVIEW_WIDTH_CURRENT else PREVIEW_WIDTH)
						.aspectRatio(
							if (vCard.orientation == CardOrientation.LANDSCAPE) {
								1039f / 744f
							} else {
								744f / 1039f
							},
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
private fun CardDetailContent(
	state: CardDetailContract.UiState,
	card: CardPrinting,
	onZoomToggle: (Boolean) -> Unit,
	onLanguageSelected: (CardLanguage) -> Unit,
	onFinishSelected: (Finish) -> Unit,
	onOpenCardmarket: () -> Unit,
	onOpenFullscreen: () -> Unit,
	modifier: Modifier = Modifier,
) {
	// Measured outside the scroll on purpose. A vertically scrolling Column hands its children an
	// infinite height constraint, so an `aspectRatio` inside one derives its height from the width
	// and grows as tall as the ratio demands -- which is how a portrait card ended up taller than
	// the window with its own name scrolled off the bottom. This is the last place that still knows
	// how much room there actually is.
	BoxWithConstraints(modifier.fillMaxSize()) {
		val vMaxImageHeight = maxHeight * IMAGE_HEIGHT_FRACTION

		Column(
			modifier = Modifier
				.fillMaxSize()
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 20.dp)
				.padding(bottom = 32.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			ZoomableCardImage(
				card = card,
				maxImageHeight = vMaxImageHeight,
				onZoomToggle = onZoomToggle,
				onOpenFullscreen = onOpenFullscreen,
			)

			Spacer(Modifier.height(16.dp))

			Text(card.displayName, style = MaterialTheme.typography.headlineSmall)
			Text(
				text = "${card.setName} · ${card.collectorNumber}",
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)

			Spacer(Modifier.height(12.dp))

			// Stats and classification, each chip omitted when the provider did not state it.
			FlowRow(
				horizontalArrangement = Arrangement.spacedBy(6.dp),
				verticalArrangement = Arrangement.spacedBy(2.dp),
			) {
				card.classification.rarity?.let { StatChip(it) }
				card.classification.type?.let { StatChip(it) }
				card.classification.supertype?.let { StatChip(it) }
				card.classification.domains.forEach { StatChip(it) }
				card.attributes.energy?.let { StatChip("$it energy") }
				card.attributes.might?.let { StatChip("$it might") }
				card.attributes.power?.let { StatChip("$it power") }
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

			card.artwork.artist?.let { vArtist ->
				Spacer(Modifier.height(10.dp))
				Text(
					text = "Art by $vArtist",
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}

			// ============
			//  Language

			SectionDivider()
			SectionTitle("Language")

			val vResolution = state.languageResolutionFor(card)
			val vShown = vResolution.shown
			if (vShown == null) {
				Note("This card database does not state a printing language for this card.")
			} else if (vResolution.isFallback) {
				// The line that keeps the app honest about French.
				Note(
					"You prefer ${vResolution.requested.displayName}. This database has only " +
						"${vShown.displayName} for this card, so the text and image shown are " +
						"${vShown.displayName}. That does not mean a " +
						"${vResolution.requested.displayName} printing does not exist.",
				)
			}

			Spacer(Modifier.height(8.dp))
			FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				state.languageOptionsFor(card).forEach { vOption ->
					AvailabilityChip(
						label = vOption.language.displayName,
						availability = vOption.availability,
						isSelected = vOption.isSelected,
						onClick = if (vOption.isSelectable) {
							{ onLanguageSelected(vOption.language) }
						} else {
							null
						},
					)
				}
			}

			// ============
			//  Finish

			SectionDivider()
			SectionTitle("Finish")

			val vFinishes = state.finishOptionsFor(card)
			if (vFinishes.isEmpty()) {
				Note(
					if (state.providerStatesFinishes) {
						"No finish is recorded for this card."
					} else {
						"This card database does not record finishes, so none can be offered. " +
							"Foil and non-foil versions may still exist."
					},
				)
			} else {
				Spacer(Modifier.height(8.dp))
				FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					vFinishes.forEach { vOption ->
						AvailabilityChip(
							label = vOption.finish.displayName,
							availability = vOption.availability,
							isSelected = vOption.isSelected,
							onClick = if (vOption.isSelectable) {
								{ onFinishSelected(vOption.finish) }
							} else {
								null
							},
						)
					}
				}
			}

			// ============
			//  Artwork

			state.artworkNote?.let { vNote ->
				SectionDivider()
				SectionTitle("Other artwork")
				Note(vNote)
			}

			// ============
			//  Cardmarket

			state.cardmarketLinkFor(card)?.let { vLink ->
				SectionDivider()
				Spacer(Modifier.height(4.dp))
				OutlinedButton(onClick = onOpenCardmarket, modifier = Modifier.fillMaxWidth()) {
					Icon(
						imageVector = Icons.Outlined.OpenInNew,
						contentDescription = null,
						modifier = Modifier.size(16.dp),
					)
					Spacer(Modifier.size(8.dp))
					Text(vLink.label)
				}
				Note(
					when (vLink) {
						is CardmarketLink.Product ->
							"Opens this card's Cardmarket page. It does not place an order."
						is CardmarketLink.CardSearch ->
							"Opens a Cardmarket search for \"${vLink.terms}\" in ${vLink.expansion}. " +
								"This database does not map cards to Cardmarket products, so this is " +
								"a search rather than an exact product page. It does not place an order."
						is CardmarketLink.ExpansionSingles ->
							"Opens the ${vLink.expansion} singles listing. It does not place an order."
						is CardmarketLink.GameHome ->
							"Opens Cardmarket's Riftbound section. It does not place an order."
					},
				)
			}

			state.attribution?.let { vAttribution ->
				SectionDivider()
				Note(vAttribution)
			}
		}

		// Hints, on the edges, only while there is somewhere to go and nothing is magnified.
		if (state.canSwipe && !state.isZoomed) {
			SwipeHint(Icons.Outlined.ChevronLeft, Alignment.CenterStart, state.currentIndex > 0)
			SwipeHint(
				icon = Icons.Outlined.ChevronRight,
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
 * The large image, pinch- and tap-to-zoom.
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
private fun ZoomableCardImage(
	card: CardPrinting,
	maxImageHeight: Dp,
	onZoomToggle: (Boolean) -> Unit,
	onOpenFullscreen: () -> Unit,
) {
	var vScale by remember(card.id) { mutableFloatStateOf(1f) }
	var vOffsetX by remember(card.id) { mutableFloatStateOf(0f) }
	var vOffsetY by remember(card.id) { mutableFloatStateOf(0f) }

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
			.clip(RoundedCornerShape(12.dp))
			.background(MaterialTheme.colorScheme.surfaceVariant)
			.pointerInput(card.id) {
				// Hand-rolled rather than `detectTransformGestures`, which consumes every drag it
				// sees -- including the one-finger horizontal one that is meant to be a swipe to
				// the next card. Pointer events are only consumed here when there are two fingers
				// down (a pinch, which is never a page swipe) or when the card is already magnified
				// and a drag means "pan". At rest, a horizontal drag passes straight through to the
				// pager.
				awaitEachGesture {
					awaitFirstDown(requireUnconsumed = false)
					do {
						val vEvent = awaitPointerEvent()
						val vIsPinch = vEvent.changes.count { it.pressed } > 1
						if (vIsPinch || vScale > 1f) {
							val vZoom = vEvent.calculateZoom()
							val vPan = vEvent.calculatePan()
							vScale = (vScale * vZoom).coerceIn(1f, 4f)
							if (vScale > 1f) {
								vOffsetX += vPan.x
								vOffsetY += vPan.y
							} else {
								vOffsetX = 0f
								vOffsetY = 0f
							}
							onZoomToggle(vScale > 1f)
							vEvent.changes.forEach { it.consume() }
						}
					} while (vEvent.changes.any { it.pressed })
				}
			}
			.clickable {
				if (vScale > 1f) {
					// Tapping a magnified card puts it back, which is the gesture people try first
					// when they are lost.
					vScale = 1f
					vOffsetX = 0f
					vOffsetY = 0f
					onZoomToggle(false)
				} else {
					// At rest, a tap means "show me this properly".
					onOpenFullscreen()
				}
			},
	) {
		CardImage(
			artwork = card.artwork,
			contentDescription = card.artwork.accessibilityText ?: card.displayName,
			variant = ImageVariant.DISPLAY,
			// The inline image can be pinched, so it needs real pixels rather than a bitmap sized
			// to the box it sits in.
			decodeAtSourceResolution = true,
			contentScale = ContentScale.Fit,
			modifier = Modifier
				.fillMaxSize()
				.graphicsLayer(
					scaleX = vScale,
					scaleY = vScale,
					translationX = vOffsetX,
					translationY = vOffsetY,
				),
		)
	}
}

// ==================
// MARK: Small parts
// ==================

/** A plain fact chip. */
@Composable
private fun StatChip(label: String) {
	Surface(
		color = MaterialTheme.colorScheme.secondaryContainer,
		shape = RoundedCornerShape(20.dp),
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSecondaryContainer,
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
		)
	}
}

/**
 * A chip whose appearance encodes whether the thing is offered, ruled out, or simply unknown.
 *
 * Three states rather than two, because a greyed-out chip that means "we have no idea" and a
 * greyed-out chip that means "this does not exist" must not look the same.
 */
@Composable
private fun AvailabilityChip(
	label: String,
	availability: Availability,
	isSelected: Boolean,
	onClick: (() -> Unit)?,
) {
	val vSuffix = when (availability) {
		Availability.AVAILABLE -> ""
		Availability.UNAVAILABLE -> " — not printed"
		Availability.UNKNOWN -> " — unknown"
	}
	AssistChip(
		onClick = onClick ?: {},
		enabled = onClick != null,
		label = { Text(label + vSuffix) },
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

@Composable
private fun Note(text: String) {
	Spacer(Modifier.height(6.dp))
	Text(
		text = text,
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** Preview thumbnails: small enough that several fit, large enough to recognise the art. */
private val PREVIEW_WIDTH = 34.dp
private val PREVIEW_WIDTH_CURRENT = 44.dp
