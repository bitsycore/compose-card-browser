import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

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
	// Applied under the flag only, from here. On the classpath so that applying it by id works.
	alias(libs.plugins.composeDesktopNativeBridge) apply false
}

// The experimental Kotlin/Native desktop target: off unless `nativeDesktop` is true. Everything it
// needs -- the four targets, the bridge plugin, one dependency substitution -- is declared here so
// that a module's own build file says nothing about it. See docs/NATIVE_DESKTOP.md.
if (providers.gradleProperty("nativeDesktop").map(String::toBoolean).getOrElse(false)) {
	val vNavigationEvent = libs.versions.androidxNavigationEvent.get()

	subprojects {
		plugins.withId("org.jetbrains.kotlin.multiplatform") {
			// The bridge goes on every multiplatform module, not only those naming Compose: it
			// rewrites what a module *resolves*, and a provider resolves Compose transitively.
			pluginManager.apply("com.bitsycore.compose-desktop-native.bridge")
			extensions.configure<KotlinMultiplatformExtension> {
				mingwX64()
				linuxX64()
				linuxArm64()
				macosArm64()
			}
		}
		// JetBrains' navigationevent wrapper is macOS-only; Google publishes all four targets at
		// the same version.
		configurations.configureEach {
			resolutionStrategy.dependencySubstitution {
				substitute(module("org.jetbrains.androidx.navigationevent:navigationevent-compose"))
					.using(module("androidx.navigationevent:navigationevent-compose:$vNavigationEvent"))
			}
		}
	}
}
