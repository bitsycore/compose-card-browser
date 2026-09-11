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
		// The compose-desktop-native bridge plugin, which is not on the plugin portal.
		maven("https://maven.bitsycore.com/releases") {
			mavenContent { includeGroupAndSubgroups("com.bitsycore") }
		}
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

// One module per game: what the app knows about the game itself, as opposed to about any source
// that serves it. Its vocabulary, its rarity ladder, its Cardmarket segment, its logo.
//
// These exist so :core can name no game at all. It holds the mechanisms -- how to rank a rarity,
// how to label a stat, how to build a marketplace URL -- and every fact those mechanisms consume
// arrives from one of these modules through a `GameProfile`. Adding a game is a module here plus a
// routing entry; nothing shared changes, and there is no table anywhere to forget to extend.
//
// `:games:api` is where the Compose dependency a bundled logo needs is confined; see its build file.
include(":games:api")
include(":games:riftbound")
include(":games:pokemon")
include(":games:magic")
include(":games:onepiece")
include(":games:altered")
include(":games:yugioh")
include(":games:wutheringwaves")

// Three more, added once a source for each was measured. Cyberpunk is here because the reason it
// was absent -- no data source existed -- stopped being true; see docs/PROVIDER_RESEARCH.md.
include(":games:lorcana")
include(":games:cyberpunk")
include(":games:wowtcg")

// One module per provider. A second source for an existing game adds a sibling here and changes
// nothing above it. See docs/ARCHITECTURE.md.
include(":providers:riftcodex")

// The other six, in the order they were asked for. Each is one `CardProvider`, one Koin `single`
// and one `ProviderRoute`; none of them changed a line of :core, :data or a screen.
//
include(":providers:tcgdex")
include(":providers:scryfall")
include(":providers:optcg")
include(":providers:altered")
include(":providers:ygoprodeck")
include(":providers:wuwa")

// The one adapter serving three games. TCGCSV is a single catalogue keyed by a category number, so
// Lorcana, Cyberpunk and the WoW TCG share its HTTP code and differ only in a number and a field
// map. Each still gets its own `CardProvider` naming its game in its type.
include(":providers:tcgcsv")

// Compose UI and Pulse presentation logic, plus the Android/iOS/desktop targets it compiles to.
// The desktop entry point lives in its `desktopMain`; iOS is the framework `iosApp/` links against.
// The card store. Complete sets and everything derived from them -- pins, labels, counts -- live
// in one SQLite database; the small metadata scopes stay in the file cache. See database/README.md
// for what was measured and why the split is where it is.
include(":database")

include(":composeApp")

// The Android application. Separate because AGP 9 forbids `com.android.application` in the same
// subproject as the Kotlin Multiplatform plugin.
include(":androidApp")
