package com.bitsycore.cardbrowser.providers.scryfall

import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.data.net.HttpClientFactory
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The bulk import, against Scryfall's real files.
 *
 * **Not part of the ordinary test run.** Run it with
 * `./gradlew :providers:scryfall:liveProviderTest`.
 *
 * The manifest check is cheap -- a 3 KB request -- and runs with the rest. The import itself
 * transfers 74.6 MB and expands it to 598 MB, so it is `@Ignore`d: it is a thing to run
 * deliberately when the streaming code changes, not on every live sweep.
 */
class ScryfallBulkLiveSmokeTest {

	private fun storage(): AppStorage {
		val vRoot = "build/bulk-live".toPath()
		FileSystem.SYSTEM.createDirectories(vRoot)
		return AppStorage(FileSystem.SYSTEM, vRoot, vRoot).also { it.prepare() }
	}

	private fun provider() = ScryfallProvider(HttpClientFactory.create(), mStorage = storage())

	@Test
	fun `the manifest names a default_cards file with a real size`(): Unit = runBlocking {
		val vSummary = assertNotNull(provider().bulkSummary(), "Scryfall published no bulk manifest")

		// 74.6 MB when this was written. Asserted as a range rather than a number, because the
		// file grows with the game and a test that fails on a new Magic set is noise.
		assertTrue(
			vSummary.compressedBytes in 40_000_000..400_000_000,
			"unexpected bulk size: ${vSummary.compressedBytes}",
		)
		assertTrue(vSummary.description.isNotBlank())
		assertNotNull(vSummary.updatedAt, "Scryfall states a rebuild date and it should survive mapping")
	}

	@Test
	@Ignore(
		"Transfers 74.6 MB and expands it to 598 MB. Verified on 2026-09-10: 80,000+ cards and " +
			"700+ sets in about four seconds. Run deliberately when the streaming code changes.",
	)
	fun `the whole catalogue streams -- decompresses and maps`(): Unit = runBlocking {
		var vCards = 0
		var vLastBytes = 0L
		val vSets = mutableSetOf<String>()

		provider().streamAll(
			onBytes = { vDone, _ -> vLastBytes = vDone },
		) { vCard ->
			vCards++
			vSets += vCard.setId.local
		}

		// Magic has well over 100,000 printings and about a thousand sets. Floors, because both
		// only ever grow.
		assertTrue(vCards > 80_000, "only $vCards cards came out of the bulk file")
		assertTrue(vSets.size > 700, "only ${vSets.size} sets came out of the bulk file")
		assertTrue(vLastBytes > 40_000_000, "progress reported only $vLastBytes bytes")
	}
}
