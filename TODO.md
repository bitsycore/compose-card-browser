# What is left before 1.0

Found on 2026-09-13 by reading the tree, not from memory. Each item says how solid it is: **measured**
means a number was taken, **verified** means the code was read and the claim checked, **unverified**
means it is believed and has not been tested.

Tick an item when it is done. Delete it when it stops being true — a stale entry here is worse than
no entry, for the same reason it is in [CLAUDE.md](CLAUDE.md).

---

## Correctness

- [x] **The cost filter means two different things.** *(fixed 2026-09-13)*
  `CardSearchFilter` carries a `costs` set instead of a span, `searchPrintings` has the membership
  predicate the rarity and card-type axes already used, and `SqlCardStoreTest` runs the real engine
  and the real database over the same chips and asserts they agree. The span stays on
  `SqlCardStore.search` — it is a real thing to be able to ask — but nothing reaches it through a
  filter any more.

- [ ] **The text predicate differs between the engine and SQL.** *(unverified)*
  `CardFilterEngine.matchesText` matches a folded name substring **or** a collector-number prefix;
  `searchPrintings` matches `name_folded LIKE '%x%'` only. Searching a collector number finds cards
  in a set and nothing across a game. Worth confirming before changing either.

- [ ] **`ArtworkTreatment.STANDARD` cannot be filtered for in a stored browse.** *(unverified)*
  The store has a `:standardArt` flag keyed on the payload *not* containing a treatment, which is a
  different question from the treatment being `STANDARD`. Check what the adapters actually write.

---

## Verification — the largest gap, and none of it is code

Everything below compiles and is desktop-tested. None of it has run where it will actually run.

- [ ] **iOS has never been linked or run.** Kotlin compiles for `iosArm64` and `iosSimulatorArm64`
  in every build; the framework, the Swift shell and a simulator run need a Mac.
- [ ] **Android has opened the card store once, and it crashed.** The pragma trap in
  [CLAUDE.md](CLAUDE.md) is fixed and the fix is **not** confirmed on a device.
- [ ] **The wipe-on-old-schema path is desktop-logic only.** `CardStoreFactory.verifyShape` discards
  a sound database missing a table the code queries. Unit-tested; no phone has taken it — and the
  next install on an existing phone will.
- [ ] **Scryfall's live suite has never had a clean run.** Twelve checks in one burst exceeds what
  the host accepts and a pause did not clear it. Either pace the suite or split it.
- [ ] **The bulk import has not been run against the real 598 MB dump** since it started skipping
  digital-only sets. The rule and its guard are unit-tested against a fake.

---

## Features

- [ ] **The app no longer states what it is or what it deliberately does not do.** *(verified)*
  The setup flow's third page was removed on 2026-09-13 on the understanding that its points lived
  in Settings. They do not: Settings carries the provider attributions and nothing else. "No
  accounts, no collection tracking, no deckbuilding", "No prices" and "works offline once a set is
  downloaded" are now in no screen at all. This was a mistake in that change, not a decision.

- [ ] **Decide whether a failed download should retry itself.** Retry is manual and resumes from
  where it stopped. Auto-retry after a transient failure — a phone sleeping, a network drop — is a
  queue-behaviour change and wants a deliberate answer rather than drifting into one.

---

## Performance

- [ ] **`localSetFacts` issues one query per set.** *(verified by reading, never timed)*
  `confirmedCardCounts` loops `mSetStore.cardCount(...)` per set and `storedSetCount` loops
  `languagesHeld(...)`, so opening Magic's set list is on the order of a thousand queries. **Measure
  before optimising** — the store answers counts in tens of milliseconds and this may be invisible.

- [ ] **The advanced search's text half uses no index, deliberately.** `name_folded LIKE '%x%'` has
  a leading wildcard, which no B-tree index can serve. Listed so it stays a decision. FTS5 would
  have to work on Android's bundled SQLite, the JVM driver and both native ones, and three of those
  four cannot be exercised here.

---

## Cleanup

- [ ] **`CardRepository.kt` is 2057 lines.** The one genuinely oversized file in the tree.
- [ ] **`SetListScreen.kt` is 1603 lines and builds `DownloadRequest`s in the composition layer.**
  It reads `DownloadManager` through `koinInject` and enqueues from a lambda — the last
  side-effecting work outside a view model. It belongs in `SetListViewModel`; it was left because it
  changes the download path, which cannot be exercised without a device.
- [ ] **`CacheUsage.metadataEntries` is computed and displayed nowhere.** *(verified)*
- [ ] **The Wuthering Waves snapshot goes stale silently.** It is a bundled file, so the game
  gaining cards is invisible until `:providers:wuwa:liveProviderTest` fails. Regenerate with
  `python providers/wuwa/tools/scrape_wuwa.py --refresh`.

There are no `TODO`, `FIXME` or `HACK` markers anywhere in the source. *(verified)*

---

## UI

- [ ] **Settings still calls its section "Cache"** while it now holds only the image limit. The
  storage screen already says "Image cache".
- [ ] **The About / scope statement has nowhere to live.** Same item as under Features; it is a UI
  decision as much as a content one.

---

## Naming

- [ ] **Decide the app's name.** Checked on 2026-09-13:
  - *TCG Browser* collides with `tcgbrowser.com`, a WoW TCG card browser and deckbuilder — dormant
    since 2019, but the same words doing the same job. Purely descriptive, so hard to own.
  - *TCG Bro* collides with `tcgbro.com` and several other "TCG Bros" shops and channels. Reads as a
    storefront rather than a reference.
  - *TCG Codex* is an existing iOS app covering Pokémon, Lorcana, Magic, One Piece and Star Wars
    Unlimited — so "Codex" is out.
  - *Riffle* is taken several times over, including a current iOS app.
  - **Cardshelf** was searched and is effectively clear: one KLWP theme pack and physical shelves.
  - *Toploader* and *Playset* were searched and no app surfaced, but both name the wrong thing — a
    card protector and a set of four copies.
