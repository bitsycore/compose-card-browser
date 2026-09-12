import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Downloads the SQLite amalgamation and checks it against the hash sqlite.org publishes.
 *
 * SQLiter declares `sqlite3.h` and ships no implementation: Apple targets take libsqlite3 from the
 * SDK, Linux from the distribution, MinGW from nowhere.
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
