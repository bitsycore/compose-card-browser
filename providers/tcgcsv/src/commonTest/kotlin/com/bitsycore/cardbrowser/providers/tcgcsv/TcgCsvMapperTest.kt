package com.bitsycore.cardbrowser.providers.tcgcsv

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.RarityLadder
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.games.cyberpunk.CyberpunkGame
import com.bitsycore.cardbrowser.games.lorcana.LorcanaGame
import com.bitsycore.cardbrowser.games.wowtcg.WowTcgGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The TCGCSV mapping, against DTOs copied from real responses. Deterministic and offline.
 *
 * The field values here are verbatim from the live catalogue on 2026-09-09 -- `x1` really is how
 * Cyberpunk writes RAM, and `C` really is the whole of a WoW rarity.
 */
class TcgCsvMapperTest {

	private val mProvider = ProviderId("tcgcsv-test")

	private fun extended(vararg pairs: Pair<String, String>) =
		pairs.map { TcgCsvExtendedDto(name = it.first, value = it.second) }

	/** A Lorcana card, as the catalogue really sends one. */
	private fun lorcanaCard(
		productId: Long = 690200,
		name: String = "Mike Wazowski - Well-Rounded Entertainer",
		ink: String = "Amber",
		number: String = "21/207",
	) = TcgCsvProductDto(
		productId = productId,
		name = name,
		imageUrl = "https://tcgplayer-cdn.tcgplayer.com/product/${productId}_200w.jpg",
		groupId = 24666,
		extendedData = extended(
			"Rarity" to "Common",
			"Number" to number,
			"Description" to "When you play this character, you may pay 2 Ink to give chosen character -2 strength.",
			"Property" to "Monsters, Inc.",
			"Cost Ink" to "1",
			"InkType" to ink,
			"CardType" to "Character",
			"Classification" to "Storyborn;Hero;Monster",
			"Strength" to "2",
			"Willpower" to "1",
			"Lore Value" to "1",
		),
	)

	/** A booster box: a product with a UPC and no game fields at all. */
	private fun sealedProduct() = TcgCsvProductDto(
		productId = 55968,
		name = "Epic Collection Set",
		imageUrl = "https://tcgplayer-cdn.tcgplayer.com/product/55968_200w.jpg",
		groupId = 1100,
		extendedData = extended("UPC" to "815542012197"),
	)

	private fun printing(
		dto: TcgCsvProductDto,
		game: GameProfile = LorcanaGame,
		mapping: TcgCsvMapping = TcgCsvMapping.LORCANA,
	) = TcgCsvMapper.toPrinting(dto, mProvider, game, mapping, set = null)

	// ==================
	// MARK: Sets
	// ==================

	@Test
	fun `a group becomes a set -- and its card count is not guessed at`() {
		val vSet = TcgCsvMapper.toSet(
			TcgCsvGroupDto(
				groupId = 24666,
				name = "Attack of the Vine!",
				abbreviation = "13",
				publishedOn = "2026-07-17T00:00:00",
			),
			mProvider,
			LorcanaGame,
		)

		assertEquals("24666", vSet.id.local)
		assertEquals("13", vSet.code)
		assertEquals(LorcanaGame.id, vSet.game)
		assertEquals("2026-07-17", vSet.releaseDate.toString())
		// The group record carries no count and the only way to learn one is to fetch the products,
		// which is the request the set list exists to avoid. A wrong count is what makes the app
		// label a partial page as a whole set.
		assertNull(vSet.cardCount)
	}

	@Test
	fun `a group with no abbreviation falls back to its id rather than inventing a code`() {
		// Every Cyberpunk group has an empty abbreviation, and several WoW ones do too. The group id
		// is TCGplayer's own name for the set, which is at least true.
		val vSet = TcgCsvMapper.toSet(
			TcgCsvGroupDto(groupId = 24855, name = "Welcome to Night City - Retail", abbreviation = ""),
			mProvider,
			CyberpunkGame,
		)

		assertEquals("24855", vSet.code)
		assertNull(vSet.releaseDate, "A missing date must not become today's")
	}

	// ==================
	// MARK: Cards and sealed product
	// ==================

	@Test
	fun `sealed product is not a card`() {
		// Booster boxes share the catalogue with singles. A grid full of pictures of cardboard boxes
		// is the failure this prevents.
		assertNull(printing(sealedProduct(), WowTcgGame, TcgCsvMapping.WOW_TCG))
		assertNull(printing(sealedProduct()))
	}

	@Test
	fun `ids are distinct across a page -- because a repeated key crashes the grid`() {
		// Cyberpunk really does print the same collector number twice: its Beta and Retail groups
		// are the same cards listed again, 408 cards over 314 distinct numbers. Keying on the number
		// would throw in `LazyVerticalGrid` rather than degrading, so the key is the product id.
		val vBeta = TcgCsvProductDto(
			productId = 715006,
			name = "6th Street Recruits",
			imageUrl = "https://tcgplayer-cdn.tcgplayer.com/product/715006_200w.jpg",
			groupId = 24845,
			extendedData = extended("Number" to "006", "CardType" to "Unit", "Color" to "Red"),
		)
		val vRetail = vBeta.copy(productId = 715999, groupId = 24855)

		val vCards = listOf(vBeta, vRetail).mapNotNull {
			printing(it, CyberpunkGame, TcgCsvMapping.CYBERPUNK)
		}

		assertEquals(2, vCards.size)
		assertEquals(2, vCards.map { it.id }.toSet().size, "Got ${vCards.map { it.id }}")
		assertEquals("006", vCards[0].collectorNumber, "The shared number is still reported as-is")
	}

	@Test
	fun `a card id carries its group -- because there is no per-product endpoint`() {
		val vCard = assertNotNull(printing(lorcanaCard()))

		assertEquals("24666-690200", vCard.id.local)
		assertEquals(24666L, TcgCsvMapper.groupOf(vCard.id))
	}

	// ==================
	// MARK: Fields
	// ==================

	@Test
	fun `a dual ink card is both of its inks rather than a seventh one`() {
		// 15 of Lorcana's 21 distinct InkType values are pairs. Filtering by Amethyst has to match
		// an Amethyst/Sapphire card, which it only does if the pair is split.
		val vCard = assertNotNull(printing(lorcanaCard(ink = "Amethyst;Sapphire")))

		assertEquals(listOf("amethyst", "sapphire"), vCard.classification.domains)
	}

	@Test
	fun `an ink the game has never heard of is kept rather than dropped`() {
		// A source inventing a value must not vanish from the filter.
		val vCard = assertNotNull(printing(lorcanaCard(ink = "Obsidian")))

		assertEquals(listOf("Obsidian"), vCard.classification.domains)
	}

	@Test
	fun `WoW rarity letters are expanded into the words the ladder uses`() {
		// The catalogue stores `C`/`U`/`R`/`E`/`L` and nothing else. Left as letters they would all
		// rank unknown and sort alphabetically -- E before R before U -- which is not an order.
		val vCard = assertNotNull(
			printing(
				TcgCsvProductDto(
					productId = 43276,
					name = "Anaka the Light's Bulwark",
					imageUrl = "https://tcgplayer-cdn.tcgplayer.com/product/43276_200w.jpg",
					groupId = 1100,
					extendedData = extended("Rarity" to "E"),
				),
				WowTcgGame,
				TcgCsvMapping.WOW_TCG,
			),
		)

		assertEquals("Epic", vCard.classification.rarity)
		assertTrue(
			RarityLadder.rankOf(WowTcgGame.rarityLadder, vCard.classification.rarity) <
				RarityLadder.rankOf(WowTcgGame.rarityLadder, "Legendary"),
		)
	}

	@Test
	fun `a WoW card states no collector number rather than borrowing one`() {
		// There is genuinely none in the category. Using the product id would put a TCGplayer
		// database key on screen in the place a printed number goes.
		val vCard = assertNotNull(
			printing(
				TcgCsvProductDto(
					productId = 43276,
					name = "Gobbler",
					imageUrl = "https://tcgplayer-cdn.tcgplayer.com/product/43276_200w.jpg",
					groupId = 1100,
					extendedData = extended("Rarity" to "C"),
				),
				WowTcgGame,
				TcgCsvMapping.WOW_TCG,
			),
		)

		assertEquals("", vCard.collectorNumber)
		assertNull(vCard.attributes.cost)
		assertNull(vCard.text.rules)
	}

	@Test
	fun `no record claims a language -- silence is not a claim of English`() {
		val vCard = assertNotNull(printing(lorcanaCard()))

		assertNull(vCard.text.language)
		assertFalse(
			vCard.text.isProviderStated,
			"The catalogue states no language, so the reading is ours rather than its claim",
		)
		assertTrue(vCard.languages.isUnstated)
		assertTrue(vCard.finishes.isUnstated, "There is no finish field to read")
	}

	@Test
	fun `Cyberpunk's RAM is not forced into a number slot`() {
		// It is written `x1` -- a multiplier, not a quantity. Stripping the `x` would report a value
		// the game does not print.
		val vCard = assertNotNull(
			printing(
				TcgCsvProductDto(
					productId = 715006,
					name = "6th Street Recruits",
					imageUrl = "https://tcgplayer-cdn.tcgplayer.com/product/715006_200w.jpg",
					groupId = 24855,
					extendedData = extended(
						"Number" to "006",
						"CardType" to "Unit",
						"Color" to "Red",
						"Cost" to "4",
						"Power" to "6",
						"RAM" to "x1",
						"Eddies" to "FALSE",
						"Tags" to "6th Street;Ganger",
					),
				),
				CyberpunkGame,
				TcgCsvMapping.CYBERPUNK,
			),
		)

		assertEquals(4, vCard.attributes.cost)
		assertEquals(6, vCard.attributes.primary)
		assertNull(vCard.attributes.secondary, "RAM has no slot and must not be squeezed into one")
		assertEquals(listOf("6th Street", "Ganger"), vCard.tags)
	}

	// ==================
	// MARK: Artwork
	// ==================

	@Test
	fun `the three renditions are derived from the one URL the record carries`() {
		val vCard = assertNotNull(printing(lorcanaCard()))
		val vBase = "https://tcgplayer-cdn.tcgplayer.com/product/690200"

		assertEquals("${vBase}_in_1000x1000.jpg", vCard.artwork.imageUrl)
		assertEquals("${vBase}_200w.jpg", vCard.artwork.thumbnailUrl)
		assertEquals("${vBase}_400w.jpg", vCard.artwork.displayUrl)
	}

	@Test
	fun `a WoW card offers one image because the CDN really only has one`() {
		// Measured: all three renditions return the same 200x280 pixels, `_200w` merely being a
		// worse re-encode at 27 KB against 16. Naming three variants for one file would make the
		// download screen sell a "full art" purchase that buys nothing.
		val vCard = assertNotNull(
			printing(
				TcgCsvProductDto(
					productId = 43276,
					name = "Gobbler",
					imageUrl = "https://tcgplayer-cdn.tcgplayer.com/product/43276_200w.jpg",
					groupId = 1100,
					extendedData = extended("Rarity" to "C"),
				),
				WowTcgGame,
				TcgCsvMapping.WOW_TCG,
			),
		)

		assertNull(vCard.artwork.thumbnailUrl)
		assertNull(vCard.artwork.displayUrl)
		assertEquals(
			"https://tcgplayer-cdn.tcgplayer.com/product/43276_in_1000x1000.jpg",
			vCard.artwork.imageUrl,
		)
	}
}
