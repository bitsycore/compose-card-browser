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
	 * This game's colour-like axis, in the game's own order, with the colour each one is drawn in.
	 *
	 * Riftbound domains, Magic colours, Pokémon types, Altered factions, Yu-Gi-Oh attributes. The
	 * *rule* is here because it is a fact about the game -- Magic's red is red whoever supplies the
	 * data, and WUBRG is an order every player knows and no alphabetical sort produces. What each
	 * provider happens to *call* red is that provider's business: an adapter maps its own value to
	 * one of these keys, which is what makes a filter work when the source is answering in French.
	 *
	 * Empty for a game with no such axis, and a value a game does not declare still shows -- as its
	 * own raw text, uncoloured. A source inventing a new domain must not vanish from the filter.
	 */
	val domains: List<GameDomain> get() = emptyList()

	/** The declared domain for [key], or `null` when this game has never heard of it. */
	fun domainFor(key: String): GameDomain? =
		domains.firstOrNull { it.key.equals(key, ignoreCase = true) }

	/**
	 * The colour each of this game's rarities is drawn in, keyed as [rarityLadder] names them.
	 *
	 * A game fact like the ladder itself: Riftbound's Epic is orange on the card, whoever serves
	 * the data. Empty is the ordinary answer -- most games have no established colour for a rarity
	 * -- and an undeclared rarity draws in the plain chip colours rather than being hidden or
	 * guessed at.
	 */
	val rarityColours: List<GameRarityColour> get() = emptyList()

	/** The declared colour for [key], or `null` when this game states none. */
	fun rarityColourFor(key: String): Long? =
		rarityColours.firstOrNull { it.rarity.equals(key, ignoreCase = true) }?.colourArgb

	/**
	 * This game's separate product lines, or empty for a game that ships one worldwide.
	 *
	 * Not translations -- *different products*. Pokémon prints a Japanese line and an international
	 * line that share neither set list nor numbering: `sv1a` (Triplet Beat) has no international
	 * counterpart, and there is no Japanese edition of `swsh1` to fetch. Treating them as one
	 * catalogue meant only one line was ever visible, chosen by whichever language the user happened
	 * to prefer, so 218 of Pokémon's 486 sets simply did not exist as far as the app was concerned.
	 *
	 * A region is *not* a language. Every Korean Pokémon set id is a Japanese set id -- Korea prints
	 * the Japanese line -- so Korean is a language of the Japan region rather than a region of its
	 * own. What a set is published in is [com.bitsycore.cardbrowser.core.model.CardSet.languages].
	 *
	 * Which line a set belongs to is the provider's to state, because it is a fact about how that
	 * source keys its data; what the lines *are* is the game's, which is why they are declared here.
	 */
	val regions: List<GameRegion> get() = emptyList()

	/** The declared region for [key], or `null` when this game has never heard of it. */
	fun regionFor(key: String?): GameRegion? =
		key?.let { vKey -> regions.firstOrNull { it.key.equals(vKey, ignoreCase = true) } }

	/**
	 * Cardmarket's path segment for this game, or `null` when it is not known.
	 *
	 * `null` suppresses the marketplace link entirely rather than shipping a button that lands on a
	 * 404. Cardmarket answers 403 to every scripted request, so these can only be confirmed against
	 * a real page in a browser -- and an unconfirmed slug is left null rather than guessed.
	 */
	val cardmarketSlug: String? get() = null

	/**
	 * Cardmarket's numeric id for this game's "Cards" category, or `null` when it is not known.
	 *
	 * Per game, not global: Riftbound is 1655, Pokémon 51, One Piece 1621, each read off a real
	 * search URL -- so the single hardcoded constant this replaced was silently wrong for every game
	 * but the one it came from, sending a Pokémon search to Riftbound's category.
	 *
	 * It narrows an already-Singles listing to cards rather than sealed product, which makes it a
	 * refinement and not a requirement: a real Magic search URL carries no category at all, and a
	 * real Yu-Gi-Oh one sends `0`, meaning any. So `null` here still produces a working search --
	 * it is only a *wrong* id that would return nothing.
	 */
	val cardmarketCategoryId: Int? get() = null

	/**
	 * Whether to put the card's printed code in the Cardmarket search box alongside its name.
	 *
	 * Off by default, because for most games the name alone is the better query and an extra term
	 * the site does not index turns a good search into an empty one.
	 *
	 * One Piece is the case that needs it: the game reprints the same character across sets, so
	 * "Yamato" matches a page of them and "Yamato OP16-098" matches the one card. Cardmarket indexes
	 * the code for that game, confirmed against a real search URL.
	 */
	val cardmarketSearchIncludesCode: Boolean get() = false
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

/**
 * One of a game's product lines. See [GameProfile.regions].
 *
 * @property key what an adapter tags a set with, and what goes into a cache file: `intl`, `jp`
 * @property label the filter chip: "International", "Japan"
 * @property badge the two- or three-letter form for the badge on a set row, where the full label
 *   does not fit beside a set name: `INTL`, `JP`
 */
data class GameRegion(
	val key: String,
	val label: String,
	val badge: String,
)

/**
 * One value of a game's colour-like axis.
 *
 * @property key what an adapter maps its own value onto, and what a `CardPrinting` carries. Stable,
 *   because it goes into cache files: Magic's `W`, One Piece's `red`, Pokémon's `fire`
 * @property label what to show. The app's UI is English, so this is English -- a provider's own
 *   localised name is not used, which is the point: a French Pokémon card's type still reads
 *   "Fire" and still matches the Fire filter
 * @property colourArgb the chip colour, as `0xAARRGGBB`. A `Long` rather than a Compose `Color`
 *   because `:core` has no Compose in it; the UI converts it once
 */
data class GameDomain(
	val key: String,
	val label: String,
	val colourArgb: Long,
)

/**
 * One rarity and the colour that game prints it in.
 *
 * Separate from [GameDomain] because a rarity has no key of its own: it is the provider's own
 * string, matched case-insensitively against the ladder.
 */
data class GameRarityColour(
	val rarity: String,
	val colourArgb: Long,
)
