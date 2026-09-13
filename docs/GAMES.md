# Games

A **game** is a vocabulary and a set of rules about how its cards are described. It is not a data
source — that is a [provider](PROVIDERS.md), and the distinction is the one most often got wrong.

> Riftbound calls its cost axis "Energy" whoever supplies the data → **game**.
> Whether TCGdex can serve Korean → **provider**.

One module per game under `games/`. Each declares a `GameProfile` (in `:core`'s vocabulary) and a
`GameArt` (in `:games:api`, which owns the Compose dependency so `:core` does not have one).

`:core` has no `Game` enum and no table of which games exist. A game is a `GameProfile` identified
by `GameId("riftbound")`, and the app discovers them through the Koin graph. If you find yourself
adding a `when (game)` to `:core`, the design has been misunderstood.

---

## `GameProfile`

`core/…/game/GameProfile.kt`

| Member | Default | What it is |
| --- | --- | --- |
| `id: GameId` | — | Stable. It goes into the card store and cache paths. |
| `displayName: String` | — | "Magic: The Gathering". |
| `shortName: String` | `displayName` | For a narrow bar: "Magic". |
| `vocabulary: GameVocabulary` | — | What this game calls its axes. See below. |
| `rarityLadder: List<String>` | empty | Commonest first. Orders the rarity chips and the rarity sort. Empty means the app sorts alphabetically and says nothing — which is a real answer, not a gap. |
| `domains: List<GameDomain>` | empty | The colour-like axis: key, English label, ARGB. |
| `rarityColours: List<GameRarityColour>` | empty | A rarity and the colour the game prints it in. Matched case-insensitively. |
| `regions: List<GameRegion>` | empty | Product lines, where a game has more than one. Only Pokémon does. |
| `cardmarketSlug: String?` | `null` | Cardmarket's path segment. `null` where there is no section or none has been confirmed. |
| `cardmarketCategoryId: Int?` | `null` | Refines a Cardmarket search. Optional: a missing id costs the refinement, a wrong one returns nothing. |
| `cardmarketSearchIncludesCode: Boolean` | `false` | Whether to put the set code in the search text. |

`domainFor`, `rarityColourFor` and `regionFor` are case-insensitive lookups over the lists above.

### `GameVocabulary`

Five slots, all optional except `cardType`. A `null` slot means the game has no such axis, and the
UI omits the row rather than printing an empty one.

| Slot | Default | Examples |
| --- | --- | --- |
| `domain` | `null` | "Domain", "Colour", "Faction", "Ink", "Attribute", "Type" |
| `cost` | `null` | "Energy", "Mana value", "Hand cost", "Level" |
| `cardType` | `"Type"` | "Type", "Category" |
| `primaryStat` | `null` | "Might", "Power", "ATK", "HP", "Strength" |
| `secondaryStat` | `null` | "Power", "Toughness", "DEF", "Life", "Willpower" |

### `GameArt`

`games/api/…/GameArt.kt` — the logo and the colours around it.

| Member | Default | What it is |
| --- | --- | --- |
| `game` | — | The profile this art belongs to. |
| `logo` | — | A `DrawableResource` bundled by the game's own module. |
| `accentArgb` | — | The tile colour, tinted rather than saturated. |
| `tintLogo` | `false` | True only for a single-colour wordmark, which is then painted in the theme's foreground. Never for colour artwork — tinting flattens it to a silhouette. |
| `logoTintDarkArgb` | `null` | What to paint a tinted mark on the dark theme, where the brand states a colour. |
| `backdropArgb` | `null` | The plate this artwork was drawn for, or `null` for a wash of the accent. |
| `backdropDarkArgb` | `backdropArgb` | The dark theme's plate, where it differs. |

Licensing of the ten bundled logos — three licence-checked, seven supplied by the project owner
with no verified licence — is documented in `GameArt`'s own KDoc, which owns that detail.

---

## What ships

Generated from the running Koin graph on 2026-09-13.

| Game | Source | Domain axis | Cost | Card type | Stats | Domains | Rarity ladder |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Riftbound | Riftcodex | Domain | Energy | Type | Might / Power | 7 | 5, **coloured** |
| Pokémon | TCGdex | Type | *(none)* | Category | HP | 11 | *(none)* |
| Magic: The Gathering | Scryfall | Colour | Mana value | Type | Power / Toughness | 6 | 6 |
| One Piece | OPTCG API | Colour | Cost | Type | Power / Life | 6 | 5 |
| Altered | Altered TCG Card Database | Faction | Hand cost | Type | Reserve cost | 6 | 3 |
| Yu-Gi-Oh! | YGOPRODeck | Attribute | Level | Type | ATK / DEF | 7 | 6 |
| Wuthering Waves | UCP | Attribute | Cost | Type | Damage / Speed | 6 | 6 |
| Disney Lorcana | TCGCSV | Ink | Ink cost | Type | Strength / Willpower | 6 | 6 |
| Cyberpunk TCG | TCGCSV | Colour | Cost | Type | Power | 4 | 4 |
| WoW TCG | TCGCSV | *(none)* | Cost | Type | Attack / Health | 0 | 5 |

Two entries worth understanding rather than copying:

- **Pokémon declares no cost and no rarity ladder.** Cost is per attack, so there is no one number.
  The ladder is empty because TCGdex's rarity strings vary by era — nineteen distinct values across
  thirteen sets from Base Set to Surging Sparks, measured 2026-09-13 — and no ordering of them is a
  fact about the game. Alphabetical and silent beats an invented ladder that looks deliberate.
- **Riftbound is the only game with rarity colours**, because it is the only one whose publisher
  prints them.

### Cardmarket

| Game | Slug | Category id |
| --- | --- | --- |
| Riftbound | `Riftbound` | 1655 |
| Magic | `Magic` | *(none — its real URLs send none)* |
| Pokémon | `Pokemon` | 51 |
| Yu-Gi-Oh! | `YuGiOh` | *(none — its real URLs send `0`)* |
| One Piece | `OnePiece` | 1621 |
| Altered | *(none — checked, Cardmarket has no Altered section)* | — |
| Lorcana, Cyberpunk, WoW TCG, Wuthering Waves | *(none yet)* | — |

Cardmarket answers 403 to every scripted request and blocks an automated browser, so a slug is only
ever confirmed by a human with a browser. **Do not guess one.** Altered's absence is checked, not
unknown; Wuthering Waves' is a gap. `AppModuleTest` pins the whole table.

---

## Adding a game

1. A module under `games/<name>` with a `GameProfile` and a `GameArt`, and the logo in its own
   `composeResources`.
2. Declare the vocabulary from the game's own rulebook, not from what its source happens to call
   things. A source calling a field `attribute` does not make "Attribute" the game's word.
3. Add both to the Koin graph, and a routing entry pointing the game at its provider.
4. `AppModuleTest` will fail until the game has a mark and a route — that is the check that
   replaced compile-time exhaustiveness when the `Game` enum was removed.
