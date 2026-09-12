plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
	alias(libs.plugins.kotlinSerialization)
}

// The TCGdex adapter: its endpoints, its DTOs, the mapping into core's model, and its own declared
// capabilities. Nothing outside this module knows what TCGdex's JSON looks like.
kotlin {
	jvmToolchain(21)

	jvm("desktop")

	android {
		namespace = "com.bitsycore.cardbrowser.providers.tcgdex"
		compileSdk = libs.versions.androidCompileSdk.get().toInt()
		minSdk = libs.versions.androidMinSdk.get().toInt()
		withHostTest {}
	}

	iosArm64()
	iosSimulatorArm64()

	sourceSets {
		commonMain.dependencies {
			api(project(":core"))
			// The game this adapter serves, named in its own type: `CardProvider<PokemonGame>`.
			// `api` so a consumer can see the profile without depending on the game module too.
			api(project(":games:pokemon"))
			implementation(project(":data"))
			implementation(libs.ktor.client.core)
			implementation(libs.ktor.client.content.negotiation)
			implementation(libs.ktor.serialization.json)
			implementation(libs.kotlinx.datetime)
			implementation(libs.kotlinx.coroutines.core)
		}
		commonTest.dependencies {
			implementation(libs.kotlin.test)
			implementation(libs.kotlinx.coroutines.test)
			implementation(libs.ktor.client.mock)
		}
		getByName("desktopTest").dependencies {
			implementation(libs.ktor.client.java)
			// A real card store for the repository these live checks drive. In-memory, so the
			// check is about the provider and leaves nothing behind.
			implementation(project(":database"))
			implementation(libs.sqldelight.driver.jvm)
		}
	}
}

// The deterministic run never touches the network. The live check is opt-in and has its own task,
// so a provider outage cannot fail an ordinary build.
tasks.named<Test>("desktopTest") {
	filter {
		excludeTestsMatching("*LiveSmokeTest")
		// The wiring check is live too, and excluding only `*LiveSmokeTest` let it run in the
		// deterministic suite -- so an ordinary `./gradlew desktopTest` was making twelve requests
		// to someone else's server and would fail on a train. Both patterns are listed rather than
		// widened to `*Live*` so what is excluded stays readable.
		excludeTestsMatching("*LanguageWiringTest")
		isFailOnNoMatchingTests = false
	}
}

/** Hits the real TCGdex API. Run deliberately: `./gradlew :providers:tcgdex:liveProviderTest`. */
tasks.register<Test>("liveProviderTest") {
	group = "verification"
	description = "Runs the TCGdex smoke checks against the live API. Needs a network."
	val vDesktopTest = tasks.named<Test>("desktopTest").get()
	testClassesDirs = vDesktopTest.testClassesDirs
	classpath = vDesktopTest.classpath
	filter {
		includeTestsMatching("*LiveSmokeTest")
		// The wiring test is live too: it drives a real repository over the real API, which is
		// where the last language bug hid. Left out, it would have sat in the source tree
		// compiling and never running.
		includeTestsMatching("*LanguageWiringTest")
		isFailOnNoMatchingTests = false
	}
	outputs.upToDateWhen { false }
}
