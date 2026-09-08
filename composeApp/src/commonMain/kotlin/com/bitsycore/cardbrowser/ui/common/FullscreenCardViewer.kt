package com.bitsycore.cardbrowser.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.core.model.Artwork
import kotlin.math.abs

/**
 * One card, as large as the screen allows, with pan and zoom.
 *
 * Deliberately a plain overlay rather than a `Dialog`: it is drawn over the whole detail screen,
 * including the app bar, and a dialog on three platforms brings three sets of sizing and
 * insets quirks for no gain.
 *
 * ## About "download a bigger one"
 *
 * There is no bigger one. The CDN will resize a card up to any width asked of it, but the source
 * asset is 744x1040 and the upscale carries no extra detail -- measured, its `w=1488` render is
 * *less* sharp than a plain Lanczos upscale of the native image. Asking for more would spend
 * megabytes on interpolation.
 *
 * What this does instead is decode at **full source resolution**. That is the real fix: Coil sizes a
 * request from the layout by default, so the detail screen's 420 dp box was getting a ~420 px bitmap
 * and zooming was magnifying that, not the card. Here the image is decoded at its native pixels and
 * the zoom has something to show. See [CardImage] and [ImageVariant.DISPLAY].
 */
@Composable
fun FullscreenCardViewer(
	artwork: Artwork,
	contentDescription: String?,
	isVisible: Boolean,
	onDismiss: () -> Unit,
) {
	AnimatedVisibility(
		visible = isVisible,
		enter = fadeIn(),
		exit = fadeOut(),
	) {
		BoxWithConstraints(
			modifier = Modifier
				.fillMaxSize()
				// Opaque, not translucent: the point is to look at the art with nothing behind it.
				.background(Color.Black),
		) {
			var vScale by remember { mutableFloatStateOf(1f) }
			var vOffsetX by remember { mutableFloatStateOf(0f) }
			var vOffsetY by remember { mutableFloatStateOf(0f) }

			/** Keeps the image from being dragged entirely off screen. */
			fun clampOffsets() {
				val vMaxX = (constraints.maxWidth * (vScale - 1f) / 2f).coerceAtLeast(0f)
				val vMaxY = (constraints.maxHeight * (vScale - 1f) / 2f).coerceAtLeast(0f)
				vOffsetX = vOffsetX.coerceIn(-vMaxX, vMaxX)
				vOffsetY = vOffsetY.coerceIn(-vMaxY, vMaxY)
			}

			CardImage(
				artwork = artwork,
				contentDescription = contentDescription,
				variant = ImageVariant.DISPLAY,
				// The whole reason this screen exists: the bitmap is decoded at the source's own
				// pixels rather than at the size of the box it happens to sit in.
				decodeAtSourceResolution = true,
				contentScale = ContentScale.Fit,
				modifier = Modifier
					.fillMaxSize()
					.graphicsLayer(
						scaleX = vScale,
						scaleY = vScale,
						translationX = vOffsetX,
						translationY = vOffsetY,
					)
					.pointerInput(artwork.id) {
						awaitEachGesture {
							awaitFirstDown(requireUnconsumed = false)
							do {
								val vEvent = awaitPointerEvent()
								val vZoom = vEvent.calculateZoom()
								val vPan = vEvent.calculatePan()
								if (vZoom != 1f || vScale > 1f) {
									vScale = (vScale * vZoom).coerceIn(1f, MAX_ZOOM)
									if (vScale > 1f) {
										vOffsetX += vPan.x
										vOffsetY += vPan.y
										clampOffsets()
									} else {
										vOffsetX = 0f
										vOffsetY = 0f
									}
									vEvent.changes.forEach { it.consume() }
								}
							} while (vEvent.changes.any { it.pressed })
						}
					}
					.pointerInput(artwork.id) {
						detectTapGestures(
							// The gesture everyone tries first on a photo.
							onDoubleTap = { vTap ->
								if (vScale > 1f) {
									vScale = 1f
									vOffsetX = 0f
									vOffsetY = 0f
								} else {
									vScale = DOUBLE_TAP_ZOOM
									// Zoom toward the point tapped rather than the middle.
									vOffsetX = (size.width / 2f - vTap.x) * (DOUBLE_TAP_ZOOM - 1f)
									vOffsetY = (size.height / 2f - vTap.y) * (DOUBLE_TAP_ZOOM - 1f)
									clampOffsets()
								}
							},
							onTap = {
								// A single tap closes, but only when not zoomed in -- otherwise
								// every attempt to reposition a magnified card would dismiss it.
								if (abs(vScale - 1f) < 0.01f) onDismiss()
							},
						)
					},
			)

			IconButton(
				onClick = onDismiss,
				modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
			) {
				Icon(
					imageVector = Icons.Outlined.Close,
					contentDescription = "Close full screen",
					tint = Color.White,
					modifier = Modifier.size(28.dp),
				)
			}
		}
	}
}

/** Four times is past the point where a 744 px source has anything left to show. */
private const val MAX_ZOOM = 4f

private const val DOUBLE_TAP_ZOOM = 2.5f
