plugins {
	alias(libs.plugins.androidApplication)
	alias(libs.plugins.composeCompiler)
}

// The Android shell, and there is deliberately almost nothing in it: an Activity that starts Koin
// and calls the shared App(). Everything the app is lives in :composeApp.
//
// A separate module because AGP 9 refuses `com.android.application` in the same subproject as the
// Kotlin Multiplatform plugin. Kotlin here is compiled by the Android plugin itself, which is why
// no Kotlin plugin is applied alongside it.
android {
	namespace = "com.bitsycore.tcgexplorer.android"
	compileSdk = libs.versions.androidCompileSdk.get().toInt()

	defaultConfig {
		applicationId = "com.bitsycore.tcgexplorer"
		minSdk = libs.versions.androidMinSdk.get().toInt()
		targetSdk = libs.versions.androidTargetSdk.get().toInt()
		versionCode = libs.versions.appCode.get().toInt()
		versionName = libs.versions.app.get()
	}

	// One debug key for everyone, checked in.
	//
	// AGP's own debug keystore is generated per machine, so a build from a second computer has a
	// different signature and Android refuses to install it over the first -- you have to uninstall
	// and lose the downloaded card data to move between machines. A shared key removes that.
	//
	// Not a secret, and not meant to be: these are AGP's own debug credentials -- alias
	// `androiddebugkey`, password `android`, `CN=Android Debug`.
	//
	// It signs `release` as well as `debug`, so a minified local build installs over an ordinary
	// one. The Play artifact is a separate thing signed with `publish.jks`, which no Gradle or CI
	// file here references: that signing happens outside the build.
	signingConfigs {
		getByName("debug") {
			storeFile = file("debug.keystore")
			storePassword = "android"
			keyAlias = "androiddebugkey"
			keyPassword = "android"
		}
	}

	buildTypes {
		release {
			isMinifyEnabled = true
   			isShrinkResources = true
			proguardFile(getDefaultProguardFile("proguard-android-optimize.txt"))
			// The shared debug key, so a minified local build installs over a debug one. Deliberate:
			// this build type is for testing R8 on a device, not for the store. Play refuses a
			// debug-signed artifact, so the upload is signed with `publish.jks` outside Gradle and
			// cannot be produced by this task -- see the signing configs above.
			signingConfig = signingConfigs.getByName("debug")
		}
	}

	buildFeatures {
		compose = true
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
}

dependencies {
	implementation(project(":composeApp"))
	implementation(libs.androidx.activity.compose)
	implementation(libs.koin.android)
}
