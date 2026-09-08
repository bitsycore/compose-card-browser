plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
	alias(libs.plugins.kotlinSerialization)
}

// The Riftcodex adapter: its endpoints, its DTOs, the mapping into core's model, and its own
// declared capabilities. Nothing outside this module knows what Riftcodex's JSON looks like.
kotlin {
	jvmToolchain(21)

	jvm("desktop")

	android {
		namespace = "com.bitsycore.cardbrowser.providers.riftcodex"
		compileSdk = libs.versions.androidCompileSdk.get().toInt()
		minSdk = libs.versions.androidMinSdk.get().toInt()
		withHostTest {}
	}

	// iosX64 is absent deliberately: Compose Multiplatform 1.12.0 no longer publishes an
	// Intel-simulator artifact, and a target the app module cannot build is not worth declaring here.
	iosArm64()
	iosSimulatorArm64()

	sourceSets {
		commonMain.dependencies {
			api(project(":core"))
			implementation(project(":data"))
			implementation(libs.ktor.client.core)
			implementation(libs.ktor.client.content.negotiation)
			implementation(libs.ktor.serialization.json)
			implementation(libs.koin.core)
		}
		commonTest.dependencies {
			implementation(libs.kotlin.test)
			implementation(libs.kotlinx.coroutines.test)
			implementation(libs.ktor.client.mock)
		}
		getByName("desktopTest").dependencies {
			// A real engine, for the live smoke check only.
			implementation(libs.ktor.client.java)
		}
	}
}

// The deterministic run never touches the network. The live check is opt-in and has its own task,
// so a provider outage cannot fail an ordinary build.
tasks.named<Test>("desktopTest") {
	filter {
		excludeTestsMatching("*LiveSmokeTest")
		isFailOnNoMatchingTests = false
	}
}

/**
 * Hits the real Riftcodex API. Run deliberately:
 *
 * ```
 * ./gradlew :providers:riftcodex:liveProviderTest
 * ```
 */
tasks.register<Test>("liveProviderTest") {
	group = "verification"
	description = "Runs the Riftcodex smoke checks against the live API. Needs a network."
	val vDesktopTest = tasks.named<Test>("desktopTest").get()
	testClassesDirs = vDesktopTest.testClassesDirs
	classpath = vDesktopTest.classpath
	filter {
		includeTestsMatching("*LiveSmokeTest")
		isFailOnNoMatchingTests = false
	}
	// Somebody else's server is not a cacheable input.
	outputs.upToDateWhen { false }
}
