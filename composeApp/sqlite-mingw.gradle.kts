import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject
import org.gradle.process.ExecOperations

// ==================
// MARK: SQLite for the Windows native link
// ==================
//
// Applied from `build.gradle.kts` under `-PnativeDesktop` only.
//
// ## Why this file exists
//
// `:composeApp` compiles for `mingwX64` and then fails to link on twenty undefined symbols, every
// one of them SQLite's. SQLDelight's `native-driver` sits on SQLiter, whose cinterop declares
// `sqlite3.h` and ships **no implementation**: Apple targets take libsqlite3 from the SDK and Linux
// from the distribution, and its manifest carries `linkerOpts` for `linux_x64` and `macos_x64` and
// none for `mingw_x64`. Windows has nowhere to get it.
//
// So the amalgamation is fetched, checked, compiled and handed to the linker. Fetched rather than
// vendored, which was the decision: a 3 MB C file in the repository would be read by nobody and
// reviewed by nobody, and the download is pinned to one version and one published hash.
//
// ## What makes the download trustworthy
//
// `SQLITE_SHA3` is the value sqlite.org publishes beside the file on its own download page, not a
// hash computed here from whatever arrived. A mismatch fails the build rather than warning: a
// tampered or truncated archive is compiled into the app's data layer, which is the last place to
// be relaxed about provenance.
//
// ## Why the toolchain is found rather than configured
//
// Kotlin/Native already downloads a clang and a MinGW sysroot to build with -- they are in
// `~/.konan/dependencies` -- and compiling SQLite with the same toolchain that links it avoids a
// second compiler with its own opinion about the ABI. It is looked up rather than pinned because
// the directory names carry the LLVM version and change with the Kotlin version.

/** The version this is pinned to, and the path component sqlite.org serves it under. */
val SQLITE_VERSION = "3530400"
val SQLITE_YEAR = "2026"

/**
 * SHA3-256, as published on <https://sqlite.org/download.html> beside the file itself.
 *
 * Verified against the real download on 2026-09-12. Note it is SHA3, not SHA-2: that is what
 * sqlite.org publishes, and re-hashing with something else would only prove the bytes are the ones
 * that arrived.
 */
val SQLITE_SHA3 = "628a44cfe82c66aed1ccbbe85a562d2e33ebe64b3288981ed76285612227934e"

/**
 * Downloads the SQLite amalgamation, checks it against the published hash, and unpacks the two
 * files that matter.
 */
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
		check(vDigest == sha3.get()) {
			"${url.get()} did not match the hash sqlite.org publishes.\n" +
				"  expected ${sha3.get()}\n  got      $vDigest"
		}

		val vOut = source.get().asFile
		vOut.deleteRecursively()
		vOut.mkdirs()
		// Flattened: the archive holds one directory, and the name of it carries the version, which
		// the rest of this file would then have to know twice.
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

/** Compiles the amalgamation into a static library, with Kotlin/Native's own clang. */
abstract class BuildSqliteStaticLibrary @Inject constructor(
	private val mExec: ExecOperations,
) : DefaultTask() {

	@get:InputDirectory
	abstract val source: DirectoryProperty

	@get:OutputFile
	abstract val library: RegularFileProperty

	/**
	 * Not an `@Input`: it is a path on this machine, and making it part of the task's identity
	 * would mean the output was never reused between two checkouts on the same computer.
	 */
	@get:Internal
	abstract val konanDirectory: Property<String>

	@TaskAction
	fun build() {
		val vDependencies = File(konanDirectory.get(), "dependencies")
		val vEntries = vDependencies.listFiles().orEmpty()
		// The newest, because several Kotlin versions leave their toolchains side by side and the
		// one that will link this is the one the current compiler brought.
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
				// What SQLiter's own build asks for. Threadsafe because the driver hands
				// connections between threads; the rest is SQLite's recommended baseline.
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
	description = "Downloads the SQLite amalgamation and checks it against sqlite.org's own hash."
	url.set("https://sqlite.org/$SQLITE_YEAR/sqlite-amalgamation-$SQLITE_VERSION.zip")
	sha3.set(SQLITE_SHA3)
	source.set(vSqliteSource)
}

val vBuildSqlite = tasks.register<BuildSqliteStaticLibrary>("buildSqliteMingw") {
	description = "Compiles SQLite for mingwX64, which SQLiter declares and does not supply."
	dependsOn(vFetchSqlite)
	source.set(vSqliteSource)
	library.set(vSqliteLibrary)
	konanDirectory.set(
		providers.environmentVariable("KONAN_DATA_DIR")
			.orElse(providers.systemProperty("user.home").map { "$it/.konan" }),
	)
}

// Handed to `build.gradle.kts`, which puts it on the link line: an applied script has no typed
// accessor for the `kotlin` extension, and the one place that configures targets should stay the
// one place that configures targets.
extra["sqliteMingwLibrary"] = vSqliteLibrary
extra["sqliteMingwTask"] = vBuildSqlite
