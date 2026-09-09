package com.bitsycore.cardbrowser.ui.common

import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.bitsycore.cardbrowser.data.download.ImagePrefetcher

/**
 * Fetches an image into Coil's disk cache, for the download queue.
 *
 * ## Why this is not just `enqueue`
 *
 * `PrefetchCardArt` fires requests off and ignores the result, which is right for warming the two
 * cards either side of the one on screen: a prefetch that fails costs nothing, because the real
 * request will do the work when the user gets there.
 *
 * A *download* is the opposite. The user asked for this set to be on their device, so whether each
 * image actually arrived is the entire point -- it decides the progress bar, and it decides whether
 * the job reports images that failed. So this awaits the result and reports it, and the manager
 * counts.
 *
 * ## Why the context is captured rather than injected
 *
 * Coil resolves its singleton loader from a `PlatformContext`, which on Android is the Android
 * `Context` and can only be got from a composition or the Application. The download manager is a
 * plain Koin singleton with no composition anywhere near it, so [InstallImageLoader] hands the
 * context over once, at startup, and this holds it.
 *
 * Before that happens, [prefetch] returns false rather than throwing. That state is real but
 * vanishingly brief -- the loader is installed by the first composition of `App()`, and nothing can
 * queue a download before there is a screen to tap.
 */
class CoilImagePrefetcher : ImagePrefetcher {

	/**
	 * Written once at startup and read on the download worker.
	 *
	 * Unsynchronised on purpose: it is a single assignment of an immutable value that happens before
	 * any download can be queued, and the worst a stale read could do is return false once and have
	 * the manager count one image as failed.
	 */
	private var mContext: PlatformContext? = null

	/** Called once by [InstallImageLoader]. Idempotent: the same context every time. */
	fun attach(context: PlatformContext) {
		mContext = context
	}

	override suspend fun prefetch(url: String): Boolean {
		if (url.isBlank()) return false
		val vContext = mContext ?: return false
		return try {
			val vResult = SingletonImageLoader.get(vContext)
				.execute(ImageRequest.Builder(vContext).data(url).build())
			vResult is SuccessResult
		} catch (vError: kotlinx.coroutines.CancellationException) {
			// A cancelled download must unwind, not be recorded as an image that failed.
			throw vError
		} catch (vError: Exception) {
			false
		}
	}
}
