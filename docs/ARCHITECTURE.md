# Architecture

How CardBrowser is laid out, and what it costs to add a provider.

---

## Modules

Five, chosen so that each boundary stops something specific from leaking. Not one per class.

```
:core                    domain vocabulary + the provider contract + the GameProfile interface.
                         Names no game. No Ktor. No Okio. No Compose.
   ↑
:data                    HTTP stack, the two caches, preferences, repositories.
   ↑                     No Compose.
:games:api               what a game module implements beyond GameProfile: GameArt.
   ↑                     The one place Compose's resources runtime is allowed below the UI.
:games:*                 one module per game: its vocabulary, rarity ladder, Cardmarket
   ↑                     segment, logo and accent. riftbound, pokemon, magic, onepiece,
                         altered, yugioh, wutheringwaves, lorcana, cyberpunk, wowtcg.
                         Knows no endpoint.
:providers:*             one module per adapter: endpoints, DTOs, mapping, its own quirks.
   ↑                     riftcodex, tcgdex, scryfall, optcg, altered, ygoprodeck, wuwa,
                         tcgcsv. Each depends on exactly one :games:* module and names it
                         in its own type -- except :providers:tcgcsv, which is one adapter
                         over one API serving three games and therefore ships three
                         CardProvider classes keyed by TCGplayer category. None of them
                         knows another provider exists.
:composeApp              Compose screens, Pulse view models, Koin wiring.
   ↑                     Targets android + desktop + iosArm64/iosSimulatorArm64.
:androidApp              an Activity and an Application. Nothing else.
```

**Games and providers are different axes, and the module graph says so.** A game is what the *rules*
call things -- Riftbound's cost axis is Energy whoever supplies the data. A provider is where the
data comes from. Splitting them is what lets a second Riftbound source inherit the ladder, the
vocabulary and the marketplace slug for free, and it is why `:core` can hold the mechanisms without
holding a list of games.

**`:core` names no game.** There is no `Game` enum. A game's identity is a `GameId("riftbound")`
string declared by its module, and what games *exist* is whatever the routing table routes. That
replaced four separate tables keyed by a closed enum -- vocabulary, rarity ladders, Cardmarket
slugs and logos -- living in `:core` and `:composeApp` and all needing to be found when a game was
added. Three were exhaustive `when`s the compiler enforced; the fourth returned `null` for an
unknown game and failed silently.

The trade is stated plainly: dropping the enum drops compile-time exhaustiveness. What replaced it
is that there is nothing left to be exhaustive *over* -- every per-game fact now lives in the game's
own module, so a game cannot be half-added. The one thing a new module can still be left out of is
the art list in `AppModule`, and `AppModuleTest` asserts that instead.

`iosApp/` holds the Swift shell; the desktop entry point is `composeApp/src/desktopMain`.

**Why `:core` has no dependencies worth speaking of.** A provider adapter compiles against `:core`
and therefore inherits none of the app's transport or storage choices. A future adapter that wants a
different HTTP client is not fighting the module graph to get one.

**Why `:games:api` exists at all.** A game module bundles its own logo, and a bundled asset that
works on JVM, Android *and* both iOS targets means Compose Multiplatform resources -- Kotlin
Multiplatform has no standard resource API and a klib carries no files. That would drag the Compose
runtime into `:core` if `GameProfile` carried a `DrawableResource`, so it does not: `GameProfile` is
pure rules and lives in `:core`, and `GameArt` is presentation and lives here. It is a small module
whose entire job is to confine one dependency.

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
is small, and the game it serves is in its **type** rather than in a parameter:

```kotlin
interface CardProvider<out G : GameProfile> {
    val id: ProviderId
    val displayName: String
    val game: G
    val capabilities: ProviderCapabilities

    suspend fun listSets(language: CardLanguage?): List<CardSet>
    suspend fun listCards(request: CardPageRequest): CardPage
    suspend fun cardDetail(id: SourceId, language: CardLanguage?): CardPrinting?

    // Both defaulted, so an adapter implements one only when its source can do better
    // than the default. See § "Adding a provider".
    suspend fun confirmLanguages(setId: SourceId, candidates: Set<CardLanguage>): Set<CardLanguage>
    suspend fun searchAllSets(request: CardSearchRequest): CardPage
}
```

A source that can hand over its whole catalogue in one download additionally implements
[`BulkCatalogue`](../core/src/commonMain/kotlin/com/bitsycore/cardbrowser/core/provider/BulkCatalogue.kt),
which is optional and separate for exactly that reason: two of the eight sources have one.

### Capabilities describe the source, coverage describes the fact

`ProviderCapabilities` is the ceiling: what this provider *can* supply, stated once. Whether a given
printing actually exists in French is `LanguageCoverage` on that printing. The UI uses capabilities
to decide which controls to draw at all, and coverage to decide what each one may claim.

The most consequential field is the filtering split:

```kotlin
FilterSupport(
    remote    = emptySet(),                                  // the provider does these
    localOnly = setOf(TEXT, DOMAIN, CARD_TYPE, RARITY, COST),  // the app does these, in memory
)
```

A field in neither set is **not offered by the UI at all**. That is how a source with no finish
data ends up with no finish filter, without a single `if (provider is ...)` anywhere.

**No shipped adapter declares a remote filter**, and every one of them writes `remote = emptySet()`.
The mechanism is kept for a source that can genuinely narrow server-side, but the repository fetches
and caches a set whole for offline use, and filtering that in memory is instant where a round trip
per filter chip is not.

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

### 0. The game, if it is new

A game with no `:games:*` module gets one first: `settings.gradle.kts`, a build file copied from a
sibling, and one object.

```kotlin
object RiftboundGame : GameProfile {
	override val id = GameId("riftbound")
	override val displayName = "Riftbound"
	override val vocabulary = GameVocabulary(domain = "Domain", cost = "Energy", …)
	override val rarityLadder = listOf("Common", "Uncommon", "Rare", "Epic", "Showcase")
	override val cardmarketSlug = "Riftbound"
}
```

Every default is a real answer rather than a placeholder. `rarityLadder` defaults to empty, which
means "no honest order is known" -- Pokémon declares exactly that, and says why. `cardmarketSlug`
defaults to `null`, which suppresses the marketplace link rather than shipping a guessed URL.

A source that already serves an existing game skips this step entirely.

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
single { ScryfallProvider(mClient = get()) } bind CardProvider::class
```

`ProviderRegistry` is built from `getAll<CardProvider>()`, so it picks the new one up.

**Use `bind`, not `single<CardProvider> { … }`.** The latter gives every adapter the same primary
type and no qualifier, so Koin keeps only the last one registered — `getAll` then returns a single
provider and the registry throws at startup with *"routing table names providers that are not
registered"*. This is not hypothetical: it is how the seven-provider build first crashed. It
compiles, every unit test passes, and every adapter works in isolation, because nothing about it is
visible until the graph is assembled. `AppModuleTest` assembles the real graph and asserts the
wiring for exactly this reason.

### 3. A routing entry

In the same file:

```kotlin
val providerRoutes = listOf(
    ProviderRoute(game = RiftboundGame.id, provider = RiftcodexProvider.PROVIDER_ID),
    ProviderRoute(game = MagicGame.id,     provider = ScryfallProvider.PROVIDER_ID),
)
```

The game is named by its profile's `GameId`, not by an enum constant — there is no enum. The
registry checks at construction that each route's game matches the profile the named provider
actually declares, so a route pointing at the wrong adapter fails at startup rather than serving the
wrong catalogue.

A second source for a game that already has one, filling a language gap, is the same shape with a
`language`:

```kotlin
ProviderRoute(RiftboundGame.id, SomeKoreanSource.PROVIDER_ID, language = CardLanguage.KOREAN)
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

Two assertions have earned their place in every adapter's tests, because each caught a real bug:

- **Ids must be unique across a page.** `LazyVerticalGrid` throws outright on a repeated key rather
  than degrading, so a provider that issues one is a crash rather than a cosmetic problem. The
  worked example is Wuthering Waves, whose printed codes are not unique -- see `WuwaCatalogueTest`
  for the assertion and CLAUDE.md for the trap. No count here on purpose: the snapshot grows, and
  the last two numbers written down went stale within a fortnight.
- **Omitting a language gets the app's *first preference*, not English.** `resolveLanguage(null)`
  walks `CardLanguage.PREFERENCE_ORDER`, so a source that carries French answers in French. A test
  written without an explicit language gets French names back and looks broken when it is not.
- **A provider's own tag for a language is the provider's business.** `CardLanguage.code` is this
  app's tag; where a source disagrees — Scryfall writes Chinese `zhs`/`zht` — the adapter maps it and
  `CardLanguage.fromCode` reads the source's spelling back through `aliases`. Nothing about one
  source's spelling reaches the enum.

### What a provider no longer has to do

Three things went away when the game moved into the type:

- **`capabilities.games`.** A provider serves the game in its own type argument. The registry checks
  the routing table agrees with it at construction, so a route pointing a game at the wrong adapter
  is a startup failure with a clear message rather than a screen that loads forever.
- **`require(game == …)` at the top of every method.** Around twenty of those, all restating
  something the compiler already knew. The `game` parameter they checked is gone from `listSets`,
  and `CardSearchRequest` no longer carries one either.
- **Its own rarity ladder or vocabulary.** Those belong to the game, not the source.

### What you do *not* have to touch

`SetListScreen`, `CardGridScreen`, `CardDetailScreen`, `FilterSheet`, `CardRepository`,
`MetadataCache`. A second provider for an existing game reaches the screens through the same
`CardPrinting` and the same capabilities, and the filter sheet redraws itself from what the new
provider declares.

A **new game** needs a `:games:*` module and an entry in the art list. Nothing shared changes, and
there is no table anywhere to forget to extend — every per-game fact is in that one module.

The *fields* stay shared. `CardFilterField` has one `DOMAIN`, not seven, and `CardAttributes` has
three deliberately unnamed numeric slots rather than a type per game, because only the *label*
differs and a per-game field would have to be threaded through the query type, the cache format and
the filter engine for no gain. `GameVocabulary` supplies the word; a game with no such axis leaves
it `null` and the sheet omits the section.

Six games were added before this split, each touching four files across two modules. The seventh
would touch one module.

---

## Branding: what belongs to a game, and what belongs to a provider

Two kinds of artwork, and they live in different places on purpose.

**A game's logo belongs to the game.** Scryfall is not Magic, and TCGdex is not Pokémon; if a second
provider started serving Pokémon, the logo would not change. So it lives in that game's own module,
as a `GameArt` beside its `GameProfile`, with the image file next to both — the same place its
rarity ladder and its vocabulary live, for the same reason.

`GameArt` is declared in `:games:api` rather than `:core` only because it holds a
`DrawableResource`, and `:core` has no Compose in it — which is what lets a provider adapter be
written without inheriting the app's UI stack. `:composeApp` reaches the art through
`GameArtRegistry`, and never enumerates games itself.

**A set's symbol belongs to the provider**, because it is part of the set record. `CardSet.symbol`
is populated by whichever mapper has one to give, and three of the eight do. That field carries an
`isMonochrome` flag alongside the URL, because whether an asset has a colour of its own is a fact
about the asset that only the provider knows: Scryfall's SVGs have no `fill` and default to black,
while TCGdex's logos are full-colour wordmarks that must never be recoloured.

The test for which side a thing falls on is simple: if swapping the provider would change the
image, it belongs to the provider.

---

## Cross-set search, and why its scope is part of the answer

`CardRepository.searchAllSets` returns a `CardSearchResults` carrying a `SearchScope`, and the
screen shows which one it got. There are two:

- `REMOTE_ALL_SETS` — the provider searched its whole catalogue.
- `LOCAL_CACHED_SETS` — only the sets already on this device were searched, because the provider
  declares `crossSetSearch = false`.

Collapsing these into one "results" list would be the app's most quietly damaging lie. An empty
remote search means the card does not exist. An empty local search almost always means the user has
never opened the set it is in — and on a fresh install, *every* local search is empty. The screen
says which happened, and how many of the game's sets were actually looked at.

A search page **is** cached, under its own `CacheScope.Search` — the normalised needle, the
provider, the language and the page — with the same TTL as card data. Its own scope, and that is
the point: a slice of many sets under one query must never be reachable where a complete set is
expected, and a sealed scope makes that a type error rather than a convention.

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

`setList()` emits **at most twice**: the cached value, then the network result. `cards()` emits
more than that on purpose — the cached value first, then one emission per page batch as a set is
walked, and a final one carrying the complete set. See § Progressive loading. Either way a failed
refresh re-emits the cached value with the error attached rather than replacing it.

### The completeness rule

The single most important behaviour in `CardRepository`.

No provider here filters remotely — every adapter declares `remote = emptySet()`. So every filter
runs locally, and running one against a single page would produce results a user would reasonably
read as "the whole set", which they are not.

So when a query needs a filter the provider cannot apply, the repository fetches **every page** of
the set, caches it as `CacheScope.CompleteSet`, and filters that. When it cannot finish, it returns
what it has with `isCompleteSet = false` and the real set size beside it, and the grid says:

> Filtered from 200 of 352 downloaded cards — not the whole set.

**A page is never cached.** Only the complete set is, under `CacheScope.CompleteSet` with no query
at all. There used to be a `CacheScope.CardPage` carrying a query fingerprint and nothing ever wrote
one — a page is only ever a step towards the complete set, so caching it would mean holding the same
cards twice under two different rules about how complete they are. Filing a filtered page where a
complete set is expected is exactly the bug that would make every later filter silently wrong, which
is why the scopes are a sealed type rather than a string.

### Progressive loading

Fetching every page before drawing anything is correct and was also unusable: Origins is four pages,
and four sequential round trips measured between 7 and 16 seconds of spinner over cards that had
arrived in the first second.

So page one is emitted on its own, marked partial, before the remaining pages are even requested;
those then go out together, bounded to four at a time, and a second emission carries the complete
set. Measured against the live API: first cards at ~0.9 s, complete at ~2.1 s, against ~16 s before.

This does not weaken the completeness rule — the first emission is `isCompleteSet = false` and the
grid labels it, exactly as a genuinely partial set is labelled. Concurrent pages are reassembled in
page order rather than completion order, so what lands in the cache does not depend on which request
happened to answer first.

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

### Pinned records: what the ceiling may not touch

A record written by a download or a bulk import is **pinned** — a zero-byte sibling file, plus a
label — and pinned bytes are left out of the budget entirely rather than merely evicted last.

That is not a tuning choice. A bulk import of Magic is larger than any sane browsing ceiling, so
counting it against one meant the ceiling was breached the moment the import finished and the trim
evicted what had just been written. Raising the ceiling to 1 GB papered over it; taking pinned bytes
out of the budget is the actual fix, and it is what let the ceilings come back down.

The label is the game id, the set id and the language joined by tabs, and it is there because the
alternative failed. The
route from a hashed cache filename back to a set used to be the game's cached *set list*, which is
ordinary browsing data — so clearing the cache deleted the only thing that could name a downloaded
set, and the storage screen went blank while the download dialog still reported the same sets as
held. Two screens contradicting each other about the same disk.

Consequences worth knowing before touching any of it:

- **`clearUnpinned()` is what "clear cached data" runs.** `clear()` takes the downloads too.
- **Counting is per set, per language.** A set held in two languages is two records and one set.
  Every count on the storage screen has had that wrong at least once; see the trap in
  [`CLAUDE.md`](../CLAUDE.md).
- **One walk, not five.** `MetadataCache.snapshot()` produces total bytes, entry count, pinned bytes
  and the pinned entries from a single listing, because the storage screen needs all four and the
  directory holds three files per cached set. `StorageScreenCostBench` measures it.

### If this becomes a database

A SQLite migration has been raised as a possibility -- for speed, and to make cross-set search do
more than match a name. Nothing has been started. This is the brief for whoever does it, written
while the reasons were still in one head.

**What it would genuinely buy.** Most of what is slow here is slow because a record is a *file*:

- The storage screen's counts are a directory walk today and would be
  `SELECT COUNT(DISTINCT set_id)`. The same goes for pinned bytes, the language breakdown, and the
  per-set existence checks that `savedLanguages` and `availableLanguages` run per row.
- Filtering across sets is the real prize. `CardFilterEngine` filters in memory over whatever is
  loaded; a table of printings with indexed columns makes "every Fury card under 4 cost across
  every downloaded set" a query rather than a fan-out over hundreds of parsed JSON documents.
- A bulk import becomes one transaction over a stream instead of 64 shard files and a write per
  set. The sharding exists *only* because the cache is a filesystem -- see below.

**What it must not change.**

1. **A local search is still local.** SQLite makes that search fast; it does not put a single extra
   card on the device. `SearchScope.LOCAL_CACHED_SETS`, its set count and `isLimitedByCache` must
   survive intact, and an empty result must keep meaning "not in what you have downloaded" rather
   than "does not exist". This is the single most likely thing to be lost in a rewrite, because a
   fast complete-feeling search *feels* authoritative.
2. **Unknown stays distinct from absent.** Columns are nullable for a reason; a schema that defaults
   a missing language or rarity to a value has thrown away `Availability`'s third state.
3. **Pinned records stay outside the eviction budget**, and a pin still has to name its set well
   enough to be listed after everything else is cleared. Today that is the label on the marker file;
   in a schema it is a row that eviction skips and a join that does not depend on the set list.
4. **Interrupted writes cannot corrupt a good record, and a corrupt one reads as a miss.** Per-file
   atomic replacement gives both for free today. SQLite gives the first with WAL and a transaction;
   the second needs a deliberate answer for a corrupt *database*, which is a single point of failure
   where today the blast radius is one set.
5. **Language is part of a record's identity**, not a column to be collapsed. `(provider, set,
   language)` is the key everywhere -- cache, downloads, pins, image records.

**Costs to weigh before starting.**

- A multiplatform driver is a dependency decision in a project that pins and records every version
  it uses. It also adds native linkage to the **iOS targets, which have never been linked** -- so
  the one target that cannot be tested here gains the most new risk.
- Okio is the storage abstraction throughout, including `AppStorage`'s roots and the image cache.
  A database replaces the metadata half only; the image cache stays a directory.
- Existing data does not need migrating -- the owner has said a wipe is acceptable at this stage --
  but `bulkImports` and the download records in `BrowsingPreferences` describe what is on disk, so
  they have to be cleared with it or they will claim a catalogue that is gone.

**Measure it.** `CacheWriteCostBench` and `StorageScreenCostBench` exist and both print figures; a
migration that cannot beat them on the same machine has not earned itself. The current numbers to
beat are in each file's KDoc.

### A bulk import is not a catalogue

`BulkCatalogue` is optional and additive — a `CardProvider` implements it *as well as* the ordinary
contract. Only Scryfall does today.

Three rules the import obeys, each of which was a bug first:

1. **Nothing holds the file.** 598 MB of JSON streams one card at a time into 64 hash-sharded
   scratch files, which are then grouped and written a shard at a time. A sink per set was ~1100
   open descriptors against iOS's 256; a map of set to cards was the whole catalogue on the heap.
2. **Each card is filed under the language its own record states**, never under one asked for.
   `streamAll` deliberately has no language parameter: a dump contains what it contains, and
   `resolveLanguage(null)` walks the preference order — so an overwhelmingly English file was once
   imported, cached and reported as French.
3. **Cards whose set the catalogue does not list are skipped.** `listSets` drops digital-only and
   empty sets, so those cards have no row to open. Guarded on the catalogue being non-empty:
   `listSets` is one request and it can fail, and an empty answer must not be read as "skip
   everything". Skipped cards are counted and reported.

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

### Every screen is a Screen and a Content

```kotlin
@Composable fun XScreen(..., viewModel: XViewModel = koinViewModel()) {
    val state by viewModel.collectAsStateWithLifecycle()
    XContent(state, viewModel::dispatch, ...)
}

@Composable fun XContent(state: X.UiState, dispatch: (X.Intent) -> Unit, ...)
```

`XScreen` is the only half allowed to touch Koin, view models or effects. `XContent` takes
everything as arguments and is therefore previewable — and `@Preview` is unforgiving about this: a
preview has no Koin graph, so a stray `koinInject` inside a Content throws
`KoinApplication has not been started` and the preview shows a stack trace instead of a screen.

That is why the grid's focused-card id and the detail screen's prefetch radius are *parameters*
rather than injections, even though both are read from a singleton one function up.

Navigation is an intent and arrives back as an effect. A tap dispatches `BackPressed` or
`SetOpened(set)`; the view model emits `Effect.NavigateBack` or `Effect.OpenSet(set)`; `XScreen`
collects it and calls the lambda the composition root gave it. So `XContent` really does take a
state and a dispatch and nothing else, and no reducer knows about a route -- the effect names a
destination in the screen's own vocabulary and `App.kt` decides what that is.

This used to be the opposite: navigation stayed as a callback threaded through `XContent`, on the
grounds that where the app goes next is the caller's business. It still is, but the *body* was the
wrong place to hold it, and it split single interactions across two mechanisms -- opening a set both
dispatched `SetOpened`, which remembers the last set, and called `onOpenSet`, which navigated.

### Two effects that drive each other need a tiebreaker

The card pager reports its settled page to the state, and the state drives the pager when something
else moves the selection. Those two are a loop, and the state alone cannot say which direction a
change came from.

Without a tiebreaker the failure is specific and confusing: a settled swipe reports its page, that
becomes `currentIndex`, and that lands back in the follow effect — by which time the user may have
started the *next* swipe, so `currentIndex` no longer matches `targetPage` and the pager animates
back to the page just left. Quick successive swipes appear to cancel each other at random.

`vLastReportedByPager` records what the pager itself last said, and the follow effect ignores an
echo of it. A tap on the preview strip is not an echo, so it still moves the pager.

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

## What the app knows about a set's languages

Three different questions, three different answers, and conflating any two of them has produced a
user-visible bug:

| Question | Answered by | Strength |
|---|---|---|
| What could this source serve at all? | `ProviderCapabilities.data.languages` | a **capability**, measured per provider |
| What does the source say about *this set*? | `CardSet.languages` | a **claim**, and sources over-claim |
| What has the source actually served or confirmed? | `CardRepository.confirmedLanguagesFor` | a **fact** |

`confirmedLanguagesFor` is the union of a confirmation record (`languagesFor` asked and the source
answered) and every language whose cards are on disk — cards are there only because they were
served, which is the strongest evidence available.

**A menu lists facts. A claim only decides whether the menu is worth offering.** The card grid's
language menu used to list the claim and then narrow to the confirmed set when the probe landed,
which is the app showing something it had not checked. It now lists `confirmedLanguages` plus what
is on screen, with an explicit "checking" row while the probe runs and an explicit "could not check"
row when it fails — *could not check* and *there are none* being opposite facts that a short list
expressed identically.

`knownLanguagesFor` still returns claim-or-confirmation and is what the **detail** screen offers,
deliberately: narrowing that one to on-disk languages is a bug this codebase has already had, where
detail listed two languages while the grid beside it offered eleven.

### Opening a set in a language nobody asked for

`openingLanguageFor` returns an `OpeningLanguage`, not a bare language, because "you are reading
English" is not the whole answer. Its steps, cheapest first:

1. the wanted language is on disk — no request at all;
2. it is not, but the set was **downloaded** in another (pinned records only) — open that one,
   `LanguageSubstitution.NOT_DOWNLOADED`;
3. one probe for the wanted language;
4. the full confirmation, and if the source has no such edition, `NOT_PUBLISHED`.

The two substitutions are different facts and the grid says different things about them: one offers
to fetch, the other offers nothing because there is nothing to fetch. Step 2 is what makes a
one-language bulk import usable — records are cached per language, so an English import under a
French preference otherwise left 988 sets on disk that every read missed.

Pinned-only is the load-bearing part of step 2: a set browsed in English last week is not a request
to stop showing French today, and treating incidental cache as a preference would make the app's
language drift with its history.

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
| Downloads outliving the cache ceiling | `data/cache/MetadataCache.kt` (`pin`, `trimLocked`) |
| What is on disk, and what may be deleted | `data/repository/CardRepository.kt` (`keptByGame`), `ui/storage/` |
| Claim vs confirmed languages | `data/repository/CardRepository.kt` (`confirmedLanguagesFor`, `knownLanguagesFor`) |
| Opening a set in a substituted language | `data/repository/CardRepository.kt` (`openingLanguageFor`), `data/repository/DataSnapshot.kt` (`OpeningLanguage`) |
| Streaming a whole catalogue without holding it | `data/repository/CardRepository.kt` (`importBulk`) |
| Cardmarket URLs | `core/cardmarket/CardmarketLinks.kt` |
| Provider routing | `core/provider/ProviderRegistry.kt`, `di/AppModule.kt` |
