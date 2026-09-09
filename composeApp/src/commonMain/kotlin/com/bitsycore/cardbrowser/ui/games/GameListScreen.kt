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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.games.api.GameArt
import com.bitsycore.cardbrowser.games.lorcana.LorcanaGame
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
 * actually serve is not in it at all. Duel Masters is absent for exactly that reason.
 *
 * Each row names the source its data comes from, which is both the attribution those sources ask
 * for and useful information: a community mirror and an official API are not the same promise.
 *
 * ## Reordering and hiding
 *
 * The tune button turns the list into an editor: each row grows up/down arrows and an eye, and the
 * games the user has hidden appear below a divider so they can be brought back. Both are display
 * choices layered over the routing table, which is untouched -- hiding a game does not unregister
 * its adapter or delete anything it downloaded. The one real cost it carries is that
 * `SetCatalogueWarmer` stops prefetching hidden games, which is rather the point of hiding one.
 *
 * Deliberately buttons rather than drag-and-drop. A drag handle in a `LazyColumn` needs its own
 * gesture plumbing and item-level animation to look right, and it is the worse control on a
 * ten-row list that barely scrolls -- two taps beat a drag you can drop in the wrong place.
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
				title = { Text(if (state.isEditing) "Customise list" else "Card Browser") },
				actions = {
					IconButton(onClick = { dispatch(GameListContract.Intent.EditingToggled) }) {
						Icon(
							imageVector = if (state.isEditing) {
								Icons.Outlined.Check
							} else {
								Icons.Outlined.Tune
							},
							contentDescription = if (state.isEditing) {
								"Done customising"
							} else {
								"Reorder or hide games"
							},
						)
					}
					// Hidden while editing. Settings is a different screen, and leaving mid-edit is
					// a good way to forget the list is in a mode at all.
					if (!state.isEditing) {
						IconButton(onClick = onOpenSettings) {
							Icon(Icons.Outlined.Settings, contentDescription = "Settings")
						}
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
					val vVisible = state.games
					itemsIndexed(vVisible, key = { _, vGame -> vGame.id.value }) { vIndex, vGame ->
						GameRow(
							game = vGame,
							source = state.sources[vGame],
							isLastOpened = vGame == state.lastGame,
							isEditing = state.isEditing,
							isHidden = false,
							// Judged against the *visible* list, so an arrow greys out exactly when
							// the row cannot move on screen. `GameOrder.moved` still steps over any
							// hidden game underneath, so one press moves it one visible place.
							canMoveUp = vIndex > 0,
							canMoveDown = vIndex < vVisible.lastIndex,
							canHide = state.canHideMore,
							onClick = {
								dispatch(GameListContract.Intent.GameOpened(vGame))
								onOpenGame(vGame)
							},
							dispatch = dispatch,
						)
					}

					if (state.isEditing && state.hiddenGames.isNotEmpty()) {
						item { HiddenHeading(count = state.hiddenGames.size) }
						items(state.hiddenGames, key = { "hidden-" + it.id.value }) { vGame ->
							GameRow(
								game = vGame,
								source = state.sources[vGame],
								isLastOpened = false,
								isEditing = true,
								isHidden = true,
								// A hidden row has no position to move: it is out of the list the
								// arrows act on, and bringing it back is all there is to do with it.
								canMoveUp = false,
								canMoveDown = false,
								canHide = true,
								onClick = {},
								dispatch = dispatch,
							)
						}
					}

					if (state.isEditing) {
						item {
							ResetRow(
								isEnabled = state.isCustomised,
								onReset = { dispatch(GameListContract.Intent.CustomisationReset) },
							)
						}
					} else {
						item {
							Text(
								text = "Every game listed has a working data source. Card data is " +
									"supplied by the projects named above; this app is not " +
									"affiliated with any game's publisher. " +
									GameArt.LOGO_ATTRIBUTION,
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
}

/**
 * One game: its mark, its name, and the source behind it.
 *
 * While editing, the row stops being a button and grows controls instead. Deliberately the same
 * composable rather than a second one -- a separate editor row is how the two drift apart until the
 * list you are editing no longer looks like the list you edited.
 */
@Composable
private fun GameRow(
	game: GameProfile,
	source: String?,
	isLastOpened: Boolean,
	isEditing: Boolean,
	isHidden: Boolean,
	canMoveUp: Boolean,
	canMoveDown: Boolean,
	canHide: Boolean,
	onClick: () -> Unit,
	dispatch: (GameListContract.Intent) -> Unit,
) {
	val vArt = koinInject<GameArtRegistry>().forGame(game)
	val vColors = if (isLastOpened) {
		CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
	} else {
		CardDefaults.cardColors()
	}
	// A hidden row is dimmed as a whole rather than greyed piece by piece, so its logo reads as
	// "off" along with its text.
	val vModifier = Modifier
		.fillMaxWidth()
		.graphicsLayer { alpha = if (isHidden) HIDDEN_ROW_ALPHA else 1f }

	val vContent: @Composable () -> Unit = {
		Row(
			// Tighter while editing: a mark plus three controls is a lot for one phone-width row.
			modifier = Modifier
				.padding(horizontal = if (isEditing) 8.dp else 16.dp, vertical = 12.dp)
				.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (isEditing) {
				MoveButtons(
					canMoveUp = canMoveUp,
					canMoveDown = canMoveDown,
					onMove = { vDelta -> dispatch(GameListContract.Intent.GameMoved(game, vDelta)) },
				)
			}
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
			if (isEditing) {
				IconButton(
					onClick = { dispatch(GameListContract.Intent.GameVisibilityToggled(game)) },
					// Disabled only for the last visible game. `GameOrder` refuses that case as
					// well, so the button cannot promise something the reducer would decline.
					enabled = isHidden || canHide,
				) {
					Icon(
						imageVector = if (isHidden) {
							Icons.Outlined.VisibilityOff
						} else {
							Icons.Outlined.Visibility
						},
						contentDescription = if (isHidden) {
							"Show ${game.displayName}"
						} else {
							"Hide ${game.displayName}"
						},
					)
				}
			} else {
				Icon(
					imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}

	if (isEditing) {
		// Not clickable while editing: the row's job is to be rearranged, and opening a game from
		// under a press aimed at an arrow is the obvious way to get that wrong.
		Card(modifier = vModifier, colors = vColors) { vContent() }
	} else {
		Card(onClick = onClick, modifier = vModifier, colors = vColors) { vContent() }
	}
}

/** How far a hidden row is faded. Enough to read as off, not so far it cannot be read. */
private const val HIDDEN_ROW_ALPHA = 0.45f

/** The up/down pair, greyed at the ends of the list rather than wrapping around it. */
@Composable
private fun MoveButtons(
	canMoveUp: Boolean,
	canMoveDown: Boolean,
	onMove: (Int) -> Unit,
) {
	Column {
		IconButton(onClick = { onMove(-1) }, enabled = canMoveUp, modifier = Modifier.size(30.dp)) {
			Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Move up")
		}
		IconButton(onClick = { onMove(1) }, enabled = canMoveDown, modifier = Modifier.size(30.dp)) {
			Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Move down")
		}
	}
}

/** Separates the hidden games from the listed ones, and says how many there are. */
@Composable
private fun HiddenHeading(count: Int) {
	Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
		HorizontalDivider()
		Text(
			text = if (count == 1) "1 hidden game" else "$count hidden games",
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
		)
	}
}

/**
 * Puts the list back to the routing table's own order, and says what hiding actually costs.
 *
 * The note matters: "hidden" could reasonably be read as uninstalled, and it is not. Saying so here
 * is cheaper than a user wondering whether hiding a game threw away the sets they downloaded.
 */
@Composable
private fun ResetRow(isEnabled: Boolean, onReset: () -> Unit) {
	Column(Modifier.padding(vertical = 8.dp)) {
		TextButton(onClick = onReset, enabled = isEnabled) {
			Text("Reset to default order")
		}
		Text(
			text = "Hiding a game only takes it off this list. Its data source stays registered " +
				"and anything already downloaded stays on your device — but its set list is no " +
				"longer fetched in the background.",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
		)
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
	LorcanaGame to "TCGCSV",
)

@Preview
@Composable
private fun GameListPreview() = PreviewFrame {
	GameListContent(
		state = GameListContract.UiState(
			allGames = PREVIEW_SOURCES.keys.toList(),
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
			allGames = PREVIEW_SOURCES.keys.toList(),
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
			allGames = listOf(RiftboundGame),
			sources = mapOf(RiftboundGame to "Riftcodex"),
			isLoading = false,
		),
		dispatch = {},
	)
}

@Preview
@Composable
private fun GameListEditingPreview() = PreviewFrame {
	// The editor with a reordered list and two games hidden, which is the state the arrows, the eye
	// and the hidden divider all have to look right in at once.
	GameListContent(
		state = GameListContract.UiState(
			allGames = PREVIEW_SOURCES.keys.toList(),
			sources = PREVIEW_SOURCES,
			order = listOf("lorcana", "magic", "riftbound"),
			hiddenIds = setOf("yugioh", "wuwa"),
			isEditing = true,
			isLoading = false,
		),
		dispatch = {},
	)
}

@Preview
@Composable
private fun GameListLastVisibleGamePreview() = PreviewFrame {
	// Everything but one hidden: the eye on the survivor is disabled, because emptying the picker
	// would strand the user on the one screen every route into the app goes through.
	GameListContent(
		state = GameListContract.UiState(
			allGames = PREVIEW_SOURCES.keys.toList(),
			sources = PREVIEW_SOURCES,
			hiddenIds = PREVIEW_SOURCES.keys.drop(1).map { it.id.value }.toSet(),
			isEditing = true,
			isLoading = false,
		),
		dispatch = {},
	)
}
