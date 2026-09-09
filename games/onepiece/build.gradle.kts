plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
	// For the logo, which is a Compose Multiplatform resource so it ships on every target. See
	// `:games:api`, which is where the art abstraction and this dependency are justified.
	alias(libs.plugins.composeMultiplatform)
	alias(libs.plugins.composeCompiler)
}

// One Piece Card Game: what the app knows about the game itself, independent of any source that serves it.
// Its vocabulary, its rarity ladder, its Cardmarket segment and its logo. Nothing here knows an
// endpoint; nothing in :core knows this game exists.
kotlin {
	jvmToolchain(21)

	jvm("desktop")

	android {
		namespace = "com.bitsycore.cardbrowser.games.onepiece"
		compileSdk = libs.versions.androidCompileSdk.get().toInt()
		minSdk = libs.versions.androidMinSdk.get().toInt()
		androidResources { enable = true }
	}

	iosArm64()
	iosSimulatorArm64()

	sourceSets {
		commonMain.dependencies {
			api(project(":games:api"))
		}
	}
}

compose.resources {
	packageOfResClass = "com.bitsycore.cardbrowser.games.onepiece.resources"
	// `always`, not the default `auto`: the resources dependency arrives transitively through
	// `:games:api`, and `auto` only generates the class for a module that declares it directly.
	generateResClass = always
	publicResClass = false
}
