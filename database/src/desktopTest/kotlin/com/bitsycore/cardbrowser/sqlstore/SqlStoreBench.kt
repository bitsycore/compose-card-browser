package com.bitsycore.cardbrowser.sqlstore

import com.bitsycore.cardbrowser.core.model.Artwork
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardAttributes
import com.bitsycore.cardbrowser.core.model.CardClassification
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.FinishCoverage
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.model.LanguageCoverage
import com.bitsycore.cardbrowser.core.model.LocalizedText
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * What a SQLite store would cost, on the operations the file cache is actually slow at.
 *
 * Run by hand. The file-cache numbers it is compared against come from `CacheWriteCostBench` and
 * `StorageScreenCostBench` in `:data`, run on the same machine in the same session -- they are not
 * re-run here, because importing `:data` into this module would give the spike a dependency the
 * real thing would not have.
 *
 * Three things are measured, chosen because they are the three the brief in `ARCHITECTURE.md`
 * names:
 *
 * 1. **Writing a catalogue.** 1000 sets of 150 printings -- roughly Magic's shape.
 * 2. **The storage screen.** Counts that are four directory walks today.
 * 3. **A filtered cross-set search**, which the file cache cannot do at all: today's equivalent is
 *    loading every cached set off disk and filtering in memory, which is why the app's cross-set
 *    search matches names only and says so on screen.
 *
 * Numbers go in the commit message rather than here, so this comment cannot drift from them.
 */
class SqlStoreBench {

	@Test
	@Ignore("A measurement tool, not a check. Remove to re-run; it writes ~240 MB to temp.")
	fun `time a catalogue write, the storage counts and a filtered search`() {
		val vFile = File(System.getProperty("java.io.tmpdir"), "cardbrowser-sqlspike.db")
		vFile.delete()
		val vDriver = DriverFactory().create(vFile.absolutePath)
		val vStore = SqlCardStore(vDriver)

		val vSets = 1000
		val vPerSet = 150

		val vWriteStart = System.nanoTime()
		val vMarks = mutableListOf<Long>()
		for (vSet in 0 until vSets) {
			val vStart = System.nanoTime()
			vStore.writeSet(
				provider = "scryfall",
				setId = "scryfall:set$vSet",
				language = CardLanguage.ENGLISH,
				label = "Set $vSet",
				isPinned = true,
				fetchedAt = 0L,
				printings = (0 until vPerSet).map { printing(vSet, it) },
			)
			vMarks += System.nanoTime() - vStart
		}
		val vWriteTotal = (System.nanoTime() - vWriteStart) / 1_000_000.0

		fun avg(from: Int, to: Int) = vMarks.subList(from, to).average() / 1_000_000
		println("--- write %d sets x %d printings = %,d rows".format(vSets, vPerSet, vSets * vPerSet))
		println("  sets #1-50      avg %.1f ms".format(avg(0, 50)))
		println("  sets #451-500   avg %.1f ms".format(avg(450, 500)))
		println("  sets #951-1000  avg %.1f ms".format(avg(950, 1000)))
		println("  total           %.1f s".format(vWriteTotal / 1000))
		println("  database        %.1f MB".format(vFile.length() / 1e6))

		val vSnapStart = System.nanoTime()
		val vSnapshot = vStore.storageSnapshot()
		println("--- storage snapshot: %.2f ms  (%d sets, %,d printings)".format(
			(System.nanoTime() - vSnapStart) / 1_000_000.0, vSnapshot.sets, vSnapshot.printings,
		))

		val vOpenStart = System.nanoTime()
		val vOpened = vStore.readSet("scryfall", "scryfall:set500", CardLanguage.ENGLISH)
		println("--- open one set: %.2f ms  (%d cards)".format(
			(System.nanoTime() - vOpenStart) / 1_000_000.0, vOpened.size,
		))

		// The query the file cache has no equivalent for.
		val vSearchStart = System.nanoTime()
		val vHits = vStore.search(
			game = "magic",
			text = "card",
			excludeText = "77",
			cardType = "Creature",
			maxCost = 4,
			domain = "fury",
			limit = 200,
		)
		println("--- filtered cross-set search: %.2f ms  (%d hits of %,d rows)".format(
			(System.nanoTime() - vSearchStart) / 1_000_000.0, vHits.size, vSets * vPerSet,
		))

		val vNameStart = System.nanoTime()
		val vByName = vStore.search(game = "magic", text = "card 4 of set 900", limit = 200)
		println("--- name-only search: %.2f ms  (%d hits)".format(
			(System.nanoTime() - vNameStart) / 1_000_000.0, vByName.size,
		))

		vDriver.close()
		vFile.delete()

		fileCacheComparison(vSets, vPerSet)
	}

	/**
	 * The same catalogue, through the store the app actually uses.
	 *
	 * Same process, same machine, same printings, same session -- which is the only way the two
	 * numbers mean anything next to each other. `MetadataCache` writes one JSON document per set,
	 * so this is its whole job for a Magic-shaped catalogue.
	 */
	private fun fileCacheComparison(sets: Int, perSet: Int) = kotlinx.coroutines.runBlocking {
		val vRoot = okio.FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "cardbrowser-filecache-bench"
		okio.FileSystem.SYSTEM.deleteRecursively(vRoot, mustExist = false)
		val vStorage = com.bitsycore.cardbrowser.data.cache.AppStorage(
			okio.FileSystem.SYSTEM, vRoot, vRoot / "prefs",
		).also { it.prepare() }
		val vCache = com.bitsycore.cardbrowser.data.cache.MetadataCache(
			mStorage = vStorage,
			mJson = kotlinx.serialization.json.Json,
			mIoDispatcher = kotlinx.coroutines.Dispatchers.IO,
			mClock = { 0L },
		)
		val vSerializer = com.bitsycore.cardbrowser.data.cache.CacheEnvelope.serializer(
			kotlinx.serialization.builtins.ListSerializer(CardPrinting.serializer()),
		)

		val vMarks = mutableListOf<Long>()
		val vStart = System.nanoTime()
		for (vSet in 0 until sets) {
			val vAt = System.nanoTime()
			val vKey = com.bitsycore.cardbrowser.data.cache.CacheKey.of("set", vSet.toString())
			vCache.pin(vKey, "Set $vSet")
			vCache.write(
				key = vKey,
				envelope = com.bitsycore.cardbrowser.data.cache.CacheEnvelope(
					schemaVersion = com.bitsycore.cardbrowser.data.cache.CacheEnvelope.CURRENT_SCHEMA_VERSION,
					provider = ProviderId("scryfall"),
					language = CardLanguage.ENGLISH,
					scope = com.bitsycore.cardbrowser.data.cache.CacheScope.CompleteSet("scryfall:set$vSet"),
					fetchedAtEpochMillis = 0L,
					completeness = com.bitsycore.cardbrowser.data.cache.Completeness.COMPLETE,
					payload = (0 until perSet).map { printing(vSet, it) },
				),
				serializer = vSerializer,
			)
			vMarks += System.nanoTime() - vAt
		}
		val vTotal = (System.nanoTime() - vStart) / 1_000_000.0

		fun avg(from: Int, to: Int) = vMarks.subList(from, to).average() / 1_000_000
		var vBytes = 0L
		okio.FileSystem.SYSTEM.listRecursively(vRoot).forEach {
			vBytes += okio.FileSystem.SYSTEM.metadataOrNull(it)?.size ?: 0L
		}
		println("--- FILE CACHE, same %d sets x %d printings".format(sets, perSet))
		println("  sets #1-50      avg %.1f ms".format(avg(0, 50)))
		println("  sets #451-500   avg %.1f ms".format(avg(450, 500)))
		println("  sets #951-1000  avg %.1f ms".format(avg(950, 1000)))
		println("  total           %.1f s".format(vTotal / 1000))
		println("  on disk         %.1f MB".format(vBytes / 1e6))

		val vSnap = System.nanoTime()
		val vSnapshot = vCache.snapshot()
		println("--- file-cache storage snapshot: %.2f ms (%d entries)".format(
			(System.nanoTime() - vSnap) / 1_000_000.0, vSnapshot.entryCount,
		))

		val vOpen = System.nanoTime()
		val vRead = vCache.read(
			com.bitsycore.cardbrowser.data.cache.CacheKey.of("set", "500"), vSerializer,
		)
		println("--- file-cache open one set: %.2f ms (%d cards)".format(
			(System.nanoTime() - vOpen) / 1_000_000.0, vRead?.payload?.size ?: 0,
		))

		okio.FileSystem.SYSTEM.deleteRecursively(vRoot, mustExist = false)
	}

	private fun printing(set: Int, number: Int): CardPrinting {
		val vProvider = ProviderId("scryfall")
		return CardPrinting(
			id = SourceId(vProvider, "c-$set-$number"),
			game = GameId("magic"),
			setId = SourceId(vProvider, "scryfall:set$set"),
			identity = null,
			setCode = "S$set",
			setName = "Set $set",
			collectorNumber = number.toString(),
			providerRawCollectorNumber = number.toString(),
			text = LocalizedText(CardLanguage.ENGLISH, "Card $number of Set $set"),
			artwork = Artwork(
				id = SourceId(vProvider, "a-$set-$number"),
				imageUrl = "https://example.test/$set/$number.png",
				thumbnailUrl = "https://example.test/$set/$number-small.png",
				artist = "An Artist",
				treatment = ArtworkTreatment.STANDARD,
				language = CardLanguage.ENGLISH,
			),
			attributes = CardAttributes(cost = number % 8),
			classification = CardClassification(
				type = if (number % 2 == 0) "Creature" else "Instant",
				rarity = if (number % 5 == 0) "Rare" else "Common",
				domains = if (number % 3 == 0) listOf("Fury") else listOf("Calm"),
			),
			languages = LanguageCoverage.ENGLISH_ONLY,
			finishes = FinishCoverage(),
		)
	}
}
