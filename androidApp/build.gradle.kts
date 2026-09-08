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
	namespace = "com.bitsycore.cardbrowser.android"
	compileSdk = libs.versions.androidCompileSdk.get().toInt()

	defaultConfig {
		applicationId = "com.bitsycore.cardbrowser"
		minSdk = libs.versions.androidMinSdk.get().toInt()
		targetSdk = libs.versions.androidTargetSdk.get().toInt()
		versionCode = 1
		versionName = "1.0"
	}

	buildTypes {
		release {
			isMinifyEnabled = true
   			isShrinkResources = true
			proguardFile(getDefaultProguardFile("proguard-android-optimize.txt"))
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
