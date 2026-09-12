# Compose Desktop on Kotlin/Native

An in-progress port to [compose-desktop-native](https://github.com/bitsycore/compose-desktop-native),
which runs Compose Desktop as a native binary instead of on the JVM — `mingwX64`, `linuxX64`,
`linuxArm64` and `macosArm64`.

**Nothing here affects the ordinary build.** The targets only exist under a Gradle property:

```bash
./gradlew -PnativeDesktop=true :core:compileKotlinLinuxX64
```

Without it, no native target is declared and `./gradlew build` is exactly what it was.

## Where it has got to

**It runs.** `composeApp.exe` is a native Windows binary with no JVM under it -- SDL3 and Skia in
place of AWT -- and it starts, opens its window and draws the game picker. That happened on
2026-09-12, after `compose-desktop-native` `1.12.0-3` published the mirrors that were missing.

```bash
./gradlew -PnativeDesktop=true :composeApp:linkDebugExecutableMingwX64
./gradlew -PnativeDesktop=true :composeApp:packageDebugComposeResourcesMingwX64
composeApp/build/bin/mingwX64/debugExecutable/composeApp.exe
```

Three things had to be found by running it, none of which fails a compile:

1. **SQLite is not supplied by anyone.** SQLDelight's `native-driver` sits on SQLiter, whose
   cinterop declares `sqlite3.h` and ships no implementation: Apple targets take libsqlite3 from the
   SDK and Linux from the distribution, and its manifest carries `linkerOpts` for `linux_x64` and
   `macos_x64` and none for `mingw_x64`. Twenty undefined symbols at link, every one of them
   SQLite's, and nothing else missing. `composeApp/sqlite-mingw.gradle.kts` fetches the
   amalgamation, checks it against the SHA3-256 sqlite.org publishes beside the file, and compiles
   it with the clang and MinGW sysroot Kotlin/Native already downloaded.
2. **Ktor has no engine on these targets.** The app declares okhttp, java and darwin; native
   desktop needs its own. `HttpClient {}` discovers an engine, an engineless binary finds none, and
   it surfaces as a global-initialiser failure that names nothing. WinHttp on Windows, Darwin on
   macOS, curl on Linux -- curl publishes `mingwX64` too and one shared dependency would compile,
   but it would then want libcurl to link against, which is the problem SQLite already cost a build
   script. WinHttp and Darwin are the system stacks and need nothing linked.
3. **The resource archive was empty.** The bridge's `data.kres` task zips *this project's* prepared
   resources, and `:composeApp` has none -- every logo lives in the `:games:*` module that owns the
   game. `composeApp/native-resources.gradle.kts` adds them; the fix belongs upstream, and says so.

What has *not* been exercised: anything past the first screen. No set has been downloaded, no card
store has been opened, no link has been clicked. The app being up is not the app working.

`linuxX64`, `linuxArm64` and `macosArm64` compile and have not been linked from here. Linux needs
libcurl and libsqlite3 present, which an ordinary distribution has; macOS needs neither. Both are
guesses and are written down as such.

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

Both of the pieces that used to be listed as missing are written, and one of them has run.

- **`NativeDesktopDriverFactory`** (`:database`, `nativeDesktopMain`) — SQLDelight's
  `native-driver`, the same one iOS uses. It hands SQLiter an explicit `basePath`, because the
  default is not where `AppStorage` says the cache lives, and sets WAL, `synchronous=FULL`, foreign
  keys and the busy timeout as *connection configuration* rather than as pragmas. That is the
  lesson from `DesktopDriverFactory`: SQLiter pools connections, and a pragma sent once configures
  one of them. **Unexercised** -- Koin builds it lazily and nothing has asked for a set yet.
- **`platformModule()`** (`:composeApp`, `nativeDesktopMain`) — the same dotted directory under
  `$HOME` the JVM desktop uses, deliberately, so the two desktop builds read one cache and can be
  compared. This one has run: the app starts, which means `AppStorage.prepare()` created those
  directories on Windows. The link opener picks its command from `Platform.osFamily`, and refuses a URL rather
  than escaping it if it carries anything a shell would read as more than text: the consumer is
  `system()`, and a card name is provider-supplied.

## The plan for the rest

Settled with the project owner on 2026-09-11 and delivered on 2026-09-12: pulse got native targets,
and coil, koin-compose and navigation3-ui are forks the bridge redirects to. What that plan did not
cover was the three things running the binary found — SQLite, the HTTP engine and the resource
archive — and all three are handled above.

## What is still unknown

**Almost everything past startup.** The window opens and the game picker draws. Nothing beyond that
has been tried: no provider call has been made over WinHttp, the card store has never been opened
on this target, and the link opener has never been asked to open anything.

The executable itself is declared by the bridge, not here: `compose.desktop { native { entryPoint
= ... } }` puts one on each of the four targets, and the same block carries the icon spec, which
builds a `.ico` from the PNGs the JVM distribution already uses and embeds it in the binary. It is
configured through `extensions.configure(ComposeDesktopNativeExtension::class.java)` rather than as
a `native { }` block, because Gradle only generates a typed accessor for a plugin named in a
`plugins { }` block and this one is applied imperatively, under the flag, from the root build.

The dark title bar is still JVM-only -- it uses JNA. The same `DwmSetWindowAttribute` call is
cinterop on Kotlin/Native, and rather less work than the JVM version was.

## Why the targets are opt-in rather than always on

Because the native build fetches and compiles SQLite, and because a target that is always declared
makes every ordinary `./gradlew build` carry work for a platform nobody is on that day. Without the
flag no native target is declared, `tasks --all` lists nothing for it, and neither of the two script
plugins is read -- so the ordinary build never reaches for the network.
