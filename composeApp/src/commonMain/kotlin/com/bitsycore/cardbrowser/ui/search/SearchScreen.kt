package com.bitsycore.cardbrowser.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.bitsycore.cardbrowser.ui.common.sharedCardArt
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.data.repository.SearchScope
import com.bitsycore.cardbrowser.games.altered.AlteredGame
import com.bitsycore.cardbrowser.games.magic.MagicGame
import com.bitsycore.cardbrowser.games.pokemon.PokemonGame
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import com.bitsycore.cardbrowser.ui.common.CardImage
import com.bitsycore.cardbrowser.ui.common.EmptyState
import com.bitsycore.cardbrowser.ui.common.ErrorState
import com.bitsycore.cardbrowser.ui.common.ImageVariant
import com.bitsycore.cardbrowser.ui.common.LoadingState
import com.bitsycore.cardbrowser.ui.common.NoticeBanner
import com.bitsycore.cardbrowser.ui.preview.PreviewData
import com.bitsycore.cardbrowser.ui.preview.PreviewFrame
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import com.bitsycore.cardbrowser.ui.common.AppIcons

/**
 * Search every set of one game.
 *
 * The screen's real job is not finding cards -- the repository does that -- but being straight
 * about *what was searched*. Some providers can search their whole catalogue and some cannot, and
 * for the ones that cannot this searches only the sets already downloaded. Those two produce very
 * different empty results, and conflating them would tell a user a card does not exist when they
 * have simply never opened the set it is in.
 */
@Composable
fun SearchScreen(
	game: GameId,
	onBack: () -> Unit,
	onOpenCard: (CardPrinting) -> Unit,
	viewModel: SearchViewModel = koinViewModel { parametersOf(SearchArgs(game)) },
) {
	val vState by viewModel.collectAsStateWithLifecycle()

	SearchContent(
		state = vState,
		dispatch = viewModel::dispatch,
		onBack = onBack,
		onOpenCard = onOpenCard,
	)
}

/**
 * The search screen, given a state and somewhere to send intents.
 *
 * No view model, no Koin, no coroutines, so every state below is previewable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchContent(
	state: SearchContract.UiState,
	dispatch: (SearchContract.Intent) -> Unit,
	onBack: () -> Unit = {},
	onOpenCard: (CardPrinting) -> Unit = {},
) {
	val vState = state
	val vFocus = remember { FocusRequester() }

	// Focused once, on the way in, and never again.
	//
	// The user got here by tapping a search button, so the keyboard should already be up. But this
	// screen is recomposed from scratch when they come back from a card, and a plain
	// `LaunchedEffect(Unit)` fires again then -- throwing the keyboard back over the results they
	// returned to look at. `rememberSaveable` survives the trip, so the second arrival is quiet.
	var vHasFocused by rememberSaveable { mutableStateOf(false) }
	LaunchedEffect(Unit) {
		if (!vHasFocused) {
			vHasFocused = true
			runCatching { vFocus.requestFocus() }
		}
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("Search ${vState.game?.shortName.orEmpty()}") },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
					}
				},
			)
		},
	) { vPadding ->
		Column(Modifier.padding(vPadding).fillMaxSize()) {

			OutlinedTextField(
				value = vState.query,
				onValueChange = { dispatch(SearchContract.Intent.QueryChanged(it)) },
				label = { Text("Card name") },
				leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
				trailingIcon = {
					if (vState.query.isNotEmpty()) {
						IconButton(onClick = { dispatch(SearchContract.Intent.Clear) }) {
							Icon(Icons.Outlined.Close, contentDescription = "Clear")
						}
					}
				},
				singleLine = true,
				// The keyboard's action still submits, which matters for the sources that go to the
				// network -- several ask callers to go easy, and a request per character would be
				// rude. A cache-scoped search has no such cost and runs as you type; the view model
				// decides which of the two applies. See `SearchViewModel.handleIntent`.
				keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
				keyboardActions = KeyboardActions(onSearch = { dispatch(SearchContract.Intent.Submit) }),
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 8.dp)
					.focusRequester(vFocus),
			)

			CoverageNotice(vState, dispatch)

			Box(Modifier.weight(1f)) {
				when {
					vState.isLoading && vState.results.isEmpty() -> LoadingState()

					vState.results.isEmpty() && vState.error != null -> ErrorState(
						error = vState.error,
						onRetry = { dispatch(SearchContract.Intent.Submit) },
					)

					vState.isIdle -> EmptyState(
						if (vState.isProviderSearchable) {
							"Search every ${vState.game?.shortName.orEmpty()} set by card name."
						} else {
							"Search the ${vState.game?.shortName.orEmpty()} sets you have already opened."
						},
					)

					vState.isEmptyResult -> EmptyState(emptyMessageFor(vState))

					else -> LazyColumn(
						contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp),
					) {
						items(vState.results, key = { it.id.qualified }) { vCard ->
							SearchResultRow(card = vCard, onClick = { onOpenCard(vCard) })
						}

						if (vState.isTruncated) {
							item {
								// Never silently truncate. A page of matches is not the match list.
								Text(
									text = truncationMessage(vState),
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.fillMaxWidth().padding(16.dp),
								)
							}
						}
					}
				}
			}
		}
	}
}

/**
 * The strip that says what was actually searched.
 *
 * This is the whole reason the screen distinguishes [SearchScope] values, and it is shown before
 * the results rather than under them.
 */
@Composable
private fun CoverageNotice(
	state: SearchContract.UiState,
	dispatch: (SearchContract.Intent) -> Unit,
) {
	when {
		state.error != null && state.results.isNotEmpty() -> NoticeBanner(
			text = "Showing matches from saved sets. The search request failed.",
			icon = AppIcons.CloudOff,
			onAction = { dispatch(SearchContract.Intent.Submit) },
		)

		state.coverageNotice != null -> NoticeBanner(
			text = state.coverageNotice.orEmpty(),
			icon = AppIcons.Storage,
			onAction = null,
		)
	}
}

/**
 * What to say when nothing matched.
 *
 * Two genuinely different sentences, because two genuinely different things happened.
 */
private fun emptyMessageFor(state: SearchContract.UiState): String = when {
	state.scope == SearchScope.LOCAL_CACHED_SETS && state.searchedSetCount == 0 ->
		"Nothing downloaded yet, so there was nothing to search."

	state.scope == SearchScope.LOCAL_CACHED_SETS ->
		"No match in the ${state.searchedSetCount} " +
			"${if (state.searchedSetCount == 1) "set" else "sets"} you have downloaded."

	else -> "No ${state.game?.shortName.orEmpty()} card matches \"${state.submitted}\"."
}

/** How many matched against how many are shown, when the provider says. */
private fun truncationMessage(state: SearchContract.UiState): String {
	val vTotal = state.totalCount
	return if (vTotal != null) {
		"Showing the first ${state.results.size} of $vTotal matches. Narrow the search to see more."
	} else {
		"More matches exist than are shown. Narrow the search to see them."
	}
}

/** One result: art, name, and which set it is from -- which is the point of a cross-set search. */
@Composable
private fun SearchResultRow(card: CardPrinting, onClick: () -> Unit) {
	Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
		Row(
			modifier = Modifier.padding(12.dp).fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Box(Modifier.width(52.dp).aspectRatio(CARD_ASPECT)) {
				CardImage(
					artwork = card.artwork,
					contentDescription = null,
					variant = ImageVariant.THUMBNAIL,
					// The same element as the large image on the detail screen, so opening a result
					// grows its art out of this row rather than cross-fading two pictures. Keyed on
					// the printing's id exactly as the grid's tiles are, which is what lets a card
					// reached by search animate like one reached by browsing.
					modifier = Modifier.fillMaxSize().sharedCardArt(card.id.qualified),
				)
			}
			Spacer(Modifier.size(12.dp))
			Column(Modifier.weight(1f)) {
				Text(
					text = card.displayName,
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.Medium,
					maxLines = 2,
				)
				Spacer(Modifier.height(2.dp))
				Text(
					// The set is the whole reason a cross-set result is worth showing.
					text = buildList {
						add(card.setName)
						add("#${card.collectorNumber}")
						card.classification.rarity?.let { add(it) }
					}.joinToString(" · "),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
				)
			}
		}
	}
}

/** The usual trading-card ratio, which every game here shares. */
private const val CARD_ASPECT = 0.716f

// ==================
// MARK: Previews
// ==================

@Preview
@Composable
private fun SearchResultsPreview() = PreviewFrame {
	SearchContent(
		state = SearchContract.UiState(
			game = RiftboundGame,
			query = "annie",
			submitted = "annie",
			results = PreviewData.CARDS,
			scope = SearchScope.REMOTE_ALL_SETS,
			knownSetCount = 6,
			searchedSetCount = 6,
			totalCount = 10,
		),
		dispatch = {},
	)
}

@Preview
@Composable
private fun SearchCacheLimitedPreview() = PreviewFrame {
	// The state this screen exists for: a provider that cannot search remotely, so only part of
	// the game was looked at, and the screen says exactly how much.
	SearchContent(
		state = SearchContract.UiState(
			game = AlteredGame,
			query = "vaike",
			submitted = "vaike",
			results = PreviewData.CARDS.take(3),
			scope = SearchScope.LOCAL_CACHED_SETS,
			searchedSetCount = 2,
			knownSetCount = 20,
			isProviderSearchable = false,
		),
		dispatch = {},
	)
}

@Preview
@Composable
private fun SearchNothingDownloadedPreview() = PreviewFrame(isDark = false) {
	// An empty result that is *not* evidence the card does not exist.
	SearchContent(
		state = SearchContract.UiState(
			game = AlteredGame,
			query = "vaike",
			submitted = "vaike",
			scope = SearchScope.LOCAL_CACHED_SETS,
			searchedSetCount = 0,
			knownSetCount = 20,
			isProviderSearchable = false,
		),
		dispatch = {},
	)
}

@Preview
@Composable
private fun SearchIdlePreview() = PreviewFrame {
	SearchContent(state = SearchContract.UiState(game = PokemonGame), dispatch = {})
}

@Preview
@Composable
private fun SearchFailedPreview() = PreviewFrame {
	SearchContent(
		state = SearchContract.UiState(
			game = MagicGame,
			query = "bolt",
			submitted = "bolt",
			error = ProviderError.Offline(),
		),
		dispatch = {},
	)
}
