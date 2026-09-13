package com.bitsycore.toploader.core

import com.bitsycore.toploader.core.game.RarityLadder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A rarity is shown as its source wrote it, except when its source wrote it in lower case.
 *
 * The asymmetry is the whole rule and it is easy to "tidy" away. Scryfall serialises `mythic`,
 * which is a JSON convention rather than how Magic writes the word, and beside Riftbound's
 * "Showcase" it read as a bug. Every other source writes the game's own capitalisation -- TCGdex
 * serves "ACE SPEC Rare" and "Illustration rare" -- and title-casing those would be this app
 * editing names it did not choose.
 */
class RarityDisplayTest {

	@Test
	fun `an all-lower-case rarity gets a capital`() {
		assertEquals("Common", RarityLadder.display("common"))
		assertEquals("Mythic", RarityLadder.display("mythic"))
	}

	@Test
	fun `a rarity that already has a capital is untouched`() {
		// Including the ones that look wrong and are not: this is how the source prints them.
		assertEquals("ACE SPEC Rare", RarityLadder.display("ACE SPEC Rare"))
		assertEquals("Illustration rare", RarityLadder.display("Illustration rare"))
		assertEquals("Rare Holo LV.X", RarityLadder.display("Rare Holo LV.X"))
		assertEquals("Showcase", RarityLadder.display("Showcase"))
	}

	@Test
	fun `an empty rarity stays empty`() {
		assertEquals("", RarityLadder.display(""))
	}
}
