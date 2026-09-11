# Compose Desktop on Kotlin/Native

An in-progress port to [compose-desktop-native](https://github.com/bitsycore/compose-desktop-native),
which runs Compose Desktop as a native binary instead of on the JVM — `mingwX64`, `linuxX64`,
`linuxArm64` and `macosArm64`.

**Nothing here affects the ordinary build.** The targets only exist under a Gradle property:

```bash
./gradlew -PnativeDesktop=true :core:compileKotlinLinuxX64
```

Without it, no native target is declared and `./gradlew build` is exactly what it was. That is
deliberate — the app module cannot link yet, and a permanently red target is worse than an opt-in
one.

## Where it has got to

**Every module below the UI compiles natively, on all three platform families.** All 22 of them —
`:core`, `:data`, `:database`, the ten `:games:*` and the eight `:providers:*` — build for
`mingwX64`, `linuxX64` and `macosArm64`, verified rather than assumed.

That is the part worth knowing, because it says the porting problem is not in this codebase's own
layers. The data stack in particular came through untouched: coroutines, serialization, datetime,
Okio, Ktor and Koin's core all publish for all four targets, and so does SQLDelight — including a
`native-driver` for `mingwX64` and `linuxX64`, which was the piece most likely to have been
Apple-only.

`:composeApp` is the one module that does not build, and the reasons are all other people's
artifacts.

## What the bridge plugin substitutes

Read out of `compose-desktop-native-bridge-0.4.2.jar` rather than guessed, because guessing the
group name got this wrong once:

- `org.jetbrains.compose.*` → `com.bitsycore.compose.*` for `animation`, `animation-core`,
  `animation-graphics`, `components-resources`, `foundation`, `foundation-layout`, `material3`,
  `material-ripple`, `ui`, `ui-backhandler`, `ui-geometry`, `ui-graphics`, `ui-text`,
  `ui-tooling-preview`, `ui-unit`, `ui-util`.
- `org.jetbrains.androidx.navigation3:navigation3-ui` → **`com.bitsycore.navigation3:navigation3-ui`**.

`org.jetbrains.compose.runtime:runtime` needs no mirror — JetBrains already publishes it natively.

The one non-obvious part of the setup: **the plugin has to be applied to every module that
*resolves* Compose klibs, not only to those that declare them.** The `:providers:*` modules depend
on a `:games:*` module, which depends on `:games:api`, which is where the Compose dependency is —
so each provider resolves Compose transitively and fails without the bridge, despite never
mentioning Compose itself.

## The Skia fork, and the version that is not there

`mingwX64` needs the fork because upstream Skiko supports macOS and Linux but not MinGW. This is
**not** an authentication problem, which is what it first looked like:

- bridge `0.4.2` asks for `com.bitsycore.skiko:skiko:0.150.1-mingw.1`
- `maven.bitsycore.com` publishes `0.150.1-mingw.2`, and nothing else

So resolution fails on a version that was never published. Forcing the published one compiles, and
the root build does exactly that under the flag — see `SKIKO_MINGW_FORK` in `build.gradle.kts`.
**Delete that block when a bridge release points at a version that exists.**

## What is still missing

Measured on 2026-09-11 against the versions this project pins, by resolving `:composeApp` for
`mingwX64` — the hardest target — after the skiko pin above.

| Dependency | What it needs | Whose |
| --- | --- | --- |
| `com.bitsycore.lib:pulse`, `-viewmodel`, `-compose` | native targets published at all | ours |
| `io.coil-kt.coil3:coil-compose`, `-network-ktor3`, `-svg` | a mirror + an entry in the bridge's table | upstream, via a mirror |
| `io.insert-koin:koin-compose`, `-viewmodel` | same | upstream, via a mirror |
| `org.jetbrains.androidx.navigationevent:navigationevent-compose` | same | upstream, via a mirror |

Notes:

- **Pulse gates everything.** It publishes no native targets whatsoever, not even the `macosArm64`
  that every upstream dependency here manages, so no target can link until it does.
- **Coil's Linux story is partial and its Windows story is not.** `coil-network-ktor3` and
  `coil-svg` do publish `linuxX64`/`linuxArm64`; `coil-compose` is macOS-only, and none of the three
  publishes `mingwX64`.
- **`navigationevent-compose` is predictive back.** NavigationEvent is the multiplatform successor
  to Android's `OnBackPressedDispatcher`, and the `-compose` artifact is the binding `NavDisplay`
  uses to drive a back *gesture* — which is what `predictivePopTransitionSpec` in `App.kt` renders.
  No code here names it: it is declared because Navigation 3 needs it, and it also arrives
  transitively through `navigation3-ui`. On a desktop with no back gesture it does nothing at
  runtime, but `NavDisplay` still has to link against it.
- **`material-icons-core` is gone**, so it is off this list. Every icon is generated from Material
  Symbols into `AppIcons` by `composeApp/tools/gensymbols.py`, and the app now depends on no icon
  library on any platform. That turned out to be more than a dozen glyphs: the twenty-four icons
  already embedded were built with `materialIcon`/`materialPath`, which are *from* that library, so
  dropping it broke icons that had nothing to do with it. All thirty-six are built from
  `ImageVector.Builder` now.

**macOS is the closest**: every upstream dependency already publishes `macosArm64`, so it is blocked
on Pulse alone.

## The plan for the rest

Settled with the project owner on 2026-09-11:

| Dependency | Plan |
| --- | --- |
| `com.bitsycore.lib:pulse` | a Desktop Native compatibility target, in its own repository |
| `io.coil-kt.coil3:*` | fork and redirect, from compose-desktop-native |
| `io.insert-koin:koin-compose`, `-viewmodel` | fork and redirect, from compose-desktop-native |
| `org.jetbrains.androidx.navigationevent:navigationevent-compose` | fork and redirect, from compose-desktop-native |

Each of the bottom three is an entry in the bridge's substitution table, alongside the
`navigation3-ui` one that is already there — so nothing in this repository has to change for them to
start resolving.

## What is still to write on this side

Two pieces of this project's own code, both small and both flagged in `Main.native.kt`:

- **`platformModule()` has no native-desktop actual.** It supplies `AppStorage`'s cache and
  preferences roots and a `LinkOpener`. Both are per-platform by nature.
- **`DriverFactory` has no native-desktop implementation.** SQLDelight publishes the driver for
  these targets, so this is a class, not a research problem.

The dark title bar is a third, smaller one: it uses JNA, which is JVM-only. The same
`DwmSetWindowAttribute` call is cinterop on Kotlin/Native, and rather less work than the JVM version
was.

## Why the targets are opt-in rather than always on

Because `:composeApp` cannot link, and a target that is always declared and always broken makes
`./gradlew build` fail for everyone, on every branch, for a port nobody is working on that day.
The property keeps the scaffolding in the repository and buildable on demand — which is what makes
the claim above ("every module below the UI compiles") something anyone can check in one command,
rather than something that rots.
