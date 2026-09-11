package com.bitsycore.cardbrowser.ui.common

import androidx.compose.foundation.focusGroup
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
