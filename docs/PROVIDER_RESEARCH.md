# Provider research

Every entry below was verified by hitting the live endpoint from this machine and reading the real
response, not from documentation alone. Dates are when the check ran.

Nothing is added to the app on the strength of a documentation page. Where a check failed, the game
is not shipped and the reason is recorded here rather than papered over with a stub.

## Summary

| Game | Source | Verified | Languages served | Shipped |
| --- | --- | --- | --- | --- |
| Riftbound | Riftcodex | 2026-09 | en | yes |
| Pokémon | TCGdex | 2026-09-08 | fr, ja, en, ko | yes |
| Magic: The Gathering | Scryfall | 2026-09-08 | fr, ja, en, ko | yes |
| One Piece | OPTCG API | 2026-09-08 | en | yes |
| Altered | Altered TCG Card Database (community mirror) | 2026-09-08 | fr, en | yes |
| Cyberpunk TCG | — | 2026-09-08 | — | **no** |
| Yu-Gi-Oh! | YGOPRODeck | 2026-09-08 | fr, ja, en, ko | yes |
| Wuthering Waves TCG | UCP `mc-api.ucp-jp.com` | 2026-09-09 | ja, zh-cn, ko | yes, bundled |

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

## Cyberpunk TCG — not shipped

No data source exists to adapt.

- The game does not reach retail until **6 November 2026**.
- There is no official API. `cyberpunktcg.com/cards` is server-rendered HTML with no JSON endpoint
  behind it, and the community databases (`choomdex.com`, `ripperdeck.gg`) publish neither.
- Sources disagree on whether the first set holds 150 or 151 cards, which is a fair signal that the
  data itself is not settled.

Scraping a rendered page for an unreleased game would produce exactly the "fake production data to
make the app appear complete" the brief rules out, so Cyberpunk has no route and does not appear in
the game switcher. It can be added later as one module and one routing entry once WeirdCo publishes
something stable.

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
costs 123 requests per locale — 357 for all three. Using the provider's own filters to tag cards in
bulk would be far cheaper and does not work: `rarity_id` is silently ignored under every spelling
tried, and `fee=0` means "no filter" rather than "costs zero", which would misreport the 31 cards
that genuinely cost 0.

For a game whose entire output is **128 printings**, that is a lot of somebody else's bandwidth to
spend on every cold start. So the catalogue is scraped once, aligned across the three locales, and
committed as a bundled asset, `providers/wuwa/src/commonMain/composeResources/files/wuwa-cards.json` (170 KB). `providers/wuwa/tools/scrape_wuwa.py`
regenerates it and documents the alignment rules; `WuwaSnapshotFreshnessTest` is an opt-in live
check that asks UCP whether the file is still current.

This remains an undocumented internal endpoint for a recently launched game. It may change without
notice, and unlike the others there is no published stability promise to rely on — which is now a
risk to the *scraper* rather than to the app.
