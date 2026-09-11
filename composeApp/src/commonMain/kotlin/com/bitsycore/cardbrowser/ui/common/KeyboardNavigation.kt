package com.bitsycore.cardbrowser.ui.common

import androidx.compose.animation.core.animateTo
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * Keyboard navigation, for the platforms that have a keyboard.
 *
 * Desktop is the reason this exists, but nothing here is desktop-only: an Android tablet with a
 * keyboard case, or an iPad with a Magic Keyboard, gets the same behaviour from the same code. The
 * modifiers below live in `commonMain` for exactly that reason -- a `desktopMain` copy would have
 * been a second implementation for the same hardware.
 *
 * Two different problems, and they are solved differently on purpose:
 *
 * - **Moving between items in a list or a grid** is [arrowSelection], which keeps an index and
 *   moves it. The obvious alternative was to lean on Compose's own focus traversal -- every row is
 *   a `Card(onClick = ...)` and therefore focusable -- and it was tried, twice. It does not work
 *   for a lazy list here: focus never lands, because the items are composed during layout and a
 *   request made before that has nothing to land on, and retrying across frames did not fix it
 *   either. Both attempts shipped doing nothing, which is why `ListArrowNavigationTest` exists.
 * - **Moving between cards on the detail screen** is not a focus traversal at all. There is one
 *   card on screen and the arrows change *which*, so it is handled explicitly by [arrowKeys].
 * - **Scrolling a page that is not a list** is a third case. A `verticalScroll` column has nothing
 *   to traverse between -- a settings page is mostly text -- so nothing moves and the arrows do
 *   nothing at all. [arrowScroll] gives those the up and down keys.
 */

/**
 * Handles the arrow keys that are given a handler, and leaves the rest alone.
 *
 * A direction with no handler is not consumed, so a screen that only wants left and right does not
 * also swallow up and down from the list scrolling underneath it.
 *
 * `onKeyEvent` rather than `onPreviewKeyEvent`, and that is the important part: preview runs from
 * the root *down*, so a handler here would take the arrow keys before a focused text field could
 * use them to move its caret. Bubbling means the field gets first refusal and this only sees what
 * nothing else wanted.
 *
 * @param takeFocus whether to claim focus when this appears, so the keys work without a click
 *   first. There is no visible focus indicator on the node itself -- it is a container, and what it
 *   is for is receiving keys rather than being pointed at.
 */
@Composable
fun Modifier.arrowKeys(
	onLeft: (() -> Unit)? = null,
	onRight: (() -> Unit)? = null,
	onUp: (() -> Unit)? = null,
	onDown: (() -> Unit)? = null,
	takeFocus: Boolean = true,
): Modifier {
	val vRequester = remember { FocusRequester() }
	LaunchedEffect(takeFocus) {
		if (takeFocus) runCatching { vRequester.requestFocus() }
	}
	return this
		.focusRequester(vRequester)
		.focusable()
		.onKeyEvent { vEvent ->
			// Key *down* only. A key that is held repeats as further downs, which is what makes
			// holding an arrow walk the list; acting on the up as well would move twice per press.
			if (vEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
			val vHandler = when (vEvent.key) {
				Key.DirectionLeft -> onLeft
				Key.DirectionRight -> onRight
				Key.DirectionUp -> onUp
				Key.DirectionDown -> onDown
				else -> null
			}
			vHandler?.invoke()
			vHandler != null
		}
}

/**
 * Scrolls a `verticalScroll` container with the up and down keys.
 *
 * For the pages that are not lists. Compose scrolls a lazy list as focus moves through its items,
 * which is most of why the lists needed nothing but [focusOnFirstItem] -- but a column of text and
 * headings has few focusable children and long stretches between them, so the arrows either did
 * nothing or jumped past a screenful of content that was never scrolled to.
 *
 * Left and right are deliberately not handled, so a screen inside something that uses them -- the
 * card detail's pager, which is exactly that -- still gets them.
 *
 * @param takeFocus false when something else on the screen has the better claim, such as a text
 *   field that opens focused
 */
@Composable
fun Modifier.arrowScroll(state: ScrollState, takeFocus: Boolean = true): Modifier {
	val vScope = rememberCoroutineScope()
	val vStep = with(LocalDensity.current) { SCROLL_STEP.toPx() }
	return arrowKeys(
		onUp = { vScope.launch { state.animateScrollBy(-vStep) } },
		onDown = { vScope.launch { state.animateScrollBy(vStep) } },
		takeFocus = takeFocus,
	)
}

/**
 * How far one press of an arrow key scrolls.
 *
 * About three lines of body text. A page at a time belongs to Page Up and Page Down; an arrow key
 * that moved that far would be impossible to read along with.
 */
private val SCROLL_STEP = 64.dp

/**
 * How many frames to keep asking for the focus before giving up.
 *
 * A handful, because the only thing being waited for is the list's first layout. Giving up quietly
 * is right: a screen that never gets focus behaves as it did before, which is worse than working
 * and better than hanging on to a request forever.
 */
private const val FOCUS_ATTEMPTS = 10

/**
 * Moves a selection through a list or a grid with the arrow keys, and opens it with Enter.
 *
 * The selection is the caller's state, not this modifier's, for the same reason a `Content` takes a
 * state and a dispatch: the screen has to draw the highlight and scroll the row into view, and a
 * selection hidden in here would be invisible to both.
 *
 * Applied *outside* the list rather than to its items. That is the whole design: one focus target,
 * on a node that always exists, so nothing depends on when a lazy item happens to be composed.
 *
 * @param count how many items there are. Zero disables every key rather than clamping to an item
 *   that is not there
 * @param columns 1 for a list. For a grid, up and down move by a row and left and right by one --
 *   which is why a grid has to tell this how wide it is
 * @param onActivate Enter and Space, or null for a list where opening the selection means nothing
 * @param takeFocus false when something else on the screen has the better claim, such as a search
 *   field that opens focused -- it would otherwise eat the first letter typed
 */
@Composable
fun Modifier.arrowSelection(
	count: Int,
	selected: Int,
	onSelect: (Int) -> Unit,
	columns: Int = 1,
	onActivate: (() -> Unit)? = null,
	takeFocus: Boolean = true,
): Modifier {
	fun move(delta: Int): (() -> Unit)? {
		if (count <= 0) return null
		val vNext = (selected + delta).coerceIn(0, count - 1)
		// No handler when there is nowhere to go, so the key is passed on rather than swallowed at
		// the ends of the list.
		return if (vNext == selected) null else ({ onSelect(vNext) })
	}
	return this
		.arrowKeys(
			// A one-column list leaves left and right alone: on the card detail they change card,
			// and a list inside something that uses them should not eat them.
			onLeft = if (columns > 1) move(-1) else null,
			onRight = if (columns > 1) move(1) else null,
			onUp = move(-columns),
			onDown = move(columns),
			takeFocus = takeFocus,
		)
		.onKeyEvent { vEvent ->
			if (vEvent.type != KeyEventType.KeyDown || onActivate == null) return@onKeyEvent false
			if (vEvent.key != Key.Enter && vEvent.key != Key.Spacebar) return@onKeyEvent false
			onActivate()
			true
		}
}
