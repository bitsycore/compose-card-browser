plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
	alias(libs.plugins.kotlinSerialization)
	alias(libs.plugins.sqldelight)
}

// A spike, and nothing in the app depends on it.
//
// It exists to answer two questions with numbers rather than opinion, before anything commits to a
// database: whether a SQLite store beats the file-per-record cache on the operations that are
// actually slow, and what a native driver does to the iOS targets -- which have never been linked,
// and which are therefore where a new native dependency carries all its risk.
//
// It builds for every target the app does, on purpose. Compiling for iosArm64 is most of what is
// being tested here.
kotlin {
	jvmToolchain(21)

	jvm("desktop")

	android {
		namespace = "com.bitsycore.cardbrowser.sqlstore"
		compileSdk = libs.versions.androidCompileSdk.get().toInt()
		minSdk = libs.versions.androidMinSdk.get().toInt()
		withHostTest {}
	}

	iosArm64()
	iosSimulatorArm64()

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
		// The bench needs a real database file, so it needs the JVM driver on the test path too --
		// and `:data`, so the comparison runs both stores over identical data in one process on
		// one machine. Test-only on purpose: the spike itself must not depend on `:data`, because
		// a real migration would have the dependency the other way round.
		getByName("desktopTest").dependencies {
			implementation(libs.sqldelight.driver.jvm)
			implementation(project(":data"))
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
	}
}

sqldelight {
	databases {
		create("CardDatabase") {
			packageName.set("com.bitsycore.cardbrowser.sqlstore.db")
		}
	}
}
