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
		var vBytes = 0L
		mDatabase.transaction {
			// Replacing a set, not merging into it. A refetch that returns fewer cards must not
			// leave the ones it dropped behind -- that is how a set ends up holding printings the
			// source no longer serves, with nothing to say where they came from.
			mQueries.deleteSetRows(provider, setId, vLanguage)
			for (vCard in printings) {
				val vPayload = JSON.encodeToString(CardPrinting.serializer(), vCard)
				// Measured from what is stored, not from the file. SQLite's page overhead is real
				// but it is not a thing a user's ceiling should be spent accounting for, and a
				// budget that moves when the database is vacuumed is a budget nobody can reason
				// about.
				vBytes += vPayload.length.toLong()
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
					payload = vPayload,
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
				bytes = vBytes,
				accessed_at = fetchedAt,
			)
		}
	}

	// ==================
	// MARK: Pins and the budget
	// ==================

	/**
	 * Marks a set as deliberately downloaded, or releases it.
	 *
	 * A pinned set is outside the eviction budget entirely -- not merely skipped when evicting.
	 * Counting it would be a ceiling measured against bytes it has no power over: one import is
	 * enough to exceed any sane limit, and from then on every write would evict browsing records
	 * that together came nowhere near it.
	 *
	 * Idempotent, and safe to call before the set exists: the download queue pins first so that a
	 * set large enough to breach the ceiling cannot be evicted by the write that stores it.
	 */
	fun setPinned(provider: String, setId: String, language: CardLanguage?, isPinned: Boolean) {
		mQueries.setPinned(
			pinned = if (isPinned) 1L else 0L,
			provider = provider,
			set_id = setId,
			language = language?.code ?: "-",
		)
	}

	/** What browsing occupies. Pinned sets excluded -- see [setPinned]. */
	fun unpinnedBytes(): Long = mQueries.unpinnedBytes().executeAsOne()

	/** What downloads occupy, reported separately so a screen can say why the ceiling gave way. */
	fun pinnedBytes(): Long = mQueries.pinnedBytes().executeAsOne()

	/** Every downloaded set, by label, so the storage screen can name what it would delete. */
	fun pinnedSets(): List<PinnedSet> = mQueries.pinnedSets().executeAsList().map {
		PinnedSet(
			provider = it.provider,
			setId = it.set_id,
			language = it.language,
			label = it.label,
			cardCount = it.card_count.toInt(),
			bytes = it.bytes,
		)
	}

	/**
	 * Evicts least-recently-used unpinned sets until browsing fits under [ceilingBytes].
	 *
	 * Least recently *used*, which is the thing the file cache could not do: it kept access times
	 * in memory, so after a relaunch every record sorted equally old and the order collapsed to
	 * whatever the filesystem listed. A column survives the process.
	 *
	 * Returns how many sets were removed, so a caller can tell "nothing to do" from "nothing left
	 * that may be removed" -- the second is the ordinary state during a download and is why the
	 * ceiling is allowed to give way.
	 */
	fun trim(ceilingBytes: Long): Int {
		var vTotal = unpinnedBytes()
		if (vTotal <= ceilingBytes) return 0
		var vRemoved = 0
		for (vCandidate in mQueries.evictionCandidates().executeAsList()) {
			if (vTotal <= ceilingBytes) break
			mDatabase.transaction {
				mQueries.deleteSetRows(vCandidate.provider, vCandidate.set_id, vCandidate.language)
				mQueries.deleteCachedSet(vCandidate.provider, vCandidate.set_id, vCandidate.language)
			}
			vTotal -= vCandidate.bytes
			vRemoved++
		}
		return vRemoved
	}

	/** Records that a set was read, which is what eviction orders by. */
	fun touch(provider: String, setId: String, language: CardLanguage?, at: Long) {
		mQueries.touchSet(at, provider, setId, language?.code ?: "-")
	}

	/** Whether this exact edition is on disk, which is the set list's saved mark. */
	fun hasSet(provider: String, setId: String, language: CardLanguage?): Boolean =
		mQueries.hasSet(provider, setId, language?.code ?: "-").executeAsOne() > 0

	/** Which languages of a set are held, without a file-existence check per candidate. */
	fun languagesHeld(provider: String, setId: String): List<String> =
		mQueries.languagesHeldForSet(provider, setId).executeAsList()

	/** How many cards a set really holds in one language. The `.n` sidecar, as a column. */
	fun cardCount(provider: String, setId: String, language: CardLanguage?): Int? =
		mQueries.cardCountForSet(provider, setId, language?.code ?: "-")
			.executeAsOneOrNull()
			?.toInt()

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

/** One downloaded set, named well enough for a screen to offer deleting it. */
data class PinnedSet(
	val provider: String,
	val setId: String,
	val language: String,
	val label: String,
	val cardCount: Int,
	val bytes: Long,
)

/** What the storage screen needs, in one read. */
data class StorageSnapshot(
	val sets: Int,
	val pinnedSets: Int,
	val printings: Int,
	val byLanguage: Map<String, Int>,
)
