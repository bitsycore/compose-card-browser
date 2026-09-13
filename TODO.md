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

- [x] **The text predicate differs between the engine and SQL.** *(fixed 2026-09-13)*
  Confirmed, then fixed: the engine matched a folded name substring **or** a collector-number
  prefix, and `searchPrintings` matched the name only — so a number found cards in a set and
  nothing across a game, which reads as "there are none". `printing` has a `collector_number`
  column and an index it can actually use (a prefix test, unlike the name half), and
  `SqlCardStoreTest` runs the real engine and the real database over the same needles and asserts
  they agree.

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

- [x] **The app no longer states what it is or what it deliberately does not do.**
  *(fixed 2026-09-13)* There is an About page now, reached from Settings: what the app does and
  does not do, every source with the games it answers for and its own required wording, and a
  trademark notice — which had never existed anywhere. The credits are read off the running
  provider graph rather than typed out, and `AboutCreditsTest` fails if a routed game has no
  credited source.

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

- [x] **Settings still calls its section "Cache"** while it now holds only the image limit.
  *(fixed 2026-09-13)* It says "Image cache", matching the storage screen.
- [x] **The About / scope statement has nowhere to live.** *(fixed 2026-09-13)* See Features.

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
