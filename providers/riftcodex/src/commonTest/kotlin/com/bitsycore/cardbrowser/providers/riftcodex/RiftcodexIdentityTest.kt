package com.bitsycore.cardbrowser.providers.riftcodex

import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one inferred card identity in the project.
 *
 * `RiftcodexMapper.identityOf` matches printings on their name, which the rest of the codebase
 * refuses to do -- see its doc for why it is allowed here. These are the cases that were checked
 * against the live catalogue before it was allowed, kept so a later change cannot quietly widen it.
 */
class RiftcodexIdentityTest {

	@Test
	fun `a variant's parenthetical is stripped so it joins its base printing`() {
		assertEquals(
			"Poppy - Paragon",
			RiftcodexMapper.baseNameOf("Poppy - Paragon (Alternate Art)", ArtworkTreatment.ALTERNATE_ART),
		)
		assertEquals(
			"Vi - Piltover Enforcer",
			RiftcodexMapper.baseNameOf("Vi - Piltover Enforcer (Signature)", ArtworkTreatment.SIGNATURE),
		)
		assertEquals(
			"Vi - Piltover Enforcer",
			RiftcodexMapper.baseNameOf("Vi - Piltover Enforcer (Overnumbered)", ArtworkTreatment.OVERNUMBERED),
		)
	}

	@Test
	fun `a plain printing's name is never touched`() {
		// The strip is licensed by the provider's own treatment flags, not by how a name reads. A
		// standard card that happens to end in a parenthetical keeps it, because nothing said it
		// was a variant.
		assertEquals(
			"Some Card (Not A Variant)",
			RiftcodexMapper.baseNameOf("Some Card (Not A Variant)", ArtworkTreatment.STANDARD),
		)
		assertEquals(
			"Voracious Gromp",
			RiftcodexMapper.baseNameOf("Voracious Gromp", ArtworkTreatment.STANDARD),
		)
	}

	@Test
	fun `a flagged variant with no parenthetical keeps its whole name`() {
		// Real case: several OGN variants reuse the base name outright rather than annotating it.
		// Those already group correctly, and must not be truncated on the strength of the flag.
		assertEquals(
			"Zed, From the Shadows",
			RiftcodexMapper.baseNameOf("Zed, From the Shadows", ArtworkTreatment.ALTERNATE_ART),
		)
	}

	@Test
	fun `an unbalanced parenthesis is left alone rather than mangled`() {
		assertEquals("Odd Name)", RiftcodexMapper.baseNameOf("Odd Name)", ArtworkTreatment.SIGNATURE))
		assertEquals("(Whole Name)", RiftcodexMapper.baseNameOf("(Whole Name)", ArtworkTreatment.SIGNATURE))
	}
}
