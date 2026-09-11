package com.bitsycore.cardbrowser.ui.games

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.runtime.collectAsState
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.data.download.DownloadJob
import com.bitsycore.cardbrowser.data.download.DownloadManager
import com.bitsycore.cardbrowser.games.api.GameArt
import com.bitsycore.cardbrowser.games.lorcana.LorcanaGame
import com.bitsycore.cardbrowser.games.wutheringwaves.WutheringWavesGame
import com.bitsycore.cardbrowser.games.yugioh.YuGiOhGame
import com.bitsycore.cardbrowser.games.altered.AlteredGame
import com.bitsycore.cardbrowser.games.onepiece.OnePieceGame
import com.bitsycore.cardbrowser.games.magic.MagicGame
import com.bitsycore.cardbrowser.games.pokemon.PokemonGame
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import com.bitsycore.cardbrowser.ui.common.DRAGGED_ROW_ELEVATION
import com.bitsycore.cardbrowser.ui.common.LoadingState
import com.bitsycore.cardbrowser.ui.common.ReorderHandle
import com.bitsycore.cardbrowser.ui.common.ReorderState
import com.bitsycore.cardbrowser.ui.common.rememberReorder
import com.bitsycore.cardbrowser.ui.common.reorderHandle
import com.bitsycore.cardbrowser.ui.common.FinePrint
import com.bitsycore.cardbrowser.ui.preview.PreviewFrame
import com.bitsycore.cardbrowser.ui.theme.isDarkTheme
import org.koin.compose.koinInject
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import com.bitsycore.lib.pulse.compose.collectEffect
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.viewmodel.koinViewModel
import com.bitsycore.cardbrowser.ui.common.AppIcons
import com.bitsycore.cardbrowser.ui.common.AppOverflowMenu

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
 * The tune button turns the list into an editor: each row grows a drag handle and an eye, and the
 * games the user has hidden appear below a divider so they can be brought back. Both are display
 * choices layered over the routing table, which is untouched -- hiding a game does not unregister
 * its adapter or delete anything it downloaded. The one real cost it carries is that
 * `SetCatalogueWarmer` stops prefetching hidden games, which is rather the point of hiding one.
 *
 * Reordering is a Material drag: the row lifts, follows the finger, and the rest slide under it as
 * it passes, rearranging live rather than on release. See [ReorderState] for how that is done
 * without a reorderable-list dependency, and why the handle is a grip rather than the whole row.
 *
 * A drag is unreachable by a screen reader, so every row also carries "Move up" and "Move down" as
 * custom accessibility actions. They are the same move by another route, not a second code path.
 */
@Composable
fun GameListScreen(
	onOpenGame: (GameProfile) -> Unit,
	onOpenSettings: () -> Unit,
	onOpenStorage: () -> Unit = {},
	onOpenDownloads: () -> Unit = {},
	viewModel: GameListViewModel = koinViewModel(),
) {
	// The only part of this screen that knows a back stack exists. Everything below dispatches.
	viewModel.collectEffect { vEffect ->
		when (vEffect) {
			is GameListContract.Effect.OpenGame -> onOpenGame(vEffect.game)
			GameListContract.Effect.OpenSettings -> onOpenSettings()
			GameListContract.Effect.OpenStorage -> onOpenStorage()
			GameListContract.Effect.OpenDownloads -> onOpenDownloads()
		}
	}
	val vState by viewModel.collectAsStateWithLifecycle()

	// The queue is application-scoped, so it is read here and handed down as plain state --
	// `GameListContent` stays free of Koin and therefore previewable. This screen needs it because
	// it is where a download is most likely to be *left* running: going back here from the sets is
	// exactly the moment a long import stops being visible anywhere else.
	val vJobs by koinInject<DownloadManager>().jobs.collectAsState()

	// Resolved here rather than in the row, because `GameListContent` and everything under it must
	// stay free of Koin: a preview has no graph and `koinInject` throws in one. `GameRow` used to
	// call it directly, which made five of this file's six previews unrenderable. The sibling
	// screens already hoist exactly like this -- see `SetListScreen`.
	val vArtRegistry = koinInject<GameArtRegistry>()

	GameListContent(
		state = vState,
		dispatch = viewModel::dispatch,
		downloads = vJobs,
		artFor = vArtRegistry::forGame,
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
	downloads: List<DownloadJob> = emptyList(),
	/**
	 * A game's logo and accent colour, supplied by the caller.
	 *
	 * A parameter rather than a `koinInject` in the row, so this composable and its previews need
	 * no Koin graph. The default answers `null`, which `GameMark` already draws a fallback for --
	 * so a preview shows the monogram rather than throwing.
	 */
	artFor: (GameProfile) -> GameArt? = { null },
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
								AppIcons.Tune
							},
							contentDescription = if (state.isEditing) {
								"Done customising"
							} else {
								"Reorder or hide games"
							},
						)
					}
					// Hidden while editing. All three lead somewhere else, and leaving mid-edit
					// is a good way to forget the list is in a mode at all.
					if (!state.isEditing) {
						AppOverflowMenu(
							onOpenSettings = { dispatch(GameListContract.Intent.SettingsRequested) },
							onOpenStorage = { dispatch(GameListContract.Intent.StorageRequested) },
							onOpenDownloads = { dispatch(GameListContract.Intent.DownloadsRequested) },
							activeDownloads = downloads.count { it.isActive },
						)
					}
				},
			)
		},
	) { vPadding ->
		Box(Modifier.padding(vPadding).fillMaxSize()) {
			if (state.isLoading) {
				LoadingState()
			} else {
				// Memoised, not read straight off the state. `UiState.games` derives a fresh list on
				// every access, and a list whose identity changes each recomposition restarts any
				// `pointerInput` keyed on it -- which is what made a drag stop dead after one slot.
				val vVisible = remember(state.allGames, state.order, state.hiddenIds) { state.games }
				val vListState = rememberLazyListState()
				val vReorder = rememberReorder(vListState)
				// The keys the drag may move between: the visible games and nothing else, so the
				// hidden section and the reset row below cannot be dropped onto.
				val vKeys = vVisible.map { it.id.value }
				val vOnMove: (String, Int) -> Unit = { vKey, vTo ->
					vVisible.firstOrNull { it.id.value == vKey }?.let { vGame ->
						dispatch(GameListContract.Intent.GameMovedTo(vGame, vTo))
					}
				}

				LazyColumn(
					state = vListState,
					contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					itemsIndexed(vVisible, key = { _, vGame -> vGame.id.value }) { vIndex, vGame ->
						val vIsDragging = vReorder.draggedKey == vGame.id.value
						GameRow(
							game = vGame,
							source = state.sources[vGame],
							isLastOpened = vGame == state.lastGame,
							isEditing = state.isEditing,
							isHidden = false,
							isDragging = vIsDragging,
							// The dragged row follows the finger, so it must not also be animated
							// into place: the two fight and the row lags behind the pointer.
							itemModifier = if (vIsDragging) Modifier else Modifier.animateItem(),
							dragOffsetY = if (vIsDragging) vReorder.offsetY else 0f,
							handleModifier = Modifier.reorderHandle(vReorder, vGame.id.value, vKeys, vOnMove),
							// A drag is unreachable by a screen reader, so the same two moves are
							// offered as custom actions. Bounded by the visible list, which is what
							// the index means.
							onMoveUp = if (vIndex > 0) {
								{ dispatch(GameListContract.Intent.GameMovedTo(vGame, vIndex - 1)) }
							} else {
								null
							},
							onMoveDown = if (vIndex < vVisible.lastIndex) {
								{ dispatch(GameListContract.Intent.GameMovedTo(vGame, vIndex + 1)) }
							} else {
								null
							},
							canHide = state.canHideMore,
							onClick = {
								dispatch(GameListContract.Intent.GameOpened(vGame))
							},
							dispatch = dispatch,
							art = artFor(vGame),
						)
					}

					if (state.isEditing && state.hiddenGames.isNotEmpty()) {
						// Keyed, so the lazy list can tell this heading from the rows around it and
						// fade it rather than swapping it in whole. An unkeyed item is identified by
						// its index, which changes the moment a game is hidden.
						item(key = "hidden-heading") {
							HiddenHeading(
								count = state.hiddenGames.size,
								modifier = Modifier.animateItem(),
							)
						}
						items(state.hiddenGames, key = { "hidden-" + it.id.value }) { vGame ->
							GameRow(
								game = vGame,
								source = state.sources[vGame],
								isLastOpened = false,
								isEditing = true,
								isHidden = true,
								// A hidden row has no position to drag to: it is out of the list the
								// order applies to, and bringing it back is all there is to do here.
								isDragging = false,
								itemModifier = Modifier.animateItem(),
								dragOffsetY = 0f,
								handleModifier = null,
								onMoveUp = null,
								onMoveDown = null,
								canHide = true,
								onClick = {},
								dispatch = dispatch,
								art = artFor(vGame),
							)
						}
					}

					// The foot of the list swaps between the two modes. Both are keyed and both
					// animate, so entering edit mode fades the attribution out and the reset row in
					// where it used to be a hard cut mid-scroll.
					if (state.isEditing) {
						item(key = "reset-row") {
							ResetRow(
								isEnabled = state.isCustomised,
								onReset = { dispatch(GameListContract.Intent.CustomisationReset) },
								modifier = Modifier.animateItem(),
							)
						}
					} else {
						item(key = "fine-print") {
							FinePrint(
								text = "Not affiliated with any game's publisher. " +
									GameArt.LOGO_ATTRIBUTION,
								modifier = Modifier
									.animateItem()
									.padding(horizontal = 4.dp, vertical = 16.dp),
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
	isDragging: Boolean,
	itemModifier: Modifier,
	dragOffsetY: Float,
	handleModifier: Modifier?,
	onMoveUp: (() -> Unit)?,
	onMoveDown: (() -> Unit)?,
	canHide: Boolean,
	onClick: () -> Unit,
	dispatch: (GameListContract.Intent) -> Unit,
	art: GameArt?,
) {
	val vColors = if (isLastOpened) {
		CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
	} else {
		CardDefaults.cardColors()
	}
	val vMoveActions = buildList {
		onMoveUp?.let { add(CustomAccessibilityAction("Move up") { it(); true }) }
		onMoveDown?.let { add(CustomAccessibilityAction("Move down") { it(); true }) }
	}
	val vModifier = itemModifier
		.fillMaxWidth()
		// The lifted row rides above its neighbours while they slide underneath it.
		.zIndex(if (isDragging) 1f else 0f)
		.graphicsLayer {
			translationY = dragOffsetY
			// A hidden row is dimmed whole rather than greyed piece by piece, so its logo reads
			// as "off" along with its text.
			alpha = if (isHidden) HIDDEN_ROW_ALPHA else 1f
		}
		.then(
			if (vMoveActions.isEmpty()) {
				Modifier
			} else {
				Modifier.semantics { customActions = vMoveActions }
			},
		)

	// Tighter while editing: a mark plus two controls is a lot for one phone-width row. Animated
	// rather than switched, because the handle expanding into a row whose padding jumps at the same
	// moment reads as two separate things happening to one row.
	val vHorizontalPadding by animateDpAsState(
		targetValue = if (isEditing) 8.dp else 16.dp,
		animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
		label = "row padding",
	)

	val vContent: @Composable () -> Unit = {
		Row(
			modifier = Modifier
				.padding(horizontal = vHorizontalPadding, vertical = 12.dp)
				.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			// Expands from the left edge rather than appearing at full width, so the mark and the
			// name slide over to make room for it instead of being redrawn somewhere else.
			//
			// Clipped, which is the default and is the point: unclipped, the handle draws at full
			// size from the first frame and sits on top of the game's mark while the space for it
			// is still opening.
			AnimatedVisibility(
				visible = isEditing,
				enter = expandHorizontally() + fadeIn(),
				exit = shrinkHorizontally() + fadeOut(),
			) {
				ReorderHandle(handleModifier ?: Modifier)
			}
			GameMark(art)
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
			// A cross-fade rather than a swap: the chevron and the eye occupy the same corner and
			// mean opposite things about what a tap does, so the one replacing the other is worth
			// showing. Sized to the eye button either way, so the row does not resize mid-fade.
			AnimatedContent(
				targetState = isEditing,
				transitionSpec = { fadeIn() togetherWith fadeOut() },
				label = "row trailing control",
			) { vIsEditing ->
				if (vIsEditing) {
					IconButton(
						onClick = { dispatch(GameListContract.Intent.GameVisibilityToggled(game)) },
						// Disabled only for the last visible game. `GameOrder` refuses that case as
						// well, so the button cannot promise something the reducer would decline.
						enabled = isHidden || canHide,
					) {
						Icon(
							imageVector = if (isHidden) {
								AppIcons.VisibilityOff
							} else {
								AppIcons.Visibility
							},
							contentDescription = if (isHidden) {
								"Show ${game.displayName}"
							} else {
								"Hide ${game.displayName}"
							},
						)
					}
				} else {
					// A box of the button's size rather than a disabled button. Both keep the row
					// from resizing mid-fade, but a disabled `IconButton` also greys the chevron
					// and announces a dead button on every row to a screen reader. The whole row is
					// the target out here; the chevron is decoration and says so with a null
					// description.
					Box(
						modifier = Modifier.size(CHEVRON_SLOT),
						contentAlignment = Alignment.Center,
					) {
						Icon(
							imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
							contentDescription = null,
							tint = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
			}
		}
	}

	// The lift: Material raises the dragged item off the list rather than only moving it.
	val vElevation = CardDefaults.cardElevation(
		defaultElevation = if (isDragging) DRAGGED_ROW_ELEVATION else 0.dp,
	)

	if (isEditing) {
		// Not clickable while editing: the row's job is to be rearranged, and opening a game from
		// under a press that was aimed at the handle is the obvious way to get that wrong.
		Card(modifier = vModifier, colors = vColors, elevation = vElevation) { vContent() }
	} else {
		Card(onClick = onClick, modifier = vModifier, colors = vColors) { vContent() }
	}
}

/**
 * How to paint a game's mark, or `null` to leave the artwork alone.
 *
 * Three cases, and the middle one is why this is a function rather than a line at each call site:
 *
 * - full-colour artwork is never recoloured, because tinting flattens it to a silhouette;
 * - a single-colour mark with a brand colour is painted in it on dark and left as drawn on light;
 * - any other single-colour mark follows the theme's own foreground, so it inverts with the theme.
 */
@Composable
internal fun logoTintFor(art: GameArt): ColorFilter? {
	if (!art.tintLogo) return null
	val vDarkTint = art.logoTintDarkArgb
	return if (vDarkTint != null && isDarkTheme()) {
		ColorFilter.tint(Color(vDarkTint.toInt()))
	} else {
		ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
	}
}

/** How far a hidden row is faded. Enough to read as off, not so far it cannot be read. */
private const val HIDDEN_ROW_ALPHA = 0.45f

/**
 * The width the row's trailing corner holds in both modes.
 *
 * Material's own minimum touch target, which is what an `IconButton` occupies. Stated so the
 * browsing chevron reserves the same space as the editing eye: without it the row's text column
 * resizes underneath the cross-fade and the name shuffles sideways while the icons swap.
 */
private val CHEVRON_SLOT = 48.dp

/** Separates the hidden games from the listed ones, and says how many there are. */
@Composable
private fun HiddenHeading(count: Int, modifier: Modifier = Modifier) {
	Column(modifier.padding(top = 16.dp, bottom = 4.dp)) {
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
private fun ResetRow(isEnabled: Boolean, onReset: () -> Unit, modifier: Modifier = Modifier) {
	Column(modifier.padding(vertical = 8.dp)) {
		TextButton(onClick = onReset, enabled = isEnabled) {
			Text("Reset to default order")
		}
		Text(
			text = "Hidden games keep their downloads.",
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
 *
 * Internal, and shared with the first-launch setup. Two surfaces drawing a logo their own way is
 * how three of them ended up invisible on one screen and fine on the other -- see
 * [logoBackdropFor], which exists because that already happened once.
 */
@Composable
internal fun GameMark(art: GameArt?, width: Dp = 72.dp, height: Dp = 48.dp) {
	Box(
		modifier = Modifier
			// Wider than it is tall, because most of these are wordmarks. A square tile squeezes a
			// 960x275 logo into a smear; the icon rows simply centre their glyph in the space.
			.size(width = width, height = height)
			.clip(RoundedCornerShape(12.dp))
			.background(backdropFor(art)),
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
				// A monochrome wordmark is painted rather than left as drawn -- see [logoTintFor].
				// Deliberately never the row accent: a teal Wuthering Waves logo is not its logo,
				// and colour artwork is not tinted at all.
				colorFilter = logoTintFor(art),
			)
		}
	}
}

/**
 * The tile a game's mark sits on.
 *
 * A logo drawn for a particular background states one, and keeps it on both themes rather than
 * washing out against a pale tile. That is usually [GameArt.DARK_BACKDROP], and for Cyberpunk it is
 * the brand's own yellow with a black wordmark on it -- which is exactly why `backdropArgb` is a
 * colour rather than the "prefers dark" boolean it replaced.
 *
 * Everything else gets its accent, heavily tinted rather than saturated, so ten of these in a
 * column read as one list rather than as a paint chart.
 */
@Composable
private fun backdropFor(art: GameArt?): Color =
	art?.let { logoBackdropFor(it) }
		?: (art?.accent ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.18f)

/**
 * The plate this mark states for the theme in force, or `null` if it states none.
 *
 * Shared with the set list's title bar so the two surfaces cannot drift -- which they did once
 * already, when the picker had a tile and the title bar did not and three logos were invisible on
 * one screen and fine on the other.
 */
@Composable
internal fun logoBackdropFor(art: GameArt): Color? {
	val vArgb = if (isDarkTheme()) art.backdropDarkArgb else art.backdropArgb
	return vArgb?.let { Color(it.toInt()) }
}

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
		imageVector = AppIcons.Style,
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
