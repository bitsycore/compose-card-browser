# iOS shell

Three files, and none of them contain any of the app. Everything CardBrowser does lives in
`:composeApp` and crosses into Swift as a single `UIViewController` from
[`MainViewController.kt`](../composeApp/src/iosMain/kotlin/com/bitsycore/cardbrowser/MainViewController.kt).

- `iosApp/iOSApp.swift` — the `@main` entry point. Starts Koin, shows `ContentView`.
- `iosApp/ContentView.swift` — wraps the Compose view controller for SwiftUI.
- `iosApp/Info.plist` — bundle metadata.

## There is no `.xcodeproj` in this repository, on purpose

It was developed on Windows, where no part of the iOS build can be run: Kotlin/Native cannot
produce Apple binaries off a Mac, and Xcode does not exist here. A `project.pbxproj` is a large
generated file with build-phase references that are easy to get subtly wrong and impossible to
check without Xcode, so shipping an unverified one would be worse than shipping none — it would
look finished and fail on first open.

**What this means concretely: the iOS target compiles nowhere in this repository's history. It is
unverified.** The Kotlin is written and the Swift is written; whether they link has not been
demonstrated.

## Creating the project on a Mac

1. Confirm the shared framework builds at all — this is the first thing to check, and the first
   thing that will fail if something is wrong:

   ```bash
   ./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
   ```

   The framework lands in
   `composeApp/build/bin/iosSimulatorArm64/debugFramework/ComposeApp.framework`.

2. In Xcode, create a new iOS App named `iosApp` in the `iosApp/` directory (SwiftUI lifecycle,
   Swift). Replace the generated `ContentView.swift` and `iosAppApp.swift` with the two files
   already here, and point the target at the `Info.plist` here.

3. Add a Run Script build phase *before* "Compile Sources", so Gradle builds the framework
   whenever Xcode does:

   ```bash
   cd "$SRCROOT/.."
   ./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
   ```

4. Set `Framework Search Paths` to
   `$(SRCROOT)/../composeApp/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)` and add
   `ComposeApp.framework` to "Frameworks, Libraries, and Embedded Content" as *Do Not Embed*
   (the framework is static — see `isStatic = true` in `composeApp/build.gradle.kts`).

5. Build and run on an **Apple Silicon** simulator or a device. `iosX64` is not a declared target:
   Compose Multiplatform 1.12.0 does not publish an Intel-simulator artifact, so an Intel Mac
   cannot run this in a simulator at all.

## What to check first when it does run

The three things most likely to differ from desktop:

- The card grid's `GridCells.Adaptive` column count at phone widths.
- Japanese and Korean glyphs, if a provider ever supplies them — desktop and iOS resolve fonts
  differently, and this app has never had non-Latin card text to render.
- `IosLinkOpener` actually opening Safari from the Cardmarket button.

## App icon

`iosApp/Assets.xcassets/AppIcon.appiconset/` holds the icon set and its `Contents.json`, in the
layout Xcode expects. It is already in the right place: when a project is generated here, add
`Assets.xcassets` to the target's resources and set **App Icons Source** to `AppIcon`, and nothing
else is needed.

Like everything else under `iosApp/`, this has **not** been verified in Xcode -- there is no Mac on
the machine this was built on. The files are the icon kit's own output, unmodified.
