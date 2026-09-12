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
	// Only ever applied under -PnativeDesktop, below. On the classpath here so that
	// applying it by id is possible at all.
	alias(libs.plugins.composeDesktopNativeBridge) apply false
}

if (providers.gradleProperty("nativeDesktop").isPresent) {
	subprojects {
		// The bridge, on every multiplatform module rather than on the ones that name Compose:
		// it rewrites what a module *resolves*, and a provider resolves Compose transitively
		// through its game module without ever declaring it.
		//
		// Applied here rather than in each module's `plugins` block because it is for a port that
		// is parked. Applying it unconditionally registered its packaging and Skia-provisioning
		// tasks on every ordinary build -- configuration work, and ten tasks in `tasks --all`, for
		// targets that did not exist.
		plugins.withId("org.jetbrains.kotlin.multiplatform") {
			apply(plugin = "com.bitsycore.compose-desktop-native.bridge")
		}
		// The one substitution the bridge does not make, because nothing needs forking to make it.
		//
		// `org.jetbrains.androidx.navigationevent:navigationevent-compose` is JetBrains' wrapper
		// and is macOS-only on every version it has published. Google's own
		// `androidx.navigationevent:navigationevent-compose` publishes `mingwx64`, `linuxx64`,
		// `linuxarm64` and `macosarm64` at the *same* version this project already pins, so the
		// coordinates change and the version does not. Checked against Google's maven on
		// 2026-09-12.
		//
		// No code here names it: `NavDisplay` links against it to drive the predictive-back
		// gesture. See docs/NATIVE_DESKTOP.md.
		configurations.configureEach {
			resolutionStrategy.dependencySubstitution {
				substitute(module("org.jetbrains.androidx.navigationevent:navigationevent-compose"))
					.using(module("androidx.navigationevent:navigationevent-compose:$NAVIGATION_EVENT"))
					.because("the JetBrains wrapper publishes no native desktop targets")
			}
		}
	}
}

/** Kept equal to `androidxNavigationEvent` in the version catalogue, which is what it replaces. */
val NAVIGATION_EVENT = libs.versions.androidxNavigationEvent.get()
