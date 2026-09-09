# CardBrowser

A Kotlin Multiplatform card browser for **seven trading card games**, sharing Compose Multiplatform
UI and all application logic between Android, iOS and JVM desktop.

Pick a game, browse its sets, open one, filter its cards, search across every set, and read a card
in detail. No backend: the apps talk to each game's card database directly over Ktor. Browsing only
— no accounts, no collection, no prices, no deckbuilding.

`CardBrowser` is a temporary name.

---

## Contents

- [Status](#status)
- [Prerequisites](#prerequisites)
- [Build, run, test](#build-run-test)
- [Verified dependency versions](#verified-dependency-versions)
- [Games and providers](#games-and-providers)
- [Chosen defaults](#chosen-defaults)
- [Known limitations](#known-limitations)
- [Architecture](docs/ARCHITECTURE.md)
- [Provider research](docs/PROVIDER_RESEARCH.md) — what each source was measured to supply
- [Working on this project](CLAUDE.md) — conventions, layering rules and the traps

---

## Status

| Target | State |
|---|---|
| **JVM desktop** | Built, launched, browsed. Real sets and real cards on screen. |
| **Android** | Debug APK builds and runs on a real device. Game picker, set lists and card grids verified on screen. |
| **iOS** | **Kotlin now compiles** for `iosArm64` and `iosSimulatorArm64`, from scratch, as part of `./gradlew build`. Linking the framework and building the Swift shell still need a Mac and have never been done. See [`iosApp/README.md`](iosApp/README.md). |

**291 deterministic tests pass on every target** and `./gradlew build` is green end to end,
including both iOS targets. See [Build, run, test](#build-run-test).

The live provider checks are a separate story: **60 of 63 pass**. The three failures are all
Scryfall and all `429 Rate limited` — not assertion failures. The client paces itself at 150 ms and
shares one mutex across a whole run, so it is not missing a brake; Scryfall's suite is simply the
largest here at 12 checks and it exceeds what that host will accept in one burst. Re-running it
alone after a short pause did **not** clear it, so this is not a transient I have seen recover.
Treat a clean Scryfall live run as unconfirmed until someone gets one from a cold quota.

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | **21** | The Gradle toolchain is pinned to 21. A newer JDK can run Gradle itself; the compile toolchain is still 21. |
| Android SDK | Platform **37**, build-tools **36.1.0** | Only needed for the Android target. |
| Xcode | 16+ | macOS only, for iOS. |
| Gradle | — | Do not install one. Use the wrapper; it fetches the pinned version. |

Point Gradle at your Android SDK by creating `local.properties` in the repository root:

```
sdk.dir=/path/to/Android/Sdk
```

On Windows use forward slashes (`sdk.dir=C:/Users/you/AppData/Local/Android/Sdk`). It is gitignored.

---

## Build, run, test

### Desktop

```bash
./gradlew :composeApp:run
```

### Android

```bash
./gradlew :androidApp:assembleDebug
```

The APK lands in `androidApp/build/outputs/apk/debug/androidApp-debug.apk`. To install on a
connected device or running emulator:

```bash
./gradlew :androidApp:installDebug
```

### iOS

The shared framework, which is as far as this repository can take you without a Mac:

```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Then follow [`iosApp/README.md`](iosApp/README.md) to create the Xcode project.

### Tests

Everything deterministic, no network:

```bash
./gradlew :core:desktopTest :data:desktopTest :providers:riftcodex:desktopTest :composeApp:desktopTest
```

Or, more briefly, every check on every buildable target:

```bash
./gradlew allTests
```

### Live provider smoke checks

Deliberately separate: they need a network and depend on somebody else's server, so they are
excluded from the ordinary run and cannot fail a build because one provider is down.

Each adapter has its own task:

```bash
./gradlew :providers:riftcodex:liveProviderTest
./gradlew :providers:tcgdex:liveProviderTest
./gradlew :providers:scryfall:liveProviderTest
./gradlew :providers:optcg:liveProviderTest
./gradlew :providers:altered:liveProviderTest
./gradlew :providers:ygoprodeck:liveProviderTest
./gradlew :providers:wuwa:liveProviderTest
```

48 checks in total, all passing as of 2026-09-09. These are what catch a provider changing shape
underneath us — including a full round trip through the real API, the real disk cache, a local
filter and an offline replay, and, for the two sources whose images are mirrored or undocumented, a
direct check that the card art still loads.

---

## Verified dependency versions

Every version is pinned exactly in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). No
dynamic versions, no `+`.

| Component | Version | How it was verified |
|---|---|---|
| Gradle | 9.7.1 | Wrapper. Builds and tests run on it here. |
| Android Gradle Plugin | 9.4.0 | `:androidApp:assembleDebug` produces an APK. |
| Kotlin | 2.4.20 | Every module compiles; all tests run. |
| Compose Multiplatform | 1.12.0 | Desktop app launched and browsed. |
| Compose Material 3 | 1.9.0 | Every screen uses it. |
| Pulse MVI | 0.3.7 | `com.bitsycore.lib:pulse{,-viewmodel,-compose,-test}` from `maven.bitsycore.com`. Real API confirmed against the published sources jar — the reducer lives on `ContainerContract`, and state is exposed as `stateFlow`, both of which differ from the README. |
| Koin | 4.2.2 | Graph resolves; the desktop app runs from it. |
| Ktor Client | 3.5.2 | OkHttp on Android, Darwin on iOS, Java on desktop. No Ktor Server anywhere. |
| Kotlinx Serialization | 1.11.0 | Provider DTOs and the disk cache. |
| Kotlinx Coroutines | 1.11.0 | |
| Kotlinx DateTime | 0.8.0 | Set release dates. |
| Okio | 3.18.1 | Metadata cache and preferences. |
| Coil | 3.6.2 | Card images, over the app's own Ktor client. |
| Navigation 3 | 1.1.1 | |
| JDK toolchain | 21 | |
| compileSdk / targetSdk / minSdk | 37 / 37 / 24 | |

### On the AGP 9 module split

AGP 9 refuses `com.android.application` in the same Gradle subproject as the Kotlin Multiplatform
plugin, and deprecates `com.android.library` for KMP entirely. So the shared modules apply
`com.android.kotlin.multiplatform.library` and `:androidApp` is a separate subproject that does
nothing but start Koin and call `App()`.

### Libraries chosen beyond the required stack

Three, each for a concrete need:

- **Coil 3** — the only mainstream image loader covering Android, iOS and JVM desktop from one API,
  and it accepts a Ktor engine, so the app keeps a single HTTP stack with one User-Agent and one
  retry policy. A card browser is mostly images; hand-rolling this was not sensible.
- **Navigation 3** — its nav-entry decorators scope a `ViewModel` and a saveable state holder to a
  back-stack entry. That is what makes the card grid keep its filters and scroll position across a
  trip into card detail, and release them on the way back to the set list.
- **Material 3** — accessible controls, light and dark themes, and touch targets, without
  hand-building them.

**BitsyKonfig is deliberately not used.** It generates build-time constants, and this app has none
worth generating: the one endpoint is a public URL that is already a documented constant on the
adapter that owns it, and there are no API keys of any kind. Adding a build plugin to inline one
string would be cost without benefit. (Build-time constants would be the wrong place for a secret
anyway — they end up in the binary.)

---

## Games and providers

Seven games, seven adapters, one authoritative source each. A game and a provider are separate
modules: `:games:riftbound` declares what Riftbound *is* — its vocabulary, its rarity ladder, its
Cardmarket segment, its logo — and `:providers:riftcodex` declares where the data comes from, naming
the game in its own type as `CardProvider<RiftboundGame>`. `:core` names no game at all. Every one was verified by hitting the
live endpoint and reading the real response — [`docs/PROVIDER_RESEARCH.md`](docs/PROVIDER_RESEARCH.md)
records what each check found, including the two that changed the design.

| Game | Source | Languages served | Notes |
|---|---|---|---|
| Riftbound | [Riftcodex](https://riftcodex.com) | en | Unofficial community database. |
| Pokémon | [TCGdex](https://tcgdex.dev) | **all 11** | The widest coverage here: every language the app knows is a separate catalogue. Real Cardmarket product ids and two-sided finish data. |
| Magic: The Gathering | [Scryfall](https://scryfall.com) | **all 11** | The only source with a real cross-printing identity (`oracle_id`). Spells Chinese `zhs`/`zht`, which the adapter maps. |
| One Piece | [OPTCG API](https://optcgapi.com) | none stated | Data is plainly English; the source never says so, so nothing is claimed. |
| Altered | [Altered TCG Card Database](https://github.com/PolluxTroy0/Altered-TCG-Card-Database) | fr, en | A community mirror. The official API is gone — see below. |
| Yu-Gi-Oh! | [YGOPRODeck](https://ygoprodeck.com) | fr, ja, en, ko, de, it, pt | Sets are addressed by name; rarity is per printing. |
| Wuthering Waves TCG | UCP `mc-api.ucp-jp.com` (bundled snapshot) | ja, zh-cn, ko | Undocumented internal endpoint with no stability promise, so the whole 128-card game is scraped and shipped. The only adapter that makes no requests for card data. |

**Cyberpunk TCG is deliberately absent.** It was asked for, and there is nothing to adapt: the game
does not reach retail until 6 November 2026, there is no official API, and the community databases
publish no JSON. Scraping a rendered page for an unreleased game would be exactly the fabricated
data this project refuses to ship. It becomes one module and one routing entry the day a real source
appears. Because `ProviderRegistry.games` is derived from the routing table, a game with no adapter
cannot appear in the picker at all — there is no greyed-out row.

### What each source cannot do, and what the app does about it

The point of the model is that these gaps stay visible rather than being smoothed over.

| The brief asks for | Where reality falls short | What the app does |
|---|---|---|
| Printings in eleven languages | Riftcodex, OPTCG and Altered have no Japanese or Korean at all; Wuthering Waves has Japanese, Simplified Chinese and Korean and nothing else. | The requested language is *confirmed* only when the source served it. Everything else is **unknown, never unavailable** — a source not carrying Korean is not evidence that no Korean printing exists. Asking Scryfall for a language a set was never printed in falls back to English and **labels the records English**. |
| Finish options | Only TCGdex and Scryfall state finishes. | Both sides of `FinishCoverage` are populated for those two, because their data genuinely distinguishes "not printed" from "not mentioned". For the other five, finish is *unstated* and no finish filter is offered. |
| Card identity across printings | Only Scryfall states one. | `identity` is null everywhere else, and no identity is ever inferred from a shared name. |
| Card → Cardmarket product | Only TCGdex maps printings to products. | See [Cardmarket](#cardmarket) — links are suppressed entirely for games whose Cardmarket path has not been seen on a real page. |
| Filtering | No source here can filter reliably on domain, type, rarity and cost together. | Every filter is declared `localOnly` and applied to the complete set the repository already caches. That makes filters instant and offline, and it is why the completeness rule matters. |

### Two findings that changed the design

**Altered's official API no longer exists.** `api.altered.gg` has no DNS A record at all — confirmed
against Cloudflare's resolver, not just this machine's — and the official art bucket
`altered-prod-eu.s3.amazonaws.com` answers `403` for every card image its own data links to. The
adapter therefore reads a community mirror that stores the real API's JSON unmodified, plus a copy
of the images, served through jsDelivr. That is a snapshot maintained by a volunteer rather than a
live API, and the adapter's own documentation says so.

**Riftcodex's search endpoint is not a name search.** `/cards/search` exists, takes a `query` and
answers `200` — and re-checking it on 2026-09-08 found `query=Cull` matching nothing while
`query=Cull the Weak` returns "Aspirant's Climb", a card sharing neither name nor text with it. The
app had text declared as a *remote* filter, so the search box was wired to that endpoint and
returned "no results" for most of what anyone typed. Text is now matched locally against the
complete set the app already holds, which costs no extra request and actually works.

### Product lines, where a game has more than one

Pokémon does not print one catalogue. It prints an international line and a Japanese line that
share neither set list nor numbering — `sv1a` has no international counterpart, and there is no
Japanese edition of `swsh1`. Measured across all eleven locales there are **486 distinct Pokémon
sets**, of which the English catalogue holds 218.

Treating those as one list meant only ever showing part of itself, with which part decided by an
unrelated language preference. The set list now merges every catalogue and offers region chips over
it: International 221, Japan 180, Taiwan & HK 36, China 49.

Korea is deliberately **not** a region. All 95 Korean set ids are Japanese set ids — Korea prints
the Japan line — so Korean is a *language of* the Japan region rather than a region of its own. What
lines a game has is declared by the game (`GameProfile.regions`); which line a set belongs to is
tagged by the adapter, because that is a fact about how the source keys its data.

### Changing the language of a set you are looking at

The language preference picks a default; a set can also be switched in place, and only to languages
that set was really printed in. `CardSet.languages` carries what the source stated, so the picker
offers Japanese for a Japanese-line Pokémon set and does not offer Russian for a game that has none.
A switch that fails says so rather than silently showing the previous language.

### Downloading a set

A set row has a download button, and the top bar can queue every set currently shown. Three things
are offered separately, because they cost very differently:

| | What it buys | Rough size |
|---|---|---|
| **Card info** | the set browsable and searchable offline | a handful of small requests |
| **Grid thumbnails** | the grid renders offline | ~32 KB a card |
| **Full card art** | a card readable and zoomable offline | ~94 KB a card |

Those per-card figures are measured, not guessed — sampled across three providers on 2026-09-09:
TCGdex 19.5 KB against 63 KB, Scryfall 47 against 67, YGOPRODeck 28 against 153. A thumbnail is
about a quarter of the pair, which is why it is a separate purchase: browsing a set offline costs
roughly a quarter of what reading it does. The spread is wide, so the dialog says "about".

Jobs run **one at a time**. The provider clients already pace within a host, but nothing bounded how
many jobs ran at once, and several of these APIs return 429 when pushed. Images within a job go four
at a time, since those hit a CDN rather than the API.

The set list then shows what a set has: a document mark for records, a grid mark for thumbnails, a
photo mark for full art, with a percentage when a download did not finish. The image marks are
records of a *download*, not proof of *presence* — the image cache is an LRU and the OS may purge it
— so they read "downloaded", and art that arrived through ordinary browsing is not counted at all.

### Cross-set search

Search every set of a game by card name. Five of the seven sources can search their whole catalogue;
Riftbound and Altered cannot, so for those the app searches **only the sets already downloaded** and
says exactly that, with a count of how many of the game's sets that was.

The distinction is not cosmetic. A whole-catalogue search finding nothing means the card does not
exist; a cache-scoped search finding nothing usually means you have never opened the set it is in.
Presenting the second as the first would be the app lying about what it knows.

---

## Chosen defaults

Where the brief left a choice, these were taken. All are one edit to change.

| Choice | Value | Why |
|---|---|---|
| Metadata cache ceiling | 256 MB | Every Riftbound set is a few megabytes of JSON. Card metadata is what makes the app work offline and is two orders of magnitude cheaper than images, so evicting it to save megabytes would be a poor trade. |
| Image cache ceiling | 1 GB | A ceiling, not an allocation — a full browse of all 352 Origins cards came to under 4 MB. At ~22 KB a thumbnail this is room for tens of thousands of cards, so the limit stops being what evicts. On Android and iOS it sits in the OS cache directory, which the system may purge regardless. |
| Thumbnail format | WebP at `w=320` | Pinned, not negotiated — see [Known limitations](#known-limitations). ~22 KB against ~260 KB for the same image as PNG. |
| Detail image | WebP at the asset's native width, `q=90` | ~180 KB against ~1.17 MB for the lossless PNG, and no visible difference. Decoded at source resolution rather than layout size so zoom has real pixels. |
| First request when opening a set | 24 cards, thrown away | Time-to-first-card. This API's transfer time tracks payload and swings hard — a 100-card page measured between 1.6 s and 11.8 s, a 24-card one about 1 s. Skipped for a provider whose own pages are already that small. |
| Card art prefetched around the open card | 3 either side | Enqueued into the cache without composing anything, so a swipe lands on finished art. ~180 KB apiece against a 1 GB ceiling. |
| Pages fetched at once | 4 | Page one is drawn before the rest are even requested; the remainder go out together. Four covers every Riftbound set in one batch while staying polite to a free API. |
| Set list freshness | 24 hours | Set catalogues change when a set is announced. |
| Card data freshness | 24 hours | Stale data still displays immediately; this only governs when a refresh is attempted. |

**What "freshness" means in practice.** The cached copy is *always* drawn first and a refresh never
blocks it. What the window controls is whether the screen calls the copy stale ("Saved copy,
refreshing…"). A refresh that fails leaves the cached data in place and adds a retry, so going
offline never costs you what you already had.

The **set list** is additionally revalidated in the background if it is more than five minutes old,
which in practice means on every launch: the list is one 2 KB request, it is drawn from cache
instantly either way, and the cost of not asking is a set released this morning not showing up until
tomorrow. Card data uses the full 24 hours before re-fetching, because re-checking a set is four
requests rather than one.

A refresh now asks *whether* anything changed rather than re-downloading blindly. Provider clients
carry an HTTP response cache (`OkioHttpCacheStorage`, over the same Okio filesystem the rest of the
app uses, because Ktor's own file storage is JVM-only and this has to run on iOS). Against an origin
that sends an `ETag` — and all of these do — a revalidation comes back as an empty `304` instead of
a body. 64 MB ceiling, oldest-written evicted first.

This is measurable rather than asserted: **Settings shows a live count of requests sent per host
since launch.** That counter is the point — the claims above about what is and is not re-fetched
were previously unobservable, and a host whose count climbs while you sit still is a bug you can
now see. Counted per host rather than per provider, because that is what actually leaves the device
and it separates a provider's API host from its image CDN.
| Grid tile minimum width | 108 dp | Columns adapt to the window; this keeps art legible on a phone. |
| Search debounce | 300 ms | |
| Retries | 2 extra attempts, exponential, transient failures only | A 4xx is never retried. |
| Default sort | Natural collector number, ascending. Tapping the selected sort again reverses it |
| Rarity order | Common → Uncommon → Rare → Epic → Showcase | Riftbound's own ladder. Providers supply rarity as a bare string with no ordering, and sorting those alphabetically puts Common between Uncommon and Epic. Unrecognised rarities sort last rather than being ranked. | |
| Card language preference | French → Japanese → English → Korean, then the other seven | As specified for the first four. A preference, not a claim: what a given source can actually serve is its own capability, and the set list only offers languages that set was really printed in. |
| Seller country, minimum condition | **Unset** | Buying preferences, deliberately not chosen. |

---

## Known limitations

Things that are genuinely not done or not proven, stated plainly.

### Riftbound's CDN serves AVIF, and that broke images

Riot's image CDN picks an output format per asset and **ignores the `Accept` header**. For a
minority of Riftbound cards it answers the resized thumbnail URL with AVIF, which Skia cannot decode
— so those cards showed a broken-image mark on desktop and iOS while every other card worked, and
would also fail on Android below API 31. Because the response is a valid `200`, it was cached, and
no amount of HTTP retrying helps: the request never failed.

Two changes: thumbnails now pin `fm=webp`, which removes the negotiation entirely and happens to be
about ten times smaller than PNG; and a failed image makes one automatic attempt that **evicts the
memory and disk entries first**, which is what actually cures an undecodable cached response. On the
large detail image, where there is room for it, a retry button appears if that also fails.

The full-resolution URL is untouched — it carries no resize parameter and the CDN always answers it
with the original PNG.

### Riftcodex sends some printings twice

Riftcodex's **Vendetta** returns 358 card records for 227 distinct `riftbound_id`s — 37% of the set
is sent twice, each copy under its own database id, so nothing downstream can tell the copies apart
by id. Origins, Unleashed, Spiritforged and Proving Grounds have none of this.

The repository collapses them, but only on evidence: two records merge when they share the
provider's *own* per-printing key, never because they share a name or a collector number. Origins
299 is two genuinely different cards with the same number and name, and it must stay two. A provider
that declares no printing key is never de-duplicated at all.

Where two copies disagree — Vendetta ships `ven-019a` once flagged alternate art and once not — the
copy asserting a treatment wins, because `true` is a statement and `false` is indistinguishable from
a field nobody filled in.

### Set symbols exist for three games, and the game marks are not logos

**Sets.** Three of the seven sources publish artwork for a set, and the set list uses it:

| Source | What it publishes | Coverage |
|---|---|---|
| Scryfall | the real set symbol, as SVG | 988 of 988 paper sets |
| TCGdex | the set's logo | 157 of 218 sets |
| YGOPRODeck | the set's box art | many sets |

Scryfall's symbols carry no `fill` at all, so they render in the SVG default of black and are
recoloured to the theme's foreground or they vanish on a dark background. The other two are
full-colour and are never recoloured. TCGdex also advertises a `symbol` URL, which would suit a
small tile better than a wordmark — its CDN serves nothing for it, checked across several sets with
`.png`, `.webp` and `.jpg`, and a live test asserts that so the mapper can switch the day it starts
working.

The other four sources publish nothing, and so do the sets those three skip. Those rows fall back
to the set's short code (`OGN`, `SFD`) in a tile tinted with a colour derived from that code, so a
catalogue of several hundred Magic sets is not a column of identical grey squares. The colour is a
hash, not a symbol: derived rather than random, so a set looks the same on every launch and every
device, but it carries no meaning.

**Games.** The game picker uses Material symbols, not publisher logos. Every game here is somebody's
trademark and this app is affiliated with none of them; shipping their brand assets would be both a
licensing problem and the same category of dishonesty as inventing card data. Each game gets a
distinct icon and colour, which is enough to tell seven rows apart without implying any of them is
official.

### There is no higher-resolution card art

Riftbound card assets are 744x1040, and that is the ceiling. The CDN will happily resize one *up* —
`w=1488` returns a 1488x2080 PNG of 5 MB — but it is interpolating, and badly: measured against a
real asset, its 1488 render has a high-pass variance of 94 against 122 for a plain Lanczos upscale
of the native image, and the two differ by a mean of under 4/255. It is softer than what the client
would produce itself.

So the app asks for the native width and no more. Zooming past roughly 2x is soft because the source
is soft at that magnification, not because of how it is being drawn.

What *was* a rendering fault: Coil sizes a request from the layout by default, so the detail
screen's 420 dp box received a ~420 px bitmap and zooming magnified that rather than the card. Both
the inline image and the fullscreen viewer now decode at source resolution.

### Not verified

- **iOS has never been compiled.** No Mac, no Xcode, and Kotlin/Native cannot target Apple platforms
  from Windows. The Kotlin and Swift are written; whether they link is unknown. There is no
  `.xcodeproj` — see [`iosApp/README.md`](iosApp/README.md) for why, and for the setup steps.
- **The Android APK was never installed or run.** It builds; no device or emulator was available, so
  nothing about its runtime behaviour has been observed. Desktop working proves nothing about it.
- **Non-Latin text rendering is untested in practice.** French accents render correctly on desktop
  and are covered by tests (accent folding in search and sorting). Japanese and Korean cannot be
  tested because no integrated provider supplies any — there is no such text to render.

### Cardmarket

- Cardmarket serves 403 to non-browser clients, so nothing could be verified by fetching it. The URL
  shapes used were confirmed by inspecting live pages in a real browser.
- **Card-level slugs are not generated.** A real one looks like `KaiSa-Survivor-V1-Epic` — it folds
  in an apostrophe-stripped name, an abbreviated subtitle, a variant ordinal and the rarity.
  Riftcodex publishes no variant ordinal at all, so synthesising a slug would 404 for every card
  whose name is not a single plain word. The app links to a *search scoped to the expansion*
  instead, using the expansion id Riftcodex already supplies — which is precise enough to land on
  the card's own printings.
- **Only six query parameters are used**, all seen on a working Cardmarket URL: `searchMode`,
  `idCategory`, `idExpansion`, `searchString`, `idRarity`, `perSite`. No language, condition, finish
  or seller-country preset — none has been observed surviving a page load.
- An expansion slug is only derived when the set name is a single word. "Origins: Proving Grounds"
  could be hyphenated, truncated or abbreviated on Cardmarket's side, so those sets fall back to the
  game page rather than guessing.
- **The button appears for Riftbound only.** Cardmarket's per-game path segment could not be
  verified for the other six, because 403 applies to every scripted request — including one for the
  Riftbound path that is known to work. "Pokemon", "Magic" and "YuGiOh" are all plausible and all
  unverified, and a plausible-looking button that lands on a 404 is worse than no button. Each is a
  one-line change in `CardmarketLinkBuilder.gameSlug` the moment a real URL is seen. Wuthering Waves
  is a separate case: Cardmarket has no section for it, because the game is Japan-only so far.
- Scryfall and TCGdex both publish Cardmarket *product ids*, and TCGdex's are per printing. They are
  stored on the card for provenance and no URL is built from them, because this app only constructs
  Cardmarket links from path shapes it has actually seen.
- Opening a link never places an order. Cardmarket is outbound navigation, not an API dependency.

### Scope

- Browsing only. No favorites, wishlists, collection counts, accounts, sync, prices, scanning or
  deckbuilding.
- Desktop packaging is configured as an app image but distribution, signing and store publication
  are out of scope.
- Every game is served by exactly one provider, so the routing table's language-specific route slot
  — a second source filling another's language gap — is exercised by tests but not by a real
  deployment.
- **Altered's grid tiles are expensive.** Its mirror has no resized variant, so a grid tile loads
  the same 200–300 KB JPEG the detail screen does. Every other provider serves a small variant. The
  artwork records this by leaving `thumbnailUrl` null rather than pointing it at the full image.
- **Wuthering Waves is a snapshot, not a live source.** Its list endpoint carries six fields per
  card, so rarity, attribute, cost and rules text had to be fetched one card at a time — 357
  requests to describe a game that ships 128 printings across three languages. That catalogue is now
  scraped once, aligned across the locales, and committed as
  [`wuwa-cards.json`](providers/wuwa/src/commonMain/composeResources/files/wuwa-cards.json) — a Compose Multiplatform resource, so it ships on every
  target including iOS. The adapter makes no
  requests for card data at all and works offline from a cold start. The cost is that a new set needs
  `python providers/wuwa/tools/scrape_wuwa.py --refresh` and a rebuild. The game gets one perhaps
  twice a year, and `./gradlew :providers:wuwa:liveProviderTest` asks UCP whether the file is stale.
- **Cardmarket links exist for Riftbound only.** Cardmarket answers 403 to every scripted request,
  including one for the Riftbound path that is known to work, so the other six games' path segments
  could not be verified. Plausible guesses are not shipped; see [Cardmarket](#cardmarket).
- The LRU ordering of the metadata cache restarts with the process. The size ceiling always holds;
  what resets is which entry is considered least recently used. Tracking access times in memory is
  deliberate — several platforms do not update file access time on read, which would silently turn
  "least recently used" into "least recently written".

---

## Attribution

Every provider's notice is shown in the app — on the game picker, on card detail and in settings.
This app is not affiliated with any game's publisher.

| Game | Data from |
|---|---|
| Riftbound | **Riftcodex**, an unofficial fan project not affiliated with Riot Games |
| Pokémon | **TCGdex**, not affiliated with Nintendo, Creatures or GAME FREAK |
| Magic: The Gathering | **Scryfall**, not affiliated with or endorsed by Wizards of the Coast |
| One Piece | **OPTCG API**, not affiliated with Bandai or Eiichiro Oda |
| Altered | the community **Altered TCG Card Database**; Altered is a trademark of Equinox |
| Yu-Gi-Oh! | **YGOPRODeck**, not affiliated with Konami |
| Wuthering Waves TCG | **UCP**'s official card list; not affiliated with UCP or Kuro Games |

### Game logos

All seven games show their real logo in the picker, but they do not all come from the same place
and the difference matters.

**Five from Wikimedia Commons**, which is what makes bundling them possible: Commons accepts only
freely-licensed media, whereas a logo merely *shown* on Wikipedia normally lives there under a
non-free fair-use rationale that does not permit redistribution. Each licence was checked
individually through the Commons API:

| Game | Licence | Attribution |
|---|---|---|
| Pokémon | Public domain (trademarked) | not required |
| Magic: The Gathering | Public domain (trademarked) | not required |
| Yu-Gi-Oh! | CC BY 3.0 | **required** — Kazuki Takahashi, credited in the app |
| One Piece | Public domain (trademarked) | not required |
| Wuthering Waves | Public domain (trademarked) | not required |

"Public domain, trademarked" is the normal state of a wordmark: nobody holds a copyright in it, so
the file may be redistributed, while the trademark still belongs to its owner. Using it to identify
that owner's game is what a trademark is for.

**Two supplied by the project owner.** Riftbound and Altered exist on neither Commons nor English
Wikipedia under any name I searched — both games are recent enough that no freely-licensed mark has
been uploaded, and the only Wikipedia files are a cover and a card back under non-free fair-use
rationales. The two bundled here were chosen by the owner of this project from third-party sites (a
card shop's CDN and a retailer's blog) and downloaded on request.

**No licence was verified for those two, because there is none to verify.** They are publishers'
trademarks used to identify the publishers' own games — the ordinary nominative use every card
database relies on — but anyone redistributing this app should form their own view rather than
assume they carry the clearance the five Commons files do.

Two presentation rules, both driven by the artwork rather than by taste:

- The Wuthering Waves mark is a solid black wordmark, so it is drawn in the theme's foreground
  colour — its own black on the light theme, inverted to white on the dark one, which is how the
  official mark is presented against dark backgrounds. Not the row accent: a teal Wuthering Waves
  logo is not its logo.
- Altered, One Piece and Riftbound have no dark outline and wash out on a light background — of
  their visible pixels, 49%, 76% and 31% respectively fall below a 2:1 contrast ratio against a
  pale tile, and Riftbound's are concentrated entirely in its "League of Legends" subtitle, which
  vanishes. Those three keep a dark tile on both themes. Magic, Pokémon and Yu-Gi-Oh are not
  flagged despite similar raw figures, because their dark outlines carry the shape.
