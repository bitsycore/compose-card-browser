plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
	alias(libs.plugins.kotlinSerialization)
	alias(libs.plugins.sqldelight)
}

// Where complete sets are held: the schema, the driver per platform, and the store itself.
//
// `:data` owns *what* is cached and when; this module owns how it is held. It knows no provider and
// no game -- the same rule `:core` and `:data` follow, and for the same reason.
//
// It builds for every target the app does. `NativeSqliteDriver` links SQLite into the iOS
// framework, and those targets have never been linked or run, so compiling for iosArm64 is the only
// check this project can currently make on that.
kotlin {
	jvmToolchain(21)

	jvm("desktop")

	android {
		namespace = "com.bitsycore.cardbrowser.database"
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

		// One source set for all four, so the driver is written once. iOS keeps its own: it shares
		// `NativeSqliteDriver` but not how a file is deleted, which is Foundation there and Okio
		// here.
		@Suppress("OPT_IN_USAGE")
		applyDefaultHierarchyTemplate {
			common {
				group("nativeDesktop") {
					withMingwX64()
					withLinuxX64()
					withLinuxArm64()
					withMacosArm64()
				}
			}
		}
	}

	sourceSets {
		commonMain.dependencies {
			// The domain vocabulary being stored. No provider, no Ktor, no Compose -- the same
			// rule `:data` follows, and `LayeringTest` does not cover this module only because
			// nothing ships from it yet.
			api(project(":core"))
			implementation(libs.sqldelight.runtime)
			implementation(libs.kotlinx.serialization.json)
			implementation(libs.kotlinx.coroutines.core)
		}
		commonTest.dependencies {
			implementation(libs.kotlin.test)
			implementation(libs.kotlinx.coroutines.test)
		}
		// A real database file, so the store's tests and its bench exercise the driver rather than
		// an in-memory approximation of it.
		getByName("desktopTest").dependencies {
			implementation(libs.sqldelight.driver.jvm)
			implementation(libs.okio)
		}
		getByName("desktopMain").dependencies {
			implementation(libs.sqldelight.driver.jvm)
		}
		androidMain.dependencies {
			implementation(libs.sqldelight.driver.android)
		}
		iosMain.dependencies {
			implementation(libs.sqldelight.driver.native)
		}
		if (providers.gradleProperty("nativeDesktop").isPresent) {
			getByName("nativeDesktopMain").dependencies {
				implementation(libs.sqldelight.driver.native)
				// For deleting a database and its two sidecars. Foundation is not here, and Okio
				// publishes for all four of these targets.
				implementation(libs.okio)
			}
		}
	}
}

sqldelight {
	databases {
		create("CardDatabase") {
			packageName.set("com.bitsycore.cardbrowser.sqlstore.db")
		}
	}
}
