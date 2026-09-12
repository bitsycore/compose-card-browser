# Compose Desktop on Kotlin/Native

An experimental port to [compose-desktop-native](https://github.com/bitsycore/compose-desktop-native),
which runs Compose Desktop as a native binary instead of on the JVM — `mingwX64`, `linuxX64`,
`linuxArm64` and `macosArm64`.

**Off by default.** `nativeDesktop=false` in `gradle.properties`; flip it there or pass
`-PnativeDesktop=true`. With it off no native target is declared, the bridge plugin is not applied,
and nothing here is read.

```bash
./gradlew -PnativeDesktop=true :composeApp:linkReleaseExecutableMingwX64 \
    :composeApp:packageReleaseComposeResourcesMingwX64
composeApp/build/bin/mingwX64/releaseExecutable/composeApp.exe
```

## State

Windows runs: the binary starts, opens its window and draws the game picker (2026-09-12, bridge
`1.12.0-3`). Nothing past the first screen has been exercised — no provider call, no card store, no
link followed. `linuxX64`, `linuxArm64` and `macosArm64` compile and have not been linked.

## Where it lives

Everything the flag turns on is in four places:

- **`build.gradle.kts`** — declares the four targets on every multiplatform module, applies the
  bridge plugin to each, and substitutes `androidx.navigationevent:navigationevent-compose` for
  JetBrains' wrapper, which is macOS-only.
- **`composeApp/native-desktop.gradle.kts`** — fetches, checks and compiles SQLite, and adds the
  other modules' Compose resources to `data.kres`.
- **`composeApp/build.gradle.kts`** — the entry point and icon, the window dependency, the
  `nativeDesktop` source set, and the link inputs.
- **`nativeDesktopMain` source sets** in `:composeApp` and `:database` — entry point, Koin
  bindings, SQLDelight driver.

## The three things that are not obvious

**SQLite is supplied by nobody.** SQLDelight's `native-driver` sits on SQLiter, whose cinterop
declares `sqlite3.h` and ships no implementation: Apple targets take libsqlite3 from the SDK, Linux
from the distribution, MinGW from nowhere. So the amalgamation is downloaded (pinned, checked
against the SHA3-256 sqlite.org publishes) and compiled with the clang and sysroot Kotlin/Native
already downloaded to link with.

**Ktor needs an engine per family.** WinHttp on Windows, Darwin on macOS, curl on Linux. curl
publishes `mingwX64` too and one shared dependency would compile — and would then want libcurl to
link against. `HttpClient {}` discovers its engine, and an engineless binary fails in a global
initialiser that names nothing.

**Anything passed through `linkerOpts` is a string to Gradle.** The SQLite library and the bridge's
icon object both reach the linker that way, so both are declared as link inputs. Without that a
changed icon leaves the old binary in place and the link reports itself up to date — which is what
made a correct icon look broken.

## Upstream

Two things belong in the bridge rather than here: `data.kres` should gather resources from
dependency modules the way Compose does for Apple targets, and `compileComposeNativeIconResource`
should declare its output as an input of the native link task.
