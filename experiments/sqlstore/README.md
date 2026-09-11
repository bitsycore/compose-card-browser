# `:experiments:sqlstore` — a SQLite spike

**Nothing depends on this module.** It exists to answer, with numbers, whether the file-per-record
cache should become a database — and to find out what a native driver does to the iOS targets
before anything commits to one.

Delete it, or promote it, but do not let it rot in between.

## What was measured

SQLDelight 2.3.2, one global database, against `MetadataCache` — same process, same machine, same
session, same 1000 sets × 150 printings (roughly Magic's shape, 150,000 rows). Measured
2026-09-11 by `SqlStoreBench`, which runs both stores back to back.

| | file cache | SQLite | |
| --- | --- | --- | --- |
| Write the catalogue | **1.5 s** | 12.6 s | 8.4× slower |
| On disk | **99.1 MB** | 139.8 MB | 1.4× bigger |
| Open one set | **10.7 ms** | 16.7 ms | 1.6× slower |
| Storage screen counts | 2456 ms | **14.4 ms** | **170× faster** |
| Filtered cross-set search | *not possible* | **15.0 ms** | — |
| Name search across 150k rows | *name-only, in memory* | 17.6 ms | — |

## What the numbers say

**The write cost is real and is the price of everything else.** 8.4× slower, and it is not a
surprise: a set is one file write today and 150 `INSERT`s inside a transaction there. It matters
less than it looks — 12.6 s sits inside a bulk import whose *download* is minutes — but it is the
one number that gets worse, and a batched insert would be the first thing to try if it ever bit.

**The storage screen is the clearest win.** 2456 ms to 14 ms. That screen is slow today for a
structural reason no amount of tuning fixes: it walks a directory and stats every file, and a
Magic import puts three files per set in it. In SQL it is `COUNT(*)`.

**Advanced search is not an improvement, it is a capability.** The app's cross-set search matches
names and says so on screen, because the alternative is loading every cached set off disk and
filtering in memory. `searchPrintings` does contains, *not*-contains, type, rarity, a cost range
and domain in one indexed query, in 15 ms across 150,000 rows. There is no version of that on a
filesystem.

**iOS compiles.** `iosArm64` and `iosSimulatorArm64` both build with `native-driver` referenced
from `iosMain`, not merely declared. That is compilation, not linking — the framework has still
never been linked and this does not change that, but the dependency resolves and the Kotlin
compiles, which is the part that could have failed outright.

## One database, not one per game

Per-game files would multiply the count without helping a single query. Every query here already
filters by `game`, which is indexed, so the split buys nothing at read time — and it costs the one
thing a database is uniquely good at: asking a question across games. It also multiplies the
migration surface by ten.

## What a migration must still answer

The brief in [`docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) § "If this becomes a database"
lists five things that must not change. This spike models the first and fifth — `(provider, set,
language)` identity, and nullable columns so *unknown* stays distinct from *absent* — and
deliberately models none of the others:

- **Eviction** is not implemented. The pin flag exists; the budget does not.
- **Corrupt-database recovery** is the unanswered one, and it is the serious one. Today a corrupt
  record is one set, deleted and re-fetched. A corrupt database is every set.
- **Interrupted writes** are covered by a per-set transaction, which matches what per-file atomic
  replacement already gives.
