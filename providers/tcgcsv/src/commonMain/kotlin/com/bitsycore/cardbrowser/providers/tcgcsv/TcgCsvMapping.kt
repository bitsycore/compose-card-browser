package com.bitsycore.cardbrowser.providers.tcgcsv

/**
 * Which of a game's `extendedData` fields mean what.
 *
 * TCGCSV serves one shape for every game and then puts the game-specific part in an untyped
 * name/value list, so this is the translation table -- the only thing that differs between the
 * three adapters besides a category number.
 *
 * It is deliberately a *declaration* rather than three `when (game)` branches in the mapper. Each
 * game's entry was written by reading its real records (see each provider's class doc for the
 * counts), and a field a game does not carry is simply `null`, which the mapper reads as "this
 * source says nothing" rather than as a default.
 *
 * @property cardFields the fields whose presence marks a product as a card at all. TCGplayer lists
 *   booster boxes and starter decks in the same catalogue, and they carry a UPC and nothing else
 * @property rarityNames expands the source's own spelling where it abbreviates -- the WoW catalogue
 *   stores `C`/`U`/`R`/`E`/`L`, which are not the words the game or the rarity ladder uses
 * @property domainSeparator the character joining a multi-valued colour, e.g. Lorcana's
 *   `Amethyst;Sapphire`. A dual-ink card is both of its inks, so the mapper splits on this and each
 *   half is matched against the game's declared domains
 * @property hasLargerArt false when every rendition of the CDN's image is the same pixels, which is
 *   true of the WoW catalogue and means there is no full-size art to offer
 */
internal data class TcgCsvMapping(
	val cardFields: Set<String>,
	val number: String? = null,
	val rarity: String? = null,
	val cost: String? = null,
	val primary: String? = null,
	val secondary: String? = null,
	val cardType: String? = null,
	val domain: String? = null,
	val rules: String? = null,
	val flavour: String? = null,
	val tags: List<String> = emptyList(),
	val rarityNames: Map<String, String> = emptyMap(),
	val domainSeparator: Char = ';',
	val hasLargerArt: Boolean = true,
) {

	/** True when [fields] carry at least one of the marks that distinguish a card from a box. */
	fun isCard(fields: Map<String, String>): Boolean = cardFields.any { it in fields }

	companion object {

		/**
		 * Disney Lorcana, measured over 3654 products in all 20 groups on 2026-09-09.
		 *
		 * 3309 carry a `Number` and are cards; the rest are sealed product. `Lore Value` and
		 * `Move Cost` are real numbers this leaves unmapped -- the shared model holds three and
		 * Lorcana prints four, which `LorcanaGame`'s vocabulary explains.
		 */
		val LORCANA: TcgCsvMapping = TcgCsvMapping(
			cardFields = setOf("Number", "CardType"),
			number = "Number",
			rarity = "Rarity",
			cost = "Cost Ink",
			primary = "Strength",
			secondary = "Willpower",
			cardType = "CardType",
			domain = "InkType",
			rules = "Description",
			flavour = "Flavor Text",
			tags = listOf("Classification"),
		)

		/**
		 * Cyberpunk TCG, measured over 422 products in all nine groups on 2026-09-09.
		 *
		 * 408 are cards. `RAM` and `Eddies` are left unmapped: the first is a multiplier written
		 * `x1` rather than a number, and the second is a `TRUE`/`FALSE` flag.
		 */
		val CYBERPUNK: TcgCsvMapping = TcgCsvMapping(
			cardFields = setOf("Number", "CardType"),
			number = "Number",
			rarity = "Rarity",
			cost = "Cost",
			primary = "Power",
			cardType = "CardType",
			domain = "Color",
			rules = "Description",
			tags = listOf("Tags"),
		)

		/**
		 * World of Warcraft TCG, measured over 1549 products in 12 groups on 2026-09-09.
		 *
		 * Thin on purpose, because the catalogue is: the only game field any WoW product carries is
		 * `Rarity`, as a single letter. There is no collector number, no card text, no cost and no
		 * type anywhere in it. The game was discontinued in 2013 and no publisher database survives
		 * it, so this is the whole of what a marketplace listing can honestly supply.
		 */
		val WOW_TCG: TcgCsvMapping = TcgCsvMapping(
			cardFields = setOf("Rarity"),
			rarity = "Rarity",
			rarityNames = mapOf(
				"C" to "Common",
				"U" to "Uncommon",
				"R" to "Rare",
				"E" to "Epic",
				"L" to "Legendary",
			),
			// Every rendition of a WoW image is the same 200x280 pixels -- `_200w` is merely a
			// worse re-encode of it, at 27 KB against 16. So there is one size, and the adapter
			// says so rather than pointing a "thumbnail" and a "full art" at the same file.
			hasLargerArt = false,
		)
	}
}
