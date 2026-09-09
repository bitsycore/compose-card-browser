package com.bitsycore.cardbrowser.ui.games

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.games.api.GameArt
import com.bitsycore.cardbrowser.games.wutheringwaves.WutheringWavesGame
import com.bitsycore.cardbrowser.games.yugioh.YuGiOhGame
import com.bitsycore.cardbrowser.games.altered.AlteredGame
import com.bitsycore.cardbrowser.games.onepiece.OnePieceGame
import com.bitsycore.cardbrowser.games.magic.MagicGame
import com.bitsycore.cardbrowser.games.pokemon.PokemonGame
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import com.bitsycore.cardbrowser.ui.common.LoadingState
import com.bitsycore.cardbrowser.ui.preview.PreviewFrame
import org.koin.compose.koinInject
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.painterResource
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
	onOpenGame: (GameProfile) -> Unit,
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
	onOpenGame: (GameProfile) -> Unit = {},
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
					items(state.games, key = { it.id.value }) { vGame ->
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
								"with any game's publisher. " + GameArt.LOGO_ATTRIBUTION,
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
	game: GameProfile,
	source: String?,
	isLastOpened: Boolean,
	onClick: () -> Unit,
) {
	val vArt = koinInject<GameArtRegistry>().forGame(game)

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
			GameMark(vArt)
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
 * `null` art means the game's module ships no logo -- see [GameArtRegistry] for why that is a
 * missing logo rather than a missing game.
 */
@Composable
private fun GameMark(art: GameArt?) {
	Box(
		modifier = Modifier
			// Wider than it is tall, because most of these are wordmarks. A square tile squeezes a
			// 960x275 logo into a smear; the icon rows simply centre their glyph in the space.
			.size(width = 72.dp, height = 48.dp)
			.clip(RoundedCornerShape(12.dp))
			.background(
				if (art?.prefersDarkBackdrop == true) {
					// Artwork with no dark outline, drawn for dark backgrounds. It keeps one on
					// both themes rather than washing out against a pale tile.
					DARK_LOGO_BACKDROP
				} else {
					// Tinted rather than saturated, so seven of these in a column read as one
					// list rather than as a paint chart.
					(art?.accent ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.18f)
				},
			),
		contentAlignment = Alignment.Center,
	) {
		val vLogo = art?.logo
		if (vLogo == null) {
			GameMarkFallback(art)
		} else {
			// `Fit` rather than `Crop`: these are wordmarks of every aspect ratio -- the Magic one
			// is 960x275 -- and cropping one is far worse than letterboxing it.
			Image(
				painter = painterResource(vLogo),
				contentDescription = null,
				contentScale = ContentScale.Fit,
				modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp),
				// A monochrome wordmark is drawn in the theme's own foreground colour -- its
				// original black on the light theme, inverted to white on the dark one so it does
				// not vanish. Deliberately *not* the row accent: a teal Wuthering Waves logo is
				// not its logo. Colour artwork is never tinted. See `GameArt.tintLogo`.
				colorFilter = if (art.tintLogo) {
					ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
				} else {
					null
				},
			)
		}
	}
}

/**
 * The tile behind a logo that was drawn for a dark background.
 *
 * A fixed colour rather than a theme one, because the point is that it does *not* follow the theme
 * -- see the note in [GameArt]. Close to the dark theme's own surface, so on that theme it is
 * nearly invisible and only the light theme sees a change.
 */
private val DARK_LOGO_BACKDROP = Color(0xFF201E26)

/**
 * The fallback for a game whose module ships no logo.
 *
 * A generic symbol rather than one chosen per game. Every shipped game has a logo, so this is only
 * reached by a game added without one -- and picking a Material glyph for it would have to happen
 * here, in the UI, which is the sort of per-game table this refactor removed.
 */
@Composable
private fun GameMarkFallback(art: GameArt?) {
	Icon(
		imageVector = Icons.Outlined.Style,
		contentDescription = null,
		tint = art?.accent ?: MaterialTheme.colorScheme.primary,
		modifier = Modifier.size(26.dp),
	)
}

// ==================
// MARK: Previews
// ==================

private val PREVIEW_SOURCES: Map<GameProfile, String> = mapOf(
	RiftboundGame to "Riftcodex",
	PokemonGame to "TCGdex",
	MagicGame to "Scryfall",
	OnePieceGame to "OPTCG API",
	AlteredGame to "Altered TCG Card Database",
	YuGiOhGame to "YGOPRODeck",
	WutheringWavesGame to "UCP Wuthering Waves TCG",
)

@Preview
@Composable
private fun GameListPreview() = PreviewFrame {
	GameListContent(
		state = GameListContract.UiState(
			games = PREVIEW_SOURCES.keys.toList().toList(),
			sources = PREVIEW_SOURCES,
			lastGame = RiftboundGame,
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
			games = PREVIEW_SOURCES.keys.toList().toList(),
			sources = PREVIEW_SOURCES,
			lastGame = WutheringWavesGame,
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
			games = listOf(RiftboundGame),
			sources = mapOf(RiftboundGame to "Riftcodex"),
			isLoading = false,
		),
		dispatch = {},
	)
}
