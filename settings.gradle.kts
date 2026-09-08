rootProject.name = "CardBrowser"

pluginManagement {
	repositories {
		google {
			mavenContent {
				includeGroupAndSubgroups("androidx")
				includeGroupAndSubgroups("com.android")
				includeGroupAndSubgroups("com.google")
			}
		}
		mavenCentral()
		gradlePluginPortal()
	}
}

dependencyResolutionManagement {
	repositories {
		google {
			mavenContent {
				includeGroupAndSubgroups("androidx")
				includeGroupAndSubgroups("com.android")
				includeGroupAndSubgroups("com.google")
			}
		}
		mavenCentral()
		// Pulse is not on Maven Central.
		maven("https://maven.bitsycore.com/releases") {
			mavenContent { includeGroupAndSubgroups("com.bitsycore") }
		}
	}
}

// Four layers, and the two things you launch.
//
// :core has no Ktor, no Okio and no Compose in it: it is the domain vocabulary plus the provider
// contract, so a provider adapter can be written against it without inheriting the app's transport
// or storage choices.
include(":core")

// Everything with a socket or a file handle behind it: the shared HTTP stack, the two disk caches,
// saved preferences, and the repositories that decide between cache and network.
include(":data")

// One module per provider. Adding a game or a second source for an existing one adds a sibling here
// and changes nothing above it. See docs/ARCHITECTURE.md.
include(":providers:riftcodex")

// The other six, in the order they were asked for. Each is one `CardProvider`, one Koin `single`
// and one `ProviderRoute`; none of them changed a line of :core, :data or a screen.
//
// Cyberpunk TCG is absent on purpose and not because it was skipped -- see docs/PROVIDER_RESEARCH.md.
include(":providers:tcgdex")
include(":providers:scryfall")
include(":providers:optcg")
include(":providers:altered")
include(":providers:ygoprodeck")
include(":providers:wuwa")

// Compose UI and Pulse presentation logic, plus the Android/iOS/desktop targets it compiles to.
// The desktop entry point lives in its `desktopMain`; iOS is the framework `iosApp/` links against.
include(":composeApp")

// The Android application. Separate because AGP 9 forbids `com.android.application` in the same
// subproject as the Kotlin Multiplatform plugin.
include(":androidApp")
