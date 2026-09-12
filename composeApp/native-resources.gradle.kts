import org.gradle.api.tasks.bundling.Zip

// ==================
// MARK: Compose resources from the other modules
// ==================
//
// Applied from `build.gradle.kts` under `-PnativeDesktop` only.
//
// ## What goes wrong without this
//
// ```
// ComposeResourceArchive: not found at ...\data.kres
// MissingResourceException: composeResources/com.bitsycore.cardbrowser.games.riftbound.resources/
//     drawable/game_logo_riftbound.webp
// ```
//
// The bridge's `packageDebugComposeResources<Target>` is a `Zip` over *this project's* prepared
// resources, and `:composeApp` has none: every bundled logo lives in the `:games:*` module that
// owns the game, which is where this project deliberately keeps them. So the archive came out
// empty, and the first screen that draws a logo threw.
//
// On Android those resources arrive through the asset merger, on the JVM off the classpath, and on
// Apple targets Compose's own plugin has `copyTestComposeResourcesFor<Target>` to gather them. For
// `mingwX64` and Linux there is no such task, so they are gathered here.
//
// ## Where this belongs eventually
//
// In the bridge, alongside the `data.kres` task it already registers: a library with resources is
// an ordinary Kotlin Multiplatform arrangement, not something specific to this app, and the
// aggregation Compose performs for Apple targets is the shape to copy. Until then this is local and
// says so.

/** The Gradle-cased target name, and the source set its assembled resources land under. */
val NATIVE_DESKTOP_TARGETS = mapOf(
	"MingwX64" to "mingwX64Main",
	"LinuxX64" to "linuxX64Main",
	"LinuxArm64" to "linuxArm64Main",
	"MacosArm64" to "macosArm64Main",
)

// Every module that ships resources, found by looking rather than listed by hand.
//
// It was a list of `:games:*` at first, and the release binary found the hole within a minute: the
// Wuthering Waves *provider* bundles a snapshot of its catalogue, `wuwa-cards.json`, because that
// game has no live API worth calling per set. Any module may hold a resource, so the rule is the
// directory that holds them, not a naming convention.
val RESOURCE_MODULES = rootProject.subprojects.filter {
	it.file("src/commonMain/composeResources").isDirectory
}

NATIVE_DESKTOP_TARGETS.forEach { (vTarget, vSourceSet) ->
	val vModules = RESOURCE_MODULES

	listOf("packageDebugComposeResources$vTarget", "packageReleaseComposeResources$vTarget")
		.forEach { vTaskName ->
			tasks.matching { it.name == vTaskName }.configureEach {
				this as Zip
				vModules.forEach { vModule ->
					// The archive's paths have to stay exactly as Compose generated them --
					// `composeResources/<package>/drawable/...` -- because that is the string the
					// generated accessor asks the reader for. So the assembled directory is added
					// whole, with no `into`.
					from(
						vModule.layout.buildDirectory
							.dir("generated/compose/resourceGenerator/assembledResources/$vSourceSet"),
					)
					dependsOn("${vModule.path}:assemble${vTarget}MainResources")
				}
			}
		}
}
