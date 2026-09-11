plugins {
	alias(libs.plugins.kotlinMultiplatform)
	// Transitively resolves Compose klibs through its game module, so it needs the
	// substitution too -- the bridge rewrites whatever a module *resolves*, not only
	// what it declares.
	alias(libs.plugins.composeDesktopNativeBridge)
	alias(libs.plugins.androidKmpLibrary)
	alias(libs.plugins.kotlinSerialization)
}

// The TCGCSV adapter: its endpoints, its DTOs, the mapping into core's model, and its own declared
// capabilities. Nothing outside this module knows what TCGCSV's JSON looks like.
//
// Unusually, this one module serves three games. TCGCSV is a single catalogue keyed by a numeric
// category, so Lorcana, Cyberpunk and the WoW TCG differ only in that number and in which fields
// their records carry -- one engine and three declarations, rather than three copies of the same
// HTTP code. Each game still gets its own `CardProvider` naming it in its type, so the registry's
// route-matches-provider check works exactly as it does for every other adapter.
kotlin {
	jvmToolchain(21)

	jvm("desktop")

	android {
		namespace = "com.bitsycore.cardbrowser.providers.tcgcsv"
		compileSdk = libs.versions.androidCompileSdk.get().toInt()
		minSdk = libs.versions.androidMinSdk.get().toInt()
		withHostTest {}
	}

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
			// The three games this adapter serves, each named in its own provider's type.
			// `api` so a consumer can see the profiles without depending on the game modules too.
			api(project(":games:lorcana"))
			api(project(":games:cyberpunk"))
			api(project(":games:wowtcg"))
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

/** Hits the real TCGCSV service. Run deliberately: `./gradlew :providers:tcgcsv:liveProviderTest`. */
tasks.register<Test>("liveProviderTest") {
	group = "verification"
	description = "Runs the TCGCSV smoke checks against the live service. Needs a network."
	val vDesktopTest = tasks.named<Test>("desktopTest").get()
	testClassesDirs = vDesktopTest.testClassesDirs
	classpath = vDesktopTest.classpath
	filter {
		includeTestsMatching("*LiveSmokeTest")
		isFailOnNoMatchingTests = false
	}
	outputs.upToDateWhen { false }
}
