// Plugins are resolved here once and applied in the modules that need them, which is what keeps a
// module's own build file to a list of what it uses.
plugins {
	alias(libs.plugins.kotlinMultiplatform) apply false
	alias(libs.plugins.kotlinSerialization) apply false
	alias(libs.plugins.composeMultiplatform) apply false
	alias(libs.plugins.composeCompiler) apply false
	alias(libs.plugins.androidApplication) apply false
	// Declared at the root even though only :composeApp and the library modules apply it: without
	// this AGP 9 and the Kotlin Multiplatform plugin race to claim the android target.
	alias(libs.plugins.androidKmpLibrary) apply false
}
