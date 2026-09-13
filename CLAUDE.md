# Working on CardBrowser

Orientation for an agent picking this project up. The code is heavily commented and the documents
linked below explain the design; this file is the part that is not obvious from reading a file.

| Document | What it covers |
| --- | --- |
| [README.md](README.md) | What the app is and does, for a human |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Modules, data flow, caching, the screen pattern |
| [docs/PROVIDERS.md](docs/PROVIDERS.md) | The provider contract, every capability, what each source can do |
| [docs/GAMES.md](docs/GAMES.md) | What a `GameProfile` and a `GameArt` declare, and what each game declares |
| [docs/PROVIDER_RESEARCH.md](docs/PROVIDER_RESEARCH.md) | Dated measurements of each source's API |
| [database/README.md](database/README.md) | Why the card store is SQLite, with the benchmark |
| [docs/NATIVE_DESKTOP.md](docs/NATIVE_DESKTOP.md) | The experimental Kotlin/Native desktop target |
| [iosApp/README.md](iosApp/README.md) | The iOS shell, and why there is no `.xcodeproj` |

---

## What this is

A Kotlin Multiplatform card browser for ten trading card games, sharing Compose Multiplatform UI
and all logic across Android, iOS and JVM desktop. No backend — Ktor talks to each game's public
database directly. Browsing only: no accounts, no collection, no prices, no deckbuilding.

---

## The rule that overrides the others: do not claim what you have not checked

The app must never state something it does not know. A change that erodes this is a regression even
if it compiles and the tests pass.

- `Availability` is three-valued. "Unknown" is not "unavailable".
- A language is `confirmed` only when a source served it. Asking for Korean and getting English
  means the record says **English**.
- Filtered results drawn from part of a set are labelled partial, with the real set size beside
  them. Never present a page as a set.
- A search that only looked at downloaded sets says so, with a count. An empty result from a whole
  catalogue and an empty result from a cache mean opposite things.
- Two printings are the same card because a provider said so, never because they share a name.
- **A claim and a confirmation are drawn differently.** A source saying it serves eleven languages
  is reason to offer to look; it is not a list of this set's editions. "Could not check" and "there
  are none" must not render the same way.

The same standard applies to **you**, in commit messages, in docs, and in what you tell the user.
Do not write "all tests pass" unless you ran them and read the output. Do not call a fix verified
when you only compiled it. If you could not check something, say which thing and why — "Not
verified: iOS" is a good sentence and is in the README on purpose.

An assertion in a `.replace()`-style edit is not optional. A silent no-op edit has shipped here.

---

## Layering

```
:core            domain vocabulary, game profiles, the provider contract.
                 No Ktor. No Okio. No Compose. NAMES NO GAME.
:data            HTTP stack, caches, preferences, repositories, the download queue. No Compose.
:database        the SQLite card store: schema, per-platform driver, eviction. NAMES NO GAME.
:games:api       GameArt. Compose resources live here, not in :core.
:games:<name>    one module per game: its GameProfile, its GameArt, its logo.
:providers:<n>   one module per data source: endpoints, DTOs, mapping, its own quirks.
:composeApp      Compose screens, Pulse view models, Koin wiring.
:androidApp      an Activity and an Application. Nothing else.
```

Two boundaries matter more than the rest.

**`:core` and `:data` know no game or provider names, and `LayeringTest` enforces it.** It scans
both source trees with comments stripped and fails naming the file. It exists because the rule had
already been broken: `ProviderHttpPolicy.SCRYFALL` sat in the shared HTTP layer. A rate limit is a
fact about a source, so it lives in that source's module — see `ScryfallProvider.HTTP_POLICY`.
Comments naming a source are fine and are most of how this codebase explains itself; it is code
that must not.

**A game fact and a source fact are different things.** See [docs/GAMES.md](docs/GAMES.md). Getting
this wrong is the most common way to put the right value in the wrong place.

---

## Conventions

- `v` for locals, `m` for private members, `SCREAMING_SNAKE_CASE` for constants. Parameters are
  **not** prefixed in Kotlin — named arguments matter.
- Tabs, not spaces.
- KDoc `/** … */` on every class and non-obvious function. This codebase comments **why**, not
  what. A comment restating the line above it is noise; one recording a measurement, a rejected
  alternative, or a bug that shaped the code is the house style.
- Section separators (`// ==================` / `// MARK: Name`) between major groups.
- Screens split as `XScreen()` (binds the view model) and `XContent(state, dispatch)` (pure, so it
  previews). **No Koin inside a `Content`** — a preview has no Koin graph and will throw.
- **Navigation is dispatched, not called.** A `Content` takes a state and a dispatch and nothing
  else: a tap sends an intent, the view model emits an effect, and `XScreen` turns that into a
  route. No `onBack` threaded through a body.

---

## Build, test, run

```bash
./gradlew build -x lint          # everything, all four targets, including both iOS ones
./gradlew desktopTest            # the deterministic suite (553 tests on 2026-09-13, 11 skipped)
./gradlew :androidApp:assembleDebug
./gradlew :composeApp:run        # desktop
```

Live provider checks are **excluded from the ordinary run** and have one task each, for example
`./gradlew :providers:scryfall:liveProviderTest`. They need a network and hit someone else's
server. Do not add them to CI-style runs — the GitHub Actions workflow deliberately runs
`build -x lint` and nothing else.

The skipped tests are headless renderers under `composeApp/src/desktopTest/.../render/`. They are
`@Ignore`d tools: remove the annotation, run, look at `composeApp/build/render/*.png`, put it back.

The Kotlin/Native desktop port is experimental and off by default (`nativeDesktop=false`).

---

## Traps that have already cost time

Each was a real debugging session, and none is discoverable by reading the code that fails.

**Two numbers either side of a slash must count the same population.** Shipped three times:
`227 of 358` (distinct cards against records), `1111/988` (records per set *per language* against
sets), `1044/988` (sets in a bulk file against sets in a catalogue). Before writing `$a/$b`, say
out loud what each side counts. If the sentence needs two different nouns, the ratio is wrong.

**One corner of a row, one animation.** The arranging toggle bounced three times, and each time the
handle that appears looked like the culprit and was not. What bounced was a *second* layout change
on the same corner: a row inset easing, a button collapsing its width, a padding stepping 16dp to
4dp in one frame. `AnimatedVisibility` in a `Row` already slides content in from outside; it does
not need help, and help is what breaks it. `ArrangingMotionTest` follows the mark's left edge frame
by frame.

**`AnimatedVisibility` replays its entrance on every re-composition.** It starts invisible and
animates to its target, so a screen re-composed on every back — which is every screen under a
detail — replays the whole thing. Seed a `MutableTransitionState` with the current value instead.

**An `OutlinedTextField` with a `label` reserves 8dp above its border** for the label to float
into. Every inset around such a field is then written to cancel invisible space, and that was got
wrong three times running. The search fields use a `placeholder` for this reason.

**Koin: register providers with `bind`, never `single<CardProvider> { … }`.** Ten definitions
sharing one primary type and no qualifier means Koin keeps only the last, `getAll` returns one
adapter, and the registry throws at startup. It compiles and every unit test passes.
`AppModuleTest` assembles the real graph to catch it.

**Kotlin/Native forbids commas in backtick test names.** `fun \`a, b\`()` compiles for JVM and
breaks every iOS test compilation. Use ` -- ` instead.

**A pragma that returns a row must be run as a query, and a pragma is per connection.** Android's
`execute` refuses any statement returning rows, and `PRAGMA journal_mode=WAL` returns one — the app
crashed on its first launch on a phone. Separately, every pragma except `journal_mode` is per
connection, and a file-backed `JdbcSqliteDriver` opens one per statement, so `foreign_keys` was
silently off on desktop. `CardStoreFactory.pragma` is the query form and reads the row;
`DesktopDriverFactory.DURABILITY` sets them as connection properties; `StoreDurabilityTest` reads
all four back off a fresh connection.

**A range is not a set of values.** The filter sheet built its cost chips by expanding the store's
`MIN..MAX`, and Magic's Gleemax has a mana value of 1,000,000 — so one card turned a sixteen-value
axis into a million and the sheet ran out of memory opening. `costsInGame` answers with the values
that occur. Anything that turns a span into a list wants the same question asked of it.

**A `DropdownMenu` composes every item it is given.** It scrolls, which makes it look lazy and it is
not; a few thousand items freeze the screen before the menu appears. Magic's card type is the
printed type line, so a downloaded catalogue has thousands of distinct ones. The filter menus put a
`LazyColumn` inside, cap their height, and grow a box to narrow themselves past 24 values.

**A view model's scope is `Dispatchers.Main.immediate`, which a plain JVM test has not got.**
Every `handleIntent` is silently dropped and the state never moves, which looks exactly like the bug
you are chasing. `Dispatchers.setMain(StandardTestDispatcher())` in a `@BeforeTest` — this cost an
hour and produced a false reproduction of a bug that was already fixed.

**`LazyVerticalGrid` throws on a duplicate key** rather than degrading, so a provider issuing two
records with the same id is a crash. Wuthering Waves shipped exactly that. Any new adapter wants a
test asserting its keys are distinct.

**Omitting a language gets the user's *first preference*, not English.** `resolveLanguage(null)`
walks `CardLanguage.PREFERENCE_ORDER`, so a source carrying everything answers in French. A test
written without an explicit language gets French names back and looks broken when it is not.

**TCGCSV answers with two different content types** — `text/json` for some responses and
`application/json` for others, within the same category. Ktor's `ContentNegotiation` is registered
for the second only, so `body<T>()` threw "Expected response body of the type …" for one of the
three games it serves. That adapter reads the body as text and parses it itself; do not "fix" it.

**Cardmarket answers 403 to every scripted request** and its bot protection blocks an automated
browser too, so a `cardmarketSlug` is only ever confirmed by a human with a browser. Do not guess
one. See [docs/GAMES.md](docs/GAMES.md) for what is confirmed and what is checked-absent.

**Heredocs eat backslash escapes**, and a shell heredoc containing a large code block has broken
mid-edit here more than once. Use the `Write` and `Edit` tools for anything containing a backslash,
and assert on the replacement either way.

**`local.properties` needs forward slashes on Windows.** `C:/Users/...`, not `C:\Users\...`.

---

## Things deliberately not done

Each is a decision with a reason recorded nearby. Do not "fix" them without asking.

- **Duel Masters is absent.** Measured in [docs/PROVIDER_RESEARCH.md](docs/PROVIDER_RESEARCH.md):
  no source has both images and coverage. Put to the project owner on 2026-09-09 with three options
  and the answer was to leave it out. Settled, not pending.
- **No provider merging or failover.** One route wins and its answer is the answer. Failing over
  would silently change what every id on screen means.
- **`CardPage` is never cached.** A page is only ever a step towards a complete set, which *is*
  cached.
- **Prices are not mapped**, even where a source supplies them. This is a browser, not a price
  guide, and the figures carry no currency or timestamp.
- **Pokémon has no rarity ladder**, by the reasoning in [docs/GAMES.md](docs/GAMES.md).

A "deliberately not done" entry is a statement about a date, and re-checking one is cheap —
Cyberpunk TCG was absent for "no data source exists", which was true when checked and stopped being
true when TCGplayer opened category 92.

---

## Open threads, as of 2026-09-13

Not decisions and not bugs with a ticket — the things that are half-finished or unverified. Delete
an entry when it stops being true.

**Unverified, and why**

- **iOS has never been linked or run.** Kotlin compiles for both iOS targets in every build; the
  framework, the Swift shell and a simulator run need a Mac.
- **The card store has been opened on Android once, and it crashed** — the pragma trap above. That
  is fixed and the fix is *not* confirmed on a device, nor is the one-off cleanup of a pre-store
  install. iOS has never opened it at all.
- **Everything changed on 2026-09-13 is desktop-verified only.** The project owner's phone is
  deliberately not used for testing; headless renderers are the substitute for anything visual.
- **Scryfall's live suite has not had a clean run** since it tripped its own rate limit: 12 checks
  in one burst exceeds what the host accepts, and a pause did not clear it.
- **The bulk import has not been re-run against the real 598 MB dump** since it started skipping
  digital-only sets. The rule and its guard are unit-tested against a fake.

**Known debt**

- **`SetListScreen` builds `DownloadRequest`s in the composition layer.** It reads the
  `DownloadManager` through `koinInject` and enqueues from a lambda — the last side-effecting work
  outside a view model. It belongs in `SetListViewModel`; it was left because it changes the
  download path, which cannot be exercised without a device.
- **The advanced search uses no index for its text half, deliberately.** `name_folded LIKE '%x%'`
  has a leading wildcard, which no B-tree index can serve. The other axes have one each, leading
  with `game`, so choosing any of them shrinks what the text scan looks at. FTS5 would have to work
  on Android's bundled SQLite, the JVM driver and both native ones, and three of those four cannot
  be run here.
- **The Wuthering Waves snapshot goes stale, and a test says so.** It is a bundled file, so the
  game gaining cards is invisible until `:providers:wuwa:liveProviderTest` fails. The fix is
  `python providers/wuwa/tools/scrape_wuwa.py --refresh`, then re-running the suite — prefer
  asserting a property that survives growth over a number that does not.
