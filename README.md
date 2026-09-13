# CardBrowser

A card browser for ten trading card games, written once and running on Android, iOS and desktop.

Kotlin Multiplatform with Compose Multiplatform: one UI and all logic in `commonMain`. There is no
backend — the app talks to each game's public card database directly, and keeps what you download
on your own device.

**Browsing only.** No accounts, no collection tracking, no prices, no deckbuilding.

---

## The games

| Game | Source | Notes |
| --- | --- | --- |
| Riftbound | [Riftcodex](https://riftcodex.com/) | English only |
| Pokémon | [TCGdex](https://tcgdex.dev/) | 11 languages, four product lines |
| Magic: The Gathering | [Scryfall](https://scryfall.com/) | 11 languages, bulk download |
| One Piece Card Game | [OPTCG API](https://optcgapi.com/) | |
| Altered | [Altered TCG Card Database](https://github.com/PolluxTroy0/Altered-TCG-Card-Database) | 5 languages |
| Yu-Gi-Oh! | [YGOPRODeck](https://ygoprodeck.com/) | 7 languages |
| Wuthering Waves TCG | [UCP](https://wwcg.ucp-jp.com/jp/card) | Card data ships inside the app |
| Disney Lorcana | [TCGCSV](https://tcgcsv.com/) | |
| Cyberpunk TCG | [TCGCSV](https://tcgcsv.com/) | |
| World of Warcraft TCG | [TCGCSV](https://tcgcsv.com/) | Name, number and rarity only |

What each source can and cannot do, in full: [docs/PROVIDERS.md](docs/PROVIDERS.md).

---

## What it does

- **Browse a game's sets**, searchable, with favourites you can reorder and a fast scroller for a
  988-set catalogue.
- **Browse a set's cards** as a grid or a list, at three sizes, with filters drawn from what is
  actually in the set: type, rarity, the game's colour axis, cost, artwork treatment.
- **Search across sets**, either on the server where the source supports it or across everything
  you have downloaded — and the app says which of the two it did.
- **Read one card** with its art, rules text, stats, printings and a link to Cardmarket where the
  game has one.
- **Download for offline use**, per set or a whole game, choosing which languages you want for the
  records and which for the pictures. A queue screen shows what is running.
- **See what is on the device** and clear it, with a per-game breakdown and an eviction budget.

---

## Honesty

The app is built so it never states something it does not know, and that shapes what you see:

- A language is shown as confirmed only when the source actually served it. Asking for Korean and
  getting English means the card says English.
- "Unknown" and "unavailable" are different, and are drawn differently.
- Results filtered from part of a set are labelled partial, with the real set size beside them.
- A cross-set search that only looked at your downloads says so, with a count.
- Two printings are the same card because the source said so, never because they share a name.

---

## Build and run

Requires JDK 21 and the Android SDK. On Windows, `local.properties` needs forward slashes
(`sdk.dir=C:/Users/you/AppData/Local/Android/Sdk`).

```bash
./gradlew :composeApp:run          # desktop
./gradlew :androidApp:assembleDebug
./gradlew build -x lint            # everything, all targets
./gradlew desktopTest              # the test suite
```

iOS compiles in every build but has never been linked or run — see
[iosApp/README.md](iosApp/README.md). An experimental Kotlin/Native desktop target exists and is off
by default: [docs/NATIVE_DESKTOP.md](docs/NATIVE_DESKTOP.md).

Live checks against the real APIs are excluded from the ordinary run and have one task each, for
example `./gradlew :providers:scryfall:liveProviderTest`. They need a network and hit someone
else's server.

Key versions: Kotlin 2.4.20, Compose Multiplatform 1.12.0, AGP 9.4.0, Ktor 3.5.2,
SQLDelight 2.3.2, Koin 4.2.2, Coil 3.6.2. `minSdk` 24.

---

## Known limitations

- **iOS is unverified.** The Kotlin compiles; nothing has ever been linked or run.
- **Only three sources publish set symbols** — Scryfall, TCGdex and YGOPRODeck. Every other set row
  shows its code in a tinted tile, which is a fallback and not a logo.
- **Card art is whatever the source publishes.** There is no higher-resolution fetch, because most
  of these sources do not have one.
- **Cardmarket links exist for five games.** Cardmarket answers 403 to every scripted request, so a
  link is only added where a human confirmed the URL in a browser. Altered has no Cardmarket
  section at all — that is checked, not missing.
- **Duel Masters is not included.** No source was found with both images and coverage; the
  measurements are in [docs/PROVIDER_RESEARCH.md](docs/PROVIDER_RESEARCH.md).

---

## Attribution

Every source is credited in the app, on the game list and in Settings, with the wording each source
asks for. CardBrowser is not affiliated with or endorsed by any game's publisher, and none of these
sources is an official one unless it says so.

The ten bundled game logos are the publishers' trademarks, used to identify the publishers' own
games. Three came from Wikimedia Commons with their licences checked individually; the other seven
were supplied by the project owner from third-party sites and carry no verified licence. The
distinction is documented in `GameArt`'s KDoc, and anyone redistributing this app should make their
own decision about them.

---

## For contributors and agents

[CLAUDE.md](CLAUDE.md) is the orientation document: layering rules, conventions, the build
commands, and the traps that have already cost a debugging session.
