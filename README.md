# CardBrowser

A Kotlin Multiplatform card-set browser for **Riftbound**, sharing Compose Multiplatform UI and all
application logic between Android, iOS and JVM desktop.

Browse Riftbound's sets, open one, filter its cards, and read a card in detail. No backend: the apps
talk to a card database directly over Ktor. Browsing only — no accounts, no collection, no prices,
no deckbuilding.

`CardBrowser` is a temporary name.

---

## Contents

- [Status](#status)
- [Prerequisites](#prerequisites)
- [Build, run, test](#build-run-test)
- [Verified dependency versions](#verified-dependency-versions)
- [Provider coverage](#provider-coverage-riftcodex)
- [Chosen defaults](#chosen-defaults)
- [Known limitations](#known-limitations)
- [Architecture](docs/ARCHITECTURE.md)

---

## Status

| Target | State |
|---|---|
| **JVM desktop** | Built, launched, browsed. Real sets and real cards on screen. |
| **Android** | Debug APK builds (23 MB). **Not installed or run** — no device or emulator was available. |
| **iOS** | Kotlin and Swift written. **Never compiled.** No Mac, no Xcode. See [`iosApp/README.md`](iosApp/README.md). |

116 deterministic tests and 5 live-API smoke checks pass. See [Build, run, test](#build-run-test).

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
excluded from the ordinary run and cannot fail a build because Riftcodex is down.

```bash
./gradlew :providers:riftcodex:liveProviderTest
```

These are the checks that catch the provider changing shape underneath us — including a full
round trip through the real API, the real disk cache, a local filter, and an offline replay.

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

## Provider coverage: Riftcodex

Riftbound data comes from [Riftcodex](https://riftcodex.com), an unofficial community database with
no affiliation to Riot Games. Verified against its OpenAPI document (version 0.2.0, self-described
as "an active work in progress") and against live responses for every set it serves.

**What it gives.** 8 sets with names, codes, card counts, publication dates and Cardmarket
*expansion* ids. Cards with name, collector number, energy/might/power, type, supertype, rarity,
domains, tags, rules text, flavour text, artist, orientation and an image URL. No auth needed.

**What it does not give**, and what this app does about it:

| The brief asks for | Riftcodex reality | What the app does |
|---|---|---|
| French / Japanese / English / Korean printings | **No language field exists anywhere in the schema.** The data is English. | English is reported *confirmed*; FR, JA and KO are reported **unknown, never unavailable**. The detail screen says so in words. Selecting French shows English and labels it as English. |
| Finish options | **No finish field exists.** | Finish coverage is *unstated* for every card. The detail screen explains that foils may exist and the provider simply does not record them. No finish filter is offered. |
| Card identity across printings | **None.** Every record is one printing; `riftbound_id` is unique per printing and the lookup by it returns a single record. | `identity` is null. The detail screen says other artworks cannot be listed, rather than showing an empty section. No identity is inferred from names. |
| Card → Cardmarket product | **Expansion ids on sets only.** | The Cardmarket button is a scoped *search*, not a product page — see below. |
| Filtering | Remote: set, free text, a "new" flag. **Domain, type, rarity and energy have no query parameter.** Page size capped at 100. | Those four are declared `localOnly`, so the repository fetches every page of the set before filtering and marks results partial if it could not finish. |

Two things worth knowing about the real data, both of which shaped the model:

- **Collector numbers are not unique within a set.** Origins 299 is both "Kai'Sa (Overnumbered)" and
  "Kai'Sa (Signature)"; the integer `collector_number` is 299 for both. Only `riftbound_id`
  distinguishes them, as `299` and `299*`. The app therefore treats collector numbers as strings,
  takes them from `riftbound_id`, and sorts them naturally so `299*` follows `299` and `10` follows
  `2`.
- **Card art is on Riot's Sanity CDN, which resizes.** Appending `w=320` turns a 1.1 MB image into
  310 KB, which is the difference between a 352-card grid being usable on a phone and not. The grid
  uses thumbnails; only the detail screen loads full resolution.

### Later provider candidates — documented only, not implemented

Not verified beyond knowing they exist, and Korean coverage is unknown for all three:

- **Scryfall** for Magic: The Gathering
- **TCGdex** for Pokémon
- **OPTCG API** for One Piece

`Game.MAGIC`, `Game.POKEMON` and `Game.ONE_PIECE` exist as enum entries so the routing table has
something to name. They have no adapter and no route, so **the UI never shows them** — the set list
is Riftbound because that is the only game with a registered provider.

---

## Chosen defaults

Where the brief left a choice, these were taken. All are one edit to change.

| Choice | Value | Why |
|---|---|---|
| Metadata cache ceiling | 32 MB | The largest set is 358 cards ≈ 460 KB of JSON, so this holds every Riftbound set several times over. |
| Image cache ceiling | 128 MB | A thumbnail is ~300 KB and a full card ~1.1 MB. Holds a few sets browsed plus the cards actually opened. A real run of Origins used 82 MB for 352 thumbnails. |
| Set list freshness | 24 hours | Set catalogues change when a set is announced. |
| Card data freshness | 24 hours | Stale data still displays immediately; this only governs when a refresh is attempted. |
| Grid tile minimum width | 108 dp | Columns adapt to the window; this keeps art legible on a phone. |
| Search debounce | 300 ms | |
| Retries | 2 extra attempts, exponential, transient failures only | A 4xx is never retried. |
| Default sort | Natural collector number | |
| Card language preference | French → Japanese → English → Korean | As specified. A preference, not a claim. |
| Seller country, minimum condition | **Unset** | Buying preferences, deliberately not chosen. |

---

## Known limitations

Things that are genuinely not done or not proven, stated plainly.

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
- Opening a link never places an order. Cardmarket is outbound navigation, not an API dependency.

### Scope

- Browsing only. No favorites, wishlists, collection counts, accounts, sync, prices, scanning or
  deckbuilding.
- Desktop packaging is configured as an app image but distribution, signing and store publication
  are out of scope.
- Only one provider exists, so the routing table's language-specific route slot is exercised by
  tests but not by a real second source.
- The LRU ordering of the metadata cache restarts with the process. The size ceiling always holds;
  what resets is which entry is considered least recently used. Tracking access times in memory is
  deliberate — several platforms do not update file access time on read, which would silently turn
  "least recently used" into "least recently written".

---

## Attribution

Card data from **Riftcodex**, an unofficial fan project not affiliated with Riot Games. The
attribution is shown in the app, on card detail and in settings.
