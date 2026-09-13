# Architecture

How CardBrowser is put together. The provider and game contracts have their own documents —
[PROVIDERS.md](PROVIDERS.md) and [GAMES.md](GAMES.md) — and this one covers everything around them:
the modules, how data flows from a source to a screen, what is cached where, and the pattern every
screen follows.

---

## Modules

24 Gradle modules. The shape is one module per *thing that can be swapped*: a game, a source, the
store.

```
:core         The domain. Cards, sets, languages, availability, the GameProfile and CardProvider
              contracts. No Ktor, no Okio, no Compose, and no game or provider names.

:data         Everything between a source and a screen: the Ktor stack and its per-source policy,
              the metadata file cache, preferences, the repository, the download queue. No Compose.

:database     The SQLite card store. Schema, per-platform driver, eviction. No game names.

:games:api    GameArt, and with it the Compose resources dependency that :core must not have.
:games:*      Ten modules, one per game: a GameProfile, a GameArt, a logo.

:providers:*  Eight modules, ten adapters. Endpoints, DTOs, mapping, and that source's quirks.

:composeApp   Screens, Pulse view models, Koin wiring, navigation.
:androidApp   An Activity and an Application. Nothing else.
```

Dependencies point inwards. `:providers:*` and `:games:*` depend on `:core`; `:composeApp` depends
on everything; nothing depends on `:composeApp`.

`LayeringTest` scans `:core` and `:data` with comments stripped and fails if either mentions a game
or a source by name.

---

## The five distinctions the model keeps apart

These are in `:core` and everything else follows from them.

**A claim is not a confirmation.** `ProviderCapabilities.data.languages` is what a source says it
can serve. `CardPrinting.languages.confirmed` is what it actually served. A menu built from the
first is a menu of things worth asking for; a list built from the second is a fact.

**Availability is three-valued.** `LanguageCoverage` and `FinishCoverage` carry `confirmed` and
`absent` sets, and anything in neither is *unknown*. A source with no language field is not telling
you a French printing does not exist. Collapsing this to a boolean is how a browser starts lying.

**Ids carry their provider.** `SourceId(ProviderId("scryfall"), "vow")` renders as
`scryfall:vow`. Every id in the app — sets, cards, cache keys, store rows — is source-qualified, so
two sources can never collide and any id can be routed back to the adapter that issued it without a
lookup table.

**A game fact is not a source fact.** See [GAMES.md](GAMES.md).

**A page is not a set.** `CardPage` is one response. `CardSet` plus a complete card list is a set.
Only the second is cached, and only the second can be filtered honestly.

---

## Data flow

```
CardProvider  ──▶  CardRepository  ──▶  PulseViewModel  ──▶  XContent
   (a source)        (cache + store)       (UiState)          (Compose)
```

`CardRepository` is the only thing that knows both a provider and a cache exist. It returns
`DataSnapshot<T>` rather than `Result<T>`:

```kotlin
data class DataSnapshot<T>(val value: T?, val origin: DataOrigin, val isStale: Boolean, val error: ProviderError?)
```

A `Result` cannot express the case this app is full of — **here is a cached answer and the refresh
failed** — which is two facts the screen has to show at once: the data, and a banner saying it is
not fresh. Origin is `NONE`, `CACHE` or `NETWORK`.

Reads are progressive. A cached set emits immediately, then the network answer replaces it if one
arrives. A view model guards on a generation counter so a slow response from an earlier request
cannot overwrite a newer one.

### Completeness

`CardCollection.isCompleteSet` is true only when every card of the set is present. It gates:

- whether filters can be applied honestly, or the results must be labelled partial;
- whether the facets that fill the filter sheet are computed at all;
- whether the set counts as downloaded.

`FilterSupport.requiresCompleteSet(query)` answers whether the query needs it.

---

## Storage: two caches and a store

| | What | Where |
| --- | --- | --- |
| **Metadata cache** | Set lists, card detail, per-set language confirmations. Small, many scopes, each with a TTL. | JSON files via Okio, `MetadataCache` |
| **Card store** | Complete sets and everything derived from them: pins, counts, the eviction budget, cross-set search. | SQLite via SQLDelight, `:database` |
| **Image cache** | Card art and thumbnails. | Coil's own disk cache, LRU |

The split is measured, not assumed — [database/README.md](../database/README.md) has the benchmark
that decided it. The short version: the store is 8× slower to write a catalogue and 170× faster to
count one, and cross-set search is not possible without it.

Both are per-language. A cache key embeds the language, so records fetched as `fr` and read as `en`
are different files — which is a bug this codebase has had, and the reason `CardRepository`
normalises the language once, for every caller, against what the provider will really answer in.

Eviction is a budget over the store's unpinned bytes. Pinning a set exempts it.

A **bulk import** is a different path: `BulkCatalogue.streamAll` reads a source's own dump and
writes complete sets straight into the store, skipping the per-set API entirely. It is not a
catalogue — a dump carries digital-only products a `listSets` drops, so counting sets from a file
against sets from a catalogue compares two different populations.

---

## Presentation: Pulse MVI

Every screen is a `ContainerContract` (a pure `reduce(state, intent)`) plus a `PulseViewModel`
(side effects, and `emitEffect` for one-shot events).

**Every screen is a `Screen` and a `Content`:**

```kotlin
@Composable
fun XScreen(onBack: () -> Unit, viewModel: XViewModel = koinViewModel()) {
    viewModel.collectEffect { effect -> /* the only place that knows a back stack exists */ }
    XContent(viewModel.collectAsStateWithLifecycle().value, viewModel::dispatch)
}

@Composable
fun XContent(state: XState, dispatch: (XIntent) -> Unit) { /* pure */ }
```

A `Content` takes a state and a dispatch and nothing else. No Koin — a preview has no graph and
`koinInject` throws. **Navigation is dispatched, not called**: threading an `onBack` through a body
splits one interaction across two mechanisms, which is exactly how opening a set once both
remembered the set and navigated, by two different routes.

Navigation is Navigation 3: a `SnapshotStateList<Route>` of `@Serializable` routes, with
`rememberSaveableStateHolderNavEntryDecorator` and `rememberViewModelStoreNavEntryDecorator` so a
screen's view model and saved state survive a trip into a detail and back.

Screens: game list, set list, card grid, card detail, downloads, storage, settings, first-launch
setup.

### Transitions

- **Sets → cards** stands its screen transition down entirely, because a shared container grows out
  of the tapped row and a cross-fade over the top of that reads as two unrelated animations that
  happen to overlap.
- **A lateral slide is the app's answer for "went deeper with no shared element"** — games → sets,
  and the global search, which opens the card grid from a bar button with no row to grow out of.
- **Everything else** cross-fades, with `sizeTransform = null` — the default live `SizeTransform`
  clips its content and cuts a shared element's flight in half.

---

## Where the load-bearing behaviours live

| Behaviour | File |
| --- | --- |
| Three-valued availability | `core/…/model/Card.kt` — `LanguageCoverage`, `FinishCoverage` |
| Language resolution and preference order | `core/…/model/Language.kt`, `CardProvider.resolveLanguage` |
| Which filters a source can honour | `core/…/provider/CardProvider.kt` — `FilterSupport` |
| Cached-plus-failed-refresh | `data/…/repository/DataSnapshot.kt` |
| Language normalisation before a cache key | `data/…/repository/CardRepository.kt` |
| Complete sets, search, eviction | `database/…/SqlCardStore.kt` |
| The download queue | `data/…/download/DownloadManager.kt` |
| Partial-result and coverage notices | `composeApp/…/ui/cards/CardGridContract.kt` |
| Filter sheet, chips and menus | `composeApp/…/ui/cards/FilterSheet.kt` |
| Cardmarket URLs | `core/…/cardmarket/CardmarketLinks.kt` |

---

## Adding things

- A provider: [PROVIDERS.md § Adding a provider](PROVIDERS.md#adding-a-provider).
- A game: [GAMES.md § Adding a game](GAMES.md#adding-a-game).
- Artwork: a game's logo belongs to the game module; a set's symbol comes from the provider. The
  test is whether swapping the provider would change the image.
