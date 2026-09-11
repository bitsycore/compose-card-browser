package com.bitsycore.cardbrowser.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.bitsycore.cardbrowser.ui.common.AppIcons

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
 * So a failure is answered in two ways, in order:
 *
 * 1. **One retry that evicts the memory and disk entries first.** This is what distinguishes it
 *    from a plain retry and what cures a poisoned cache -- the response was a `200`, so Coil is
 *    quite right to have kept it, and it has to be thrown away explicitly.
 * 2. **The next rendition up.** A file that cannot be decoded says nothing about the card, and the
 *    larger rendition of the same art is a *different file*, often in a different format: for
 *    Riftbound, 23 KB of WebP, then 132 KB of WebP, then 744 KB of PNG. Retrying the same URL
 *    cannot fix a format the platform does not support; asking for another one can. See
 *    [ImageVariant.chainFor].
 *
 * Only when every rendition is spent does the mark stay, and where there is room for it the user
 * gets a retry button that starts again from the cheapest.
 *
 * Nothing here changes what is fetched *first*. A grid still asks for thumbnails, and only a tile
 * whose thumbnail is genuinely broken ever pays for full art.
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
	// Every rendition of this art, best first. See [ImageVariant.chainFor].
	val vChain = remember(artwork, variant) { variant.chainFor(artwork) }

	if (vChain.isEmpty()) {
		// Nothing to retry: the provider supplied no image at all.
		ImagePlaceholder(modifier, isError = true, onRetry = null)
		return
	}

	val vContext = LocalPlatformContext.current
	val vScope = rememberCoroutineScope()

	// All keyed on the chain, so a recycled tile showing a different card starts fresh rather than
	// inheriting the previous card's failure.
	var vIndex by remember(vChain) { mutableIntStateOf(0) }
	var vAttempt by remember(vChain) { mutableIntStateOf(0) }
	// Which URLs have been retried once, and which have given up entirely. Per URL rather than per
	// composable, because each rendition gets its own single automatic attempt.
	val vRetried = remember(vChain) { mutableStateListOf<String>() }
	val vFailed = remember(vChain) { mutableStateListOf<String>() }

	val vUrl = vChain[vIndex]

	// The same art, smaller, and almost certainly already in the cache because the grid drew it.
	// Shown underneath while the full-size one is still coming down, so opening a card lands on the
	// card rather than on a grey rectangle. `null` when this *is* the small one -- or when it is
	// the rendition that just failed, in which case putting it back would be showing the user the
	// broken thing they escalated away from.
	val vPlaceholderUrl = artwork.thumbnailUrl
		?.ifBlank { null }
		?.takeIf { it != vUrl && it !in vFailed }

	val vRetry: () -> Unit = {
		vScope.launch {
			// Start again from the best rendition, forgetting everything: a manual retry is the
			// user saying the conditions have changed, so a cheaper rendition that failed before
			// deserves another go before an expensive one is fetched.
			withContext(Dispatchers.Default) { vChain.forEach { evictCachedImage(vContext, it) } }
			vRetried.clear()
			vFailed.clear()
			vIndex = 0
			vAttempt++
		}
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
					when {
						// One automatic attempt per rendition, evicting first, which is what cures
						// a cached-but-undecodable response. Once only: a CDN that is genuinely
						// down should not be hammered by every visible tile in a grid.
						vUrl !in vRetried -> {
							vRetried.add(vUrl)
							withContext(Dispatchers.Default) { evictCachedImage(vContext, vUrl) }
							vAttempt++
						}
						// This rendition is beyond help. Try the next one up -- a different file,
						// so there is nothing to evict and no reason to expect the same result.
						vIndex < vChain.lastIndex -> {
							vFailed.add(vUrl)
							vIndex++
						}
						// Out of renditions.
						else -> vFailed.add(vUrl)
					}
				}
				ImagePlaceholder(
					modifier = Modifier.fillMaxSize(),
					isError = true,
					// Offered only once every rendition has been spent, so the button is never a
					// no-op and never pre-empts an escalation that is still coming.
					onRetry = if (allowManualRetry && vUrl in vFailed) vRetry else null,
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
				imageVector = AppIcons.Refresh,
				contentDescription = "Image unavailable. Tap to try again.",
				modifier = Modifier.size(32.dp).clickable(onClick = onRetry),
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		} else {
			Icon(
				imageVector = AppIcons.BrokenImage,
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
	fun urlFor(artwork: Artwork): String = chainFor(artwork).firstOrNull().orEmpty()

	/**
	 * Every rendition worth trying for this variant, best first.
	 *
	 * A chain rather than one URL because a rendition failing to load is a fact about *that file*,
	 * not about the card. A CDN answering `200 OK` with an intact image the platform cannot decode
	 * is the case that motivates all of this -- Riot's CDN serves AVIF for a minority of Riftbound
	 * cards and Skia decodes none of it -- and the larger rendition of the same art is a different
	 * file, often in a different format. Retrying the same URL cannot fix that; asking for the
	 * other one can.
	 *
	 * Ordered by cost, so nothing here changes what is fetched first. A grid still asks for
	 * thumbnails, and only a tile whose thumbnail is genuinely broken ever pays for full art --
	 * which is the trade being made: a few hundred kilobytes on the rare broken tile instead of a
	 * permanently broken tile.
	 *
	 * Blanks and duplicates are dropped, so a provider that publishes one image for all three
	 * renditions yields a chain of one and behaves exactly as before.
	 */
	fun chainFor(artwork: Artwork): List<String> = when (this) {
		THUMBNAIL -> listOf(artwork.thumbnailUrl, artwork.displayUrl, artwork.imageUrl)
		DISPLAY -> listOf(artwork.displayUrl, artwork.imageUrl)
		// Nothing to escalate to: this *is* what the provider published.
		ORIGINAL -> listOf(artwork.imageUrl)
	}.mapNotNull { it?.ifBlank { null } }.distinct()
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
