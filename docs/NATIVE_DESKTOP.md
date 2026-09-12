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

**Every module compiles for `mingwX64`, including `:composeApp`.** That is new as of 2026-09-12:
`compose-desktop-native` `1.12.0-3` publishes the mirrors that were missing, so every dependency
this app declares now resolves natively. The table of blockers that used to be here is empty.

What is left is the *link*, and it is one library:

```
ld.lld: error: undefined symbol: sqlite3_step
ld.lld: error: undefined symbol: sqlite3_open_v2
...
```

Twenty undefined symbols, every one of them SQLite's. Nothing from Compose, Skiko, SDL, Coil, Koin,
Pulse or Navigation is missing — skiko's DLL and ICU data are provisioned by the bridge's own tasks
and the rest links. SQLDelight's `native-driver` sits on SQLiter, whose cinterop declares
`sqlite3.h` and **no implementation**: Apple targets get libsqlite3 from the SDK, Linux from the
distribution, and Windows from nowhere. Its manifest carries `linkerOpts` for `linux_x64` and
`macos_x64` and none for `mingw_x64`.

So a Windows native binary needs a SQLite to link against, from somewhere. Who supplies it is the
open question — this repository vendoring the amalgamation and compiling it, or
`compose-desktop-native` publishing it the way it already publishes skiko.

## What the bridge plugin substitutes

Read out of `compose-desktop-native-bridge-1.12.0-3.jar` rather than guessed, because guessing the
group name got this wrong once:

- `org.jetbrains.compose.*` → `com.bitsycore.compose.*` for `animation`, `animation-core`,
  `animation-graphics`, `components-resources`, `foundation`, `foundation-layout`, `material3`,
  `material-ripple`, `ui`, `ui-backhandler`, `ui-geometry`, `ui-graphics`, `ui-text`,
  `ui-tooling-preview`, `ui-unit`, `ui-util`.
- `org.jetbrains.androidx.navigation3:navigation3-ui` → `com.bitsycore.navigation3:navigation3-ui`.
- `io.coil-kt.coil3:*` → `com.bitsycore.coil3:*` — `coil`, `coil-core`, `coil-compose`,
  `coil-compose-core`, `coil-network-core`, `coil-network-ktor3`, `coil-svg`.
- `io.insert-koin:koin-compose`, `-viewmodel`, `-navigation3`, `koin-core-viewmodel` →
  `com.bitsycore.koin:*`.
- `com.bitsycore.lib:pulse`, `-compose`, `-viewmodel`, `-savedstate` → `com.bitsycore.pulse:*`.

`org.jetbrains.compose.runtime:runtime` needs no mirror — JetBrains already publishes it natively.

The bridge version tracks Compose: `bridge-version.properties` inside the jar reads
`compose=1.12.0`, `composeMaterial3=1.12.0-alpha03`, which is exactly what this project pins.

The one non-obvious part of the setup: **the plugin has to be applied to every module that
*resolves* Compose klibs, not only to those that declare them.** The `:providers:*` modules depend
on a `:games:*` module, which depends on `:games:api`, which is where the Compose dependency is —
so each provider resolves Compose transitively and fails without the bridge, despite never
mentioning Compose itself.

### The one substitution that is ours

`org.jetbrains.androidx.navigationevent:navigationevent-compose` is not in the bridge's table, and
does not need to be. JetBrains' wrapper is macOS-only on every version it has published; Google's
own `androidx.navigationevent:navigationevent-compose` publishes `mingwx64`, `linuxx64`,
`linuxarm64` and `macosarm64` — including at `1.1.0`, which is the version this project already
pins, and every version back to `1.0.0-alpha06`. Checked against Google's maven on 2026-09-12.
So the root build substitutes the coordinates and keeps the version, under the flag only. Nothing
is forked.

## The Skia fork

`mingwX64` needs it because upstream Skiko supports macOS and Linux but not MinGW. Bridge
`1.12.0-3` asks for `com.bitsycore.skiko:skiko:0.150.1-mingw.2`, which is what
`maven.bitsycore.com` publishes, so there is nothing to do. The forced version this build carried
for bridge `0.4.2` — which asked for a `mingw.1` that was never published — is deleted.

## What this side had to write, and did

Both of the pieces that used to be listed as missing are written. Neither has been *run*.

- **`NativeDesktopDriverFactory`** (`:database`, `nativeDesktopMain`) — SQLDelight's
  `native-driver`, the same one iOS uses. It hands SQLiter an explicit `basePath`, because the
  default is not where `AppStorage` says the cache lives, and sets WAL, `synchronous=FULL`, foreign
  keys and the busy timeout as *connection configuration* rather than as pragmas. That is the
  lesson from `DesktopDriverFactory`: SQLiter pools connections, and a pragma sent once configures
  one of them.
- **`platformModule()`** (`:composeApp`, `nativeDesktopMain`) — the same dotted directory under
  `$HOME` the JVM desktop uses, deliberately, so the two desktop builds read one cache and can be
  compared. The link opener picks its command from `Platform.osFamily`, and refuses a URL rather
  than escaping it if it carries anything a shell would read as more than text: the consumer is
  `system()`, and a card name is provider-supplied.

The dark title bar is still JVM-only: it uses JNA. The same `DwmSetWindowAttribute` call is
cinterop on Kotlin/Native, and rather less work than the JVM version was.

## The plan for the rest

Settled with the project owner on 2026-09-11 and now delivered: pulse got native targets, and
coil, koin-compose and navigation3-ui are forks the bridge redirects to. What that plan did *not*
cover is SQLite on Windows, which is the single open item above.

## What is still unknown

**Nothing here has run.** `:composeApp` compiles for `mingwX64` and links everything except SQLite;
no binary has been produced, so the window, the storage paths, the link opener and the card store
are all unexercised. The first run is likely to find things, in the way the first Android run of
the card store did.

`linuxX64`, `linuxArm64` and `macosArm64` have not been linked from here either — only compiled.
Linux is likely to link where Windows does not, since SQLiter's manifest carries `linkerOpts` for
it and a distribution supplies `libsqlite3`; that is a guess and is written down as one.

## Why the targets are opt-in rather than always on

Because `:composeApp` cannot link, and a target that is always declared and always broken makes
`./gradlew build` fail for everyone, on every branch, for a port nobody is working on that day.
The property keeps the scaffolding in the repository and buildable on demand — which is what makes
the claim above ("every module below the UI compiles") something anyone can check in one command,
rather than something that rots.
