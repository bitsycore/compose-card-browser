// Plugins are resolved here once and applied in the modules that need them, which is what keeps a
// module's own build file to a list of what it uses.
plugins {
	alias(libs.plugins.kotlinMultiplatform) apply false
	alias(libs.plugins.kotlinSerialization) apply false
	alias(libs.plugins.composeMultiplatform) apply false
	alias(libs.plugins.composeCompiler) apply false
	alias(libs.plugins.androidApplication) apply false
	// Declared at the root even though only :composeApp and the library modules apply it: without
	// this AGP 9 and the Kotlin Multiplatform plugin race to claim the android target.
	alias(libs.plugins.androidKmpLibrary) apply false
}

// The Skia fork the bridge plugin uses for Windows, pinned to what is actually published.
//
// `com.bitsycore.compose-desktop-native.bridge:0.4.2` asks for `com.bitsycore.skiko:skiko`
// at `0.150.1-mingw.1`; `maven.bitsycore.com` has `0.150.1-mingw.2` and nothing else, so
// `mingwX64` fails to resolve out of the box. Nothing is wrong beyond the version, and forcing
// it compiles -- see docs/NATIVE_DESKTOP.md.
//
// Only under the flag, so a build that declares no native target never sees this rule. Delete it
// when a bridge release points at a version that exists.
if (providers.gradleProperty("nativeDesktop").isPresent) {
	subprojects {
		configurations.configureEach {
			resolutionStrategy.eachDependency {
				if (requested.group == "com.bitsycore.skiko") useVersion(SKIKO_MINGW_FORK)
			}
		}
	}
}

/** The only `com.bitsycore.skiko` version published at the time of writing. */
val SKIKO_MINGW_FORK = "0.150.1-mingw.2"
