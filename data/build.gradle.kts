plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
	alias(libs.plugins.kotlinSerialization)
}

// Sockets and file handles: the shared Ktor stack, the two Okio-backed caches, saved preferences,
// and the repositories that route between cache and provider.
//
// No Compose. The UI layer depends on this; nothing here depends on the UI.
kotlin {
	jvmToolchain(21)

	jvm("desktop")

	android {
		namespace = "com.bitsycore.cardbrowser.data"
		compileSdk = libs.versions.androidCompileSdk.get().toInt()
		minSdk = libs.versions.androidMinSdk.get().toInt()
		withHostTest {}
	}

	// iosX64 is absent deliberately: Compose Multiplatform 1.12.0 no longer publishes an
	// Intel-simulator artifact, and a target the app module cannot build is not worth declaring here.
	iosArm64()
	iosSimulatorArm64()

	// The native desktop targets, behind a switch. See :core for why they are opt-in.
	if (providers.gradleProperty("nativeDesktop").isPresent) {
		mingwX64()
		linuxX64()
		linuxArm64()
		macosArm64()
	}

	sourceSets {
		commonMain.dependencies {
			api(project(":core"))
			// The card store. `:data` owns *what* is cached and when; `:database` owns how it is
			// held. Neither knows a provider or a game -- `LayeringTest` covers both.
			implementation(project(":database"))
			api(libs.okio)
			api(libs.ktor.client.core)
			implementation(libs.ktor.client.content.negotiation)
			implementation(libs.ktor.serialization.json)
			implementation(libs.koin.core)
		}
		commonTest.dependencies {
			implementation(libs.kotlin.test)
			implementation(libs.kotlinx.coroutines.test)
			implementation(libs.ktor.client.mock)
			// An in-memory FileSystem, so the cache tests exercise the real Okio code paths
			// without touching a disk or needing a temp directory per platform.
			implementation(libs.okio.fakefilesystem)
		}
		androidMain.dependencies {
			implementation(libs.ktor.client.okhttp)
		}
		iosMain.dependencies {
			implementation(libs.ktor.client.darwin)
		}
		getByName("desktopMain").dependencies {
			implementation(libs.ktor.client.java)
		}
	}
}
