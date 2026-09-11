package com.bitsycore.cardbrowser.ui.common

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.ui.common.AppIcons

/**
 * Drag-to-reorder for a `LazyColumn`, shared by the game picker and the set list's favourites.
 *
 * Hand-rolled rather than pulled from a library. `LazyColumn` has no reorder support of its own, and
 * the two things that make one hard -- animating the displaced rows, and keying items so they are
 * not recreated mid-drag -- are already solved by `Modifier.animateItem()` and the `key` both lists
 * already had. What is left is the part below, which is small enough not to be worth a dependency.
 *
 * ## How a drag becomes a move
 *
 * The dragged row is drawn at an offset from where the list actually placed it, so the finger and
 * the row stay together. Each move event asks whether the row's *centre* has crossed into another
 * row's bounds; when it has, the reorder is dispatched immediately rather than on release, so the
 * list rearranges live under the finger the way Material does it.
 *
 * ## Three bugs this has already had
 *
 * Each cost a round of testing, and none is visible in the code that fails:
 *
 * - **Keying `pointerInput` on the list cancels the drag it is tracking.** It restarts its block
 *   when a key changes, and the list being dragged through changes on every reorder -- so the first
 *   successful move tore down the detector holding the finger and the drag died one slot in. The
 *   key is the row's own id, and the list is read through [rememberUpdatedState] instead.
 * - **Pointer events outrun recomposition.** The events landing between dispatching a move and the
 *   reordered list coming back all measure against the stale one and fire the same move again, so
 *   one crossing jumped the row several places. Hence [ReorderState.mPending].
 * - **A `LazyColumn` restores its scroll position by key.** Moving the top row down takes the
 *   anchor with it and the viewport follows, which looks like the list scrolling itself. Hence the
 *   `requestScrollToItem` below.
 */
@Stable
class ReorderState(val listState: LazyListState) {

	/** The key of the row being dragged, or `null` when nothing is. */
	var draggedKey: String? by mutableStateOf(null)
		private set

	/** How far the dragged row is drawn from where the list placed it. */
	var offsetY: Float by mutableFloatStateOf(0f)
		private set

	/**
	 * The index the last dispatched move aimed at, until the list is seen to have caught up.
	 *
	 * Pointer events arrive faster than recomposition, so without this the two or three events that
	 * land between dispatching a move and the reordered list coming back all measure against the
	 * stale one and fire the same move again.
	 */
	private var mPending: Int? = null

	fun start(key: String) {
		draggedKey = key
		offsetY = 0f
		mPending = null
	}

	fun stop() {
		draggedKey = null
		offsetY = 0f
		mPending = null
	}

	/**
	 * Accumulates [delta] and reorders if the row has moved far enough to displace a neighbour.
	 *
	 * @param orderedKeys the keys this drag may move between, in the order they are drawn. Only
	 *   these are considered, so a list with other items in it -- headings, a footer, a second
	 *   section -- reorders within one group rather than across the lot
	 * @param onMove passed per event rather than held on the state, so it cannot go stale: this
	 *   object is remembered across recompositions and the dispatcher it would capture is not
	 */
	fun drag(
		delta: Float,
		orderedKeys: List<String>,
		onMove: (String, Int) -> Unit,
	) {
		offsetY += delta
		val vKey = draggedKey ?: return

		// Wait for the list to reflect the move already dispatched before considering another.
		val vIndex = orderedKeys.indexOf(vKey)
		if (vIndex < 0) return
		if (mPending != null && mPending != vIndex) return
		mPending = null

		val vItems = listState.layoutInfo.visibleItemsInfo
		val vSelf = vItems.firstOrNull { it.key == vKey } ?: return
		val vCentre = vSelf.offset + vSelf.size / 2f + offsetY

		val vTarget = vItems.firstOrNull { vOther ->
			vOther.key != vKey &&
				vOther.key in orderedKeys &&
				vCentre >= vOther.offset &&
				vCentre <= vOther.offset + vOther.size
		} ?: return

		val vTo = orderedKeys.indexOf(vTarget.key as String)
		if (vTo < 0) return
		// Keeps the row under the finger across the swap: it is about to be re-placed at the
		// target's offset, so the offset it is drawn at shrinks by the same distance.
		offsetY += (vSelf.offset - vTarget.offset).toFloat()
		mPending = vTo
		onMove(vKey, vTo)

		// Pin the viewport. A `LazyColumn` restores its scroll position by *key*: at measure time it
		// finds where the key that used to be first has gone and re-anchors to it. Right for
		// insertions above you, wrong here -- moving the top row down takes the anchor with it, so
		// the list appears to scroll itself. Re-requesting the position already on screen overrides
		// that for the next measure.
		listState.requestScrollToItem(
			listState.firstVisibleItemIndex,
			listState.firstVisibleItemScrollOffset,
		)
	}
}

/** Remembers a [ReorderState] bound to this list. */
@Composable
fun rememberReorder(listState: LazyListState): ReorderState =
	remember(listState) { ReorderState(listState) }

/**
 * The drag gesture, attached to one row's handle.
 *
 * Not `detectDragGesturesAfterLongPress`: the handle exists precisely so the drag can start
 * immediately, and making someone hold down a control that is already a grip is the worst of both.
 *
 * Keyed on [key] alone, for the reason in [ReorderState]'s doc: keying on the list would cancel the
 * drag at the first move.
 */
@Composable
fun Modifier.reorderHandle(
	reorder: ReorderState,
	key: String,
	orderedKeys: List<String>,
	onMove: (String, Int) -> Unit,
): Modifier {
	val vKeys by rememberUpdatedState(orderedKeys)
	val vOnMove by rememberUpdatedState(onMove)
	return this.pointerInput(key) {
		detectDragGestures(
			onDragStart = { reorder.start(key) },
			onDragEnd = { reorder.stop() },
			onDragCancel = { reorder.stop() },
			onDrag = { vChange, vDragged ->
				vChange.consume()
				reorder.drag(vDragged.y, vKeys, vOnMove)
			},
		)
	}
}

/**
 * The grip a row is dragged by.
 *
 * A handle rather than the whole row, so a press that lands on the card still does what the card
 * does and the list can still be scrolled with a finger anywhere else.
 *
 * The gesture lives on the handle, and a screen reader gets the row's custom actions instead, so
 * this announces nothing -- naming a control a screen reader cannot use is noise.
 */
@Composable
fun ReorderHandle(modifier: Modifier = Modifier) {
	Icon(
		imageVector = AppIcons.DragHandle,
		contentDescription = null,
		tint = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = modifier.padding(horizontal = 6.dp).size(24.dp),
	)
}

/** How far the dragged row lifts off the list. Material's resting elevation for a dragged item. */
val DRAGGED_ROW_ELEVATION = 8.dp
