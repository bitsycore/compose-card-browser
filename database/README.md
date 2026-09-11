# `:database` — the SQLite card store

Complete sets and everything derived from them — pins, labels, card counts, the eviction budget —
live here in one SQLite database. The small metadata scopes (set lists, card detail, search pages,
per-set languages) stay in `MetadataCache`'s file cache, because they are few and tiny and the
whole cost was never there.

It began as a spike to decide whether to migrate at all, and the app switched over to it on
2026-09-11. The numbers below are what that spike measured, and they are why.

## What was measured

SQLDelight 2.3.2, one global database, against `MetadataCache` — same process, same machine, same
session, same 1000 sets × 150 printings (roughly Magic's shape, 150,000 rows). Measured
2026-09-11 by `SqlStoreBench`, which ran both stores back to back. The comparison half of that
bench went with the file cache's set records; what it produced is here.

| | the old file cache | this store | |
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

## The three things that blocked it, and what each answer is

[`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) § "The card store" lists what must not change.
Three of them were unmodelled when this was a spike; each now has code and a test that fails
without it.

**Corrupt database.** The real blocker, because it is the one place a database is strictly worse
than a file per record: there, a record that will not parse is one set, deleted and re-fetched.
Here, one bad page could be every set.

`CardStoreFactory` runs `PRAGMA integrity_check` once per process at startup. A store that will
not open or will not verify is **deleted and recreated, not repaired** — everything in it is
re-fetchable by definition, and a half-salvaged database is a store whose contents nobody can
characterise, which is the one thing this app must never serve. The caller is *told*
(`OpenedStore.wasRecovered`), because the records of what is on disk live in preferences: a store
that silently starts fresh leaves the download dialog reporting an import that is gone.

`journal_mode=WAL` gives back the property per-file atomic replacement gave for free — a process
killed mid-transaction leaves the last transaction unapplied and everything before it intact.

**Eviction.** Least recently *used*, ordered by an `accessed_at` column. That is a genuine
improvement rather than a port: the file cache kept access times in memory, so after a relaunch
every record sorted equally old and the order collapsed to whatever the filesystem listed.

**The pin budget.** A pinned set is outside the budget entirely, not merely skipped when evicting
— `unpinnedBytes` is `WHERE pinned = 0`. Counting downloads made one import exceed any sane
ceiling, after which every write evicted browsing records that together came nowhere near it.
`pinnedSets` carries a label so the storage screen can name what a download left even after
everything else is gone; the file cache learned that the hard way when its zero-byte pin markers
made kept records anonymous.

## Still not modelled, and still unverified

- **Migrations.** The schema has no version beyond SQLDelight's own. The switchover did not need
  one — a pre-store install is wiped once, by `CacheReconciler` — but the *next* schema change
  will.
- **Neither phone driver has ever run.** `AndroidSqliteDriver` takes its `Context` from
  `platformModule()` and `NativeSqliteDriver` compiles for both iOS targets, but the only driver
  that has opened a real file is the desktop one. That one is covered: `CardStoreRecoveryTest`
  opens, reopens, corrupts, truncates and recovers an actual database on disk — and the reopen
  case exists because the first version of the desktop driver called `Schema.create`
  unconditionally, which works on a fresh install and throws on every launch after it.
