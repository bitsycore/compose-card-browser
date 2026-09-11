plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
	alias(libs.plugins.composeMultiplatform)
	alias(libs.plugins.composeCompiler)
	alias(libs.plugins.kotlinSerialization)
}

kotlin {
	jvmToolchain(21)

	compilerOptions {
		// The platform file-system and link-opener are expect/actual classes, which are still
		// flagged Beta; without this the warning prints on every build for something deliberate.
		freeCompilerArgs.add("-Xexpect-actual-classes")
	}

	jvm("desktop")

	android {
		namespace = "com.bitsycore.cardbrowser"
		compileSdk = libs.versions.androidCompileSdk.get().toInt()
		minSdk = libs.versions.androidMinSdk.get().toInt()
		androidResources { enable = true }
		withHostTest {}
	}

	// What Xcode links against. `iosX64` is absent on purpose: Compose Multiplatform 1.12.0 does not
	// publish an Intel-simulator artifact, so declaring it produces a target that cannot resolve.
	listOf(iosArm64(), iosSimulatorArm64()).forEach { vTarget ->
		vTarget.binaries.framework {
			baseName = "ComposeApp"
			isStatic = true
		}
	}

	@Suppress("OPT_IN_USAGE")
	applyDefaultHierarchyTemplate {
		common {
			group("jvmShared") {
				// AGP 9 registers its own multiplatform android target, which withAndroidTarget()
				// does not recognise, so it is matched by name instead.
				withCompilations { it.target.name == "android" }
				withJvm()
			}
		}
	}

	sourceSets {
		commonMain.dependencies {
			api(project(":core"))
			implementation(project(":data"))
			// Only for the composition root: `DriverFactory` differs per platform and the card
			// store has to be opened somewhere that knows which platform it is. Nothing else in
			// the UI touches `:database` -- everything goes through `:data`'s `SetRecordStore`.
			implementation(project(":database"))
			// The only place provider adapters are named. This list and the routing table in
			// AppModule.kt are the whole of what registering one costs; see docs/ARCHITECTURE.md.
			implementation(project(":providers:riftcodex"))
			implementation(project(":providers:tcgdex"))
			implementation(project(":providers:scryfall"))
			implementation(project(":providers:optcg"))
			implementation(project(":providers:altered"))
			implementation(project(":providers:ygoprodeck"))
			implementation(project(":providers:wuwa"))
			implementation(project(":providers:tcgcsv"))

			implementation(libs.jetbrains.compose.runtime)
			implementation(libs.jetbrains.compose.foundation)
			implementation(libs.jetbrains.compose.material3)
			// Only the ~50 icons Material ships in core. The other 24 this app draws are
			// generated into `AppIcons` -- see that file for why the extended set is not here.
			implementation(libs.jetbrains.compose.material.icons.core)
			implementation(libs.jetbrains.compose.ui)
			implementation(libs.jetbrains.compose.components.resources)
			// `@Preview` in common code. The annotation only -- the renderer is the IDE's, and on
			// Android it is `ui-tooling`, added to androidMain below.
			implementation(libs.jetbrains.compose.ui.tooling.preview)

			// Navigation 3: the current multiplatform navigation story. Its nav-entry decorators
			// are what scope a ViewModel and a saveable state holder to a back-stack entry, which
			// is what makes the card grid keep its filters and its scroll position across a trip
			// into card detail without the view models leaking for the life of the process.
			implementation(libs.androidx.navigation3.ui)
			implementation(libs.androidx.lifecycle.viewmodel.navigation3)
			implementation(libs.androidx.navigationevent.compose)

			// MVI
			implementation(libs.pulse)
			implementation(libs.pulse.viewmodel)
			implementation(libs.pulse.compose)

			implementation(libs.coil.compose)
			// Coil over the app's own Ktor stack, so there is one HTTP client and one place that
			// sets a User-Agent.
			implementation(libs.coil.network.ktor3)
			// Set symbols. Scryfall serves all 988 of its as SVG.
			implementation(libs.coil.svg)

			implementation(libs.kotlinx.coroutines.core)
			implementation(libs.kotlinx.serialization.json)
			implementation(libs.kotlinx.datetime)
			implementation(libs.okio)

			implementation(libs.koin.core)
			implementation(libs.koin.compose)
			implementation(libs.koin.compose.viewmodel)
		}

		commonTest.dependencies {
			implementation(libs.kotlin.test)
			implementation(libs.kotlinx.coroutines.test)
			implementation(libs.ktor.client.mock)
			implementation(libs.okio.fakefilesystem)
			implementation(libs.pulse.test)
		}

		androidMain.dependencies {
			implementation(libs.jetbrains.compose.ui.tooling)
			implementation(libs.androidx.activity.compose)
			implementation(libs.kotlinx.coroutines.android)
			implementation(libs.koin.android)
		}

		getByName("desktopMain").dependencies {
			implementation(compose.desktop.currentOs)
			implementation(libs.kotlinx.coroutines.swing)
		}

		// Skia, so `DetailRenderer` can draw the UI into an off-screen surface and write a PNG.
		// Checking a layout change then needs neither a display nor a device.
		getByName("desktopTest").dependencies {
			implementation(compose.desktop.currentOs)
		}
	}
}

compose.resources {
	packageOfResClass = "com.bitsycore.cardbrowser.resources"
}

compose.desktop {
	application {
		mainClass = "com.bitsycore.cardbrowser.MainKt"
		// Skia loads native code; the flag keeps JDK 24+ from warning about it on every start.
		jvmArgs += "--enable-native-access=ALL-UNNAMED"

		nativeDistributions {
			packageName = "CardBrowser"
			packageVersion = "1.0.0"
			description = "Browse trading card game sets"
			// Desktop distribution is later work; an app image is enough to run one locally and
			// needs no installer toolchain.
			targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage)

			// The app image's icon. Each platform insists on its own container format and only
			// the host platform's entry is read.
			//
			// The `.ico` is generated from the 512px source rather than taken from the icon kit's
			// `web/favicon.ico`, which holds only 16x16 and 32x32 and renders blurry at the sizes
			// Windows actually uses. macOS has no entry: it needs `.icns`, which cannot be built
			// on this machine, and a wrong-format file is worse than none -- jpackage falls back
			// to a default rather than failing.
			windows {
				iconFile.set(project.file("src/desktopMain/resources/app-icon.ico"))
			}
			linux {
				iconFile.set(project.file("src/desktopMain/resources/app-icon-512.png"))
			}
		}
	}
}
