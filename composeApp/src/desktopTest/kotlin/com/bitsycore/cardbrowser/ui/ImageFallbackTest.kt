package com.bitsycore.cardbrowser.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import coil3.decode.DataSource
import com.bitsycore.cardbrowser.core.model.Artwork
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.ui.common.CardImage
import com.bitsycore.cardbrowser.ui.common.ImageVariant
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That a broken rendition really does escalate to the next one, at runtime.
 *
 * `ImageVariantTest` covers which renditions are tried and in what order; this covers the part that
 * cannot be seen in a pure function -- whether a failure actually advances the composable to the
 * next link, and whether it stops when the links run out.
 *
 * Headless, with no network: a Coil interceptor stands in for the CDN, records every URL asked for,
 * and fails the ones the test wants failed. `ImageComposeScene` draws into a Skia surface with no
 * window, and the scene is rendered repeatedly to let the load coroutines run between frames.
 */
@OptIn(DelicateCoilApi::class)
class ImageFallbackTest {

	private val mRequested = CopyOnWriteArrayList<String>()

	private val THUMBNAIL = "https://cdn.test/card.png?w=320&fm=webp"
	private val DISPLAY = "https://cdn.test/card.png?w=744&q=90&fm=webp"
	private val ORIGINAL = "https://cdn.test/card.png"

	private val mArtwork = Artwork(
		id = SourceId(ProviderId("test"), "art-1"),
		imageUrl = ORIGINAL,
		thumbnailUrl = THUMBNAIL,
		displayUrl = DISPLAY,
		artist = null,
		treatment = ArtworkTreatment.STANDARD,
		language = null,
	)

	@AfterTest
	fun resetLoader() {
		SingletonImageLoader.reset()
	}

	/** Installs a loader that fails every URL in [failing] and succeeds for anything else. */
	private fun installLoader(failing: Set<String>) {
		mRequested.clear()
		SingletonImageLoader.setUnsafe { vContext ->
			ImageLoader.Builder(vContext)
				.components {
					add(
						Interceptor { vChain ->
							val vUrl = vChain.request.data.toString()
							mRequested += vUrl
							if (vUrl in failing) {
								ErrorResult(
									image = null,
									request = vChain.request,
									throwable = IllegalStateException("undecodable: $vUrl"),
								)
							} else {
								SuccessResult(
									image = ColorImage(width = 32, height = 44),
									request = vChain.request,
									dataSource = DataSource.MEMORY,
								)
							}
						},
					)
				}
				.build()
		}
	}

	/**
	 * Draws [ImageVariant.THUMBNAIL] of the artwork and lets the loads settle.
	 *
	 * Rendered many times with a pause between: each failure schedules the next attempt from a
	 * `LaunchedEffect`, so the escalation needs several frames to walk the chain.
	 */
	@OptIn(ExperimentalComposeUiApi::class)
	private fun drawAndSettle() = runBlocking {
		val vScene = ImageComposeScene(width = 200, height = 280, density = Density(1f)) {
			CardImage(
				artwork = mArtwork,
				contentDescription = "a card",
				modifier = Modifier.fillMaxSize(),
				variant = ImageVariant.THUMBNAIL,
			)
		}
		try {
			repeat(40) {
				vScene.render()
				delay(25)
			}
		} finally {
			vScene.close()
		}
	}

	// ============
	//  Escalation

	@Test
	fun `a working thumbnail never costs a larger rendition`() {
		// The guard on the whole feature. If a chain made a grid fetch full art for healthy cards
		// it would be a bandwidth regression that no screenshot would show.
		installLoader(failing = emptySet())

		drawAndSettle()

		assertEquals(listOf(THUMBNAIL), mRequested.distinct())
	}

	@Test
	fun `a broken thumbnail falls back to the full art`() {
		// The reported case: the thumbnail cannot be decoded but the larger rendition of the same
		// art is a different file, in a different format, and decodes.
		installLoader(failing = setOf(THUMBNAIL))

		drawAndSettle()

		assertTrue(THUMBNAIL in mRequested, "the cheap rendition should still be tried first")
		assertTrue(DISPLAY in mRequested, "the broken thumbnail never escalated: $mRequested")
		assertEquals(
			THUMBNAIL,
			mRequested.first(),
			"the thumbnail must be asked for before anything larger",
		)
		// One automatic retry of the thumbnail before giving up on it, because a cached-but-broken
		// response is cured by eviction rather than by a different URL.
		assertEquals(2, mRequested.count { it == THUMBNAIL }, "expected exactly one retry")
		// And it stops as soon as something works.
		assertTrue(ORIGINAL !in mRequested, "escalated past a rendition that worked: $mRequested")
	}

	@Test
	fun `every rendition is tried before giving up`() {
		installLoader(failing = setOf(THUMBNAIL, DISPLAY))

		drawAndSettle()

		assertTrue(ORIGINAL in mRequested, "the last rendition was never reached: $mRequested")
	}

	@Test
	fun `a wholly broken artwork stops rather than looping`() {
		// A CDN that is down must not be hammered by every visible tile in a grid. Each rendition
		// gets one attempt and one retry, and then it is over -- so the total is bounded.
		installLoader(failing = setOf(THUMBNAIL, DISPLAY, ORIGINAL))

		drawAndSettle()

		assertEquals(3, mRequested.distinct().size, "should have tried each rendition once")
		assertEquals(
			6,
			mRequested.size,
			"expected one attempt and one retry per rendition, got $mRequested",
		)
	}
}
