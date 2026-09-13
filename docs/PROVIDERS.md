# Providers

A **provider** is one data source, adapted. It answers four questions — what sets exist, what cards
are in a set, what one card is, and optionally what matches a search across sets — and declares in
`ProviderCapabilities` what it can and cannot do, so the app never offers a control the source
cannot honour.

One module per source under `providers/`. A provider knows its source's URLs, DTOs and quirks and
nothing about the UI. `:core` defines the contract and knows no provider's name; a test enforces
that (see [CLAUDE.md](../CLAUDE.md)).

Companion: [GAMES.md](GAMES.md) — what a *game* declares, which is a different set of facts.

---

## The contract

`core/…/provider/CardProvider.kt`

| Member | Required | What it is |
| --- | --- | --- |
| `id: ProviderId` | yes | Stable. It prefixes every id this source issues and goes into cache file names, so changing it orphans a user's downloads. |
| `displayName: String` | yes | Shown in the game list under the game's name. |
| `game: GameProfile` | yes | The one game this adapter serves. An adapter serves exactly one. |
| `capabilities: ProviderCapabilities` | yes | Everything below. |
| `listSets(language)` | yes | The catalogue. |
| `listCards(request)` | yes | One page of a set, or the whole set where the source returns it in one request. |
| `cardDetail(id, language)` | yes | One card, fully populated. |
| `resolveLanguage(requested)` | default | Which language this source will *really* answer in. Walks `CardLanguage.PREFERENCE_ORDER` when nothing is asked for, so a source carrying everything answers in the user's first preference rather than English. |
| `confirmLanguages(setId, candidates)` | default (returns the candidates) | Which of a set's claimed languages actually have cards. Override where a source over-claims. |
| `searchAllSets(request)` | default (throws) | Server-side search across every set. Only implement it where `crossSetSearch` is true. |

`BulkCatalogue` is a separate optional interface: `bulkVariants()` lists the dumps a source
publishes and `streamAll(variantId)` reads one. Implement it only where the source really publishes
a file; declaring `cardInfoFromBulkOnly` without it leaves no way to get records at all, and
`AppModuleTest` asserts the pair.

---

## `ProviderCapabilities`

### `filtering: FilterSupport`

Two sets of `CardFilterField`, and the split is the point.

- `remote` — the source applies it. One request.
- `localOnly` — the app applies it to a complete set. Every page must be fetched before a single
  correct result can be shown, which is why the repository has to know and why the UI can say
  "partial".
- In neither — not offered at all, and the filter sheet draws no section for it.

Fields: `TEXT`, `DOMAIN`, `CARD_TYPE`, `RARITY`, `COST`, `FINISH`, `LANGUAGE`, `ARTWORK_TREATMENT`.

Every shipped adapter declares `remote = emptySet()`. That is deliberate, not an oversight: each of
these sources returns a set small enough to filter in memory, and declaring a field remote would
send a second request to re-fetch a subset of what is already there.

### `sorting: Set<CardSortField>`

`COLLECTOR_NUMBER`, `NAME`, `RARITY`, `COST`. Applied locally, over whatever is on screen.

### `data: DataCapabilities`

| Property | Default | Meaning |
| --- | --- | --- |
| `languages` | — | Printing languages the source can describe. Empty means it never states one. A **claim** about the source, never about a set. |
| `localizedText` | — | Text is translated, not merely a printing in that language. |
| `localizedImages` | — | Per-language images. |
| `cardIdentity` | — | The source says which printings are the same card. |
| `artworkVariants` | — | The source distinguishes alternate art. |
| `finishes` | — | The source states finishes. False means *unknown*, not absent. |
| `cardmarketProductMapping` | — | Individual printings map to Cardmarket product ids. |
| `crossSetSearch` | `false` | The **server** can search every set. False does not mean the app cannot — it falls back to the sets already on disk and says so. |
| `bundledCardData` | `false` | Records ship inside the app. Nothing to download, keep or clear. |
| `thumbnailImages` | `false` | A small rendition distinct from the full image. False makes a bulk image download fall back to full art, which is roughly four times the bytes — so the dialog says the honest size. |
| `cardInfoFromBulkOnly` | `false` | Records come from the dump, not the API. Takes card info off the *single-set* download dialog and points at the whole-game import. Browsing a set still reads the API. |

`maxPageSize` and `attribution` complete the record. Attribution text is shown in Settings and on
the game list.

---

## What ships, and what each source can do

Generated from the running Koin graph on 2026-09-13. To regenerate, print
`ProviderRegistry.games` and each adapter's `capabilities` from a test that builds the real graph.

| Adapter | Serves | Languages | Cross-set | Thumbs | Identity | Art variants | Finishes | Bulk | Max page |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `riftcodex` | Riftbound | 1 (en) | no | yes | yes | yes | no | no | 100 |
| `tcgdex` | Pokémon | 11 | yes | yes | no | no | yes | no | 1000 |
| `scryfall` | Magic | 11 | yes | yes | yes | yes | yes | **yes** | 175 |
| `optcg` | One Piece | 0 | yes | no | no | no | no | no | 1000 |
| `altered-db` | Altered | 5 (de en es fr it) | no | no | no | no | no | no | 1000 |
| `ygoprodeck` | Yu-Gi-Oh! | 7 | yes | yes | no | no | no | no | 100 |
| `ucp-wuwa` | Wuthering Waves | 3 (ja ko zh-cn) | yes | yes | yes | yes | no | no | 200 |
| `tcgcsv-lorcana` | Disney Lorcana | 0 | no | yes | no | no | no | no | 1000 |
| `tcgcsv-cyberpunk` | Cyberpunk TCG | 0 | no | yes | no | no | no | no | 1000 |
| `tcgcsv-wowtcg` | WoW TCG | 0 | no | yes | no | no | no | no | 1000 |

Eight modules, ten adapters: `providers/tcgcsv` holds three, one per TCGplayer category.

**Filters each source offers** (all local):

| Adapter | Text | Domain | Type | Rarity | Cost | Finish | Artwork | Language |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `riftcodex` | ● | ● | ● | ● | ● | | ● | |
| `tcgdex` | ● | ● | ● | ● | | ● | | |
| `scryfall` | ● | ● | ● | ● | ● | ● | ● | |
| `optcg` | ● | ● | ● | ● | ● | | | |
| `altered-db` | ● | ● | ● | ● | ● | | | |
| `ygoprodeck` | ● | ● | ● | ● | ● | | | |
| `ucp-wuwa` | ● | ● | ● | ● | ● | | ● | ● |
| `tcgcsv-lorcana` | ● | ● | ● | ● | ● | | | |
| `tcgcsv-cyberpunk` | ● | ● | ● | ● | ● | | | |
| `tcgcsv-wowtcg` | ● | | | ● | | | | |

`ucp-wuwa` is the only adapter with `bundledCardData` — UCP publishes no API, so its catalogue is a
file inside the app. `scryfall` is the only one with `cardInfoFromBulkOnly` and the only
`BulkCatalogue`.

Notable absences and why they are absences rather than gaps:

- **Pokémon has no `COST`.** A Pokémon card's cost is per attack, so there is no one number to
  filter on and the field stays empty rather than being faked from the first attack.
- **`optcg` and the three TCGCSV adapters state no languages.** Those sources say nothing about
  printing language, so the app states nothing either.
- **The WoW TCG adapter offers almost nothing.** TCGplayer's category 13 carries little beyond a
  name, a number and a rarity.

---

## Adding a provider

1. **The game**, if it is new — see [GAMES.md](GAMES.md).
2. **A module** under `providers/<name>`, depending on `:core` and the game module. Endpoints,
   DTOs and a mapper live there and nowhere else.
3. **Measure the API before writing the adapter.** Not from documentation: TCGdex advertises set
   symbols its CDN 404s, YGOPRODeck's own error message lists the wrong languages, and Riftcodex's
   `/cards/search` is not a name search. Record what you find in
   [PROVIDER_RESEARCH.md](PROVIDER_RESEARCH.md) with the date.
4. **Register it in Koin with `bind`**, never `single<CardProvider> { … }`. Ten definitions sharing
   one primary type and no qualifier means Koin keeps only the last.
5. **A routing entry** in `AppModule.kt`'s table: one game, one provider.
6. **Tests**: a mapper test against real captured JSON, and a catalogue test asserting ids are
   distinct — `LazyVerticalGrid` throws on a duplicate key rather than degrading.

A live smoke test is optional and gets its own task:
`./gradlew :providers:<name>:liveProviderTest`. It is excluded from the ordinary run because it
needs a network and hits someone else's server.
