package com.bitsycore.cardbrowser.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.size.Size
import com.bitsycore.cardbrowser.core.model.Artwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A card image, with the three states a network image really has, and a way out of the third.
 *
 * Loading draws a placeholder block the same shape as the card, so a grid does not reflow as images
 * arrive. A failure draws a broken-image mark rather than empty space, because a blank tile reads as
 * "no such card" instead of "the picture did not load".
 *
 * ## Why a failure has to be recoverable
 *
 * A failed image is not always a failed *request*. A provider CDN can answer `200 OK` with a
 * perfectly intact file in a format the platform cannot decode -- Riot's CDN really does serve AVIF
 * for a minority of Riftbound cards, and Skia decodes none of it. Coil quite reasonably caches a
 * `200`, so without recovery that card is broken on every launch forever, and no amount of HTTP
 * retrying helps because the request never failed.
 *
 * So the first failure triggers one automatic attempt that **evicts the memory and disk entries
 * first**, which is what distinguishes it from a plain retry and what actually cures a poisoned
 * cache. If that also fails the mark stays, and where there is room for it the user gets a retry
 * button.
 *
 * @param variant which of the provider's image sizes to ask for. [ImageVariant.THUMBNAIL] in the
 *   grid and the preview strip: loading full-resolution art for a scrolling grid is the single
 *   easiest way to make a card browser unusable on a phone
 * @param decodeAtSourceResolution decodes the image at its own pixels instead of at the size of the
 *   box it is drawn in. Off by default, because decoding a 744 px card into a 108 dp tile wastes
 *   memory on every tile in a grid -- but **on** wherever the image can be magnified, since Coil's
 *   default sizes the bitmap to the layout and zooming then magnifies a box-sized bitmap rather
 *   than the card
 * @param filterQuality how the bitmap is resampled when it does not land on screen at its native
 *   size. Compose defaults to [FilterQuality.Low], plain bilinear, which visibly aliases card art
 *   shrunk into a grid tile -- fine lines in card borders and text crawl and shimmer. [FilterQuality.High]
 *   costs a little GPU per frame and is worth it on a screen whose entire content is downscaled art
 * @param allowManualRetry adds a retry button to the failure state. Off by default: on a grid tile
 *   or a preview thumbnail the tap belongs to opening or selecting the card, and a button competing
 *   for it would be worse than the automatic attempt alone
 */
@Composable
fun CardImage(
	artwork: Artwork,
	contentDescription: String?,
	modifier: Modifier = Modifier,
	variant: ImageVariant = ImageVariant.THUMBNAIL,
	decodeAtSourceResolution: Boolean = false,
	contentScale: ContentScale = ContentScale.Fit,
	filterQuality: FilterQuality = FilterQuality.High,
	allowManualRetry: Boolean = false,
) {
	val vUrl = variant.urlFor(artwork)

	// The same art, smaller, and almost certainly already in the cache because the grid drew it.
	// Shown underneath while the full-size one is still coming down, so opening a card lands on the
	// card rather than on a grey rectangle. `null` when this *is* the small one.
	val vPlaceholderUrl = artwork.thumbnailUrl?.takeIf { it != vUrl }

	if (vUrl.isBlank()) {
		// Nothing to retry: the provider supplied no image at all.
		ImagePlaceholder(modifier, isError = true, onRetry = null)
		return
	}

	val vContext = LocalPlatformContext.current
	val vScope = rememberCoroutineScope()

	// Both keyed on the URL, so a recycled tile showing a different card starts fresh rather than
	// inheriting the previous card's failure.
	var vAttempt by remember(vUrl) { mutableIntStateOf(0) }
	var vHasAutoRetried by remember(vUrl) { mutableStateOf(false) }

	val vRetry: () -> Unit = {
		vScope.launch {
			withContext(Dispatchers.Default) { evictCachedImage(vContext, vUrl) }
			vAttempt++
		}
		Unit
	}

	// Changing the key rebuilds the painter, which is what issues a genuinely new request. Reusing
	// the same model string would let Coil hand back the cached failure.
	val vModel = remember(vUrl, decodeAtSourceResolution, vContext) {
		ImageRequest.Builder(vContext)
			.data(vUrl)
			.apply { if (decodeAtSourceResolution) size(Size.ORIGINAL) }
			.build()
	}

	Box(modifier) {
		// Drawn for the whole life of the composable rather than only while loading, so the
		// full-size image simply appears on top of it. Swapping one for the other at the moment the
		// load finishes would flash the background between them.
		if (vPlaceholderUrl != null) {
			AsyncImage(
				model = vPlaceholderUrl,
				// The real image carries the description; this is decoration behind it.
				contentDescription = null,
				modifier = Modifier.matchParentSize(),
				contentScale = contentScale,
				filterQuality = filterQuality,
			)
		}

		key(vAttempt) {
		SubcomposeAsyncImage(
			model = vModel,
			contentDescription = contentDescription ?: artwork.accessibilityText,
			modifier = Modifier.matchParentSize(),
			contentScale = contentScale,
			filterQuality = filterQuality,
			loading = {
				if (vPlaceholderUrl != null) {
					// Something recognisable is already behind this, so all that is needed is a
					// sign that a better one is on the way.
					LoadingOverlay()
				} else {
					ImagePlaceholder(Modifier.fillMaxSize(), isError = false, onRetry = null)
				}
			},
			error = {
				LaunchedEffect(vUrl) {
					// Once, and only once. A CDN that is genuinely down should not be hammered by
					// every visible tile in a grid retrying in a loop.
					if (!vHasAutoRetried) {
						vHasAutoRetried = true
						vRetry()
					}
				}
				ImagePlaceholder(
					modifier = Modifier.fillMaxSize(),
					isError = true,
					// Offered only after the automatic attempt has been spent, so the button is
					// never a no-op.
					onRetry = if (allowManualRetry && vHasAutoRetried) vRetry else null,
				)
			},
		)
		}
	}
}

/**
 * A spinner over art that is already showing, at lower resolution.
 *
 * Sat on its own scrim disc because card art is busy and a bare indicator disappears into it.
 */
@Composable
private fun LoadingOverlay() {
	Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		Box(
			modifier = Modifier
				.size(44.dp)
				.clip(CircleShape)
				.background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
			contentAlignment = Alignment.Center,
		) {
			CircularProgressIndicator(
				modifier = Modifier.size(24.dp),
				strokeWidth = 2.dp,
				color = Color.White,
			)
		}
	}
}

/**
 * Forgets everything cached for [url], in memory and on disk.
 *
 * The disk key is the URL itself; Coil hashes it to produce the filename. Removing both is the
 * whole point of the retry -- leaving either in place would re-serve the response that could not be
 * decoded.
 */
private fun evictCachedImage(context: PlatformContext, url: String) {
	val vLoader = SingletonImageLoader.get(context)
	vLoader.memoryCache?.remove(MemoryCache.Key(url))
	vLoader.diskCache?.remove(url)
}

/** A flat block the shape of the tile it fills. */
@Composable
private fun ImagePlaceholder(
	modifier: Modifier,
	isError: Boolean,
	onRetry: (() -> Unit)?,
) {
	Box(
		modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
		contentAlignment = Alignment.Center,
	) {
		if (!isError) return@Box

		if (onRetry != null) {
			Icon(
				imageVector = Icons.Outlined.Refresh,
				contentDescription = "Image unavailable. Tap to try again.",
				modifier = Modifier.size(32.dp).clickable(onClick = onRetry),
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		} else {
			Icon(
				imageVector = Icons.Outlined.BrokenImage,
				contentDescription = "Image unavailable",
				modifier = Modifier.size(24.dp),
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

/**
 * Which of a provider's renditions of one artwork to load.
 *
 * An enum rather than a boolean because there are genuinely three, they are used in three different
 * places for three different reasons, and `useThumbnail = false` said nothing about which of the
 * other two you were getting.
 */
enum class ImageVariant {

	/** Grid tiles and the preview strip. Tens of kilobytes. */
	THUMBNAIL,

	/**
	 * The detail screen and the fullscreen viewer: the source's own resolution, compressed.
	 *
	 * Not the same as [ORIGINAL]. Both are 744 px wide for a Riftbound card; this one is WebP at
	 * ~180 KB and the other is lossless PNG at ~1.17 MB. There is no resolution to be gained by
	 * taking the larger -- see `RiftcodexMapper.displayUrl`.
	 */
	DISPLAY,

	/** Exactly what the provider published, untouched. Nothing asks for this today. */
	ORIGINAL,
	;

	/** The URL for this variant, falling back through the ones a provider did supply. */
	fun urlFor(artwork: Artwork): String = when (this) {
		THUMBNAIL -> artwork.thumbnailUrl ?: artwork.displayUrl ?: artwork.imageUrl
		DISPLAY -> artwork.displayUrl ?: artwork.imageUrl
		ORIGINAL -> artwork.imageUrl
	}
}

/**
 * Warms the cache for artwork the user has not asked for yet but is about to.
 *
 * Enqueued rather than composed: Coil fetches and decodes into its memory and disk caches without a
 * layout node, so the neighbours of a card cost bandwidth but no composition. When the user swipes,
 * the image is already there and there is nothing to wait for.
 *
 * Deliberately cheap to get wrong -- a prefetch that never gets used is a few tens of kilobytes of
 * WebP, and one that does is the difference between a smooth swipe and a spinner.
 */
@Composable
fun PrefetchCardArt(artworks: List<Artwork>, variant: ImageVariant = ImageVariant.DISPLAY) {
	val vContext = LocalPlatformContext.current
	val vUrls = artworks.map(variant::urlFor).filter { it.isNotBlank() }

	LaunchedEffect(vUrls) {
		val vLoader = SingletonImageLoader.get(vContext)
		vUrls.forEach { vUrl ->
			// Fire and forget. `enqueue` does not suspend and its result is of no interest: a
			// prefetch that fails simply means the real request will do the work later.
			vLoader.enqueue(ImageRequest.Builder(vContext).data(vUrl).build())
		}
	}
}
