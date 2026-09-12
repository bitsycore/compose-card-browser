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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.ui.text.style.TextOverflow
import com.bitsycore.cardbrowser.ui.common.AppIcons
import com.bitsycore.cardbrowser.ui.common.FilterSection
import com.bitsycore.cardbrowser.ui.common.FilterValueChip
import com.bitsycore.cardbrowser.ui.common.domainColourOf
import com.bitsycore.cardbrowser.ui.common.rarityColourOf
import com.bitsycore.cardbrowser.ui.common.RemovableFilterChip

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
	/** The one set to search, or null for the whole game. */
	setId: String? = null,
	onBack: () -> Unit,
	onOpenCard: (CardPrinting) -> Unit,
	viewModel: SearchViewModel = koinViewModel { parametersOf(SearchArgs(game, setId)) },
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
	// Dismissing the keyboard on Enter is half the screen back. Two mechanisms because they are
	// two different things: the keyboard is the platform's, and the focus is Compose's -- leaving
	// the field focused would bring the keyboard straight back on the next tap anywhere.
	val vKeyboard = LocalSoftwareKeyboardController.current
	val vFocusManager = LocalFocusManager.current

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
				title = {
					Text(
						text = vState.scopedSetName
							?.let { "Search $it" }
							?: "Search ${vState.game?.shortName.orEmpty()}",
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				},
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
				keyboardActions = KeyboardActions(
					onSearch = {
						dispatch(SearchContract.Intent.Submit)
						vKeyboard?.hide()
						vFocusManager.clearFocus()
					},
				),
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 8.dp)
					.focusRequester(vFocus),
			)

			// Search is a text box for most people most of the time, so the rest is a button and
			// a sheet rather than a panel that opens on six controls.
			FilterBar(
				state = vState,
				onOpenSheet = { dispatch(SearchContract.Intent.AdvancedToggled(true)) },
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
						if (vState.scopedSetName != null) {
							"Search the cards you have downloaded from ${vState.scopedSetName}."
						} else if (vState.isProviderSearchable) {
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

	if (vState.isAdvancedOpen) {
		// The same sheet states the card grid's filter uses, and for the same reason: a filter
		// panel at half height is a worse filter panel.
		val vSheetState = rememberBottomSheetState(
			SheetValue.Hidden,
			setOf(SheetValue.Hidden, SheetValue.Expanded),
		)
		ModalBottomSheet(
			onDismissRequest = { dispatch(SearchContract.Intent.AdvancedToggled(false)) },
			sheetState = vSheetState,
		) {
			SearchFilterSheet(
				state = vState,
				onFilterChanged = { dispatch(SearchContract.Intent.FilterChanged(it)) },
			)
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
private fun FilterBar(
	state: SearchContract.UiState,
	onOpenSheet: () -> Unit,
	onFilterChanged: (CardSearchFilter) -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		TextButton(onClick = onOpenSheet) {
			Icon(AppIcons.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
			Spacer(Modifier.width(6.dp))
			// The count is on the button and not beside it, because it is what the button is
			// about. A filter you cannot see is the reason a search returns nothing, so how many
			// are set has to be readable while the sheet is shut.
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
	ActiveSearchFilterChips(state, onFilterChanged)
}

/**
 * The filters that are on, under the search box, each removable in one tap.
 *
 * The card grid has a row of the same name for the same reason: a filter the user cannot see is a
 * filter they will blame the app for when a search comes back empty.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveSearchFilterChips(
	state: SearchContract.UiState,
	onFilterChanged: (CardSearchFilter) -> Unit,
) {
	if (!state.hasAdvancedFilters) return
	val vFilter = state.filter
	FlowRow(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		vFilter.excludeText?.takeIf { it.isNotBlank() }?.let { vText ->
			RemovableFilterChip("not \"$vText\"") {
				onFilterChanged(vFilter.copy(excludeText = null))
			}
		}
		vFilter.cardType?.let {
			RemovableFilterChip(it) { onFilterChanged(vFilter.copy(cardType = null)) }
		}
		vFilter.rarity?.let {
			RemovableFilterChip(it) { onFilterChanged(vFilter.copy(rarity = null)) }
		}
		vFilter.domain?.let {
			RemovableFilterChip(it) { onFilterChanged(vFilter.copy(domain = null)) }
		}
		if (vFilter.minCost != null || vFilter.maxCost != null) {
			val vLabel = state.game?.vocabulary?.cost ?: "Cost"
			val vFrom = vFilter.minCost?.toString() ?: "any"
			val vTo = vFilter.maxCost?.toString() ?: "any"
			RemovableFilterChip("$vLabel $vFrom-$vTo") {
				onFilterChanged(vFilter.copy(minCost = null, maxCost = null))
			}
		}
	}
}

/**
 * The filters themselves, in a sheet.
 *
 * A sheet rather than a panel that unfolds in place, which is what this was: six controls pushed in
 * between the search box and the results, so the results left the screen exactly when the user was
 * trying to narrow them. Chips rather than dropdown buttons, so what is set is readable without
 * opening anything -- the same controls the card grid's filter uses, because to anyone using them
 * the two are one thing.
 */
@Composable
private fun SearchFilterSheet(
	state: SearchContract.UiState,
	onFilterChanged: (CardSearchFilter) -> Unit,
) {
	val vFacets = state.facets
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.verticalScroll(rememberScrollState())
			.padding(horizontal = 20.dp)
			.padding(bottom = 32.dp),
	) {
		OutlinedTextField(
			value = state.filter.excludeText.orEmpty(),
			onValueChange = {
				onFilterChanged(state.filter.copy(excludeText = it.takeIf(String::isNotBlank)))
			},
			label = { Text("Name does not contain") },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)

		// One value at a time per axis, which is what the store's query takes. Tapping the chip
		// that is already on turns it off, and that is the whole of what "Any" used to be.
		if (vFacets != null && vFacets.cardTypes.isNotEmpty()) {
			FilterSection(state.game?.vocabulary?.cardType ?: "Type") {
				vFacets.cardTypes.forEach { vValue ->
					FilterValueChip(vValue, state.filter.cardType == vValue) {
						onFilterChanged(
							state.filter.copy(
								cardType = vValue.takeIf { state.filter.cardType != vValue },
							),
						)
					}
				}
			}
		}
		if (vFacets != null && vFacets.rarities.isNotEmpty()) {
			FilterSection("Rarity") {
				vFacets.rarities.forEach { vValue ->
					FilterValueChip(
						label = vValue,
						isSelected = state.filter.rarity == vValue,
						colour = rarityColourOf(state.game, vValue),
					) {
						onFilterChanged(
							state.filter.copy(
								rarity = vValue.takeIf { state.filter.rarity != vValue },
							),
						)
					}
				}
			}
		}
		if (vFacets != null && vFacets.domains.isNotEmpty()) {
			// The game's own word -- "Colour" for Magic, "Faction" for Altered -- and its own
			// order, which no alphabetical sort produces and a player reads as wrong.
			FilterSection(state.game?.vocabulary?.domain ?: "Domain") {
				vFacets.domains.sortedBy { vKey ->
					val vIndex = state.game?.domains?.indexOfFirst { it.key.equals(vKey, true) } ?: -1
					if (vIndex >= 0) vIndex else Int.MAX_VALUE
				}.forEach { vValue ->
					FilterValueChip(
						label = state.game?.domainFor(vValue)?.label ?: vValue,
						isSelected = state.filter.domain == vValue,
						colour = domainColourOf(state.game, vValue),
					) {
						onFilterChanged(
							state.filter.copy(
								domain = vValue.takeIf { state.filter.domain != vValue },
							),
						)
					}
				}
			}
		}

		// Pulled out of the facets first: it comes from another module, so it cannot be smart-cast
		// out of the nullable property it lives on.
		val vCostRange = vFacets?.costRange
		if (vCostRange != null) {
			Spacer(Modifier.height(16.dp))
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
