import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject
import org.gradle.api.tasks.bundling.Zip
import org.gradle.process.ExecOperations

// Build support for the experimental Kotlin/Native desktop target. Applied under the
// `nativeDesktop` flag only; see docs/NATIVE_DESKTOP.md.
//
// Two gaps it fills: SQLiter declares sqlite3.h and ships no implementation, which MinGW has no
// system copy of; and the bridge's data.kres holds this project's resources only, while every logo
// lives in the module that owns the game.

// ==================
// MARK: SQLite
// ==================

/** Pinned, with the SHA3-256 sqlite.org publishes beside the file. */
val SQLITE_URL = "https://sqlite.org/2026/sqlite-amalgamation-3530400.zip"
val SQLITE_SHA3 = "628a44cfe82c66aed1ccbbe85a562d2e33ebe64b3288981ed76285612227934e"

abstract class FetchSqliteAmalgamation : DefaultTask() {

	@get:Input
	abstract val url: Property<String>

	@get:Input
	abstract val sha3: Property<String>

	@get:OutputDirectory
	abstract val source: DirectoryProperty

	@TaskAction
	fun fetch() {
		val vBytes = URI(url.get()).toURL().openStream().use { it.readBytes() }
		val vDigest = MessageDigest.getInstance("SHA3-256").digest(vBytes)
			.joinToString("") { "%02x".format(it) }
		check(vDigest == sha3.get()) { "${url.get()} hashed $vDigest, expected ${sha3.get()}" }

		val vOut = source.get().asFile
		vOut.deleteRecursively()
		vOut.mkdirs()
		var vFound = 0
		ZipInputStream(vBytes.inputStream()).use { vZip ->
			while (true) {
				val vEntry = vZip.nextEntry ?: break
				val vName = vEntry.name.substringAfterLast('/')
				if (vName == "sqlite3.c" || vName == "sqlite3.h") {
					vOut.resolve(vName).writeBytes(vZip.readBytes())
					vFound++
				}
			}
		}
		check(vFound == 2) { "expected sqlite3.c and sqlite3.h in the archive, found $vFound" }
	}
}

/** Compiles it with the clang and MinGW sysroot Kotlin/Native already downloaded to link with. */
abstract class BuildSqliteStaticLibrary @Inject constructor(
	private val mExec: ExecOperations,
) : DefaultTask() {

	@get:InputDirectory
	abstract val source: DirectoryProperty

	@get:OutputFile
	abstract val library: RegularFileProperty

	/** Internal: a path on this machine, not part of the task's identity. */
	@get:Internal
	abstract val konanDirectory: Property<String>

	@TaskAction
	fun build() {
		val vDependencies = File(konanDirectory.get(), "dependencies")
		val vEntries = vDependencies.listFiles().orEmpty()
		val vLlvm = vEntries.filter { it.name.startsWith("llvm-") && it.name.contains("windows") }
			.maxByOrNull { it.name }
			?: error("no LLVM toolchain in $vDependencies -- run a native compile first")
		val vSysroot = vEntries.firstOrNull { it.name.startsWith("msys2-mingw-w64-x86_64") }
			?: error("no MinGW sysroot in $vDependencies -- run a native compile first")

		val vObject = library.get().asFile.resolveSibling("sqlite3.o")
		library.get().asFile.parentFile.mkdirs()
		mExec.exec {
			commandLine(
				File(vLlvm, "bin/clang").absolutePath,
				"--target=x86_64-w64-mingw32",
				"--sysroot=${vSysroot.absolutePath}",
				"-O2",
				"-DSQLITE_THREADSAFE=1",
				"-DSQLITE_ENABLE_COLUMN_METADATA=1",
				"-DSQLITE_DQS=0",
				"-c", source.get().asFile.resolve("sqlite3.c").absolutePath,
				"-o", vObject.absolutePath,
			)
		}
		mExec.exec {
			commandLine(
				File(vLlvm, "bin/llvm-ar").absolutePath,
				"rcs",
				library.get().asFile.absolutePath,
				vObject.absolutePath,
			)
		}
	}
}

val vSqliteSource = layout.buildDirectory.dir("sqlite/source")
val vSqliteLibrary = layout.buildDirectory.file("sqlite/lib/libsqlite3.a")

val vFetchSqlite = tasks.register<FetchSqliteAmalgamation>("fetchSqliteAmalgamation") {
	url.set(SQLITE_URL)
	sha3.set(SQLITE_SHA3)
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

// Handed to build.gradle.kts, which puts it on the link line: an applied script has no Kotlin
// Multiplatform types on its classpath.
extra["nativeSqliteLibrary"] = vSqliteLibrary
extra["nativeSqliteTask"] = vBuildSqlite

// ==================
// MARK: Compose resources
// ==================

/** Gradle-cased target, and the source set its assembled resources land under. */
val NATIVE_DESKTOP_TARGETS = mapOf(
	"MingwX64" to "mingwX64Main",
	"LinuxX64" to "linuxX64Main",
	"LinuxArm64" to "linuxArm64Main",
	"MacosArm64" to "macosArm64Main",
)

// Found by looking rather than listed: the games hold logos and :providers:wuwa holds a catalogue
// snapshot, and a missing one is a crash at runtime rather than a build failure.
val vResourceModules = rootProject.subprojects.filter {
	it.file("src/commonMain/composeResources").isDirectory
}

NATIVE_DESKTOP_TARGETS.forEach { (vTarget, vSourceSet) ->
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
