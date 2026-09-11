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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import com.bitsycore.cardbrowser.data.cache.CardSearchFilter
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.bitsycore.lib.pulse.compose.collectEffect
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
	// The only part of this screen that knows a back stack exists. Everything below dispatches.
	viewModel.collectEffect { vEffect ->
		when (vEffect) {
			SearchContract.Effect.NavigateBack -> onBack()
			is SearchContract.Effect.OpenCard -> onOpenCard(vEffect.card)
		}
	}
	val vState by viewModel.collectAsStateWithLifecycle()

	SearchContent(state = vState, dispatch = viewModel::dispatch)
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
					IconButton(onClick = { dispatch(SearchContract.Intent.BackPressed) }) {
						Icon(AppIcons.ArrowBack, contentDescription = "Back")
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
				leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
				trailingIcon = {
					if (vState.query.isNotEmpty()) {
						IconButton(onClick = { dispatch(SearchContract.Intent.Clear) }) {
							Icon(AppIcons.Close, contentDescription = "Clear")
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

			// The advanced filter, folded away until asked for. Search is a text box for most
			// people most of the time, and a screen that opens on six controls says otherwise.
			AdvancedFilterPanel(
				state = vState,
				onToggle = { dispatch(SearchContract.Intent.AdvancedToggled(it)) },
				onFilterChanged = { dispatch(SearchContract.Intent.FilterChanged(it)) },
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
							SearchResultRow(card = vCard, onClick = { dispatch(SearchContract.Intent.CardOpened(vCard)) })
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

// ==================
// MARK: The advanced filter
// ==================

/**
 * Everything a search can narrow on beyond a name.
 *
 * Only the controls this game's stored cards can actually fill. A source that publishes no rarity
 * leaves the rarity menu out entirely rather than showing an empty one -- which is the same rule
 * the rest of this app follows about claims: an empty menu says "there are none", and the truth is
 * "this source does not say".
 *
 * Collapsed by default. Search is a text box for most people most of the time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedFilterPanel(
	state: SearchContract.UiState,
	onToggle: (Boolean) -> Unit,
	onFilterChanged: (CardSearchFilter) -> Unit,
) {
	val vFacets = state.facets
	Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			TextButton(onClick = { onToggle(!state.isAdvancedOpen) }) {
				Icon(AppIcons.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
				Spacer(Modifier.width(6.dp))
				// The count is on the button and not beside it, because it is what the button is
				// about. A filter you cannot see is the reason a search returns nothing, so how
				// many are set has to be readable while the panel is shut.
				Text(
					if (state.hasAdvancedFilters) {
						"Filters (${state.activeAdvancedCount})"
					} else {
						"Filters"
					},
				)
			}
			if (state.hasAdvancedFilters) {
				Spacer(Modifier.weight(1f))
				TextButton(onClick = { onFilterChanged(CardSearchFilter()) }) { Text("Reset") }
			}
		}

		AnimatedVisibility(visible = state.isAdvancedOpen) {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				OutlinedTextField(
					value = state.filter.excludeText.orEmpty(),
					onValueChange = {
						onFilterChanged(state.filter.copy(excludeText = it.takeIf(String::isNotBlank)))
					},
					label = { Text("Name does not contain") },
					singleLine = true,
					modifier = Modifier.fillMaxWidth(),
				)

				// The pills flow rather than stack. Their labels are a word each, so a column of
				// them wasted most of the width and made the panel tall enough to push the results
				// off a phone screen -- and a filter you have to scroll past is one you stop using.
				// They wrap when the window is too narrow, which is the same behaviour, later.
				FlowRow(
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					if (vFacets != null && vFacets.cardTypes.isNotEmpty()) {
						FilterChoice(
							label = state.game?.vocabulary?.cardType ?: "Type",
							options = vFacets.cardTypes,
							selected = state.filter.cardType,
							onSelected = { onFilterChanged(state.filter.copy(cardType = it)) },
						)
					}
					if (vFacets != null && vFacets.rarities.isNotEmpty()) {
						FilterChoice(
							label = "Rarity",
							options = vFacets.rarities,
							selected = state.filter.rarity,
							onSelected = { onFilterChanged(state.filter.copy(rarity = it)) },
						)
					}
					if (vFacets != null && vFacets.domains.isNotEmpty()) {
						FilterChoice(
							// The game's own word -- "Colour" for Magic, "Faction" for Altered.
							label = state.game?.vocabulary?.domain ?: "Domain",
							options = vFacets.domains,
							selected = state.filter.domain,
							onSelected = { onFilterChanged(state.filter.copy(domain = it)) },
						)
					}
				}
				// Pulled out of the facets first: it comes from another module, so it cannot be
				// smart-cast out of the nullable property it lives on.
				val vCostRange = vFacets?.costRange
				if (vCostRange != null) {
					CostRangeFields(
						label = state.game?.vocabulary?.cost ?: "Cost",
						range = vCostRange,
						min = state.filter.minCost,
						max = state.filter.maxCost,
						onChanged = { vMin, vMax ->
							onFilterChanged(state.filter.copy(minCost = vMin, maxCost = vMax))
						},
					)
				}
			}
		}
	}
}

/** One menu of values, with "Any" as the way back out of a choice. */
@Composable
private fun FilterChoice(
	label: String,
	options: List<String>,
	selected: String?,
	onSelected: (String?) -> Unit,
) {
	var vIsOpen by remember { mutableStateOf(false) }
	Box {
		OutlinedButton(onClick = { vIsOpen = true }) {
			Text(if (selected == null) label else "$label: $selected")
			Icon(AppIcons.ArrowDropDown, contentDescription = null)
		}
		DropdownMenu(expanded = vIsOpen, onDismissRequest = { vIsOpen = false }) {
			DropdownMenuItem(
				text = { Text("Any") },
				onClick = {
					onSelected(null)
					vIsOpen = false
				},
			)
			options.forEach { vOption ->
				DropdownMenuItem(
					text = { Text(vOption) },
					onClick = {
						onSelected(vOption)
						vIsOpen = false
					},
					trailingIcon = {
						if (vOption == selected) Icon(AppIcons.Check, contentDescription = null)
					},
				)
			}
		}
	}
}

/**
 * The cost range, as two numbers.
 *
 * Blank means "no bound", which is not the same as the range's own end: leaving the maximum empty
 * asks for everything upwards, including a card whose cost is higher than anything currently
 * stored. The placeholders show what is actually present so the fields are not typed into blind.
 */
@Composable
private fun CostRangeFields(
	label: String,
	range: IntRange,
	min: Int?,
	max: Int?,
	onChanged: (Int?, Int?) -> Unit,
) {
	Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		OutlinedTextField(
			value = min?.toString().orEmpty(),
			onValueChange = { onChanged(it.toIntOrNull(), max) },
			label = { Text("$label from") },
			placeholder = { Text(range.first.toString()) },
			singleLine = true,
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
			modifier = Modifier.weight(1f),
		)
		OutlinedTextField(
			value = max?.toString().orEmpty(),
			onValueChange = { onChanged(min, it.toIntOrNull()) },
			label = { Text("$label to") },
			placeholder = { Text(range.last.toString()) },
			singleLine = true,
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
			modifier = Modifier.weight(1f),
		)
	}
}
