package com.bitsycore.tcgexplorer.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

/*
How much of one thing the app is holding, drawn as a dial rather than said as a sentence.

A row has room for two of these where it has room for one "Thumbnails 62%", and a dial is read as a
fraction without being read as a number -- which is what a list wants, since the comparison down a
column is "more than that one", not "sixty-two".
*/

/**
 * What is known about one downloadable half of a set.
 *
 * Four states rather than a percentage, because a percentage cannot say "not asked". See the app's
 * rule on claims: absent and zero are different answers and must not draw the same way.
 */
sealed interface Coverage {

	/** Nothing was ever recorded. Draws nothing at all -- absent is not zero. */
	data object Unknown : Coverage

	/** All of it is on the device. */
	data object Complete : Coverage

	/** Some of it is here, and how much is known. */
	data class Partial(val percent: Int) : Coverage

	/**
	 * Some of it is here and how much is **not** known.
	 *
	 * Card info records no per-set fraction -- a set is saved or it is whole, and an interrupted
	 * fetch is neither. Drawn as a bare track with no arc, so the ring states that it cannot say
	 * rather than inventing a sweep to fill.
	 */
	data object Interrupted : Coverage
}

/**
 * One [Coverage] as a ring with its subject's glyph in the middle.
 *
 * The glyph is what distinguishes two rings sitting side by side; colour alone would not, and is
 * not available to every reader. Nothing is drawn for [Coverage.Unknown].
 */
@Composable
fun CoverageRing(
	coverage: Coverage,
	icon: ImageVector,
	/** Names the subject in the spoken description, e.g. "Thumbnails". */
	label: String,
	modifier: Modifier = Modifier,
) {
	if (coverage is Coverage.Unknown) return

	val vTint = when (coverage) {
		// A partial download is not a tick, and takes the muted metadata colour for the same reason
		// the mark it replaces did: it reads as a qualification rather than as a win.
		is Coverage.Partial -> MaterialTheme.colorScheme.onSurfaceVariant
		Coverage.Interrupted -> MaterialTheme.colorScheme.error
		else -> MaterialTheme.colorScheme.primary
	}
	val vTrack = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = TRACK_ALPHA)
	val vSweep = when (coverage) {
		Coverage.Complete -> FULL_TURN
		// Integer division already floors, so 99% of a set is a visibly open ring. A sweep is never
		// rounded up to a closed circle: that would be the row claiming a finished download.
		is Coverage.Partial -> FULL_TURN * (coverage.percent.coerceIn(0, 100) / 100f)
		else -> 0f
	}
	val vDescription = when (coverage) {
		Coverage.Complete -> "$label downloaded"
		is Coverage.Partial -> "${coverage.percent}% of $label downloaded"
		Coverage.Interrupted -> "$label saved, and the download did not finish"
		Coverage.Unknown -> ""
	}

	Box(
		modifier = modifier
			.size(RING_SIZE)
			// One description for the pair of shapes: a reader wants "62% of Thumbnails downloaded",
			// not an arc and an icon announced separately.
			.clearAndSetSemantics { contentDescription = vDescription },
		contentAlignment = Alignment.Center,
	) {
		Canvas(Modifier.fillMaxSize()) {
			val vStroke = RING_STROKE.toPx()
			val vInset = vStroke / 2f
			val vBox = Size(size.width - vStroke, size.height - vStroke)
			drawArc(
				color = vTrack,
				startAngle = 0f,
				sweepAngle = FULL_TURN,
				useCenter = false,
				topLeft = Offset(vInset, vInset),
				size = vBox,
				style = Stroke(width = vStroke),
			)
			if (vSweep > 0f) {
				drawArc(
					color = vTint,
					// From the top, clockwise, as every other dial in the world turns.
					startAngle = -90f,
					sweepAngle = vSweep,
					useCenter = false,
					topLeft = Offset(vInset, vInset),
					size = vBox,
					style = Stroke(width = vStroke, cap = StrokeCap.Round),
				)
			}
		}
		Icon(
			imageVector = icon,
			contentDescription = null,
			tint = vTint,
			modifier = Modifier.size(RING_ICON),
		)
	}
}

/** The pair, in the order they are downloaded: records first, pictures second. */
@Composable
fun CoverageRings(
	info: Coverage,
	infoIcon: ImageVector,
	thumbnails: Coverage,
	thumbnailIcon: ImageVector,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier,
		// Spaced rather than padded, so a ring that draws nothing leaves no gap behind it.
		verticalArrangement = Arrangement.Center,
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		CoverageRing(coverage = info, icon = infoIcon, label = "Card info")
		Spacer(Modifier.height(RING_GAP))
		CoverageRing(coverage = thumbnails, icon = thumbnailIcon, label = "Thumbnails")
	}
}

/**
 * Big enough to read a sweep on, small enough that the pair fits the row's height.
 *
 * Measured: stacked, two rings and their gap are 44dp, against a row whose content is 45.6dp. At
 * 22dp they came to 48dp and pushed a two-ring row 2dp taller than its neighbours, which is a list
 * that does not line up for no reason a reader could name.
 */
private val RING_SIZE = 20.dp

private val RING_STROKE = 2.5.dp

private val RING_ICON = 11.dp

/** Enough that two rings read as two, not as a figure of eight. */
private val RING_GAP = 4.dp

/** Visible as a groove without competing with the arc drawn over it. */
private const val TRACK_ALPHA = 0.22f

private const val FULL_TURN = 360f
