package com.bitsycore.cardbrowser.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.ui.common.LoadingState
import com.bitsycore.cardbrowser.ui.preview.PreviewFrame
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

/**
 * The app's first screen: pick a game.
 *
 * Every game shown here has a working, verified adapter behind it. There is no "coming soon" row
 * and no greyed-out entry -- the list is derived from the routing table, so a game the app cannot
 * actually serve is not in it at all. Cyberpunk TCG is absent for exactly that reason.
 *
 * Each row names the source its data comes from, which is both the attribution those sources ask
 * for and useful information: a community mirror and an official API are not the same promise.
 */
@Composable
fun GameListScreen(
	onOpenGame: (Game) -> Unit,
	onOpenSettings: () -> Unit,
	viewModel: GameListViewModel = koinViewModel(),
) {
	val vState by viewModel.collectAsStateWithLifecycle()

	GameListContent(
		state = vState,
		dispatch = viewModel::dispatch,
		onOpenGame = onOpenGame,
		onOpenSettings = onOpenSettings,
	)
}

/**
 * The game picker, given a state and somewhere to send intents.
 *
 * No view model, no Koin, no coroutines, so every state is previewable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameListContent(
	state: GameListContract.UiState,
	dispatch: (GameListContract.Intent) -> Unit,
	onOpenGame: (Game) -> Unit = {},
	onOpenSettings: () -> Unit = {},
) {
	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("Card Browser") },
				actions = {
					IconButton(onClick = onOpenSettings) {
						Icon(Icons.Outlined.Settings, contentDescription = "Settings")
					}
				},
			)
		},
	) { vPadding ->
		Box(Modifier.padding(vPadding).fillMaxSize()) {
			if (state.isLoading) {
				LoadingState()
			} else {
				LazyColumn(
					contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					items(state.games, key = { it.name }) { vGame ->
						GameRow(
							game = vGame,
							source = state.sources[vGame],
							isLastOpened = vGame == state.lastGame,
							onClick = {
								dispatch(GameListContract.Intent.GameOpened(vGame))
								onOpenGame(vGame)
							},
						)
					}

					item {
						Text(
							text = "Every game listed has a working data source. Card data is " +
								"supplied by the projects named above; this app is not affiliated " +
								"with any game's publisher.",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
						)
					}
				}
			}
		}
	}
}

/** One game: its mark, its name, and the source behind it. */
@Composable
private fun GameRow(
	game: Game,
	source: String?,
	isLastOpened: Boolean,
	onClick: () -> Unit,
) {
	val vVisual = GameVisual.of(game)

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
			GameMark(vVisual)
			Spacer(Modifier.size(14.dp))
			Column(Modifier.weight(1f)) {
				Text(
					text = game.displayName,
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.Medium,
				)
				if (source != null) {
					Spacer(Modifier.height(2.dp))
					Text(
						text = source,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			Icon(
				imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

/**
 * A game's mark in a tinted tile.
 *
 * A Material symbol on a flat colour, deliberately -- see [GameVisual] for why this is not a logo.
 */
@Composable
private fun GameMark(visual: GameVisual) {
	Box(
		modifier = Modifier
			.size(48.dp)
			.clip(RoundedCornerShape(12.dp))
			// Tinted rather than saturated, so seven of these in a column read as one list rather
			// than as a paint chart.
			.background(visual.accent.copy(alpha = 0.18f)),
		contentAlignment = Alignment.Center,
	) {
		Icon(
			imageVector = visual.icon,
			contentDescription = null,
			tint = visual.accent,
			modifier = Modifier.size(26.dp),
		)
	}
}

// ==================
// MARK: Previews
// ==================

private val PREVIEW_SOURCES = mapOf(
	Game.RIFTBOUND to "Riftcodex",
	Game.POKEMON to "TCGdex",
	Game.MAGIC to "Scryfall",
	Game.ONE_PIECE to "OPTCG API",
	Game.ALTERED to "Altered TCG Card Database",
	Game.YU_GI_OH to "YGOPRODeck",
	Game.WUTHERING_WAVES to "UCP Wuthering Waves TCG",
)

@Preview
@Composable
private fun GameListPreview() = PreviewFrame {
	GameListContent(
		state = GameListContract.UiState(
			games = Game.entries.toList(),
			sources = PREVIEW_SOURCES,
			lastGame = Game.RIFTBOUND,
			isLoading = false,
		),
		dispatch = {},
	)
}

@Preview
@Composable
private fun GameListLightPreview() = PreviewFrame(isDark = false) {
	GameListContent(
		state = GameListContract.UiState(
			games = Game.entries.toList(),
			sources = PREVIEW_SOURCES,
			lastGame = Game.WUTHERING_WAVES,
			isLoading = false,
		),
		dispatch = {},
	)
}

@Preview
@Composable
private fun GameListLoadingPreview() = PreviewFrame {
	GameListContent(state = GameListContract.UiState(isLoading = true), dispatch = {})
}

@Preview
@Composable
private fun GameListSingleGamePreview() = PreviewFrame {
	// What the screen looked like before six adapters were added, and what it would look like
	// again if the routing table were cut back to one.
	GameListContent(
		state = GameListContract.UiState(
			games = listOf(Game.RIFTBOUND),
			sources = mapOf(Game.RIFTBOUND to "Riftcodex"),
			isLoading = false,
		),
		dispatch = {},
	)
}
