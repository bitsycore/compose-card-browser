plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
	alias(libs.plugins.kotlinSerialization)
}

// The vocabulary, and the contract a provider implements.
//
// Deliberately thin on dependencies: coroutines for the suspend functions in the contract and
// serialization for the annotations the disk cache needs, and nothing else. No Ktor, no Okio, no
// Compose. A provider adapter depends on this module and therefore inherits none of the app's
// transport or storage choices -- which is the whole point of the split.
kotlin {
	jvmToolchain(21)

	jvm("desktop")

	android {
		namespace = "com.bitsycore.cardbrowser.core"
		compileSdk = libs.versions.androidCompileSdk.get().toInt()
		minSdk = libs.versions.androidMinSdk.get().toInt()
		// commonTest is pure Kotlin with no device in it; without this the android target ignores
		// the directory and warns on every sync.
		withHostTest {}
	}

	// iosX64 is absent deliberately: Compose Multiplatform 1.12.0 no longer publishes an
	// Intel-simulator artifact, and a target the app module cannot build is not worth declaring here.
	iosArm64()
	iosSimulatorArm64()

	// The native desktop targets, behind a switch.
	//
	// Opt-in because the app cannot be *linked* for them yet -- several of its UI dependencies
	// publish no klibs for Windows or Linux, and the list is in docs/NATIVE_DESKTOP.md. The layers
	// below the UI have no such gap, so they are built and kept building today rather than being
	// converted in one go on the day the last dependency lands.
	//
	// `./gradlew -PnativeDesktop=true :core:compileKotlinMingwX64`
	if (providers.gradleProperty("nativeDesktop").isPresent) {
		mingwX64()
		linuxX64()
		linuxArm64()
		macosArm64()
	}

	sourceSets {
		commonMain.dependencies {
			api(libs.kotlinx.coroutines.core)
			api(libs.kotlinx.serialization.json)
			api(libs.kotlinx.datetime)
		}
		commonTest.dependencies {
			implementation(libs.kotlin.test)
			implementation(libs.kotlinx.coroutines.test)
		}
	}
}
