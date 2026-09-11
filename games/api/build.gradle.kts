plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
	alias(libs.plugins.composeMultiplatform)
	alias(libs.plugins.composeCompiler)
}

// What every `:games:*` module implements, and the one place the Compose dependency needed for a
// bundled logo is justified.
//
// `:core` declares `GameProfile` -- a game's rules -- and stays free of Compose. `GameArt` needs
// `DrawableResource`, so it lives here instead, and each game module depends on this rather than on
// :core directly. That keeps the resources runtime out of the domain module while still letting a
// game own its own logo file.
kotlin {
	jvmToolchain(21)

	jvm("desktop")

	android {
		namespace = "com.bitsycore.cardbrowser.games.api"
		compileSdk = libs.versions.androidCompileSdk.get().toInt()
		minSdk = libs.versions.androidMinSdk.get().toInt()
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
			// `api`, not `implementation`: a game module declares a `GameProfile` from :core, and
			// every consumer of a game module needs to see that type.
			api(project(":core"))
			api(libs.jetbrains.compose.components.resources)
			// `api` because the Compose compiler plugin, which every game module has to apply to
			// get its resource accessors generated, refuses to run without the runtime on the
			// class path -- even for a module that declares nothing `@Composable`.
			api(libs.jetbrains.compose.runtime)
		}
	}
}
