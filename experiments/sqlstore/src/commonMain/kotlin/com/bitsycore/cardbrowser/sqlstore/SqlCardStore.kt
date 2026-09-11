package com.bitsycore.cardbrowser.sqlstore

import app.cash.sqldelight.db.SqlDriver
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.sqlstore.db.CardDatabase
import kotlinx.serialization.json.Json

/**
 * The same job `MetadataCache` does for complete sets, done in SQLite.
 *
 * Deliberately shaped like the thing it would replace rather than like the nicest possible API:
 * write a set whole, read a set whole, and answer the questions the storage screen asks. That is
 * what makes the measurement fair -- a store with a different contract would be measuring a
 * different app.
 *
 * ## What is faithful, and what is not
 *
 * Faithful: the record identity `(provider, set, language)`, the payload stored as the serialised
 * `CardPrinting` exactly as the file cache stores it, and a pin flag that keeps a downloaded set
 * outside the eviction budget.
 *
 * Not modelled, on purpose, because none of it changes the numbers: eviction itself, the
 * `.n` card-count sidecar (a column here), corrupt-record recovery, and the image cache, which
 * stays a directory under any design.
 */
class SqlCardStore(driver: SqlDriver) {

	private val mDatabase = CardDatabase(driver)

	private val mQueries = mDatabase.printingQueries

	/**
	 * Writes a whole set in one transaction.
	 *
	 * One transaction per set rather than one per card, and rather than one for the whole import.
	 * Per card is thousands of fsyncs; the whole import is a single unit that cannot be resumed,
	 * which loses the property that a download interrupted half way leaves half a set genuinely on
	 * disk. Per set matches what the file cache already guarantees.
	 */
	fun writeSet(
		provider: String,
		setId: String,
		language: CardLanguage?,
		label: String,
		isPinned: Boolean,
		fetchedAt: Long,
		printings: List<CardPrinting>,
	) {
		val vLanguage = language?.code ?: "-"
		mDatabase.transaction {
			for (vCard in printings) {
				mQueries.upsertPrinting(
					provider = provider,
					card_id = vCard.id.local,
					game = vCard.game.value,
					set_id = setId,
					set_code = vCard.setCode,
					language = vLanguage,
					name = vCard.displayName,
					// Folded once, at write time. Folding at query time would mean no index could
					// be used, which is most of what this store is for.
					name_folded = fold(vCard.displayName),
					card_type = vCard.classification.type,
					rarity = vCard.classification.rarity,
					cost = vCard.attributes.cost?.toLong(),
					domains = vCard.classification.domains
						.takeIf { it.isNotEmpty() }
						?.joinToString("|") { fold(it) },
					payload = JSON.encodeToString(CardPrinting.serializer(), vCard),
				)
			}
			mQueries.upsertCachedSet(
				provider = provider,
				set_id = setId,
				language = vLanguage,
				label = label,
				pinned = if (isPinned) 1L else 0L,
				fetched_at = fetchedAt,
				card_count = printings.size.toLong(),
			)
		}
	}

	/** One set's cards, which is the set-open path. */
	fun readSet(provider: String, setId: String, language: CardLanguage?): List<CardPrinting> =
		mQueries.printingsInSet(provider, setId, language?.code ?: "-")
			.executeAsList()
			.map { JSON.decodeFromString(CardPrinting.serializer(), it) }

	/** What the storage screen asks, in one place. Four directory walks today. */
	fun storageSnapshot(): StorageSnapshot = StorageSnapshot(
		sets = mQueries.cachedSetCount().executeAsOne().toInt(),
		pinnedSets = mQueries.pinnedSetCount().executeAsOne().toInt(),
		printings = mQueries.printingCount().executeAsOne().toInt(),
		byLanguage = mQueries.languageBreakdown().executeAsList()
			.associate { it.language to it.total.toInt() },
	)

	/**
	 * A filtered search across every downloaded set of one game.
	 *
	 * The query the file cache cannot answer at all. Today the equivalent is loading every cached
	 * set off disk, parsing it, and filtering in memory -- which is why the app's cross-set search
	 * matches names only and says so.
	 *
	 * Every argument is optional and a null one drops its predicate, so the same statement serves
	 * any combination of filter chips. [excludeText] is the "does not contain" that has no
	 * equivalent today.
	 */
	fun search(
		game: String,
		language: CardLanguage? = null,
		text: String? = null,
		excludeText: String? = null,
		cardType: String? = null,
		rarity: String? = null,
		minCost: Int? = null,
		maxCost: Int? = null,
		domain: String? = null,
		limit: Int = 200,
	): List<CardPrinting> = mQueries.searchPrintings(
		game = game,
		language = language?.code,
		text = text?.let(::fold),
		excludeText = excludeText?.let(::fold),
		cardType = cardType,
		rarity = rarity,
		maxCost = maxCost?.toLong(),
		minCost = minCost?.toLong(),
		domain = domain?.let(::fold),
		limit = limit.toLong(),
	).executeAsList().map { JSON.decodeFromString(CardPrinting.serializer(), it) }

	private companion object {

		/** Matches the file cache's parser: a record carries fields this build has no DTO for. */
		val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }

		/**
		 * Lowercase and strip accents, so "Kai'Sa" and "kaisa" match.
		 *
		 * Crude next to `CardFilterEngine`'s folding, and deliberately so: this spike is measuring
		 * whether the index works, not reimplementing search. A migration would move the real
		 * folding here rather than keep two.
		 */
		fun fold(value: String): String = value.lowercase()
			.map { vChar ->
				when (vChar) {
					'á', 'à', 'â', 'ä', 'ã' -> 'a'
					'é', 'è', 'ê', 'ë' -> 'e'
					'í', 'ì', 'î', 'ï' -> 'i'
					'ó', 'ò', 'ô', 'ö', 'õ' -> 'o'
					'ú', 'ù', 'û', 'ü' -> 'u'
					'ç' -> 'c'
					'ñ' -> 'n'
					else -> vChar
				}
			}
			.joinToString("")
	}
}

/** What the storage screen needs, in one read. */
data class StorageSnapshot(
	val sets: Int,
	val pinnedSets: Int,
	val printings: Int,
	val byLanguage: Map<String, Int>,
)
