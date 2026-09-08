package com.bitsycore.cardbrowser.core.model

/**
 * What each game calls the things the app models generically.
 *
 * The filter and sort fields are shared -- there is one `DOMAIN` field and one `ENERGY_COST` field,
 * not seven -- because the filtering machinery, the cache format and the query type would otherwise
 * have to grow a variant per game for no gain. What differs between games is only the *word*, and a
 * screen that says "Domain" while showing Magic colours is wrong in the way users notice first.
 *
 * So the shared field keeps its shared meaning and this supplies the label:
 *
 * - `DOMAIN` is "the colour-like axis a card belongs to" -- Riftbound domains, Magic colours,
 *   Pokémon types, Altered factions, Yu-Gi-Oh attributes.
 * - `ENERGY_COST` is "the single number you pay to play it" -- Riftbound energy, Magic mana value,
 *   One Piece cost, Altered hand cost, Yu-Gi-Oh level.
 *
 * A game whose provider does not supply an axis simply never populates it, and the filter sheet
 * hides an empty facet, so a `null` here means "this game has no such concept" rather than "we did
 * not get round to it".
 *
 * @property domain what this game calls its colour-like axis, or `null` when it has none
 * @property energy what this game calls its single cost number, or `null` when it has none
 * @property cardType what this game calls a card's category
 */
data class GameVocabulary(
	val domain: String?,
	val energy: String?,
	val cardType: String = "Type",
) {

	companion object {

		/** The words [game] uses. */
		fun of(game: Game): GameVocabulary = when (game) {
			Game.RIFTBOUND -> GameVocabulary(domain = "Domain", energy = "Energy")
			// Pokémon's cost is per-attack rather than a single number on the card, so there is no
			// one value to filter on and the field stays empty rather than being faked from the
			// first attack's cost.
			Game.POKEMON -> GameVocabulary(domain = "Type", energy = null, cardType = "Category")
			Game.MAGIC -> GameVocabulary(domain = "Colour", energy = "Mana value")
			Game.ONE_PIECE -> GameVocabulary(domain = "Colour", energy = "Cost")
			Game.ALTERED -> GameVocabulary(domain = "Faction", energy = "Hand cost")
			Game.YU_GI_OH -> GameVocabulary(domain = "Attribute", energy = "Level")
			Game.WUTHERING_WAVES -> GameVocabulary(domain = "Attribute", energy = "Cost")
		}
	}
}
