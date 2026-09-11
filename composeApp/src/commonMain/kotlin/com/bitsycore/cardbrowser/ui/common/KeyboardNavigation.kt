package com.bitsycore.cardbrowser.ui.common

import androidx.compose.animation.core.animateTo
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
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
 * - **Moving between items in a list or a grid** is already Compose's job. Every row and every card
 *   is a `Card(onClick = ...)`, which is focusable, so arrow keys walk them and Enter activates
 *   them without a line of code here. What was missing is that nothing held focus to begin with, so
 *   the keys did nothing until something was clicked or tabbed to. [focusOnFirstItem] is that, and
 *   only that.
 * - **Moving between cards on the detail screen** is not a focus traversal at all. There is one
 *   card on screen and the arrows change *which*, so it is handled explicitly by [arrowKeys].
 * - **Scrolling a page that is not a list** is a third case. A `verticalScroll` column has nothing
 *   to traverse between -- a settings page is mostly text -- so nothing moves and the arrows do
 *   nothing at all. [arrowScroll] gives those the up and down keys.
 */

/**
 * Gives the screen's first focusable child the focus, once, when it appears.
 *
 * Apply to the container *around* a list or grid, not to the items. Requesting focus on a focus
 * group moves it to the first focusable thing inside, which is the first row -- and from there
 * Compose's own traversal handles the rest.
 *
 * Failures are swallowed. `requestFocus` throws if nothing under the group is focusable yet, which
 * happens when a list is still loading and is not worth a crash: the screen simply starts without
 * focus, exactly as it did before.
 *
 * @param enabled false for a screen that has a better claim on the focus. The card grid's search
 *   field is the case: opening the search and then having the grid take focus back would eat the
 *   first letter typed.
 */
@Composable
fun Modifier.focusOnFirstItem(enabled: Boolean = true): Modifier {
	val vRequester = remember { FocusRequester() }
	LaunchedEffect(enabled) {
		if (enabled) runCatching { vRequester.requestFocus() }
	}
	return this.focusRequester(vRequester).focusGroup()
}

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
