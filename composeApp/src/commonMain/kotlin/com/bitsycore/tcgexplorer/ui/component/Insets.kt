package com.bitsycore.tcgexplorer.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection

/**
 * A scaffold's insets without the bottom one, so content can travel under it.
 *
 * Applied as a margin, a bottom inset stops a list dead above the home indicator and leaves a band
 * of background below it that scrolls with nothing in it. The inset belongs to the *content*
 * instead -- as a lazy list's bottom `contentPadding`, or as padding inside a scrolling column --
 * which lets the rows pass under the indicator while the last one can still be scrolled clear of
 * it. See `CardGridScreen`, which has always done this.
 *
 * Start and end are kept: in landscape on a notched phone they are the display cutout, and content
 * must not go under that.
 */
@Composable
fun PaddingValues.withoutBottom(): PaddingValues {
	val vDirection = LocalLayoutDirection.current
	return PaddingValues(
		start = calculateStartPadding(vDirection),
		top = calculateTopPadding(),
		end = calculateEndPadding(vDirection),
	)
}
