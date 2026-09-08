# Architecture

How CardBrowser is laid out, and what it costs to add a provider.

---

## Modules

Five, chosen so that each boundary stops something specific from leaking. Not one per class.

```
:core                    domain vocabulary + the provider contract.
                         No Ktor. No Okio. No Compose.
   ↑
:data                    HTTP stack, the two caches, preferences, repositories.
   ↑                     No Compose.
:providers:riftcodex     one adapter: endpoints, DTOs, mapping, its own quirks.
   ↑
:composeApp              Compose screens, Pulse view models, Koin wiring.
   ↑                     Targets android + desktop + iosArm64/iosSimulatorArm64.
:androidApp              an Activity and an Application. Nothing else.
```

`iosApp/` holds the Swift shell; the desktop entry point is `composeApp/src/desktopMain`.

**Why `:core` has no dependencies worth speaking of.** A provider adapter compiles against `:core`
and therefore inherits none of the app's transport or storage choices. A future adapter that wants a
different HTTP client is not fighting the module graph to get one.

**Why `:androidApp` is separate.** AGP 9 refuses `com.android.application` in the same subproject as
the Kotlin Multiplatform plugin, and `com.android.library` is deprecated for KMP and slated for
removal in AGP 10. The shared modules use `com.android.kotlin.multiplatform.library`.

---

## The five concepts the model keeps apart

Conflating any two of these is how a card browser starts lying to people.

| Concept | Type | Note |
|---|---|---|
| Game and regional release | `Game`, `CardSet` | Regional releases stay separate unless a provider states an explicit mapping. |
| Card identity | `CardIdentity?` | **Nullable.** Present only when a provider states which printings are the same card. Never inferred from a name. |
| Set printing + collector number | `CardPrinting` | The unit the grid and detail screen show. Collector numbers are **strings**. |
| Artwork / treatment | `Artwork`, `ArtworkTreatment` | One tile per distinct artwork. |
| Finish | `Finish`, `FinishCoverage` | A choice inside detail, never a separate tile. |
| Printing language | `CardLanguage`, `LanguageCoverage` | Also a choice inside detail. |

### Availability is three-valued, and this is load-bearing

```kotlin
enum class Availability { AVAILABLE, UNAVAILABLE, UNKNOWN }
```

`UNKNOWN` is the whole reason the type exists. A provider with no language field is not telling us a
French printing does not exist — it is telling us nothing. Collapsing `UNKNOWN` into `UNAVAILABLE`
would have the app assert something it cannot know; collapsing it into `AVAILABLE` would offer a
selection it cannot honour. `LanguageCoverage` and `FinishCoverage` therefore carry *confirmed* and
*absent* sets separately, and anything in neither is unknown.

The UI reads this directly: only `AVAILABLE` is selectable, `UNAVAILABLE` shows as "not printed",
and `UNKNOWN` shows as "unknown" with a sentence explaining that the database does not record it.

### Ids carry their provider

```kotlin
SourceId(provider = ProviderId("riftcodex"), local = "OGN").qualified  // "riftcodex:OGN"
```

Two providers may both call a set `OGN`. Nothing in this app compares ids across providers, and this
type is what makes that enforceable rather than merely intended — a bare provider-local string never
escapes an adapter. There is no global or cross-provider identity anywhere.

---

## The provider contract

[`CardProvider`](../core/src/commonMain/kotlin/com/bitsycore/cardbrowser/core/provider/CardProvider.kt)
is four members:

```kotlin
val id: ProviderId
val capabilities: ProviderCapabilities
suspend fun listSets(game: Game): List<CardSet>
suspend fun listCards(request: CardPageRequest): CardPage
suspend fun cardDetail(id: SourceId): CardPrinting?
```

### Capabilities describe the source, coverage describes the fact

`ProviderCapabilities` is the ceiling: what this provider *can* supply, stated once. Whether a given
printing actually exists in French is `LanguageCoverage` on that printing. The UI uses capabilities
to decide which controls to draw at all, and coverage to decide what each one may claim.

The most consequential field is the filtering split:

```kotlin
FilterSupport(
    remote   = setOf(TEXT),                                  // the provider does these
    localOnly = setOf(DOMAIN, CARD_TYPE, RARITY, ENERGY_COST, ARTWORK_TREATMENT),
)
```

A field in neither set is **not offered by the UI at all**. That is how Riftcodex ends up with no
finish and no language filter without a single `if (provider is Riftcodex)` anywhere.

### Errors, and cancellation

`ProviderError` is a closed set — `Offline`, `Timeout`, `RateLimited`, `ServerError`, `BadRequest`,
`MalformedResponse`, `Unknown` — with an `isTransient` flag that governs both retries and whether
the UI offers a "try again" button.

**Cancellation is deliberately not in that set.** `mapProviderErrors` rethrows
`CancellationException` first and untouched. A cancelled request is not a failed one: when the user
changes set, the in-flight load for the previous set unwinds through there, and turning that into an
error would paint a failure over the screen they just opened.

---

## Adding a provider

Four things. Nothing else, and in particular no change to any screen.

### 1. The adapter

A new module under `providers/`, applying the same three plugins as
`providers/riftcodex/build.gradle.kts`, depending on `:core` (api) and `:data` (implementation).

Inside it: DTOs, a mapper, and a `CardProvider`. **DTOs never leave the module** — nothing above it
should know a field is called `riftbound_id`. Use `mapProviderErrors` so its failures speak the same
vocabulary as everything else.

Declare capabilities honestly. If the provider has no finish field, `finishes = false` and every
printing gets `FinishCoverage()`. Do not invent a non-foil default.

### 2. Koin registration

One line in [`AppModule.kt`](../composeApp/src/commonMain/kotlin/com/bitsycore/cardbrowser/di/AppModule.kt):

```kotlin
single<CardProvider> { ScryfallProvider(mClient = get()) }
```

`ProviderRegistry` is built from `getAll<CardProvider>()`, so it picks the new one up.

### 3. A routing entry

In the same file:

```kotlin
val providerRoutes = listOf(
    ProviderRoute(game = Game.RIFTBOUND, provider = RiftcodexProvider.PROVIDER_ID),
    ProviderRoute(game = Game.MAGIC,     provider = ScryfallProvider.PROVIDER_ID),
)
```

A second source for a game that already has one, filling a language gap, is the same shape with a
`language`:

```kotlin
ProviderRoute(Game.RIFTBOUND, SomeKoreanSource.PROVIDER_ID, language = CardLanguage.KOREAN)
```

Resolution picks a language-specific route when one matches and the game-wide route otherwise.
**One route wins and that is the answer.** There is no merging of two sources into one result list
and no failover when the first errors — either would make it impossible to say honestly where a
record came from, and a failover would quietly change the meaning of every id on screen.

`ProviderRegistry.games` is derived from the routing table, so a game with no route never appears in
the UI. There are no dead menu entries.

### 4. Contract and mapping checks

Copy the shape of `RiftcodexProviderTest`: real captured JSON, id distinctness, coverage claims
matching what the provider actually sends, pagination, and the error mapping. Add a
`*LiveSmokeTest` under `desktopTest` plus a `liveProviderTest` task; the ordinary test run excludes
them by name.

### What you do *not* have to touch

`SetListScreen`, `CardGridScreen`, `CardDetailScreen`, `FilterSheet`, `CardRepository`,
`MetadataCache`. A second provider for an existing game reaches the screens through the same
`CardPrinting` and the same capabilities, and the filter sheet redraws itself from what the new
provider declares.

A **new game** is more than this: it will usually need game-specific filter definitions (Riftbound's
"domain" and "energy" are not Magic's "color" and "mana value") and some presentation of its own.
That is expected, and is why `CardFilterField` is an enum rather than a free-form string.

---

## Data flow

```
CardProvider ──► CardRepository ──► DataSnapshot<T> ──► PulseViewModel ──► Screen
                      │
                      ├─ MetadataCache  (Okio, LRU, bounded, atomic writes)
                      └─ Coil DiskCache (separate, separately bounded)
```

### `DataSnapshot`, and why it is not a `Result`

```kotlin
data class DataSnapshot<T>(
    val value: T?, val origin: DataOrigin, val completeness: Completeness,
    val fetchedAtEpochMillis: Long?, val isStale: Boolean, val error: ProviderError?,
)
```

The state this app spends most of its time in is *"here is cached data **and** the refresh failed"*.
A type that forces a choice between a value and an error cannot express it, and collapsing it either
throws away usable data or hides a failure.

`cards()` and `setList()` are flows that emit **at most twice**: the cached value, then the network
result. A failed refresh re-emits the cached value with the error attached.

### The completeness rule

The single most important behaviour in `CardRepository`.

Riftcodex filters remotely by text and nothing else. A filter on rarity therefore has to run
locally — and running it against one page would produce results a user would reasonably read as
"the whole set", which they are not.

So when a query needs a filter the provider cannot apply, the repository fetches **every page** of
the set, caches it as `CacheScope.CompleteSet`, and filters that. When it cannot finish, it returns
what it has with `isCompleteSet = false` and the real set size beside it, and the grid says:

> Filtered from 200 of 352 downloaded cards — not the whole set.

A page is cached under `CacheScope.CardPage` with a query fingerprint, and a complete set only under
`CacheScope.CompleteSet` with no query at all. Filing a filtered page where a complete set is
expected is exactly the bug that would make every later filter silently wrong, so the scopes are a
sealed type rather than a string.

There is one further guard, added after a real failure: if the provider reports a total and the
collected cards fall short of it, the result is marked partial no matter how cleanly the paging
ended. A wrong `set_id` once produced `200 OK` with zero cards and `hasMore = false`, which paged
"successfully" to nothing and was cached as a complete empty set.

### The cache

Two caches, separate on purpose. Metadata is small, cheap to refetch, and what makes offline
browsing work; images are most of the bytes and the first thing worth dropping. Preferences live
under a **different root entirely**, so "clear cache" cannot take the user's choices with it.

`MetadataCache` guarantees:

- **Interrupted writes cannot corrupt a good record.** Write to a temp file, then `atomicMove`. A
  process killed mid-write leaves a stray temp file, which the next `trim()` removes; the previous
  record is untouched.
- **A corrupt or incompatible record reads as a miss, never a crash.** Truncated, unparseable, or
  written by an older `schemaVersion` — all three delete the file and report absent, so the app
  refetches. Bumping `CURRENT_SCHEMA_VERSION` *is* the migration.
- **Disk use is bounded**, evicting least-recently-*used*. Access times are tracked in memory rather
  than read from the filesystem, because several platforms do not update access time on read and
  that would silently turn LRU into "least recently written".

Every record carries source, language, query/pagination scope, fetch time, schema version and
completeness — see `CacheEnvelope`. A cache that stores only the data cannot answer "is this stale",
"did this come from the provider I am now asking", or "was this the whole set".

---

## Presentation: Pulse MVI

Each screen is a `ContainerContract` object plus a `PulseViewModel`.

- **The reducer lives on the contract** — `ContainerContract.reduce(state, intent)`. Pure, total,
  synchronous. (The library README shows it on the view model; the published source has it here.)
- **Async work lives in `handleIntent`** on the view model.
- **State is `stateFlow`**, collected with `collectAsStateWithLifecycle()`.
- Effects are one-shot, via `emitEffect` / `collectEffect`.

Because the reducer is a pure function, the awkward cases are tested directly rather than by
orchestrating coroutines and hoping a race reproduces — see `CardGridContractTest`.

### Stale responses cannot overwrite a newer selection

Three mechanisms, because none alone is enough:

1. **Debounce** — text input waits 300 ms, so typing "Annie" is one load, not five.
2. **Cancellation** — starting a load cancels the previous job; `collectLatest` does the same within
   a flow.
3. **Generation tagging** — every state change that starts a load bumps `requestGeneration`, and
   every response carries the generation it was started for. The reducer discards anything from a
   superseded generation.

The third is the one that actually guarantees correctness: a response already past the point of
cancellation still arrives, and without a generation tag it would repopulate a grid the user has
just cleared.

---

## Where each brief-critical behaviour lives

| Behaviour | File |
|---|---|
| Unknown ≠ unavailable | `core/model/Identity.kt`, `core/model/Language.kt` |
| French request → English shown, labelled | `ui/detail/CardDetailContract.kt` (`languageResolution`) |
| Collector numbers as strings, natural sort | `core/model/Card.kt` (`CollectorNumberComparator`) |
| Remote vs local filtering | `core/provider/CardProvider.kt` (`FilterSupport`) |
| Partial-set honesty | `data/repository/CardRepository.kt`, `ui/cards/CardGridContract.kt` (`coverageNotice`) |
| Stale response suppression | `ui/cards/CardGridContract.kt` (`requestGeneration`) |
| Corruption recovery, LRU, atomic writes | `data/cache/MetadataCache.kt` |
| Cardmarket URLs | `core/cardmarket/CardmarketLinks.kt` |
| Provider routing | `core/provider/ProviderRegistry.kt`, `di/AppModule.kt` |
