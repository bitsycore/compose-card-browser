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
 * Run by hand. It used to run the file cache over the same data in the same process for a direct
 * comparison; that half is gone with the file cache's set records, and the numbers it produced are
 * in the migration's commit message.
 *
 * Three things are measured, chosen because they are the three the brief in `ARCHITECTURE.md`
 * names:
 *
 * 1. **Writing a catalogue.** 1000 sets of 150 printings -- roughly Magic's shape.
 * 2. **The storage screen.** Counts that are four directory walks today.
 * 3. **A filtered cross-set search**, which the file cache could not do at all: its equivalent was
 *    loading every cached set off disk and filtering in memory.
 *
 * Numbers go in the commit message rather than here, so this comment cannot drift from them.
 */
class SqlStoreBench {

	@Test
	@Ignore("A measurement tool, not a check. Remove to re-run; it writes ~240 MB to temp.")
	fun `time a catalogue write, the storage counts and a filtered search`() {
		val vFile = File(System.getProperty("java.io.tmpdir"), "cardbrowser-sqlspike.db")
		vFile.delete()
		val vDriver = DesktopDriverFactory().create(vFile.absolutePath)
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
				game = "magic",
				label = "Set $vSet",
				isPinned = true,
				fetchedAt = 0L,
				printings = (0 until vPerSet).map { printing(vSet, it) },
				isComplete = true,
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
