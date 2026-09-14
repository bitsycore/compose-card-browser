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

- [x] **`ArtworkTreatment.STANDARD` cannot be filtered for in a stored browse.**
  *(fixed 2026-09-13)* Confirmed and worse than described: `Artwork.treatment` has no default, so
  it is always serialised, and the `:standardArt` predicate looked for the field's *absence* — so
  the chip matched nothing in every game, while `gameHasTreatment` read the payload and cheerfully
  offered it. Standard art is a value like any other now, and a test asserts every treatment the
  sheet is offered actually matches something.

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

- [x] **`localSetFacts` issues one query per set.** *(measured 2026-09-13, left alone)*
  **80.7 ms** for 988 sets, half held in two languages, against a real database —
  `LocalSetFactsBench`. And it is rarely on the path: `SetFactsWarmer` reads it ahead of the set
  list and memoises it per catalogue, so the cost is paid on a cold miss only, inside a coroutine,
  and decides when the "saved" marks appear rather than when the list draws.

  Not optimised, deliberately. The batch version exists — `storedSetsFor(game)` already answers all
  of it in one query — but wiring it in beside the per-set methods would leave two implementations
  of "is this set saved", which is the exact bug class fixed three times this week (costs, text,
  treatment). Worth revisiting if a phone measures far worse than this; the bench is there to ask.

- [ ] **The advanced search's text half uses no index, deliberately.** `name_folded LIKE '%x%'` has
  a leading wildcard, which no B-tree index can serve. Listed so it stays a decision. FTS5 would
  have to work on Android's bundled SQLite, the JVM driver and both native ones, and three of those
  four cannot be exercised here.

---

## Cleanup

- [x] **`CardRepository.kt` was 2057 lines.** *(2026-09-13)* The bulk import is its own class:
  1797 + 384. It was the one part with a lifecycle of its own — a scratch directory and sixty-four
  shard files — where the rest of the class is "read a thing, cache a thing". `dedupedPrintings`
  and `resolvedLanguage` went top-level rather than being copied, which also closed a duplicated
  `effectiveLanguage`. Still large; split again only where there is a seam, not for the number.
- [x] **`SetListScreen` built `DownloadRequest`s in the composition layer.** *(fixed 2026-09-13)*
  The rules — resolving the language the source really answers in, splitting records from pictures,
  one job per language — are in `SetListViewModel`, and the queue reaches the screen as state. The
  binder reads one thing from Koin now, a game's logo, which is a painter rather than data.
  `SetListDownloadTest` asserts the rules for the first time; the claim that this "cannot be
  exercised without a device" was wrong.
- [ ] **`SetListScreen.kt` is still 1507 lines.** Large, but it is now one composable tree with no
  logic in it. Split by section if it grows again.
- [x] **`CacheUsage.metadataEntries` is computed and displayed nowhere.** *(removed 2026-09-13)*
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

- [x] **Decide the app's name.** *(settled 2026-09-14)* **TCG Explorer**, and
  `com.bitsycore.tcgexplorer`.

  It was *Toploader* for a day. That name is taken on the App Store, which is the one kind of
  collision there is no arguing with — so the whole rename ran a second time.

  Checked and rejected along the way: *Cardshelf*, *Cardstock*, *Near Mint*, *Riffle*, *Codex* and
  *Toploader* are taken; *TCG Browser* is free on both stores but collides with a dormant WoW TCG
  site and is unsearchable and unownable. Two lessons worth keeping: **anything starting with
  "Card" is gone**, and **check the App Store, not just the web** — Toploader survived a web search
  and did not survive Apple.

  A note for whoever renames this next: display strings take the space ("TCG Explorer"), everything
  that is also a path does not (`TCGExplorer` for the Gradle root, the jpackage name, the Xcode
  target, product and scheme). In a `.pbxproj`, a value with a space has to be quoted --
  `INFOPLIST_KEY_CFBundleDisplayName = "TCG Explorer";` -- and that is the only one that has one.

