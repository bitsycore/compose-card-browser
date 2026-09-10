# Provider research

Every entry below was verified by hitting the live endpoint from this machine and reading the real
response, not from documentation alone. Dates are when the check ran.

Nothing is added to the app on the strength of a documentation page. Where a check failed, the game
is not shipped and the reason is recorded here rather than papered over with a stub.

## Summary

| Game | Source | Verified | Languages served | Shipped |
| --- | --- | --- | --- | --- |
| Riftbound | Riftcodex | 2026-09 | en | yes |
| Pokémon | TCGdex | 2026-09-08 | all eleven the app knows | yes |
| Magic: The Gathering | Scryfall | 2026-09-08 | all eleven the app knows | yes |
| One Piece | OPTCG API | 2026-09-08 | en | yes |
| Altered | Altered TCG Card Database (community mirror) | 2026-09-08 | fr, en | yes |
| Cyberpunk TCG | TCGCSV category 92 | 2026-09-09 | none stated | yes |
| Yu-Gi-Oh! | YGOPRODeck | 2026-09-08 | de, en, fr, it, ja, ko, pt | yes |
| Wuthering Waves TCG | UCP `mc-api.ucp-jp.com` | 2026-09-09 | ja, zh-cn, ko | yes, bundled |
| Disney Lorcana | TCGCSV category 71 | 2026-09-09 | none stated | yes |
| World of Warcraft TCG | TCGCSV category 13 | 2026-09-09 | none stated | yes |
| Duel Masters | — | 2026-09-09 | — | **no** |

## Bulk endpoints — every source, checked 2026-09-10

Downloading a whole game costs one request per set, which for Magic is 988 of them against a
service run on donations. Some sources publish a periodic dump precisely so that clients stop doing
that. Every source here was asked whether it has one; these are the answers, and the negatives are
worth as much as the positives because each one is a thing not to go looking for again.

| Source | Whole catalogue in one call? | Measured |
| --- | --- | --- |
| Scryfall | **yes** — `/bulk-data`, `default_cards` | 74.6 MB gzipped, 598 MB JSONL |
| YGOPRODeck | **yes** — `cardinfo.php` with no selector | 21.2 MB English, 18.7 MB `language=fr` |
| TCGdex | partial — `/v2/{lang}/cards` is briefs only | 2.4 MB, id + name + image only |
| TCGCSV | n/a — already one request per set | `/tcgplayer/13/products` → 404 |
| Riftcodex | no — paged, `size` capped at 100 | 15 requests for 1451 cards |
| OPTCG API | no | `/api/allCards/` → 404 |
| Altered mirror | no — one JSON file *per card* | `CARDS/{lang}/{set}/{faction}/…` |
| Wuthering Waves | n/a — ships as a bundled snapshot | — |

Two are real, and they are not the same shape:

- **Scryfall's dump is a file and is self-describing.** It carries whatever languages it carries,
  each record stating its own, which is why `BulkCatalogue.streamAll` deliberately takes no language
  parameter. Implemented — see `ScryfallBulk`.
- **YGOPRODeck's is a query with the selector left off**, so it is per-language: one 21.2 MB request
  gives the whole database in English and `language=fr` gives the whole thing again in French. All
  seven languages would be seven calls and ~140 MB. Not implemented yet, and it does not fit the
  current interface without a decision — see below.

`GET https://db.ygoprodeck.com/api/v7/cardinfo.php` answered `200`, `application/json`,
`21,238,702` bytes in 0.4 s; with `?language=fr`, `18,663,298` bytes. That is one request against
roughly 200 sets × several pages each, so the saving is the largest of any source here after Magic.

What blocks it is that the dump is English-only unless a language is named, while `streamAll` has no
language parameter — and that absence is load-bearing, because passing `null` through
`resolveLanguage` is what once made an English file import as French. Fetching all seven languages
unasked to preserve the signature would mean ~140 MB by default, which is worse than the problem.
Resolving it means either a `languages` hint on `streamAll` (each card still stating its own
language, so the honesty property survives) or a second, per-language interface. Not decided.

Everything else genuinely has nothing. In particular the Altered mirror stores **one file per card**
under `CARDS/{lang}/{set}/{faction}/`, so it is the opposite of a dump; and TCGCSV rejects a
category-wide product listing with 404, which is fine because its unit is already the group — a set
there is one request, the same as a bulk file would cost per set.

## Per-set languages — every source, checked 2026-09-10

A source's language list is a fact about the source. Which languages *one set* exists in is a
different fact, and getting them confused is how the app came to offer French for a Yu-Gi-Oh! set
released in November 2026 whose translations do not exist yet. `CardSet.languages` carries the
per-set answer and `CardProvider.confirmLanguages` narrows it to what a source really serves.

| Source | States it per set? | How |
| --- | --- | --- |
| TCGdex | **yes, with the catalogue** | eleven locale catalogues, already merged in `listSets` |
| Wuthering Waves | **yes** | the bundled snapshot carries a locale per card |
| YGOPRODeck | **no, but cheap to ask** | `cardinfo.php?num=1&language=…`, one per candidate |
| Scryfall | **no, but cheap to ask** | already overrides `confirmLanguages` |
| Altered mirror | no | `META/card_sets_{locale}.json` lists all 20 sets in all 5 locales |
| Riftcodex, OPTCG | n/a | one language each, so the per-set answer adds nothing |
| TCGCSV | n/a | states no language at all, so there is nothing to narrow |

TCGdex's catalogues genuinely differ and the difference is the whole Japan line:

```
/v2/en/sets  218 sets      /v2/ja/sets  184, of which 180 are in no western catalogue
/v2/fr/sets  200 sets      /v2/ko/sets   95, of which none are in the English one
```

YGOPRODeck's probe was measured at 1.7–3.8 KB and roughly 0.5 s per candidate, seven candidates
per set, run concurrently and cached for as long as the set list. Beyond the Brave answers `400`
for all six non-English languages and Magnificent Maestros answers all seven, with 4 cards against
24 — see `YgoprodeckProvider.confirmLanguages`.

Altered's per-locale set indexes are 8.7 KB each and list the same 20 sets in every one, so there
is nothing per set to learn from them. Listing a set in a locale would not be evidence of cards in
it anyway — that is exactly the TCGdex Korean trap — so it is left unstated rather than claimed.

## Pokémon — TCGdex

`https://api.tcgdex.net/v2/{lang}/…`, no key, no auth.

- `GET /v2/{lang}/sets` — the whole catalogue in one response (~35 KB for `en`).
- `GET /v2/{lang}/sets/{setId}` — the set *with every card in it*, so a set costs exactly one
  request and there is no paging to get wrong.
- `GET /v2/{lang}/cards/{cardId}` — one card.

Language coverage was measured rather than assumed, by counting sets per locale:

| Locale | Sets |
| --- | --- |
| `en` | 218 |
| `fr` | 200 |
| `ja` | 184 |
| `ko` | 95 |

Every language the app knows, which no other source in this list matches: `de`, `es`, `it`, `pt`,
`ru`, `zh-cn` and `zh-tw` each return a catalogue of their own alongside the four above, and
TCGdex's locale tags are the app's own tags unchanged.

Images are a base URL with the quality and extension appended — `{image}/{quality}.{extension}`:

| Variant | Bytes (sample card) |
| --- | --- |
| `low.webp` | 19,510 |
| `high.webp` | 63,110 |
| `low.png` | 55,131 |
| `high.png` | 263,075 |

WebP throughout, so the grid loads `low.webp` and the detail screen `high.webp`.

`variants` gives `normal` / `holo` / `reverse` / `firstEdition` as booleans, which is a genuine
finish statement rather than an absence, and `variants_detailed[].thirdParty.cardmarket` is a real
Cardmarket **product** id — the only source here that maps a single printing to a product.

## Magic: The Gathering — Scryfall

`https://api.scryfall.com`, no key. Requires a `User-Agent`, and asks for 50–100 ms between
requests.

- `GET /sets` — 622 KB, the entire catalogue.
- `GET /cards/search?q=set:{code}+lang:{lang}&unique=prints&include_multilingual=true` — 175 cards
  per page, `has_more` plus `next_page`.

`lang:fr`, `lang:ja` and `lang:ko` all return real printings, with `printed_name` and
`printed_type_line` alongside the English `name`. `oracle_id` is a genuine cross-printing identity,
which makes Scryfall the only source here that can populate `CardIdentity` honestly.

`finishes` is an explicit array (`nonfoil`, `foil`, `etched`), `image_uris` includes WebP `thumb`,
`grid` and `display` variants, and `cardmarket_id` is a product id where one exists.

## One Piece — OPTCG API

`https://optcgapi.com/api`, no key.

- `GET /allSets/` — 1.2 KB, `set_name` + `set_id`.
- `GET /sets/{setId}/` — every card in the set, unpaged (154 cards for `OP-01`).

English only; there is no language field anywhere, so the other three are reported UNKNOWN rather
than unavailable. Images are `https://optcgapi.com/media/static/Card_Images/{code}.jpg` with no
resizing, so the grid and the detail screen load the same file.

Rarity arrives abbreviated (`C`, `UC`, `R`, `SR`, `SEC`, `L`, `P`), which the adapter expands.

## Altered — community mirror

**The official API is gone.** `api.altered.gg` has no A record at all — confirmed against Cloudflare
DNS, not just this machine's resolver — and the official art bucket
`altered-prod-eu.s3.amazonaws.com` answers `403` for every card image referenced by the data.

What still works is `PolluxTroy0/Altered-TCG-Card-Database`, a community mirror that stores the
official API's own JSON unmodified, plus a copy of the card images. Served through jsDelivr:

- `META/card_sets_{lang}.json` — 20 sets, Hydra collection format.
- `SETS/{SET}/{SET}_{LANG}.json` — the whole set (327 cards, ~1 MB for `ALIZE_FR`).
- `IMAGES/{lang}/{SET}/{reference}.jpg` — one image per card per language.

Languages in the mirror are `de`, `en`, `es`, `fr`, `it`; of the app's four, French and English.
French is what Altered is originally written in, and it is the app's first preference, so this is
the one game where the top preference is genuinely honoured.

**Known limitation, not worked around:** the mirror has no thumbnail variant and no resizing CDN,
so a grid tile loads the same 200–300 KB JPEG the detail screen does. Every other provider here
serves a small variant. This is stated in the UI rather than hidden.

## TCGCSV — Lorcana, Cyberpunk and the WoW TCG

Checked 2026-09-09. https://tcgcsv.com is a public JSON mirror of TCGplayer's product catalogue. No
key, no auth, no published rate limit. Two endpoints matter:

```
/tcgplayer/{category}/groups            every set in a game
/tcgplayer/{category}/{group}/products  every product in one set
```

A category is a game and a group is a set, which is why one adapter serves three games. Of the 93
categories it publishes, three are games this app wanted — 71 Lorcana, 92 Cyberpunk TCG, 13 WoW TCG
— and there is **no Duel Masters category**, which is where that game's search started.

### What is the same for all three

- **No search endpoint of any kind.** `crossSetSearch = false`; every filter is `localOnly`, so a
  text search covers the sets already downloaded and the app labels it with a count.
- **No per-product endpoint.** Products are only served a group at a time, so a card's id carries
  its group: `{groupId}-{productId}`. Without that, a cold deep link could not be served at all.
- **Sealed product shares the catalogue.** A booster box is a product with a UPC and no game fields,
  so cards are identified by the fields they *do* carry.
- **No language is stated anywhere.** It is plainly the English storefront and never says so, so
  `languages` is empty and every record carries `isProviderStated = false`.
- **Prices are published and not mapped.** This is the source where taking them would be easiest.
- **Two content types.** `text/json` for some responses and `application/json` for others, within
  one category. This broke the adapter for exactly one of the three games; see CLAUDE.md.
- **Three image renditions**, derived from the `_200w.jpg` each record states:

  | Suffix | Lorcana | Cyberpunk | WoW |
  | --- | --- | --- | --- |
  | `_200w` | 200x280, 14 KB | 200x279, 11 KB | 200x280, 27 KB |
  | `_400w` | 400x559, 43 KB | 400x559, 37 KB | 200x280, 16 KB |
  | `_in_1000x1000` | 500x699, 61 KB | 716x1000, 91 KB | 200x280, 16 KB |

  `in_` inscribes rather than upscaling, so it returns the original and is genuinely the largest.

### Lorcana — category 71

20 groups, 3654 products, 3309 of them cards, product ids unique throughout. The richest of the
three: ink, cost, Strength, Willpower, type, classification, rules and flavour text. 21 distinct
`InkType` values, being six inks and fifteen pairs — a dual-ink card is split so filtering by one
ink matches it. 11 rarities, of which six form the ladder the game has always used; Epic and Iconic
arrived later and are left unplaced rather than guessed at. `Lore Value` and `Move Cost` are real
and unmapped: the shared model holds three numbers and Lorcana prints four.

**A better API exists and was rejected.** [Lorcast](https://api.lorcast.com/v0/) is Scryfall-shaped,
with a real `/cards/search`, collector numbers, a stated `lang` and full set metadata — everything
TCGCSV lacks. It serves card images as **AVIF only**: the `.avif` path returns 200 and the same path
with `.webp`, `.jpg` or `.png` returns 404. Coil decodes AVIF on Android 12+ but not through Skia on
desktop or iOS, so three of the four targets would render a grid of blank tiles. Worth revisiting if
that changes; it would be a second adapter, not a change to the first.

### Cyberpunk TCG — category 92

**This overturns an earlier finding in this file.** The previous entry read "no data source exists
to adapt", which was accurate on 2026-09-08 and false a day later — TCGplayer opened a category for
the game ahead of its 6 November 2026 release.

9 groups, all dated 2026-11-06, 422 products of which 408 are cards. Four of the nine groups are
Beta printings of the other four, so the same card is listed twice with two product ids and one
collector number: 408 cards over 314 distinct numbers. Ids stay unique because they are product
ids, which matters because `LazyVerticalGrid` throws on a repeated key.

Fields: colour (four), cost, Power, type (Unit/Legend/Program/Gear), tags, rules text. Rarity is on
267 of the 408 and the rest state none. `RAM` is written `x1` — a multiplier, not a number — and
`Eddies` is a `TRUE`/`FALSE` flag; neither is mapped into a numeric slot.

### World of Warcraft TCG — category 13

The thinnest source in the app, and the honest ceiling of what is knowable: the game was
discontinued in 2013 and no publisher database outlived it.

54 groups. Measured over 1549 products in 12 of them: **the only game field any WoW product carries
is `Rarity`**, as a single letter (`C`/`U`/`R`/`E`/`L`, expanded by the adapter). There is no
collector number, no card text, no cost, no type, no class and no faction anywhere in the category.
Images are 200x280 and that is the original — all three renditions return the same pixels, `_200w`
merely being a worse re-encode — so the adapter states one URL and no thumbnail.

A fuller source exists if the thinness is ever unacceptable: `wowcards.info` and the
`wowtcg-decktools` dataset both hold real card text. Either would be a second adapter.

## Duel Masters — not shipped

Checked 2026-09-09. Asked for; no source found that a card *browser* can use.

- **TCGplayer has no category for it**, so the TCGCSV route that served the other three is closed.
- [`duel-masters-json`](https://github.com/Latepate64/duel-masters-json) is a real, well-formed
  community dataset: 1152 cards, 797 KB, with civilizations, costs, types, rules text, flavour text,
  illustrators and per-printing set/rarity. It carries **no image field at all**, and covers DM-01
  to DM-12 — roughly a tenth of what the game has printed.
- The publisher's own database at `dm.takaratomy.co.jp/card/` *does* have art, on a predictable
  path (`/wp-content/card/cardthumb/dm26ex3-SEC001a.jpg`), but publishes no JSON: the page is
  server-rendered and would need a Wuthering-Waves-style scraper against a Japanese-only site, over
  a catalogue orders of magnitude larger than Wuthering Waves' 123 cards.

Shipping the text-only dataset would give a card browser with no pictures covering a tenth of the
game. That is a trade for the project owner to make rather than one to make quietly, so Duel Masters
has no route and does not appear in the picker.

## Yu-Gi-Oh! — YGOPRODeck

`https://db.ygoprodeck.com/api/v7`, no key, documented ceiling of 20 requests/second.

- `GET /cardsets.php` — 175 KB, every set with `set_name`, `set_code`, `num_of_cards`, `tcg_date`.
- `GET /cardinfo.php?cardset={name}&num={n}&offset={o}&language={lang}` — cards in a set, paged.

`language=fr`, `language=ja` and `language=ko` all return translated names and text; English is the
default and takes no parameter.

Two quirks the adapter has to handle:

- Sets are filtered **by name**, not by code, and the name is what comes back in `cardsets.php`.
- A card carries a `card_sets` array covering every set it has ever appeared in, so the collector
  number for *this* set has to be picked out of that array by matching the set name, not read from
  a top-level field.

YGOPRODeck asks that images not be hotlinked by websites. This is a client application that caches
what a user actually looks at to that user's own device, which is the behaviour their guidance is
asking for rather than against, and requests carry an identifying `User-Agent`.

## Wuthering Waves TCG — UCP

The user flagged this one as probably having no API. It does have one.

`https://wwcg.ucp-jp.com/jp/card` is a Vue application; its bundle names an axios instance with
`baseURL: "https://mc-api.ucp-jp.com/api/"` and a request interceptor that sets an **`x-lang`**
header. The endpoints are `/web/card/list`, `/web/card/info` and `/web/card/search-options`.

Locale coverage, measured by asking for a page of cards under each value:

| `x-lang` | Cards |
| --- | --- |
| `ja-jp` | 123 |
| `zh-cn` | 127 |
| `ko-kr` | 107 |
| `en-us` | 0 |
| `zh-tw` | 0 |
| `ko`, `kr` | 0 |

Three real catalogues. The tag has to be the **full locale**: bare `ko` and `kr` are both accepted
and both answer `200 OK` with an empty list, as do `en-us` and `zh-tw` — which is exactly the shape
of failure the completeness guard in `CardRepository` exists to catch, and the reason the adapter
declares three languages rather than six.

Three sets — `SD01`, `SD02`, `BP01` — derived from the `code` prefix, because the API has no set
entity and no endpoint that lists one. Images are already WebP on a Tencent COS bucket.

### Two records per code are two rarity tiers, not two artworks

36 codes carry two records each. They are the same card printed at two rarities — `SD01-003` is 秧秧
at ★★★ and again at ★★★★ — and the two carry **genuinely different illustrations**: the ★★★ is a
framed portrait, the ★★★★ a full-bleed alternate with a different pose and background. Verified by
fetching both scans and looking at them, because the alternative reading was that the higher tier is
a foil of the first, which would have made it a `FinishCoverage` entry rather than an
`ArtworkTreatment`. It is not. The API has no finish field at all.

`(code, star count)` is collision-free in all three locales and is what the snapshot keys on. Rank
within a code is **not** usable: six codes ship only one of their two tiers in some locales, and
ranking pairs a ★★★ record with a ★★★★ one there.

### Each locale is a separate catalogue with its own ids

The same card carries a different numeric id per locale — `SD01-001` is 651 under `ja-jp`, 1357
under `zh-cn`, 1139 under `ko-kr` — so an id is meaningless without the locale that issued it. The
catalogues are also different sizes: 21 printings are absent from Korean and five exist only in
Chinese. That makes per-card language coverage a real fact for this source rather than a restatement
of what was requested.

### Why the adapter ships a snapshot instead of calling this

The list endpoint returns six fields per card. Rarity, attribute, cost, level, weapon, faction and
the rules text exist **only** on `/web/card/info`, one card at a time, so assembling the catalogue
costs one request per card per locale — three times the size of the catalogue, and growing with
it. Using the provider's own filters to tag cards in
bulk would be far cheaper and does not work: `rarity_id` is silently ignored under every spelling
tried, and `fee=0` means "no filter" rather than "costs zero", which would misreport the 31 cards
that genuinely cost 0.

For a game this small — **184 records under 120 printed codes** as of the 2026-09-10 refresh,
231 KB of JSON — that is a lot of somebody else's bandwidth to spend on every cold start. So the
catalogue is scraped once, aligned across the three locales, and committed as a bundled asset,
`providers/wuwa/src/commonMain/composeResources/files/wuwa-cards.json`. The figures here are dated
because the game grows; `WuwaSnapshotFreshnessTest` is what notices. `providers/wuwa/tools/scrape_wuwa.py`
regenerates it and documents the alignment rules; `WuwaSnapshotFreshnessTest` is an opt-in live
check that asks UCP whether the file is still current.

This remains an undocumented internal endpoint for a recently launched game. It may change without
notice, and unlike the others there is no published stability promise to rely on — which is now a
risk to the *scraper* rather than to the app.
