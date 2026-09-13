package com.bitsycore.cardbrowser.data.repository

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.BulkCatalogue
import com.bitsycore.cardbrowser.core.provider.BulkSummary
import com.bitsycore.cardbrowser.core.provider.CardProvider
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.data.cache.SetRecordStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okio.ByteString.Companion.encodeUtf8
import okio.buffer
import okio.use

/**
 * Imports a whole game from its source's bulk file.
 *
 * Its own class rather than another two hundred lines of `CardRepository`, because it is the one
 * path here with a lifecycle of its own: a scratch directory, a set of shard files, and a stream
 * that must never be held in memory. Nothing else in the repository works that way, and nothing
 * else needs to know it does.
 *
 * @param setLabel what to call a set on screen, which only the repository can answer -- it reads
 *   the cached catalogue. Passed rather than depended on, so this class needs no metadata store
 */
internal class BulkImporter(
	private val mRegistry: ProviderRegistry,
	private val mSetStore: SetRecordStore,
	private val mStorage: AppStorage?,
	private val mJson: Json,
	private val mClock: () -> Long,
	private val setLabel: suspend (CardProvider<GameProfile>, SourceId) -> String,
) {


	/**
	 * Imports a provider's whole catalogue from its bulk file, writing one cached set at a time.
	 *
	 * For "download everything for this game" and nothing else. Browsing a single set stays on the
	 * per-set path, which is cheaper for that job -- see [BulkCatalogue] for why.
	 *
	 * ## How the memory stays bounded
	 *
	 * Scryfall's dump is 598 MB of JSON, so nothing may hold it. The provider hands over one card
	 * at a time; this appends each one, already mapped and therefore an order of magnitude smaller
	 * than the source record, to a scratch file for its set. Only when the stream ends is each
	 * scratch file read back and written as a cache entry -- so the high-water mark is one set's
	 * worth of cards, a few hundred, rather than a hundred thousand.
	 *
	 * The obvious alternative -- a map of set to list, filled as the stream runs -- holds the whole
	 * catalogue by the end. Measured against the mapped model rather than the source it would still
	 * be tens of megabytes of live objects on a phone, which is the sort of thing that survives
	 * testing and dies on someone's actual device.
	 *
	 * ## What it writes
	 *
	 * A complete-set record per set, pinned, exactly as a per-set download would leave it -- so a
	 * bulk import and 988 individual downloads produce the same cache, and the set list marks them
	 * saved by the same check. Sets the bulk file does not mention are left untouched.
	 *
	 * Cards whose set the game's catalogue does not list are **skipped**, not written. A dump is
	 * not a catalogue: Scryfall's carries Arena and MTGO products, which have no printed cards and
	 * which `listSets` therefore drops, so writing them spent disk and write time on sets that have
	 * no row to open and cannot be reached from anywhere in the app. They are counted and reported
	 * -- see [BulkImportResult.skippedCards] -- because discarding most of a 600 MB file must not
	 * look the same as importing it.
	 *
	 * @return what happened, or `null` when this game's source publishes no bulk file
	 */
	suspend fun importBulk(
		game: GameId,
		variantId: String? = null,
		language: CardLanguage? = null,
		onProgress: (BulkImportProgress) -> Unit = {},
	): BulkImportResult? {
		val vStorage = mStorage ?: return null
		val vProvider = mRegistry.resolve(game, language) ?: return null
		val vBulk = vProvider as? BulkCatalogue ?: return null
		// Resolved once, here, so the stream and the record of what was imported cannot disagree
		// about which file was read. Null means the default, which is the cheapest.
		val vVariants = vBulk.bulkVariants()
		// A named variant that does not exist imports nothing. It must not fall through to the
		// cheapest: Scryfall's two differ by 315 MB, and spending that on a typo -- or worse,
		// *not* spending it and reporting the every-language import as done -- is the kind of
		// silent substitution this codebase exists to avoid. Null means "the default".
		val vVariant = if (variantId == null) {
			vVariants.firstOrNull()
		} else {
			vVariants.firstOrNull { it.id == variantId }
		} ?: return null
		val vLanguage = resolvedLanguage(vProvider, language)

		// One request, for the names and codes. The bulk records carry a set *code* but not the
		// catalogue's own metadata, and a set list written from cards alone would lose release
		// dates and symbols that the ordinary path has.
		val vSets = runCatching { vProvider.listSets(vLanguage) }.getOrDefault(emptyList())
		val vSetsById = vSets.associateBy { it.id.qualified }

		// Whether a card's set has to be in that catalogue for the card to be kept.
		//
		// It does, when there is a catalogue to check against. A dump carries sets the app can
		// never show: Scryfall's holds Arena and MTGO products, which `listSets` drops because
		// they have no printed cards, and sets the source itself states are empty. Records for
		// those cost disk, write time and a line in the storage screen for something that has no
		// row to open.
		//
		// Guarded on the catalogue being non-empty, and that guard is the point rather than
		// caution: `listSets` is one request and it can fail, and a failed request must not be
		// read as "no set qualifies" and quietly import nothing at all.
		val vFilterToCatalogue = vSetsById.isNotEmpty()
		var vSkippedCards = 0
		val vSkippedSets = mutableSetOf<String>()

		val vScratch = vStorage.cacheRoot / BULK_SCRATCH_DIR
		// Sharded, not one scratch file per set, and that is not a detail.
		//
		// This used to hold an open `BufferedSink` per (set, language) for the whole read. For
		// Scryfall's English dump that is about 1100 open file descriptors at once and for the
		// every-language one it is thousands -- against a 256 limit on iOS and commonly 1024 on
		// Android. It crashed a phone, which is how it was found.
		//
		// The obvious repair -- keep a small LRU of open sinks -- does not work here, and that is
		// measured rather than assumed: the file has no locality at all. Sampled 18,050 records
		// from `default_cards` on 2026-09-11, consecutive cards were in the same bucket with an
		// average run length of **1.0**, and even a 128-entry LRU would have reopened a file
		// 10,498 times over those 18,050 writes.
		//
		// So the stream goes into a fixed [BULK_SHARDS] files by hash, which bounds descriptors
		// at a number no platform objects to, and the grouping happens afterwards one shard at a
		// time in memory. A shard is a known fraction of the whole: even the every-language dump
		// is a few megabytes per shard, which is the point of choosing the count rather than
		// letting the catalogue choose it.
		val vShards = mutableMapOf<Int, okio.BufferedSink>()
		var vCards = 0
		// Just the keys, gathered while streaming, purely to have an honest denominator for the
		// writing phase. Keys only: roughly 1100 short strings for the English dump and some
		// thousands for the every-language one, which is a rounding error against holding their
		// cards. Counting them here is what lets the write phase handle one shard at a time.
		val vBucketKeys = mutableSetOf<String>()

		try {
			vStorage.fileSystem.createDirectories(vScratch)
			vBulk.streamAll(
				variantId = vVariant.id,
				onBytes = { vDone, vTotal ->
					onProgress(BulkImportProgress.Downloading(vDone, vTotal))
				},
			) { vCard ->
				// Not in the catalogue, so nothing in the app can ever open it. Counted, because
				// silently dropping most of a file would be indistinguishable from a broken import
				// -- see [BulkImportResult.skippedCards].
				if (vFilterToCatalogue && vCard.setId.qualified !in vSetsById) {
					vSkippedCards++
					vSkippedSets += vCard.setId.qualified
					return@streamAll
				}
				// Bucketed by set *and* language. A cache entry holds one language and this file
				// carries several -- overwhelmingly English, with thousands of cards in six others
				// -- so one bucket per set would mix them and store the lot under a single label
				// that is wrong for most of it.
				val vBucketKey = bucketKey(vCard.setId, vCard.text.language)
				vBucketKeys += vBucketKey
				val vSink = vShards.getOrPut(shardOf(vBucketKey)) {
					vStorage.fileSystem.sink(vScratch / shardName(shardOf(vBucketKey))).buffer()
				}
				// The bucket key is written with the record, so the grouping pass does not have to
				// re-derive it -- and so a shard is readable on its own.
				vSink.writeUtf8(vBucketKey)
				vSink.writeUtf8("\t")
				vSink.writeUtf8(mJson.encodeToString(serializer<CardPrinting>(), vCard))
				vSink.writeUtf8("\n")
				vCards++
				if (vCards % BULK_PROGRESS_EVERY == 0) {
					onProgress(BulkImportProgress.Reading(vCards))
				}
			}
		} finally {
			// Closed before anything is read back, or the last writes are still in a buffer.
			vShards.values.forEach { runCatching { it.close() } }
		}

		var vSetsWritten = 0
		// Known from the keys seen while streaming, not by reading the shards first.
		val vBucketTotal = vBucketKeys.size
		try {
			// One shard read, written and released before the next is touched. Sharding exists to
			// bound exactly this: [BULK_SHARDS] is chosen so a shard is a few megabytes, and
			// `readShard` says so.
			//
			// It used to read *every* shard into one list and only then write, which threw that
			// away -- peak memory became the whole catalogue as parsed objects rather than a
			// sixty-fourth of it. Scryfall's English dump is 598 MB of JSON and about 300 MB once
			// re-encoded as `CardPrinting`, and the every-language dump is several times that, so
			// it exhausted the heap on a phone. Not for want of RAM, either: Android caps an app's
			// heap in the hundreds of megabytes whatever the device has, so a 16 GB phone gets no
			// further than a 4 GB one.
			//
			// The only reason it accumulated was to count buckets for the progress denominator,
			// and [vBucketKeys] now has that for the price of a set of short strings.
			for (vShard in vShards.keys.sorted()) {
				currentCoroutineContext().ensureActive()
				val vByBucket = readShard(vStorage, vScratch / shardName(vShard))
				for (vPrintings in vByBucket.values) {
					currentCoroutineContext().ensureActive()
					if (vPrintings.isEmpty()) continue
					// Read off the records rather than parsed back out of the key, so the cache is
					// written under the language the cards in it actually state.
					val vSetId = vPrintings.first().setId
					val vLanguage = vPrintings.first().text.language

					// Pinned before the write, for the same reason a download is: a set large
					// enough to breach the ceiling would otherwise be a candidate for the very
					// eviction its own write triggers. `write` preserves an existing pin rather
					// than resetting it, which is what makes this order safe.
					val vDedupedBucket = dedupedPrintings(vPrintings)
					mSetStore.setPinned(
						provider = vProvider.id,
						setId = vSetId,
						language = vLanguage,
						game = game,
						label = setLabel(vProvider, vSetId),
						isPinned = true,
					)
					mSetStore.write(
						provider = vProvider.id,
						setId = vSetId,
						language = vLanguage,
						game = game,
						label = setLabel(vProvider, vSetId),
						cards = vDedupedBucket,
						// The bulk file is the whole catalogue by definition, so a set drawn from
						// it is complete in a way a paged fetch has to prove.
						isComplete = true,
						fetchedAtEpochMillis = mClock(),
					)
					vSetsWritten++
					onProgress(BulkImportProgress.Writing(vSetsWritten, vBucketTotal))
				}
			}
		} finally {
			runCatching { vStorage.fileSystem.deleteRecursively(vScratch) }
		}

		return BulkImportResult(
			cards = vCards,
			sets = vSetsWritten,
			// Named so a screen can say "988 sets" against what the catalogue actually lists,
			// rather than implying the import covered sets it never saw.
			knownSets = vSetsById.size,
			// From the variant resolved at the top, so this names the file actually read rather
			// than whatever the manifest happens to say by the time the import finishes.
			variantId = vVariant.id,
			dumpUpdatedAt = vVariant.updatedAt,
			skippedCards = vSkippedCards,
			skippedSets = vSkippedSets.size,
		)
	}

	/**
	 * What a bulk import of [game] would cost, cheapest first, or empty when its source
	 * publishes no dump.
	 *
	 * Cheap -- a manifest request, not the file -- so a screen can state the sizes before
	 * anything large is fetched. A list, because a source may publish more than one worth
	 * offering and Scryfall does: 78 MB of one printing per card, or 393 MB of every language.
	 */
	suspend fun bulkVariants(game: GameId, language: CardLanguage? = null): List<BulkSummary> {
		if (mStorage == null) return emptyList()
		val vProvider = mRegistry.resolve(game, language) ?: return emptyList()
		return (vProvider as? BulkCatalogue)?.bulkVariants().orEmpty()
	}

	/**
	 * Reads one shard back, grouped by bucket, skipping any line that will not parse.
	 *
	 * A shard holds a hash-slice of the whole catalogue, so this is where the memory goes: one
	 * shard's cards at a time rather than the file's. [BULK_SHARDS] is chosen so that even the
	 * every-language dump leaves a few megabytes per shard.
	 */
	private fun readShard(
		storage: AppStorage,
		path: okio.Path,
	): Map<String, List<CardPrinting>> {
		if (!storage.fileSystem.exists(path)) return emptyMap()
		val vOut = LinkedHashMap<String, MutableList<CardPrinting>>()
		storage.fileSystem.source(path).buffer().use { vSource ->
			while (true) {
				val vLine = vSource.readUtf8Line() ?: break
				if (vLine.isBlank()) continue
				val vTab = vLine.indexOf('\t')
				if (vTab <= 0) continue
				val vCard = runCatching {
					mJson.decodeFromString(serializer<CardPrinting>(), vLine.substring(vTab + 1))
				}.getOrNull() ?: continue
				vOut.getOrPut(vLine.substring(0, vTab)) { mutableListOf() } += vCard
			}
		}
		return vOut
	}

	/** One bucket per set *and* language, because that is exactly what one cache entry holds. */
	private fun bucketKey(setId: SourceId, language: CardLanguage?): String =
		setId.qualified + "|" + (language?.code ?: "-")

	/** Which shard a bucket's records go to. Stable, and spread by the hash rather than by name. */
	private fun shardOf(bucketKey: String): Int {
		val vHex = bucketKey.encodeUtf8().sha256().hex()
		return vHex.substring(0, 4).toInt(16) % BULK_SHARDS
	}

	private fun shardName(shard: Int): String = "shard-$shard.jsonl"

	private companion object {

		/** Scratch directory for a bulk import's shards. Deleted when the import ends. */
		private const val BULK_SCRATCH_DIR = "bulk-scratch"

		/**
		 * How many scratch files a bulk import streams into.
		 *
		 * Two limits, pulling opposite ways. Every shard is open at once during the stream, so this
		 * is a floor on file descriptors -- iOS allows 256 for the whole process and Android is not
		 * generous either. Against that, a shard is read back whole, so fewer shards means a larger
		 * peak. 64 keeps a shard of Scryfall's dump to a few megabytes while leaving descriptors
		 * spare. `BulkImportTest` holds this to a bounded count rather than to the number itself.
		 */
		private const val BULK_SHARDS = 64

		/** How often the reading phase reports, in cards. Often enough to move, rarely enough to be free. */
		private const val BULK_PROGRESS_EVERY = 2_000
	}
}

/**
 * Collapses copies of one printing, keeping the one that asserts the most.
 *
 * Top-level and `internal` because two classes in this package need it and a second copy of a rule
 * like this is a second thing to get wrong. Identity is the provider's own, via
 * `CardPrinting.dedupeKey`: a provider that declares none is never de-duplicated at all.
 *
 * It exists because a real provider really does this. Riftcodex's Vendetta returns 358 records for
 * 227 distinct ids -- 37% of the set sent twice, each copy under its own database id, so nothing
 * downstream could tell them apart and the grid showed every one.
 *
 * Where copies disagree the one asserting the most wins: a treatment is a statement and its absence
 * is indistinguishable from a field nobody filled in. Order is otherwise preserved.
 */
internal fun dedupedPrintings(cards: List<CardPrinting>): List<CardPrinting> {
	val vSeen = LinkedHashMap<String, CardPrinting>(cards.size)
	for (vCard in cards) {
		val vExisting = vSeen[vCard.dedupeKey]
		if (vExisting == null || vCard.saysMoreThan(vExisting)) {
			vSeen[vCard.dedupeKey] = vCard
		}
	}
	return vSeen.values.toList()
}

/** True when [this] carries a positive claim the other copy does not. */
private fun CardPrinting.saysMoreThan(other: CardPrinting): Boolean =
	artwork.treatment != ArtworkTreatment.STANDARD &&
		other.artwork.treatment == ArtworkTreatment.STANDARD

/**
 * The language a provider will really answer in, given what a caller asked for.
 *
 * Top-level for the same reason as [dedupedPrintings]: every cache key embeds a language, so the
 * *requested* one cannot be the one that keys it -- two callers asking the same question
 * differently would write and read different files. That is exactly what happened once, and one
 * shared function is what stops it happening again.
 */
internal fun resolvedLanguage(
	provider: CardProvider<GameProfile>,
	requested: CardLanguage?,
): CardLanguage? = provider.resolveLanguage(requested)
