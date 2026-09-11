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

**Every module below the UI compiles natively.** All 22 of them — `:core`, `:data`, `:database`,
the ten `:games:*` and the eight `:providers:*` — build for `linuxX64` and `macosArm64` today,
verified rather than assumed:

```bash
./gradlew -PnativeDesktop=true :core:compileKotlinLinuxX64 :data:compileKotlinLinuxX64 ...
```

That is the part worth knowing, because it says the porting problem is not in this codebase's own
layers. The data stack in particular came through untouched: coroutines, serialization, datetime,
Okio, Ktor and Koin's core all publish for all four targets, and so does SQLDelight — including a
`native-driver` for `mingwX64` and `linuxX64`, which was the piece most likely to have been
Apple-only.

`:composeApp` is the one module that does not build, and the reasons are all other people's
artifacts.

## What the bridge plugin does, and where it has to be applied

The plugin substitutes `org.jetbrains.compose.*` klibs for mirrored `com.bitsycore.compose.*` ones
on native targets. That works: `ui`, `foundation`, `material3` and `components-resources` all
resolved at `0.4.2`. `runtime` needs no mirror because JetBrains already publishes it natively.

The one non-obvious part of the setup: **the plugin has to be applied to every module that
*resolves* Compose klibs, not only to those that declare them.** The `:providers:*` modules depend
on a `:games:*` module, which depends on `:games:api`, which is where the Compose dependency is —
so each provider resolves Compose transitively and fails without the bridge, despite never
mentioning Compose itself.

## What is missing

Measured on 2026-09-11 against the versions this project pins. "macOS only" means the artifact
publishes `macosArm64` but not the Windows or Linux targets.

| Dependency | Status | Whose |
| --- | --- | --- |
| `com.bitsycore.lib:pulse`, `-viewmodel`, `-compose` | **No native targets at all** | ours |
| `com.bitsycore.skiko:skiko` | `mingwX64` only, and on an **authenticated** GitHub Packages repo | ours |
| `io.coil-kt.coil3:coil-compose` | macOS only | upstream |
| `io.insert-koin:koin-compose`, `-viewmodel` | macOS only | upstream |
| `org.jetbrains.androidx.navigation3:navigation3-ui` | macOS only | upstream |
| `org.jetbrains.androidx.navigationevent:navigationevent-compose` | macOS only | upstream |
| `org.jetbrains.compose.material:material-icons-core` | macOS only | upstream |
| `net.java.dev.jna:jna` | JVM only, by nature | n/a |

Notes on three of them:

- **Pulse is the one to fix first.** It is this project's own MVI library and it publishes no native
  targets whatsoever — not even the `macosArm64` that everything else manages. Until it does, no
  native target can link, so it gates the other seven regardless of what upstream does.
- **Skiko is only a Windows problem**, and only a credentials problem. `linuxX64` and `macosArm64`
  resolve Skia through the ordinary repositories; `mingwX64` wants `com.bitsycore.skiko:skiko` at a
  `-mingw.N` version that is not on `maven.bitsycore.com`. Adding the GitHub Packages repository and
  a token to `settings.gradle.kts` is the whole of it.
- **JNA is only used by the dark title bar**, in `desktopMain`, so it does not block anything. But
  the feature would need a native equivalent — on Kotlin/Native the same `DwmSetWindowAttribute`
  call is cinterop rather than JNA, and rather less work than the JVM version was.

**macOS is the closest.** Everything upstream that this app uses already publishes `macosArm64`, so
`macosArm64` is blocked on Pulse alone. Windows and Linux additionally need Coil, Koin's Compose
bindings, Navigation 3, NavigationEvent and the Material icons.

## What is still to write when the dependencies land

Two pieces of this project's own code, both small and both flagged in `Main.native.kt`:

- **`platformModule()` has no native-desktop actual.** It supplies `AppStorage`'s cache and
  preferences roots and a `LinkOpener`. Both are per-platform by nature.
- **`DriverFactory` has no native-desktop implementation.** SQLDelight publishes the driver for
  these targets, so this is a class, not a research problem.

## Why the targets are opt-in rather than always on

Because `:composeApp` cannot link, and a target that is always declared and always broken makes
`./gradlew build` fail for everyone, on every branch, for a port nobody is working on that day.
The property keeps the scaffolding in the repository and buildable on demand — which is what makes
the claim above ("every module below the UI compiles") something anyone can check in one command,
rather than something that rots.
