package com.bitsycore.cardbrowser.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.bitsycore.cardbrowser.core.model.Artwork

/**
 * A card image, with the three states a network image really has.
 *
 * Loading draws a placeholder block the same shape as the card, so a grid does not reflow as images
 * arrive. A failure draws a broken-image mark rather than empty space, because a blank tile reads
 * as "no such card" instead of "the picture did not load".
 *
 * @param useThumbnail true in the grid. The provider's thumbnail URL is a fraction of the full
 *   image's bytes, and loading full-resolution art for a scrolling grid is the single easiest way
 *   to make a card browser unusable on a phone
 */
@Composable
fun CardImage(
	artwork: Artwork,
	contentDescription: String?,
	modifier: Modifier = Modifier,
	useThumbnail: Boolean = true,
	contentScale: ContentScale = ContentScale.Fit,
) {
	val vUrl = if (useThumbnail) artwork.thumbnailUrl ?: artwork.imageUrl else artwork.imageUrl

	if (vUrl.isBlank()) {
		ImagePlaceholder(modifier, isError = true)
		return
	}

	SubcomposeAsyncImage(
		model = vUrl,
		contentDescription = contentDescription ?: artwork.accessibilityText,
		modifier = modifier,
		contentScale = contentScale,
		loading = { ImagePlaceholder(Modifier.fillMaxSize(), isError = false) },
		error = { ImagePlaceholder(Modifier.fillMaxSize(), isError = true) },
	)
}

/** A flat block the shape of the tile it fills. */
@Composable
private fun ImagePlaceholder(modifier: Modifier, isError: Boolean) {
	Box(
		modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
		contentAlignment = Alignment.Center,
	) {
		if (isError) {
			Icon(
				imageVector = Icons.Outlined.BrokenImage,
				contentDescription = "Image unavailable",
				modifier = Modifier.size(24.dp),
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}
