plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
}

// World of Warcraft TCG: what the app knows about the game itself, independent of any source that serves it.
// Its vocabulary, its rarity ladder, its Cardmarket segment. Nothing here knows an
// endpoint; nothing in :core knows this game exists.
kotlin {
	jvmToolchain(21)

	jvm("desktop")

	android {
		namespace = "com.bitsycore.cardbrowser.games.wowtcg"
		compileSdk = libs.versions.androidCompileSdk.get().toInt()
		minSdk = libs.versions.androidMinSdk.get().toInt()
	}

	iosArm64()
	iosSimulatorArm64()

	sourceSets {
		commonMain.dependencies {
			api(project(":games:api"))
		}
	}
}
