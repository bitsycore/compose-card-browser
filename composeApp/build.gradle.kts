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
			// The only place a provider adapter is named. Registration is one line in
			// AppModule.kt; see docs/ARCHITECTURE.md for what adding a second one costs.
			implementation(project(":providers:riftcodex"))

			implementation(libs.jetbrains.compose.runtime)
			implementation(libs.jetbrains.compose.foundation)
			implementation(libs.jetbrains.compose.material3)
			implementation(libs.jetbrains.compose.ui)
			implementation(libs.jetbrains.compose.components.resources)
			implementation(libs.jetbrains.compose.material.icons.extended)
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
			description = "Browse Riftbound card sets"
			// Desktop distribution is later work; an app image is enough to run one locally and
			// needs no installer toolchain.
			targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage)
		}
	}
}
