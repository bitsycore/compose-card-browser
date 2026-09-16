package com.bitsycore.tcgexplorer.ui.component

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp

/**
 * Bounds a `DropdownMenu`'s height, because Material's own menu cannot survive an unbounded one.
 *
 * `DropdownMenuContent` is a `Column` with `width(IntrinsicSize.Max)` over a `verticalScroll`.
 * `MaxIntrinsicWidthModifier` measures its child with `Constraints.fixedWidth`, whose `maxHeight`
 * is `Infinity`, and clamps that against the constraints it was handed -- so an unbounded height
 * arriving from the popup layer survives into the scroll, which throws *"Vertically scrollable
 * component was measured with an infinity maximum height constraints"* the moment the menu opens.
 *
 * iPadOS hands the layer an unbounded height while a window is being resized, so this needs a
 * resizable window to reach at all. It first appeared when multitasking was enabled -- see the
 * orientation note in `iosApp/README.md`.
 *
 * A menu's own modifier is applied above that chain, so a maximum set here is what the intrinsic
 * width modifier then clamps to. The window's height, so nothing is clipped that would have fitted;
 * the fallback only covers the frame before the scene has a size.
 */
@Composable
fun Modifier.boundedMenuHeight(): Modifier {
	val vHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
	return heightIn(max = if (vHeight > 0.dp) vHeight else FALLBACK_MENU_HEIGHT)
}

/** Only ever used before the scene reports a size. Taller than any menu in the app. */
private val FALLBACK_MENU_HEIGHT = 480.dp
