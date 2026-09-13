import org.gradle.api.tasks.bundling.Zip

// Build support for the experimental Kotlin/Native desktop target. Does nothing unless the
// `nativeDesktop` flag is on; see docs/NATIVE_DESKTOP.md.
//
// Two gaps it fills: SQLiter declares sqlite3.h and ships no implementation, which MinGW has no
// system copy of; and the bridge's data.kres holds this project's resources only, while every logo
// lives in the module that owns the game.

if (providers.gradleProperty("nativeDesktop").map(String::toBoolean).getOrElse(false)) {

	// ==================
	// MARK: SQLite
	// ==================

	val vSqliteUrl = "https://sqlite.org/2026/sqlite-amalgamation-3530400.zip"
	// The SHA3-256 sqlite.org publishes beside the file.
	val vSqliteSha3 = "628a44cfe82c66aed1ccbbe85a562d2e33ebe64b3288981ed76285612227934e"

	val vSqliteSource = layout.buildDirectory.dir("sqlite/source")
	val vSqliteLibrary = layout.buildDirectory.file("sqlite/lib/libsqlite3.a")

	val vFetchSqlite = tasks.register<FetchSqliteAmalgamation>("fetchSqliteAmalgamation") {
		url.set(vSqliteUrl)
		sha3.set(vSqliteSha3)
		source.set(vSqliteSource)
	}

	val vBuildSqlite = tasks.register<BuildSqliteStaticLibrary>("buildSqliteMingw") {
		dependsOn(vFetchSqlite)
		source.set(vSqliteSource)
		library.set(vSqliteLibrary)
		konanDirectory.set(
			providers.environmentVariable("KONAN_DATA_DIR")
				.orElse(providers.systemProperty("user.home").map { "$it/.konan" }),
		)
	}

	// Read by the module's own build file, which puts it on the link line.
	extra["nativeSqliteLibrary"] = vSqliteLibrary
	extra["nativeSqliteTask"] = vBuildSqlite

	// ==================
	// MARK: Compose resources
	// ==================

	// Gradle-cased target, and the source set its assembled resources land under.
	val vTargets = mapOf(
		"MingwX64" to "mingwX64Main",
		"LinuxX64" to "linuxX64Main",
		"LinuxArm64" to "linuxArm64Main",
		"MacosArm64" to "macosArm64Main",
	)

	// Found by looking rather than listed: the games hold logos and :providers:wuwa holds a
	// catalogue snapshot, and a missing one is a crash at runtime rather than a build failure.
	val vResourceModules = rootProject.subprojects.filter {
		it.file("src/commonMain/composeResources").isDirectory
	}

	vTargets.forEach { (vTarget, vSourceSet) ->
		listOf("packageDebugComposeResources$vTarget", "packageReleaseComposeResources$vTarget")
			.forEach { vTaskName ->
				tasks.matching { it.name == vTaskName }.configureEach {
					this as Zip
					vResourceModules.forEach { vModule ->
						// No `into`: the archive's paths are what the generated accessors ask for.
						from(
							vModule.layout.buildDirectory
								.dir("generated/compose/resourceGenerator/assembledResources/$vSourceSet"),
						)
						dependsOn("${vModule.path}:assemble${vTarget}MainResources")
					}
				}
			}
	}
}
