package com.bitsycore.cardbrowser.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `:core` and `:data` name no game and no provider.
 *
 * The rule is in CLAUDE.md and in the architecture doc, and until now it was enforced by reading.
 * It had already been broken once: `ProviderHttpPolicy.SCRYFALL` and `ProviderHttpPolicy.YGOPRODECK`
 * sat in the shared HTTP layer, which made `:data` the one layer below the composition root that
 * knew two of the sources existed. Nothing failed, because nothing was checking.
 *
 * The point of the rule is that adding a source is a module and a routing entry. Every `when` over
 * a provider, every constant named after one, is a place a future source has to be remembered --
 * and the ones that are forgotten fail silently, because the code still compiles and the other
 * sources still work.
 *
 * ## What counts as a mention
 *
 * Code only. Comments naming a source are not merely allowed, they are most of how this codebase
 * explains itself -- "Scryfall answers 429 above ten requests a second" is exactly the sort of
 * measurement that belongs next to the constant it produced. So comments and KDoc are stripped
 * before the search, and what is left is what the compiler sees.
 *
 * A desktop test because it reads the source tree, which needs a real filesystem.
 */
class LayeringTest {

	@Test
	fun `neither core nor data names a game or a provider in code`() {
		val vOffences = mutableListOf<String>()

		for ((vLabel, vRoot) in MODULES) {
			val vDirectory = File(vRoot)
			// A wrong path must fail rather than pass by finding nothing, which is the way this
			// kind of test usually rots.
			assertTrue(
				vDirectory.isDirectory,
				"cannot find $vLabel at ${vDirectory.absolutePath} -- fix the path, do not delete the test",
			)

			vDirectory.walkTopDown()
				.filter { it.isFile && it.extension == "kt" }
				.forEach { vFile ->
					val vCode = stripComments(vFile.readText())
					for (vName in FORBIDDEN) {
						if (vCode.contains(vName, ignoreCase = true)) {
							vOffences += "$vLabel: ${vFile.name} mentions \"$vName\" in code"
						}
					}
				}
		}

		assertTrue(
			vOffences.isEmpty(),
			"These layers must know no source and no game:\n" + vOffences.joinToString("\n"),
		)
	}

	/** Removes block and line comments, so prose about a source does not count as knowing one. */
	private fun stripComments(source: String): String {
		val vNoBlocks = BLOCK_COMMENT.replace(source, " ")
		return LINE_COMMENT.replace(vNoBlocks, " ")
	}

	private companion object {

		/**
		 * Both layers, by path relative to this module.
		 *
		 * `:data` is where the breach happened and `:core` is the one with the stricter rule, so
		 * both are worth holding rather than only the one that failed.
		 */
		val MODULES = listOf(
			"core" to "../core/src/commonMain",
			"data" to "src/commonMain",
		)

		/**
		 * Every game and every source this build has, **read off the directory tree**.
		 *
		 * It used to be a hand-written list, and the obvious thing happened: it said it was "every
		 * game and every source" while omitting `magic` and `altered`. A guard that names what it
		 * checks and then does not check all of it is worse than no guard, because the gap is
		 * invisible -- `:data` could have said `MagicGame` and nothing would have failed.
		 *
		 * Derived instead, so adding a module adds it to the guard on the same commit. `api` is
		 * excluded because `:games:api` is not a game and the substring appears everywhere.
		 */
		val FORBIDDEN: List<String> = (moduleNames("../games") + moduleNames("../providers"))
			.filterNot { it == "api" }
			.distinct()
			.sorted()

		/** The subdirectory names under [parent], which are the module names. */
		private fun moduleNames(parent: String): List<String> {
			val vDirectory = File(parent)
			// A wrong path must fail loudly rather than quietly forbid nothing, which is exactly
			// the failure this whole derivation exists to prevent.
			check(vDirectory.isDirectory) { "cannot find $parent at ${vDirectory.absolutePath}" }
			return vDirectory.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }
		}

		val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
		val LINE_COMMENT = Regex("""//[^\n]*""")
	}
}
