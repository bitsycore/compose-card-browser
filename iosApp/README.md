# iOS shell

Three files, and none of them contain any of the app. Everything TCG Explorer does lives in
`:composeApp` and crosses into Swift as a single `UIViewController` from
[`MainViewController.kt`](../composeApp/src/iosMain/kotlin/com/bitsycore/tcgexplorer/MainViewController.kt).

- `iosApp/iOSApp.swift` — the `@main` entry point. Starts Koin, shows `ContentView`.
- `iosApp/ContentView.swift` — wraps the Compose view controller for SwiftUI.
- `iosApp/Info.plist` — bundle metadata.

## Status, as of 2026-09-14

`iosApp.xcodeproj` exists. It was absent for the whole of the project's history before this, and
the reason was good at the time: the app was developed on Windows, where no part of the iOS build
can be run, and an unverified `project.pbxproj` would have looked finished and failed on first open.

What is confirmed:

- **The simulator build links.** `xcodebuild -project iosApp/iosApp.xcodeproj -scheme TCG Explorer
  -sdk iphonesimulator -configuration Debug build` succeeded and produced `TCG Explorer.app`. This is
  the first time the iOS target has linked at all.
- **A device build installs and launches.** The app got as far as Compose's composition and Koin's
  graph on a physical iPhone, and threw there twice. Both are fixed below.

What is not:

- **The app has never reached a screen.** Neither fix below has been re-run.
- Everything under "What to check first" remains unlooked-at.

## Three things the copied project got wrong

`project.pbxproj` was adapted from another app's and arrived carrying three of its settings.

- **`PRODUCT_NAME` was unset.** Xcode then builds a bundle with an empty name, and
  `EXECUTABLE_PATH` collapses onto the bundle directory, so the task that creates the wrapper and
  the task that links the executable claim the same path:

  ```
  Multiple commands produce '…/Build/Products/Debug-iphoneos/.app'
  ```

  The empty name in the message is the tell. The fix is `PRODUCT_NAME` in both target
  configurations, and `TCG Explorer.app` for the product reference and the scheme's two
  `BuildableName` entries.
- **`OTHER_LDFLAGS` carried `-framework "MapLibre"`.** This app has no map.
- **`DEVELOPMENT_ASSET_PATHS` named a `Preview Content` folder** that does not exist here.

## Insets belong to Compose, not to SwiftUI

`ContentView` uses `.ignoresSafeArea()`. That is the counterpart to `enableEdgeToEdge()` in
`MainActivity` — iOS has no edge-to-edge flag to set, so the only way to hand Compose the whole
screen is to stop SwiftUI shrinking the view.

It was `.ignoresSafeArea(.keyboard)`, which is what the JetBrains template ships, and on an iPhone
SE
that put a status bar's worth of empty space above the top bar: SwiftUI inset the hosting view and
Compose then inset the content inside it again. Nothing in `commonMain` overrides
`contentWindowInsets` or a top bar's `windowInsets`, so the Material 3 defaults are the app's single
source of insets, on both platforms.

## Two runtime traps, both fatal, neither a build error

- **Compose Multiplatform requires `CADisableMinimumFrameDurationOnPhone`.** Without `<true/>` in
  `Info.plist`, its own sanity check throws `IllegalStateException` during composition: the app
  launches, shows nothing and dies. The key is iOS's opt-in to refresh rates above 60 Hz.
- **SQLiter takes a database *name*, not a path** — see `IosDriverFactory`. It rejects a name
  containing a separator, so handing it `AppStorage.databaseFile` threw *"contains a path
  separator"* up through four layers of Koin. The directory belongs in `basePath`, and putting it
  there also keeps `create` and `delete` pointing at the same file.

## Settings that matter

Everything else in the target is Xcode's default. These are not.

| Setting                                  | Value                                                                          | Why                                                                                                                                                                                                                                    |
|------------------------------------------|--------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Run Script phase, before Compile Sources | `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`                     | Builds the Kotlin framework whenever Xcode builds                                                                                                                                                                                      |
| `KOTLIN_FRAMEWORK_BUILD_TYPE`            | `debug` / `release`, per configuration                                         | Tells that task which Kotlin binary to build, so an archive gets an optimised framework. Without it the plugin falls back to matching the configuration's *name*, which works only while they are called exactly `Debug` and `Release` |
| `FRAMEWORK_SEARCH_PATHS`                 | `$(SRCROOT)/../composeApp/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)` | Where that task leaves the framework                                                                                                                                                                                                   |
| `ENABLE_USER_SCRIPT_SANDBOXING`          | `NO`                                                                           | Gradle writes outside the sandbox                                                                                                                                                                                                      |
| `OTHER_LDFLAGS`                          | `-ObjC -l"c++" -lsqlite3 -framework "ComposeApp"`                              | `ComposeApp` is static (`isStatic = true`), so it is linked rather than embedded. SQLiter is a cinterop wrapper over the system sqlite3, which is what `-lsqlite3` supplies                                                            |
| `EXCLUDED_ARCHS[sdk=iphonesimulator*]`   | `x86_64`                                                                       | `iosX64` is not a declared target: Compose Multiplatform 1.12.0 publishes no Intel-simulator artifact, so an Intel Mac cannot run this in a simulator at all                                                                           |

`IPHONEOS_DEPLOYMENT_TARGET` is 15.0, Xcode's default, and is probably too low: the simulator link
warned that Compose's bundled ICU object is built for iOS 18.5. It linked anyway, and it has not
been changed.

## What to check first

The three things most likely to differ from desktop, none of them yet looked at:

- The card grid's `GridCells.Adaptive` column count at phone widths.
- Japanese and Korean glyphs, if a provider ever supplies them — desktop and iOS resolve fonts
  differently, and this app has never had non-Latin card text to render.
- `IosLinkOpener` actually opening Safari from the Cardmarket button.

## App icon

`iosApp/Assets.xcassets/AppIcon.appiconset/` holds the icon set and its `Contents.json`. The
synchronised folder group picks the catalogue up on its own and `ASSETCATALOG_COMPILER_APPICON_NAME`
is set, so nothing else was needed: the simulator build produced `Assets.car` and
`AppIcon60x60@2x.png` in the bundle, with `CFBundleIconName` in its `Info.plist`.

`ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME` names an `AccentColor` the catalogue does not
contain. Another leftover, and harmless.
