package com.bitsycore.cardbrowser.ui.common

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.svg.SvgDecoder
import coil3.request.crossfade
import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.data.cache.CacheManager
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import io.ktor.client.HttpClient
import org.koin.compose.koinInject

/**
 * Installs the app's image loader.
 *
 * Three things are worth stating:
 *
 * 1. **One HTTP stack.** The fetcher is given the app's own Ktor client, so images go out with the
 *    same User-Agent, timeouts and retry policy as card metadata. A second client would mean a
 *    second, unpoliced way of talking to a volunteer-run CDN.
 * 2. **A bounded, separate disk cache.** It lives in [AppStorage.imageCacheDir], apart from the
 *    metadata cache, with its own ceiling. Coil evicts least-recently-used within it, so browsing a
 *    large set cannot push the app's storage up without limit -- and clearing images does not cost
 *    the metadata that makes offline browsing work.
 * 3. **No prefetching.** Coil loads what is composed and nothing more. Combined with the grid asking
 *    for thumbnail URLs and only the detail screen asking for full-resolution art, that means the
 *    app never downloads a full-size image nobody has opened.
 */
@Composable
fun InstallImageLoader() {
	val vStorage = koinInject<AppStorage>()
	val vClient = koinInject<HttpClient>()
	val vPreferences = koinInject<PreferencesStore>()

	// Read once, when the loader is built. Coil fixes a disk cache's ceiling at construction, so a
	// change in settings applies on the next launch -- which the settings screen says plainly rather
	// than pretending otherwise.
	val vLimit = vPreferences.preferences.value.imageCacheLimitBytes

	// The download queue needs a `PlatformContext` to resolve the singleton loader, and it is a
	// plain Koin singleton with no composition near it. This is the one place that has both.
	val vPrefetcher = koinInject<CoilImagePrefetcher>()
	val vContext = LocalPlatformContext.current
	LaunchedEffect(vContext) { vPrefetcher.attach(vContext) }

	setSingletonImageLoaderFactory { vFactoryContext ->
		newImageLoader(vFactoryContext, vStorage, vClient, vLimit)
	}
}

/** Builds the loader. Separate from the composable so it can be exercised without a composition. */
internal fun newImageLoader(
	context: PlatformContext,
	storage: AppStorage,
	client: HttpClient,
	diskCacheMaxBytes: Long = CacheManager.DEFAULT_IMAGE_CACHE_MAX_BYTES,
): ImageLoader = ImageLoader.Builder(context)
	.components {
		add(KtorNetworkFetcherFactory(httpClient = { client }))
		// Set symbols. Scryfall publishes all 988 of its as SVG and nothing else, so without a
		// decoder for them every Magic set falls back to its code in a tile.
		add(SvgDecoder.Factory())
	}
	.memoryCache {
		MemoryCache.Builder()
			// A share of available memory rather than a fixed figure, so a phone and a desktop each
			// get something sensible. Card art is large; caching too much of it in memory is the
			// fastest way to an OOM on a low-end device.
			.maxSizePercent(context, 0.20)
			.build()
	}
	.diskCache {
		DiskCache.Builder()
			.directory(storage.imageCacheDir)
			.maxSizeBytes(diskCacheMaxBytes)
			.build()
	}
	.crossfade(true)
	.build()
