# Working on CardBrowser

Orientation for an agent picking this project up. It is not a summary of the code — the code is
heavily commented and [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) explains the design. This is
the part that is not obvious from reading a file: what this codebase values, what it will reject,
and the traps that have already cost a debugging session once.

---

## What this project is

A Kotlin Multiplatform card browser for ten trading card games, sharing Compose Multiplatform UI
and all logic across Android, iOS and JVM desktop. No backend — Ktor talks to each game's public
database directly. Browsing only: no accounts, no collection, no prices, no deckbuilding.

Read [`README.md`](README.md) for what it does and
[`docs/PROVIDER_RESEARCH.md`](docs/PROVIDER_RESEARCH.md) for what each data source was measured to
supply.

---

## The rule that overrides the others: do not claim what you have not checked

This codebase is built around a single idea — **the app must never state something it does not
know.** It shows up everywhere, and a change that erodes it is a regression even if it compiles and
the tests pass.

- `Availability` is three-valued. "Unknown" is not "unavailable". A source that has no language
  field is not telling you a French printing does not exist.
- A language is `confirmed` only when a source served it. Asking for Korean and getting English
  means the record says **English**, and the screen says so.
- Filtered results drawn from part of a set are labelled partial, with the real set size beside
  them. Never present a page as a set.
- A cross-set search that only looked at downloaded sets says so, with a count. An empty result
  from a whole-catalogue search and an empty result from a cache-scoped one mean opposite things.
- Two printings are the same card because a provider said so, never because they share a name.
- A **claim** and a **confirmation** are drawn differently. A source saying it serves eleven
  languages is reason to offer to look; it is not a list of this set's editions. A menu that opens
  on the claim and narrows when the probe lands has told the user something it did not know --
  which is exactly what the card grid's language menu used to do. "Could not check" and "there are
  none" are opposite facts and must not render the same way.

The same standard applies to **you**, in commit messages, in docs, and in what you tell the user:

- Do not write "all tests pass" unless you ran them and read the output.
- Do not describe a fix as verified when you only compiled it.
- If you could not check something, say which thing and why. "Not verified: iOS" is a good sentence
  and appears in the README on purpose.
- An assertion in a `.replace()`-style edit is not optional. A silent no-op edit has shipped twice
  here — once a transition that was never applied, once a composable defined and never called.

---

## Layering

```
:core            domain vocabulary, game profiles, the provider contract.
                 No Ktor. No Okio. No Compose. NAMES NO GAME.
:data            HTTP stack, caches, preferences, repositories. No Compose.
:database        the SQLite card store: schema, per-platform driver, eviction. NAMES NO GAME.
:games:api       GameArt — a logo and accent colour. Compose resources live here, not in :core.
:games:<name>    one module per game: its GameProfile, its GameArt, its logo file.
:providers:<n>   one module per data source: endpoints, DTOs, mapping, its own quirks.
:composeApp      Compose screens, Pulse view models, Koin wiring.
:androidApp      an Activity and an Application. Nothing else.
```

Two boundaries matter more than the rest:

**`:core` and `:data` know no game or provider names, and a test enforces it.** `LayeringTest`
scans both source trees with comments stripped and fails naming the file. It exists because the
rule had already been broken: `ProviderHttpPolicy.SCRYFALL` and `.YGOPRODECK` sat in the shared HTTP
layer, so `:data` knew two sources existed and nothing complained. A rate limit is a fact about a
source, like its endpoints and its quirks, so it lives in that source's module -- see
`ScryfallProvider.HTTP_POLICY`. Comments naming a source are fine and are most of how this codebase
explains itself; it is code that must not.

**`:core` knows no game names.** There is no `Game` enum. A game is a `GameProfile` in its own
module, identified by `GameId("riftbound")`. Core holds *mechanisms* — how to rank a rarity, how to
label a stat, how to build a Cardmarket URL — and never a table of which games exist. If you find
yourself adding a `when (game)` to `:core`, the design has been misunderstood.

**A game fact and a source fact are different things.** Riftbound calls its cost axis "Energy"
whoever supplies the data → `GameProfile`. Whether TCGdex can serve Korean → `ProviderCapabilities`.
Getting this wrong is the most common way to put the right value in the wrong place.

---

## Conventions

Follow the user's global conventions (Spirtech prefixes, tabs, KDoc). Specifically here:

- `v` for locals, `m` for private members, `SCREAMING_SNAKE_CASE` for constants. Parameters are
  **not** prefixed in Kotlin — named arguments matter.
- Tabs, not spaces.
- KDoc `/** … */` on every class and non-obvious function. This codebase comments **why**, not what.
  A comment that restates the line above it is noise; a comment recording a measurement, a rejected
  alternative, or a bug that shaped the code is the house style.
- Section separators (`// ==================` / `// MARK: Name`) between major groups.
- Screens split as `XScreen()` (binds the view model) and `XContent(state, dispatch)` (pure, so it
  previews). **No Koin inside a `Content`** — a preview has no Koin graph and will throw.
- **Navigation is dispatched, not called.** A `Content` takes a state and a dispatch and nothing
  else: a tap sends an intent, the view model emits an effect, and `XScreen` turns that back into a
  route. No `onBack` threaded through a body. See `docs/ARCHITECTURE.md` § "Every screen is a
  Screen and a Content".

---

## Build, test, run

```bash
./gradlew build -x lint          # everything, all four targets, including both iOS ones
./gradlew desktopTest            # the deterministic suite (492 tests on 2026-09-11, 8 skipped)
./gradlew :androidApp:assembleDebug
./gradlew :composeApp:run        # desktop
```

Live provider checks are **excluded from the ordinary run** and have one task each:

```bash
./gradlew :providers:scryfall:liveProviderTest
```

They need a network and hit someone else's server. Do not add them to CI-style runs -- the GitHub
Actions workflow deliberately runs `build -x lint` and nothing else, for that reason.

---

## Traps that have already cost time

Each of these was a real debugging session. They are listed because none is discoverable by reading
the code that fails.

**Two numbers either side of a slash must count the same population.** This has now been shipped
three times, and each time it looked plausible on screen:

- `227 of 358` -- distinct cards against *records*, one per printing variant. A fully downloaded set
  read as a download that gave up two thirds of the way through.
- `Card info 1111/988` -- pinned *records*, one per set **per language**, against a catalogue of
  sets. A set held in two languages is two records and one set.
- `Card info 1044/988` -- sets from a bulk *file* against sets in the *catalogue*. A dump carries
  digital-only products that `listSets` drops, so the numerator drew from a larger universe.

Before writing `$a/$b`, say out loud what each side counts. If the sentence needs two different
nouns, the ratio is wrong. `CardGridContract.countLabel` and `StorageContract.KeptGame.summary`
both carry the scar tissue.

**A `Content` takes a state and a dispatch.** Navigation is an intent that comes back as an effect,
collected in `XScreen`. Threading an `onBack` through a body splits one interaction across two
mechanisms -- opening a set both dispatched `SetOpened`, which remembers the last set, and called
`onOpenSet`, which navigated.

**Heredocs eat backslash escapes.** Editing Kotlin through `bash <<'EOF'` with an embedded
backslash-n or backslash-t put literal newlines and tabs inside string literals more than once in
one session, and an escaped quote inside a heredoc-fed Python string arrived as a bare quote that
broke compilation. It even mangled the first draft of this paragraph. Use the `Write` and `Edit`
tools for anything containing a backslash, and assert on the replacement either way.

**Koin: register providers with `bind`, never `single<CardProvider> { … }`.** Ten definitions
sharing one primary type and no qualifier means Koin keeps only the last, `getAll` returns one
adapter, and the registry throws at startup. It compiles, every unit test passes, and every provider
works in isolation. `AppModuleTest` assembles the real graph specifically to catch this.

**Kotlin/Native forbids commas in backtick test names.** `fun \`a, b\`()` compiles for JVM and
breaks every iOS test compilation. Use ` -- ` instead. This blocked the iOS build entirely until it
was found.

**`local.properties` needs forward slashes on Windows.** `C:/Users/...`, not `C:\Users\...`; AGP
mis-parses the escapes.

**Cardmarket answers 403 to every scripted request**, including paths known to work, and its bot
protection blocks an automated browser too. Its URLs can only be confirmed by a human with a
browser, so a `cardmarketSlug` that has not been seen on a real page stays `null`. Do not guess one.

Two results already bought with someone's time — do not spend it again:

- Per-game slugs `Riftbound`, `Magic`, `Pokemon`, `YuGiOh`, `OnePiece` are all corroborated against
  real URLs. **Altered has no Cardmarket section at all** — that is checked, not unknown, so its
  `cardmarketSlug` is `null` permanently and is not a gap to go and fill. Wuthering Waves has none
  yet, which is. `AppModuleTest` pins the whole table.
- **`idCategory` is optional.** Known for Riftbound (1655), Pokémon (51) and One Piece (1621); Magic
  and Yu-Gi-Oh's real URLs send none and `0` respectively, so they declare none and the parameter is
  omitted. A missing id costs the refinement, not the search — only a *wrong* one returns nothing.
- **`?idProduct=<n>` works, but only off the right path.** `/{Game}/Products?idProduct=778435`
  lands on the card; `/{Game}/Products/Singles?idProduct=778435` is ignored and yields an
  unfiltered listing. Both confirmed in a browser on 2026-09-11. This entry used to record only
  the second and conclude that ids were useless, which was wrong and cost the app working
  card links for Magic and Pokémon -- and worse, `CardmarketLinkBuilder` was building the
  failing shape, so the link was broken wherever an id existed. A *slug* path still cannot be
  synthesised; the id is published, not guessed, which is the difference.

**Scryfall's live suite trips its own rate limit.** 12 checks in one run exceeds what the host
accepts, and re-running alone after a pause did not clear it. Five failures there were the state on
2026-09-10; anything outside Scryfall is not rate limiting and is worth reading.

**The Wuthering Waves snapshot goes stale, and a test says so.** It is a bundled file, so the game
gaining cards is invisible until `:providers:wuwa:liveProviderTest` fails -- which it did on
2026-09-10, with the live catalogue 68 cards ahead of the snapshot. The fix is one command,
`python providers/wuwa/tools/scrape_wuwa.py --refresh`, and then re-running the deterministic suite:
tests that assert facts about the old snapshot will fail, and some of those assertions are about the
*catalogue* rather than the adapter. Prefer asserting a property that survives growth over a number
that does not -- and request `WuwaProvider.MAX_PAGE_SIZE`, which is what the repository does, rather
than the default page size.

**TCGCSV answers with two different content types.** `text/json` for some responses and
`application/json` for others, within the same category -- category 13's products are the first and
its groups the second. Ktor's `ContentNegotiation` is registered for `application/json` only, so
`body<T>()` worked for two of the three games it serves and threw *"Expected response body of the
type ... but was SourceByteReadChannel"* for the third, which reads like a broken DTO and is not.
That adapter reads the body as text and parses it itself; do not "fix" it back.

**`LazyVerticalGrid` throws on a duplicate key** rather than degrading, so a provider that issues
two records with the same id is a crash rather than a cosmetic bug. Wuthering Waves shipped exactly
that: its printed card codes are not unique, several being carried by two records with different
art. Its adapter now keys on a snapshot key and `WuwaCatalogueTest` asserts those keys are distinct.
Any new adapter wants the same assertion.

**Omitting a language gets the user's *first preference*, not English.** `resolveLanguage(null)`
walks `CardLanguage.PREFERENCE_ORDER`, so a source carrying everything answers in French. A test
written without an explicit language gets French names back and looks broken when it is not.

---

## Adding things

**A provider or a game:** follow [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) § "Adding a
provider". It is current and specific — a module, a Koin `single … bind`, a routing entry, and for a
new game a `GameProfile` plus a `GameArt`.

**Before writing any adapter, measure the API.** Do not write one from documentation. Every
provider here was built by hitting the live endpoint and reading real responses, and in several
cases the documentation was wrong: TCGdex advertises set symbols its CDN 404s, YGOPRODeck's own
error message lists the wrong set of languages, and Riftcodex's `/cards/search` is not a name
search. Record findings in `docs/PROVIDER_RESEARCH.md` with the date checked.

**Artwork:** a game's logo belongs to the game module; a set's symbol comes from the provider. The
test is whether swapping the provider would change the image. **Three** bundled logos are
licence-checked Wikimedia Commons files; **seven** were supplied by the project owner from
third-party sites and carry no verified licence — that distinction is documented in `GameArt`'s
KDoc, which owns the detail, and should not be flattened.

---

## Things deliberately not done

Do not "fix" these without asking; each is a decision with a reason recorded nearby.

- **Duel Masters is absent**, and it was asked for. TCGplayer has no category for it; the community
  `duel-masters-json` dataset has good text but **no images at all** and covers 12 of 100+ sets; the
  publisher's own site has art but no JSON and is Japanese-only. A picture-less card browser is not
  one. **Put to the project owner on 2026-09-09 with the three options measured, and the answer was
  to leave it out** until a source with both images and coverage exists -- so this is settled, not
  pending. Measured in `docs/PROVIDER_RESEARCH.md`.
- **Cyberpunk TCG used to be absent and no longer is.** The recorded reason was "no data source
  exists", which was true when checked and stopped being true when TCGplayer opened category 92.
  Worth remembering as a pattern: a "deliberately not done" entry is a statement about a date, and
  re-checking one is cheap.
- **No provider merging or failover.** One route wins and its answer is the answer. Failing over
  would silently change what every id on screen means.
- **`CardPage` is never cached.** Every provider declares `remote = emptySet()`, so a page is only
  ever a step towards a complete set, which *is* cached.
- **Prices are not mapped**, even where a source supplies them. This is a browser, not a price
  guide, and the figures carry no currency or timestamp.

---

## Open threads, as of 2026-09-11

Not decisions, not bugs with a ticket -- just the things that are half-finished or unverified, so
nobody re-discovers them the slow way. Delete an entry when it stops being true.

**Known debt**

- **`SetListScreen` builds `DownloadRequest`s in the composition layer.** It reads the
  `DownloadManager` through `koinInject` and enqueues from a lambda, which is the last piece of
  side-effecting work outside a view model now that navigation has moved. It belongs in
  `SetListViewModel`; it was left alone because it changes the download path and that cannot be
  exercised without a device.
- **The advanced search the store made possible is not on screen yet.** `searchPrintings` narrows
  on type, rarity, cost range, domain and a "does not contain" exclusion, and the search screen
  still sends a name. The query is tested; nothing dispatches it.

**Unverified, and why**

- **The card store has never been opened on Android or iOS.** Both drivers compile; neither has
  run. The desktop one is covered by tests that open, reopen, damage and recover a real file, and
  `CardStoreFactory` discards anything that fails `integrity_check` -- but that path has only been
  exercised on a JVM. The first launch on a phone also runs the one-off cleanup of a pre-store
  install, which nothing can rehearse.
- **iOS has never been linked or run.** Kotlin compiles for both iOS targets in every build; the
  framework, the Swift shell and a simulator run need a Mac.
- **Scryfall's live suite has not had a clean run** since it tripped its own rate limit: 12 checks
  in one burst exceeds what the host accepts, and a pause did not clear it. The per-set language
  check written for it is therefore unconfirmed against the live API.
- **The storage screen's figures have not been read on a device** since the counting was fixed, and
  its speed was measured on a desktop SSD rather than a phone.
- **The bulk import has not been re-run against the real 598 MB dump** since it started skipping
  digital-only sets. The rule and its guard are unit-tested against a fake.
- **The project owner's phone is deliberately not used for testing.** Desktop, and headless
  renderers under `composeApp/src/desktopTest/.../render/` for anything visual. They are `@Ignore`d
  tools: remove the annotation, run, look at `composeApp/build/render/*.png`, put it back.
