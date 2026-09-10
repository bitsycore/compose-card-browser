package com.bitsycore.cardbrowser.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * A drag strip down the right edge that scrubs a long list.
 *
 * Flinging through 988 Magic sets is a lot of flinging. This turns the whole list into one gesture:
 * the strip maps the finger's vertical position straight onto an index, so the top of the screen is
 * the first set and the bottom is the last, and the list follows as it moves.
 *
 * ## What it shows while dragging
 *
 * The page dims and a column of labels appears beside the finger, largest at the finger and
 * shrinking away from it. That fisheye is not decoration -- it is what makes the gesture usable.
 * Mapping a whole list onto one screen height means a single pixel can be several sets, so the
 * strip alone cannot say where you are; and the obvious fix of listing every label at once does not
 * survive a real catalogue, because 988 of them will not fit on a phone at any legible size. A
 * window around the finger shows the neighbourhood being scrubbed through and stays legible whether
 * the list holds thirty entries or a thousand.
 *
 * ## Why it is not offered on short lists
 *
 * Below [MINIMUM_ITEMS] an ordinary scroll reaches anything in a flick or two, and a permanent strip
 * down the edge of a nine-row list is a control in the way of the content it is meant to help with.
 *
 * @param labels one per item, in list order, and the same length as the list itself. Read only
 *   while a drag is in progress
 */
@Composable
fun BoxScope.FastScroller(
	listState: LazyListState,
	labels: List<String>,
	modifier: Modifier = Modifier,
) {
	if (labels.size < MINIMUM_ITEMS) return

	val vScope = rememberCoroutineScope()
	val vLabels by rememberUpdatedState(labels)
	var vIsDragging by remember { mutableStateOf(false) }
	var vFraction by remember { mutableFloatStateOf(0f) }
	var vHeight by remember { mutableFloatStateOf(1f) }

	// The index the finger is currently over. Derived rather than stored, so it cannot disagree
	// with the position the list was actually scrolled to.
	val vIndex = (vFraction * (vLabels.size - 1)).roundToInt().coerceIn(0, vLabels.lastIndex)

	fun scrubTo(y: Float) {
		vFraction = (y / vHeight).coerceIn(0f, 1f)
		val vTarget = (vFraction * (vLabels.size - 1)).roundToInt().coerceIn(0, vLabels.lastIndex)
		vScope.launch {
			// Not animated. The list is following a finger, and an animation would mean it is
			// always chasing a position the finger has already left.
			listState.scrollToItem(vTarget)
		}
	}

	// The dim, and the labels, both belong to the whole screen rather than to the strip -- so they
	// are drawn here, above the list, and only while a drag is in progress.
	ScrubOverlay(isVisible = vIsDragging, index = vIndex, labels = vLabels)

	Box(
		modifier = modifier
			.align(Alignment.CenterEnd)
			.fillMaxHeight()
			.width(STRIP_WIDTH)
			.zIndex(2f)
			.pointerInput(Unit) {
				vHeight = size.height.toFloat().coerceAtLeast(1f)
				detectVerticalDragGestures(
					onDragStart = { vOffset ->
						vIsDragging = true
						vHeight = size.height.toFloat().coerceAtLeast(1f)
						scrubTo(vOffset.y)
					},
					onDragEnd = { vIsDragging = false },
					onDragCancel = { vIsDragging = false },
					onVerticalDrag = { vChange, _ ->
						vChange.consume()
						scrubTo(vChange.position.y)
					},
				)
			},
	) {
		// The handle. Deliberately faint until touched: it sits over the list all the time, and a
		// solid bar down the edge of every set list would read as chrome nobody asked for.
		val vHandleAlpha by animateFloatAsState(
			targetValue = if (vIsDragging) 1f else RESTING_HANDLE_ALPHA,
			label = "fast-scroll-handle",
		)
		Box(
			modifier = Modifier
				.align(Alignment.CenterEnd)
				.padding(end = 3.dp)
				.width(HANDLE_WIDTH)
				.fillMaxHeight(HANDLE_HEIGHT_FRACTION)
				.graphicsLayer { alpha = vHandleAlpha }
				.clip(RoundedCornerShape(50))
				.background(MaterialTheme.colorScheme.onSurfaceVariant),
		)
	}
}

/**
 * The dimmed page and the fisheye column of labels.
 *
 * Separate from the strip so the strip stays a gesture target and this stays a picture: the overlay
 * fills the screen and must not take the pointer, or the drag it is describing would end the moment
 * it appeared.
 */
@Composable
private fun BoxScope.ScrubOverlay(isVisible: Boolean, index: Int, labels: List<String>) {
	AnimatedVisibility(
		visible = isVisible,
		enter = fadeIn(),
		exit = fadeOut(),
		modifier = Modifier.matchParentSize().zIndex(1f),
	) {
		Box(
			Modifier
				.fillMaxSize()
				.background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)),
			contentAlignment = Alignment.CenterEnd,
		) {
			Column(
				modifier = Modifier.padding(end = STRIP_WIDTH + 8.dp),
				horizontalAlignment = Alignment.End,
				verticalArrangement = Arrangement.spacedBy(2.dp),
			) {
				val vFrom = (index - WINDOW).coerceAtLeast(0)
				val vTo = (index + WINDOW).coerceAtMost(labels.lastIndex)
				for (vAt in vFrom..vTo) {
					ScrubLabel(
						text = labels[vAt],
						// 1 at the finger, falling away with distance. This is the fisheye: the
						// neighbourhood stays readable while the ends of the window shrink out of
						// the way rather than being cut off abruptly.
						nearness = 1f - abs(vAt - index).toFloat() / (WINDOW + 1),
						isCurrent = vAt == index,
					)
				}
			}
		}
	}
}

/** One label, scaled and faded by how close it is to the finger. */
@Composable
private fun ScrubLabel(text: String, nearness: Float, isCurrent: Boolean) {
	Surface(
		color = if (isCurrent) {
			MaterialTheme.colorScheme.primaryContainer
		} else {
			MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
		},
		shape = RoundedCornerShape(8.dp),
		modifier = Modifier.graphicsLayer {
			val vScale = MIN_LABEL_SCALE + (1f - MIN_LABEL_SCALE) * nearness
			scaleX = vScale
			scaleY = vScale
			// Scaled from the right edge, so the column stays pinned to the strip instead of the
			// labels drifting sideways as they grow.
			transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
			alpha = MIN_LABEL_ALPHA + (1f - MIN_LABEL_ALPHA) * nearness
		},
	) {
		Text(
			text = text,
			style = if (isCurrent) {
				MaterialTheme.typography.titleMedium
			} else {
				MaterialTheme.typography.bodyMedium
			},
			fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
		)
	}
}

/** Below this many rows an ordinary scroll is quicker than reaching for a strip. */
private const val MINIMUM_ITEMS = 25

/** How many labels either side of the finger the fisheye shows. */
private const val WINDOW = 6

private const val SCRIM_ALPHA = 0.55f
private const val MIN_LABEL_SCALE = 0.72f
private const val MIN_LABEL_ALPHA = 0.35f
private const val RESTING_HANDLE_ALPHA = 0.22f
private const val HANDLE_HEIGHT_FRACTION = 0.45f
private val STRIP_WIDTH = 28.dp
private val HANDLE_WIDTH = 4.dp
