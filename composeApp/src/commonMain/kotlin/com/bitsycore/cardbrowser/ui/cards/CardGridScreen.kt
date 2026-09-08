package com.bitsycore.cardbrowser.ui.cards

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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardOrientation
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.ui.common.CardImage
import com.bitsycore.cardbrowser.ui.common.ImageVariant
import com.bitsycore.cardbrowser.ui.common.EmptyState
import com.bitsycore.cardbrowser.ui.common.ErrorState
import com.bitsycore.cardbrowser.ui.common.LoadingState
import com.bitsycore.cardbrowser.ui.common.NoticeBanner
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.viewmodel.koinViewModel

/**
 * One set's cards, as a grid of readable card images.
 *
 * A tile per distinct printing, which for Riftcodex means a tile per artwork: alternate art,
 * overnumbered and signature versions each get their own, because each is a different picture. What
 * does *not* multiply tiles is finish or language -- those are choices inside the detail screen, and
 * duplicating a tile for them would triple a set with nothing new to look at.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardGridScreen(
	setId: String,
	setName: String,
	setCode: String,
	onBack: () -> Unit,
	onOpenCard: (CardPrinting) -> Unit,
	viewModel: CardGridViewModel = koinViewModel(),
) {
	val vState by viewModel.collectAsStateWithLifecycle()

	// Dispatched once per set. Keyed on setId so reusing this view model for a different set
	// re-runs it, and so returning from detail does not restart the load.
	LaunchedEffect(setId) {
		if (vState.setId != setId) {
			viewModel.dispatch(CardGridContract.Intent.SetSelected(setId, setName, setCode))
		}
	}

	val vGridState = rememberLazyGridState(initialFirstVisibleItemIndex = vState.firstVisibleIndex)

	// Scroll position is kept in the view model rather than only in the grid state, so it survives
	// the trip into card detail and back even though this composable leaves the composition.
	LaunchedEffect(vGridState) {
		snapshotFlow { vGridState.firstVisibleItemIndex }
			.distinctUntilChanged()
			.collect { viewModel.dispatch(CardGridContract.Intent.ScrollPositionChanged(it)) }
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = {
					Column {
						Text(vState.setName.ifBlank { setName })
						Text(
							text = gridSubtitle(vState),
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
					BadgedBox(
						badge = {
							if (vState.activeFilterCount > 0) {
								Badge { Text("${vState.activeFilterCount}") }
							}
						},
					) {
						IconButton(
							onClick = { viewModel.dispatch(CardGridContract.Intent.FilterSheetToggled(true)) },
						) {
							Icon(Icons.Outlined.FilterList, contentDescription = "Filters")
						}
					}
				},
			)
		},
	) { vPadding ->
		Column(Modifier.padding(vPadding).fillMaxSize()) {

			OutlinedTextField(
				value = vState.query.text.orEmpty(),
				onValueChange = { vText ->
					viewModel.dispatch(
						CardGridContract.Intent.QueryChanged(
							vState.query.copy(text = vText.takeIf { it.isNotBlank() }),
						),
					)
				},
				label = { Text("Name or collector number") },
				leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
				singleLine = true,
				modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
			)

			ActiveFilterChips(
				state = vState,
				onQueryChanged = { viewModel.dispatch(CardGridContract.Intent.QueryChanged(it)) },
				onClearAll = { viewModel.dispatch(CardGridContract.Intent.ClearFilters) },
			)

			vState.coverageNotice?.let { vNotice ->
				NoticeBanner(
					text = vNotice,
					onAction = if (vState.noticeIsRetryable) {
						{ viewModel.dispatch(CardGridContract.Intent.Load) }
					} else {
						null
					},
				)
			}

			Box(Modifier.weight(1f)) {
				when {
					vState.isInitialLoad -> LoadingState()

					vState.cards.isEmpty() && vState.error != null -> ErrorState(
						error = vState.error!!,
						onRetry = { viewModel.dispatch(CardGridContract.Intent.Load) },
					)

					vState.isEmptyAfterFilter -> EmptyState(
						message = if (vState.query.isEmpty) {
							"This set has no cards."
						} else {
							"No card matches these filters."
						},
					)

					else -> CardGrid(
						cards = vState.cards,
						gridState = vGridState,
						onOpenCard = onOpenCard,
					)
				}
			}
		}
	}

	if (vState.isFilterSheetOpen) {
		val vSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
		ModalBottomSheet(
			onDismissRequest = { viewModel.dispatch(CardGridContract.Intent.FilterSheetToggled(false)) },
			sheetState = vSheetState,
		) {
			FilterSheet(
				state = vState,
				onQueryChanged = { viewModel.dispatch(CardGridContract.Intent.QueryChanged(it)) },
				onClearAll = { viewModel.dispatch(CardGridContract.Intent.ClearFilters) },
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
) {
	LazyVerticalGrid(
		columns = GridCells.Adaptive(minSize = MIN_TILE_WIDTH.dp),
		state = gridState,
		contentPadding = PaddingValues(12.dp),
		horizontalArrangement = Arrangement.spacedBy(10.dp),
		verticalArrangement = Arrangement.spacedBy(14.dp),
		modifier = Modifier.fillMaxSize(),
	) {
		items(cards, key = { it.id.qualified }) { vCard ->
			CardTile(card = vCard, onClick = { onOpenCard(vCard) })
		}
	}
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

/** "352 cards" or "12 of 352", so the count in the bar always means something exact. */
private fun gridSubtitle(state: CardGridContract.UiState): String = when {
	state.knownSetSize != null && state.cards.size != state.knownSetSize ->
		"${state.cards.size} of ${state.knownSetSize}"
	state.knownSetSize != null -> "${state.knownSetSize} cards"
	else -> "${state.cards.size} cards"
}

/** Wide enough that a card's name and art stay legible on a phone. */
private const val MIN_TILE_WIDTH = 108
