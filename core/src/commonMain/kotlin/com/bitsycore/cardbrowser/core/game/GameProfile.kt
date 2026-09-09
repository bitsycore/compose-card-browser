package com.bitsycore.cardbrowser.core.game

import com.bitsycore.cardbrowser.core.model.GameId

/**
 * Everything this app knows about one trading card game, in one declaration.
 *
 * ## Why this exists
 *
 * The same seven games used to be enumerated in four separate tables in three layers: the words a
 * game uses for its axes, the order its rarities go in, its Cardmarket path segment, and its logo
 * and accent colour. Adding a game meant finding all four, and one of them failed silently when you
 * did not. Worse, all four had to sit near the shared code that consumed them, which put a list of
 * game names inside `:core` -- the module that is supposed to know nothing about any particular
 * game.
 *
 * A profile turns that inside out. Core keeps the *mechanisms* -- how to rank a rarity, how to build
 * a Cardmarket URL, how to label a stat -- and knows nothing about which games exist. Each game is
 * a module (`:games:riftbound`, `:games:magic`, ...) declaring one of these, and a provider names
 * the profile it serves in its own type: `class RiftcodexProvider : CardProvider<RiftboundGame>`.
 *
 * Adding a game is now a module and a routing entry. Nothing in core changes, and nothing in core
 * can be forgotten, because there is no longer a table in core to forget to extend.
 *
 * ## What belongs here and what does not
 *
 * A fact about the *game*, shared by every source that serves it. Riftbound calls its cost axis
 * "Energy" whether the data came from Riftcodex or somewhere else, so that is a profile fact. What
 * a particular source can supply -- languages, whether it states finishes, how it pages -- is a
 * fact about the source and lives on `ProviderCapabilities`.
 *
 * Presentation is deliberately absent: a logo and an accent colour are not game rules, and putting
 * a `DrawableResource` here would drag the Compose runtime into `:core`. Those live alongside this,
 * in the same game module, as a `GameArt` -- see `:games:api`.
 *
 * @property id the stable identity written into cache keys and every record of this game
 * @property displayName the game's full name
 * @property shortName for the game switcher, where "Magic: The Gathering" does not fit
 */
interface GameProfile {

	val id: GameId

	val displayName: String

	val shortName: String get() = displayName

	/** What this game calls the things the shared card model holds generically. */
	val vocabulary: GameVocabulary

	/**
	 * This game's rarities, lowest to highest, or empty when no honest order is known.
	 *
	 * Rarity arrives from providers as a bare string -- Riftcodex says `"Epic"` and nothing about
	 * where Epic sits relative to Rare. Sorting those alphabetically puts Common between Uncommon
	 * and Epic, which is not an order anybody recognises and makes the filter chips read as a
	 * jumble. So the ladder is stated here, as knowledge about the game: a second provider for the
	 * same game inherits it for free.
	 *
	 * Empty is a real answer, not a gap. See `RarityLadder` for what happens to a rarity the ladder
	 * does not name, and `PokemonGame` for a game that deliberately declares none.
	 */
	val rarityLadder: List<String> get() = emptyList()

	/**
	 * Cardmarket's path segment for this game, or `null` when it is not known.
	 *
	 * `null` suppresses the marketplace link entirely rather than shipping a button that lands on a
	 * 404. Cardmarket answers 403 to every scripted request, so these can only be confirmed against
	 * a real page in a browser -- and an unconfirmed slug is left null rather than guessed.
	 */
	val cardmarketSlug: String? get() = null
}

/**
 * What one game calls the things the shared card model holds generically.
 *
 * The card model has one `domains` list and one `cost` number, not seven of each, because the
 * filtering machinery, the cache format and the query type would otherwise grow a variant per game
 * for no gain. What differs between games is only the *word* -- and a screen that says "Domain"
 * over Magic colours, or "Energy" over a Yu-Gi-Oh level, is wrong in the way users notice first.
 *
 * A game whose provider does not supply an axis simply never populates it, and the filter sheet
 * hides an empty facet, so `null` here means "this game has no such concept" rather than "we did
 * not get round to it".
 *
 * @property domain the colour-like axis a card belongs to -- Riftbound domains, Magic colours,
 *   Pokémon types, Altered factions, Yu-Gi-Oh attributes. `null` when the game has no such axis
 * @property cost the single number you pay to play a card -- Riftbound energy, Magic mana value,
 *   One Piece cost, Altered hand cost, Yu-Gi-Oh level. `null` when the game has no single such
 *   number, as with Pokémon, whose cost is per attack
 * @property cardType what this game calls a card's category
 * @property primaryStat the first of the two stat slots -- Riftbound might, Magic power, Yu-Gi-Oh
 *   ATK, Pokémon HP. `null` when the game states no such number
 * @property secondaryStat the second stat slot -- Riftbound power, Magic toughness, Yu-Gi-Oh DEF
 */
data class GameVocabulary(
	val domain: String? = null,
	val cost: String? = null,
	val cardType: String = "Type",
	val primaryStat: String? = null,
	val secondaryStat: String? = null,
)
