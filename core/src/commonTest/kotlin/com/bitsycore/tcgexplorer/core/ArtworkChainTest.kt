package com.bitsycore.tcgexplorer.core

import com.bitsycore.tcgexplorer.core.model.Artwork
import com.bitsycore.tcgexplorer.core.model.ArtworkTreatment
import com.bitsycore.tcgexplorer.core.model.CardLanguage
import com.bitsycore.tcgexplorer.core.model.ProviderId
import com.bitsycore.tcgexplorer.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a grid tile draws, and therefore what a download has to warm.
 *
 * One list, read by two modules that cannot see each other: `ImageVariant.THUMBNAIL.chainFor` in
 * the Compose layer and the queue's image step in `:data`. They are asserted together here because
 * the failure when they drift is silent -- a download that reports success and leaves the grid
 * going to the network for every tile.
 */
class ArtworkChainTest {

	private fun artwork(
		imageUrl: String = "https://cdn/full.png",
		thumbnailUrl: String? = null,
		displayUrl: String? = null,
	) = Artwork(
		id = SourceId(ProviderId("p"), "1"),
		imageUrl = imageUrl,
		thumbnailUrl = thumbnailUrl,
		displayUrl = displayUrl,
		artist = null,
		treatment = ArtworkTreatment.STANDARD,
		language = CardLanguage.ENGLISH,
	)

	@Test
	fun `a source with every rendition is asked for the cheapest first`() {
		val vChain = artwork(thumbnailUrl = "https://cdn/thumb.webp", displayUrl = "https://cdn/disp.webp")
			.thumbnailChain

		assertEquals(
			listOf("https://cdn/thumb.webp", "https://cdn/disp.webp", "https://cdn/full.png"),
			vChain,
		)
	}

	@Test
	fun `a source with no small rendition falls back to the full image`() {
		// OPTCG and Altered publish one size. The queue refused them outright, so "download
		// thumbnails" for One Piece fetched nothing, recorded nothing, and could be asked for
		// again for ever -- while the grid still went to the network for every tile.
		//
		// What makes taking the full image honest is the dialog: it reads
		// `DataCapabilities.thumbnailImages`, calls the row "Card images" and prices it at full
		// size, so nothing on screen calls this a thumbnail.
		val vChain = artwork().thumbnailChain

		assertEquals(listOf("https://cdn/full.png"), vChain)
		assertEquals(
			"https://cdn/full.png",
			vChain.firstOrNull(),
			"the queue warms this, and a tile asks for it",
		)
	}

	@Test
	fun `one file published as every rendition is fetched once`() {
		val vChain = artwork(thumbnailUrl = "https://cdn/full.png", displayUrl = "https://cdn/full.png")
			.thumbnailChain

		assertEquals(listOf("https://cdn/full.png"), vChain, "duplicates are not three requests")
	}

	@Test
	fun `a blank rendition is not a URL to try`() {
		// A source that serialises an empty string rather than omitting the field. Fetching "" is a
		// request that cannot succeed, and it would sit at the head of the chain.
		val vChain = artwork(thumbnailUrl = "", displayUrl = " ").thumbnailChain

		assertEquals(listOf("https://cdn/full.png"), vChain)
	}

	@Test
	fun `a source that published nothing yields nothing to fetch`() {
		// `hasImage` is false here, and the chain must agree: an empty list, so the queue counts no
		// images rather than queueing a blank request per card.
		val vArtwork = artwork(imageUrl = "")

		assertEquals(emptyList(), vArtwork.thumbnailChain)
		assertEquals(false, vArtwork.hasImage)
	}
}
