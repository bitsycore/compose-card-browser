package com.bitsycore.cardbrowser.ui

import com.bitsycore.cardbrowser.core.model.Artwork
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.ui.common.ImageVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which renditions of one artwork are tried, and in what order.
 *
 * The order is the whole point: a grid must still ask for the thumbnail first, and only a tile
 * whose thumbnail is genuinely broken should ever pay for full art. A chain that started with the
 * large rendition would be a performance regression invisible in a screenshot.
 */
class ImageVariantTest {

	private fun artwork(
		image: String = "https://cdn.test/card.png",
		thumbnail: String? = "https://cdn.test/card.png?w=320&fm=webp",
		display: String? = "https://cdn.test/card.png?w=744&q=90&fm=webp",
	) = Artwork(
		id = SourceId(ProviderId("test"), "art-1"),
		imageUrl = image,
		thumbnailUrl = thumbnail,
		displayUrl = display,
		artist = null,
		treatment = ArtworkTreatment.STANDARD,
		language = null,
	)

	// ============
	//  Order

	@Test
	fun `a thumbnail escalates through display to the original`() {
		// Measured on a real Riftbound asset: 23 KB WebP, 132 KB WebP, 744 KB PNG. Cheapest first.
		assertEquals(
			listOf(
				"https://cdn.test/card.png?w=320&fm=webp",
				"https://cdn.test/card.png?w=744&q=90&fm=webp",
				"https://cdn.test/card.png",
			),
			ImageVariant.THUMBNAIL.chainFor(artwork()),
		)
	}

	@Test
	fun `display escalates to the original and never down to the thumbnail`() {
		// Falling back to a 320 px thumbnail on the fullscreen viewer would be worse than the
		// broken mark: it looks like the app simply renders card art badly.
		assertEquals(
			listOf(
				"https://cdn.test/card.png?w=744&q=90&fm=webp",
				"https://cdn.test/card.png",
			),
			ImageVariant.DISPLAY.chainFor(artwork()),
		)
	}

	@Test
	fun `the original has nowhere to escalate to`() {
		assertEquals(listOf("https://cdn.test/card.png"), ImageVariant.ORIGINAL.chainFor(artwork()))
	}

	@Test
	fun `the first link is what the single-url callers get`() {
		// `urlFor` is what the prefetcher uses, so it must keep asking for the cheap one.
		ImageVariant.entries.forEach { vVariant ->
			assertEquals(vVariant.chainFor(artwork()).first(), vVariant.urlFor(artwork()))
		}
	}

	// ============
	//  Providers that publish less

	@Test
	fun `a provider with one image for everything yields a chain of one`() {
		// Four of the seven sources resize nothing, so this is the ordinary case and it must behave
		// exactly as it did before there was a chain at all.
		val vSingle = artwork(thumbnail = null, display = null)

		assertEquals(listOf("https://cdn.test/card.png"), ImageVariant.THUMBNAIL.chainFor(vSingle))
		assertEquals(listOf("https://cdn.test/card.png"), ImageVariant.DISPLAY.chainFor(vSingle))
	}

	@Test
	fun `identical urls are not tried twice`() {
		// Retrying a URL that just failed, as though it were a different rendition, would spend the
		// escalation on nothing and hide the real fallback behind it.
		val vSame = artwork(
			image = "https://cdn.test/card.png",
			thumbnail = "https://cdn.test/card.png",
			display = "https://cdn.test/card.png",
		)

		assertEquals(1, ImageVariant.THUMBNAIL.chainFor(vSame).size)
	}

	@Test
	fun `a blank url is not a rendition`() {
		val vBlanks = artwork(image = "", thumbnail = "   ", display = null)

		assertTrue(ImageVariant.THUMBNAIL.chainFor(vBlanks).isEmpty())
		// Which is what makes the screen draw the broken mark rather than request an empty URL.
		assertEquals("", ImageVariant.THUMBNAIL.urlFor(vBlanks))
	}

	@Test
	fun `a missing middle rendition is skipped rather than leaving a hole`() {
		val vNoDisplay = artwork(display = null)

		assertEquals(
			listOf("https://cdn.test/card.png?w=320&fm=webp", "https://cdn.test/card.png"),
			ImageVariant.THUMBNAIL.chainFor(vNoDisplay),
		)
	}
}
