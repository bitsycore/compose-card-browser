import java.util.zip.ZipFile

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
	alias(libs.plugins.kotlinSerialization)
	// Not for UI. This module draws nothing; the plugin is here for Compose Multiplatform's
	// *resources*, which is the one mechanism that bundles an asset for JVM, Android and iOS alike
	// -- see the `compose.resources` block below.
	//
	// `composeCompiler` comes along because the Compose plugin refuses to configure without it, and
	// it in turn insists on the Compose runtime being on the class path. Neither is used to compile
	// anything here -- this module has no `@Composable` declarations at all.
	alias(libs.plugins.composeMultiplatform)
	alias(libs.plugins.composeCompiler)
}

// The Wuthering Waves TCG adapter. Unlike the other six it makes no requests: the game ships 128
// printings in total, so its whole catalogue is a bundled asset --
// `src/commonMain/composeResources/files/wuwa-cards.json` -- and is served from there. Nothing
// outside this module knows what UCP's JSON looks like, and nothing inside the app talks to UCP at
// all; only `tools/scrape_wuwa.py` and the opt-in freshness check do.
kotlin {
	jvmToolchain(21)

	jvm("desktop")

	android {
		namespace = "com.bitsycore.cardbrowser.providers.wuwa"
		compileSdk = libs.versions.androidCompileSdk.get().toInt()
		minSdk = libs.versions.androidMinSdk.get().toInt()
		androidResources { enable = true }
		withHostTest {
			// Compose's Android resource reader logs through `android.util.Log`, which throws in a
			// host test unless the stubs return defaults.
			isReturnDefaultValues = true
			isIncludeAndroidResources = true
		}
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
			// The game this adapter serves, named in its own type: `CardProvider<WutheringWavesGame>`.
			// `api` so a consumer can see the profile without depending on the game module too.
			api(project(":games:wutheringwaves"))
			// No Ktor and no `:data`. This adapter reads a bundled asset and parses it; the HTTP
			// stack and the provider-error mapping it used to need went with the requests.
			implementation(libs.kotlinx.serialization.json)
			// `Mutex`, so the asset is read and parsed once however many callers arrive at once.
			implementation(libs.kotlinx.coroutines.core)
			// Reading the asset, and the runtime the Compose compiler plugin demands alongside it.
			// The price of the one resource API that works on every target this module builds for.
			implementation(libs.jetbrains.compose.components.resources)
			implementation(libs.jetbrains.compose.runtime)
		}
		commonTest.dependencies {
			implementation(libs.kotlin.test)
			implementation(libs.kotlinx.coroutines.test)
		}
		// Only the freshness check needs a network, so only it needs an engine -- and `:data`,
		// for `HttpClientFactory`. It builds the client the whole app uses, with the honest
		// User-Agent these volunteer-run APIs are owed; a bare `HttpClient(Java)` here sent
		// anonymous requests for a while and that is not a thing to leave in.
		getByName("desktopTest").dependencies {
			implementation(project(":data"))
			implementation(libs.ktor.client.core)
			implementation(libs.ktor.client.java)
			implementation(libs.ktor.client.content.negotiation)
			implementation(libs.ktor.serialization.json)
		}
	}
}

compose.resources {
	// Its own package, so this module's `Res` cannot collide with the app's.
	packageOfResClass = "com.bitsycore.cardbrowser.providers.wuwa.resources"
	// The module has no `@Composable` anything and no other module reads its resources, so the
	// accessors stay internal and the class is generated regardless of that.
	generateResClass = auto
	publicResClass = false
}

/*
 * The catalogue tests do not run as Android host tests.
 *
 * Not a gap in coverage so much as a gap in what a host test can be. Compose's Android resource
 * reader needs a real `Context` -- its initializer is a ContentProvider, so on Android the asset is
 * read out of the APK's assets rather than off a class path -- and a JVM unit test has none. It
 * fails with "Android context is not initialized" before reaching a single assertion.
 *
 * The alternative is Robolectric, which is a large dependency for a module that draws nothing. The
 * logic under test is common code and is fully exercised on desktop and both iOS targets; what is
 * genuinely Android-specific is whether the asset is *packaged*, and `androidAssetIsPackaged` below
 * checks that against the real AAR, which is more direct than a mocked read would be.
 */
// Matched lazily rather than by name: AGP registers this task after this script is configured, so
// `tasks.named` would not find it yet.
tasks.withType<Test>().configureEach {
	if (name == "testAndroidHostTest") {
		filter {
			excludeTestsMatching("*WuwaCatalogueTest")
			isFailOnNoMatchingTests = false
		}
	}
}

/**
 * Asserts the catalogue asset really is inside the Android artifact.
 *
 * The one thing that could break on Android alone: the asset is packaged by the Compose resources
 * plugin into the AAR's assets, under a path derived from `packageOfResClass`. If that ever stops
 * happening, the app compiles and then finds no cards at runtime -- on Android only.
 */
val vCheckAndroidAsset = tasks.register("androidAssetIsPackaged") {
	group = "verification"
	description = "Checks that wuwa-cards.json is packaged into the Android AAR's assets."
	dependsOn("assembleAndroidMain")
	val vAarDir = layout.buildDirectory.dir("outputs/aar")
	val vExpected = "assets/composeResources/com.bitsycore.cardbrowser.providers.wuwa.resources/" +
		"files/wuwa-cards.json"
	doLast {
		val vAar = vAarDir.get().asFile.listFiles()?.firstOrNull { it.extension == "aar" }
			?: error("No AAR in ${vAarDir.get().asFile}")
		val vEntries = mutableListOf<String>()
		ZipFile(vAar).use { vZip ->
			val vNames = vZip.entries()
			while (vNames.hasMoreElements()) vEntries += vNames.nextElement().name
		}
		check(vExpected in vEntries) {
			"$vExpected is missing from ${vAar.name}. Asset entries present: " +
				vEntries.filter { it.startsWith("assets/") }
		}
		logger.lifecycle("$vExpected is packaged in ${vAar.name}")
	}
}

tasks.named("check") { dependsOn(vCheckAndroidAsset) }

// The deterministic run never touches the network -- which for this adapter is the whole run except
// the freshness check. That one is opt-in and has its own task, so UCP going down, or shipping a
// new set, cannot fail an ordinary build.
tasks.named<Test>("desktopTest") {
	filter {
		excludeTestsMatching("*FreshnessTest")
		isFailOnNoMatchingTests = false
	}
}

/**
 * Asks UCP whether the bundled catalogue is still current.
 *
 * Run deliberately: `./gradlew :providers:wuwa:liveProviderTest`. A failure means the catalogue
 * moved, not that the adapter is broken -- regenerate with `tools/scrape_wuwa.py --refresh`.
 */
tasks.register<Test>("liveProviderTest") {
	group = "verification"
	description = "Checks the bundled Wuthering Waves catalogue against the live API. Needs a network."
	val vDesktopTest = tasks.named<Test>("desktopTest").get()
	testClassesDirs = vDesktopTest.testClassesDirs
	classpath = vDesktopTest.classpath
	filter {
		includeTestsMatching("*FreshnessTest")
		isFailOnNoMatchingTests = false
	}
	outputs.upToDateWhen { false }
}
