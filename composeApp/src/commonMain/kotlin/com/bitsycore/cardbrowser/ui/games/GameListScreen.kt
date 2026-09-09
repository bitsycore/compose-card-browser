package com.bitsycore.cardbrowser.ui.games

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DragHandle
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
				// Memoised, not read straight off the state. `UiState.games` derives a fresh list on
				// every access, and a list whose identity changes each recomposition restarts any
				// `pointerInput` keyed on it -- which is what made a drag stop dead after one slot.
				val vVisible = remember(state.allGames, state.order, state.hiddenIds) { state.games }
				val vListState = rememberLazyListState()
				val vReorder = rememberReorder(vListState)
				val vOnMove: (GameProfile, Int) -> Unit = { vGame, vTo ->
					dispatch(GameListContract.Intent.GameMovedTo(vGame, vTo))
				}

				LazyColumn(
					state = vListState,
					contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					itemsIndexed(vVisible, key = { _, vGame -> vGame.id.value }) { vIndex, vGame ->
						val vIsDragging = vReorder.draggedId == vGame.id.value
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
							handleModifier = Modifier.dragHandle(vReorder, vGame, vVisible, vOnMove),
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
	isDragging: Boolean,
	itemModifier: Modifier,
	dragOffsetY: Float,
	handleModifier: Modifier?,
	onMoveUp: (() -> Unit)?,
	onMoveDown: (() -> Unit)?,
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

	val vContent: @Composable () -> Unit = {
		Row(
			// Tighter while editing: a mark plus two controls is a lot for one phone-width row.
			modifier = Modifier
				.padding(horizontal = if (isEditing) 8.dp else 16.dp, vertical = 12.dp)
				.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (isEditing) {
				DragHandle(handleModifier)
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

// ==================
// MARK: Drag to reorder
// ==================

/**
 * The state of a drag in progress, and the arithmetic that turns it into a move.
 *
 * Hand-rolled rather than pulled from a library. `LazyColumn` has no reorder support of its own, and
 * the two things that make one hard -- animating the displaced rows, and keying items so they are
 * not recreated mid-drag -- are already solved by `Modifier.animateItem()` and the `key` this list
 * has always had. What is left is the part below, which is small enough not to be worth a dependency
 * on a list of ten rows.
 *
 * ## How a drag becomes a move
 *
 * The dragged row is drawn at an offset from where the list actually placed it, so the finger and
 * the row stay together. Each move event asks whether the row's *centre* has crossed into another
 * row's bounds; when it has, the reorder is dispatched immediately rather than on release, so the
 * list rearranges live under the finger the way Material does it.
 *
 * The subtle part is the line marked below. Once the move is dispatched the list re-lays the row out
 * at its new position, which would jump it out from under the pointer -- so the accumulated offset
 * is reduced by exactly the distance it just travelled. Its position on screen does not change at
 * the moment of the swap; only its index does.
 */
@Stable
private class ReorderState(val listState: LazyListState) {

	/** The `GameId` value of the row being dragged, or `null` when nothing is. */
	var draggedId: String? by mutableStateOf(null)
		private set

	/** How far the dragged row is drawn from where the list placed it. */
	var offsetY: Float by mutableFloatStateOf(0f)
		private set

	/**
	 * The index the last dispatched move aimed at, until the list is seen to have caught up.
	 *
	 * Pointer events arrive faster than recomposition, so without this the two or three events that
	 * land between dispatching a move and the reordered list coming back all measure against the
	 * stale one and fire the same move again -- the row jumps two or three places from one crossing.
	 */
	private var mPending: Int? = null

	fun start(game: GameProfile) {
		draggedId = game.id.value
		offsetY = 0f
		mPending = null
	}

	fun stop() {
		draggedId = null
		offsetY = 0f
		mPending = null
	}

	/**
	 * Accumulates [delta] and reorders if the row has moved far enough to displace a neighbour.
	 *
	 * [onMove] is passed in per event rather than held on the state, so it cannot go stale: this
	 * object is remembered across recompositions and the dispatcher it would have captured is not.
	 */
	fun drag(
		delta: Float,
		game: GameProfile,
		visible: List<GameProfile>,
		onMove: (GameProfile, Int) -> Unit,
	) {
		offsetY += delta
		val vId = draggedId ?: return

		// Wait for the list to reflect the move already dispatched before considering another.
		val vIndex = visible.indexOfFirst { it.id.value == vId }
		if (vIndex < 0) return
		if (mPending != null && mPending != vIndex) return
		mPending = null

		val vItems = listState.layoutInfo.visibleItemsInfo
		val vSelf = vItems.firstOrNull { it.key == vId } ?: return
		val vCentre = vSelf.offset + vSelf.size / 2f + offsetY

		val vKeys = visible.map { it.id.value }
		val vTarget = vItems.firstOrNull { vOther ->
			vOther.key != vId &&
				vOther.key in vKeys &&
				vCentre >= vOther.offset &&
				vCentre <= vOther.offset + vOther.size
		} ?: return

		val vTo = vKeys.indexOf(vTarget.key as String)
		if (vTo < 0) return
		// Keeps the row under the finger across the swap: it is about to be re-placed at the
		// target's offset, so the offset it is drawn at shrinks by the same distance.
		offsetY += (vSelf.offset - vTarget.offset).toFloat()
		mPending = vTo
		onMove(game, vTo)

		// Pin the viewport. A `LazyColumn` anchors its scroll position to the first visible item's
		// *key*, so moving the top row down takes the anchor with it and the whole list appears to
		// scroll under the finger. Re-requesting the position that is already showing re-anchors it
		// to wherever that scroll offset now lands, which is what keeps the list still.
		listState.requestScrollToItem(
			listState.firstVisibleItemIndex,
			listState.firstVisibleItemScrollOffset,
		)
	}
}

/** Remembers a [ReorderState] bound to this list. */
@Composable
private fun rememberReorder(listState: LazyListState): ReorderState =
	remember(listState) { ReorderState(listState) }

/**
 * The drag gesture, attached to one row's handle.
 *
 * Not `detectDragGesturesAfterLongPress`: the handle exists precisely so the drag can start
 * immediately, and making someone hold down a control that is already a grip is the worst of both.
 *
 * ## Why the key is the row and nothing else
 *
 * `pointerInput` restarts its block whenever a key changes, which cancels any gesture in flight.
 * The list being dragged through changes on every reorder -- that is the whole point of it -- so
 * keying on it means the first successful move tears down the detector that was tracking the
 * finger, and the drag dies one slot in. That bug shipped once and looked exactly like a stuck row.
 *
 * So the key is the row's id, which is stable for as long as the row exists, and the two things
 * that *do* change are read through [rememberUpdatedState] at the moment they are used.
 */
@Composable
private fun Modifier.dragHandle(
	reorder: ReorderState,
	game: GameProfile,
	visible: List<GameProfile>,
	onMove: (GameProfile, Int) -> Unit,
): Modifier {
	val vVisible by rememberUpdatedState(visible)
	val vOnMove by rememberUpdatedState(onMove)
	return this.pointerInput(game.id.value) {
		detectDragGestures(
			onDragStart = { reorder.start(game) },
			onDragEnd = { reorder.stop() },
			onDragCancel = { reorder.stop() },
			onDrag = { vChange, vDragged ->
				vChange.consume()
				reorder.drag(vDragged.y, game, vVisible, vOnMove)
			},
		)
	}
}

/** How far a hidden row is faded. Enough to read as off, not so far it cannot be read. */
private const val HIDDEN_ROW_ALPHA = 0.45f

/** How far the dragged row lifts off the list. Material's own resting elevation for a dragged item. */
private val DRAGGED_ROW_ELEVATION = 8.dp

/**
 * The grip a row is dragged by.
 *
 * A handle rather than the whole row, so a press that lands on the card still does nothing and the
 * list can still be scrolled with a finger anywhere else. `null` for a hidden row, which has no
 * position in the order to drag it to.
 */
@Composable
private fun DragHandle(handleModifier: Modifier?) {
	Icon(
		imageVector = Icons.Outlined.DragHandle,
		// The gesture is on the handle; a screen reader gets the row's custom actions instead, so
		// announcing this as a control it cannot use would be noise.
		contentDescription = null,
		tint = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = (handleModifier ?: Modifier)
			.padding(horizontal = 6.dp)
			.size(24.dp),
	)
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
	art?.backdropArgb?.let { Color(it.toInt()) }
		?: (art?.accent ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.18f)

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
