package com.bitsycore.tcgexplorer.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How wide a single column of rows or prose is allowed to grow, and two ways to hold it there.
 *
 * Only for the screens that *are* one column. The card grid is deliberately not one of them: its
 * tiles are the content, and more of them across a tablet is the point.
 *
 * Both forms hold the *content* and leave the scrolling container at full width. Narrowing the
 * container instead would leave a tablet's outer third dead to a drag and pull the scrollbar in off
 * the edge. Both are also a no-op below [READABLE_WIDTH], so a phone lays out exactly as it did
 * before either existed.
 */

/**
 * The widest the content of a single column gets.
 *
 * A tablet in landscape will stretch a set name across the whole screen, which is hard to read and
 * looks like a bug. The setup flow chose 640dp first and the rest follows it so they agree.
 */
val READABLE_WIDTH: Dp = 640.dp

/**
 * Holds a column to [READABLE_WIDTH] and centres it in whatever width it was given.
 *
 * **Goes last in the chain**, after any padding. Ahead of a 16dp inset it would cap the gutter as
 * well and leave the content at 608dp, which is not what the lists beside it are.
 *
 * A `layout` rather than `widthIn` + `wrapContentWidth` because the no-op has to be exact: those
 * two also turn a fill-width column into a wrap-width one, so on a phone a row that does not ask
 * for the full width would stop taking it. Here a narrow or unbounded parent is passed straight
 * through, measured with the constraints the modifier was handed.
 */
fun Modifier.readableColumn(): Modifier = layout { vMeasurable, vConstraints ->
	val vMax = READABLE_WIDTH.roundToPx()
	if (!vConstraints.hasBoundedWidth || vConstraints.maxWidth <= vMax) {
		val vPlaceable = vMeasurable.measure(vConstraints)
		layout(vPlaceable.width, vPlaceable.height) { vPlaceable.place(0, 0) }
	} else {
		// minWidth too, or a column told to fill its parent would be asked for a width it is no
		// longer allowed to have.
		val vPlaceable = vMeasurable.measure(
			vConstraints.copy(minWidth = minOf(vConstraints.minWidth, vMax), maxWidth = vMax),
		)
		layout(vConstraints.maxWidth, vPlaceable.height) {
			vPlaceable.place((vConstraints.maxWidth - vPlaceable.width) / 2, 0)
		}
	}
}

/**
 * Horizontal content padding that centres a [READABLE_WIDTH] column in [available], never below
 * [minimum].
 *
 * The lazy-list form of [readableColumn]: a lazy list's size *is* its scrolling viewport, so it is
 * held in through its `contentPadding` instead. Answers [minimum] on any screen narrower than
 * [READABLE_WIDTH] plus its gutters, which is every phone.
 */
fun readablePadding(available: Dp, minimum: Dp): Dp =
	((available - READABLE_WIDTH) / 2).coerceAtLeast(minimum)
