package com.bitsycore.cardbrowser.ui.sets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TravelExplore
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.data.repository.DataOrigin
import com.bitsycore.cardbrowser.ui.common.EmptyState
import com.bitsycore.cardbrowser.ui.common.ErrorState
import com.bitsycore.cardbrowser.ui.common.LoadingState
import com.bitsycore.cardbrowser.ui.common.NoticeBanner
import com.bitsycore.cardbrowser.ui.preview.PreviewData
import com.bitsycore.cardbrowser.ui.preview.PreviewFrame
import androidx.compose.ui.tooling.preview.Preview
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The Riftbound set list: the app's first screen.
 *
 * Newest first, searchable by name or code, and honest about whether what is on screen came off the
 * network or off the disk.
 */
@Composable
fun SetListScreen(
	game: Game,
	onBack: () -> Unit,
	onOpenSet: (CardSet) -> Unit,
	onOpenSettings: () -> Unit,
	onOpenSearch: (Game) -> Unit,
	viewModel: SetListViewModel = koinViewModel { parametersOf(SetListArgs(game)) },
) {
	val vState by viewModel.collectAsStateWithLifecycle()

	SetListContent(
		state = vState,
		dispatch = viewModel::dispatch,
		onBack = onBack,
		onOpenSet = onOpenSet,
		onOpenSettings = onOpenSettings,
		onOpenSearch = onOpenSearch,
	)
}

/**
 * The set list, given a state and somewhere to send intents.
 *
 * No view model, no Koin, no coroutines: everything it needs arrives as arguments, which is what
 * makes it previewable and what keeps the screen's layout separable from how its data is obtained.
 * Navigation stays as callbacks rather than intents -- where the app goes next is the caller's
 * business, not this screen's state machine's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetListContent(
	state: SetListContract.UiState,
	dispatch: (SetListContract.Intent) -> Unit,
	onBack: () -> Unit = {},
	onOpenSet: (CardSet) -> Unit,
	onOpenSettings: () -> Unit,
	onOpenSearch: (Game) -> Unit = {},
) {
	val vState = state

	Scaffold(
		topBar = {
			TopAppBar(
				title = {
					Row(verticalAlignment = Alignment.CenterVertically) {
						// A neutral mark, not the game's logo: the provider ships no artwork for a
						// game either, and inventing something that looks official would be worse
						// than a plain one.
						Icon(
							imageVector = Icons.Outlined.Style,
							contentDescription = null,
							modifier = Modifier.size(22.dp),
							tint = MaterialTheme.colorScheme.primary,
						)
						Spacer(Modifier.size(10.dp))
						Text(vState.game.shortName)
					}
				},
				actions = {
					IconButton(onClick = { onOpenSearch(vState.game) }) {
						Icon(
							Icons.Outlined.TravelExplore,
							contentDescription = "Search cards across all sets",
						)
					}
					IconButton(onClick = onOpenSettings) {
						Icon(Icons.Outlined.Settings, contentDescription = "Settings")
					}
				},
			)
		},
	) { vPadding ->
		Column(Modifier.padding(vPadding).fillMaxSize()) {

			// Hidden entirely when only one game is routed, rather than shown as a single chip
			// that cannot be changed.
			if (vState.availableGames.size > 1) {
				GameSwitcher(
					games = vState.availableGames,
					selected = vState.game,
					onSelect = { dispatch(SetListContract.Intent.GameChanged(it)) },
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
						error = vState.error!!,
						onRetry = { dispatch(SetListContract.Intent.Refresh) },
					)

					vState.isEmptySearch -> EmptyState("No set matches \"${vState.search}\".")

					else -> LazyColumn(
						contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp),
					) {
						items(vState.visibleSets, key = { it.id.qualified }) { vSet ->
							SetRow(
								set = vSet,
								isLastOpened = vSet.id.qualified == vState.lastOpenedSetId,
								isSaved = vSet.id.qualified in vState.savedSetIds,
								onClick = {
									dispatch(SetListContract.Intent.SetOpened(vSet.id.qualified))
									onOpenSet(vSet)
								},
							)
						}
					}
				}
			}
		}
	}
}

/**
 * The games this build can actually serve, as a scrolling row of chips.
 *
 * A row rather than a dropdown because the list is short, fixed and worth seeing: which games are
 * available is a genuine property of the build, and a menu would hide it behind a tap.
 */
@Composable
private fun GameSwitcher(
	games: List<Game>,
	selected: Game,
	onSelect: (Game) -> Unit,
) {
	val vScroll = rememberScrollState()
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.horizontalScroll(vScroll)
			.padding(horizontal = 16.dp, vertical = 4.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		games.forEach { vGame ->
			FilterChip(
				selected = vGame == selected,
				onClick = { onSelect(vGame) },
				label = { Text(vGame.shortName) },
			)
		}
	}
}

/** One set: name, code, card count and release date, plus a mark for where you left off. */
@Composable
private fun SetRow(
	set: CardSet,
	isLastOpened: Boolean,
	isSaved: Boolean,
	onClick: () -> Unit,
) {
	Card(
		onClick = onClick,
		modifier = Modifier.fillMaxWidth(),
		colors = if (isLastOpened) {
			CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
		} else {
			CardDefaults.cardColors()
		},
	) {
		Row(
			modifier = Modifier.padding(16.dp).fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			SetMonogram(set.code, isHighlighted = isLastOpened)
			Spacer(Modifier.size(12.dp))
			Column(Modifier.weight(1f)) {
				Text(
					text = set.name,
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.Medium,
				)
				Spacer(Modifier.height(2.dp))
				Text(
					text = setSubtitle(set),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			if (isSaved) {
				Spacer(Modifier.size(8.dp))
				Icon(
					imageVector = Icons.Outlined.OfflinePin,
					// "Saved", not "complete". A set interrupted part-way through leaves a file
					// behind too, and the mark must not promise more than that.
					contentDescription = "Saved on this device",
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(20.dp),
				)
			}
			if (isLastOpened) {
				Spacer(Modifier.size(8.dp))
				Text(
					text = "Last opened",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSecondaryContainer,
				)
			}
		}
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
			.size(44.dp)
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
			// Promo codes are two characters, expansions three; anything longer is truncated
			// rather than shrunk to illegibility.
			text = code.take(4),
			style = MaterialTheme.typography.labelLarge,
			fontWeight = FontWeight.Medium,
			maxLines = 1,
			color = if (isHighlighted) MaterialTheme.colorScheme.onPrimary else vTint,
		)
	}
}

/**
 * A stable colour for a set, derived from its code.
 *
 * Placeholder work, and deliberately so: none of the seven providers publishes a set symbol -- the
 * only image in any of their schemas is a card's own art -- so until one does, the alternative is a
 * column of identical grey tiles that are genuinely hard to tell apart when scrolling a catalogue
 * of several hundred Magic sets.
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
 * "OGN · 352 cards · Oct 2025", with each part dropped when the provider does not supply it.
 *
 * A missing release date shows nothing rather than "Unknown date": the row is not the place to
 * discuss what the provider does not know.
 */
private fun setSubtitle(set: CardSet): String = buildList {
	add(set.code)
	set.cardCount?.let { add("$it cards") }
	set.releaseDate?.let { add("${monthName(it.month.ordinal)} ${it.year}") }
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
		onOpenSet = {},
		onOpenSettings = {},
	)
}

@Preview
@Composable
private fun SetListLoadingPreview() = PreviewFrame {
	SetListContent(
		state = SetListContract.UiState(isLoading = true),
		dispatch = {},
		onOpenSet = {},
		onOpenSettings = {},
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
		onOpenSet = {},
		onOpenSettings = {},
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
		onOpenSet = {},
		onOpenSettings = {},
	)
}

@Preview
@Composable
private fun SetListGameSwitcherPreview() = PreviewFrame {
	// Every routed game at once, which is the state the switcher exists for.
	SetListContent(
		state = SetListContract.UiState(
			sets = PreviewData.SETS,
			isLoading = false,
			availableGames = Game.entries.toList(),
			game = Game.RIFTBOUND,
		),
		dispatch = {},
		onOpenSet = {},
		onOpenSettings = {},
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
		onOpenSet = {},
		onOpenSettings = {},
	)
}
