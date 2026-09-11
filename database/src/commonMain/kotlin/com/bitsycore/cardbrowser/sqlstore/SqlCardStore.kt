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
		game: String,
		label: String,
		isPinned: Boolean,
		fetchedAt: Long,
		printings: List<CardPrinting>,
		isComplete: Boolean = true,
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
						?.joinToString(DOMAIN_SEPARATOR) { fold(it) },
					payload = vPayload,
				)
			}
			mQueries.upsertCachedSet(
				provider = provider,
				set_id = setId,
				language = vLanguage,
				game = game,
				label = label,
				pinned = if (isPinned) 1L else 0L,
				fetched_at = fetchedAt,
				card_count = printings.size.toLong(),
				complete = if (isComplete) 1L else 0L,
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
	fun setPinned(
		provider: String,
		setId: String,
		language: CardLanguage?,
		game: String,
		label: String,
		isPinned: Boolean,
	) {
		val vLanguage = language?.code ?: "-"
		mQueries.transaction {
			// Only when there is nothing there. See `insertPinPlaceholder`: a pin arrives before
			// the set it protects, and an UPDATE against an absent row is a silent no-op that
			// leaves the download evictable.
			mQueries.insertPinPlaceholder(provider, setId, vLanguage, game, label)
			mQueries.setPinned(
				pinned = if (isPinned) 1L else 0L,
				provider = provider,
				set_id = setId,
				language = vLanguage,
			)
		}
	}

	/**
	 * What a game's stored cards actually contain, for a filter list to offer.
	 *
	 * From the rows, not from a game profile. A profile says what a game *can* have; this says what
	 * is on the device, so the search cannot offer a rarity that would match nothing.
	 *
	 * Domains are stored joined, because a card can have several, so they are split back apart and
	 * de-duplicated here rather than in SQL -- `DISTINCT` over the joined string would offer
	 * "Fury / Calm" as though it were one domain.
	 */
	fun facetsForGame(game: String): StoredFacets = StoredFacets(
		cardTypes = mQueries.cardTypesInGame(game).executeAsList().filterNotNull().sorted(),
		rarities = mQueries.raritiesInGame(game).executeAsList().filterNotNull().sorted(),
		domains = mQueries.domainsInGame(game).executeAsList()
			.filterNotNull()
			.flatMap { it.split(DOMAIN_SEPARATOR) }
			.map { it.trim() }
			.filter { it.isNotEmpty() }
			.distinct()
			.sorted(),
		costRange = mQueries.costRangeInGame(game).executeAsOne().let { vRow ->
			val vLow = vRow.low
			val vHigh = vRow.high
			if (vLow == null || vHigh == null) null else vLow.toInt()..vHigh.toInt()
		},
	)

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
			game = it.game,
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

	/**
	 * One set's own record: how complete it is, when it was fetched, what it is called.
	 *
	 * Separate from [readSet] because most callers want one or the other. The set list asks only
	 * "is it here and how big", which is a row; the grid asks for the cards.
	 */
	fun setMetadata(provider: String, setId: String, language: CardLanguage?): StoredSetMetadata? =
		mQueries.setMetadata(provider, setId, language?.code ?: "-")
			.executeAsOneOrNull()
			?.let {
				StoredSetMetadata(
					label = it.label,
					isPinned = it.pinned > 0,
					fetchedAtEpochMillis = it.fetched_at,
					cardCount = it.card_count.toInt(),
					isComplete = it.complete > 0,
				)
			}

	/** Whether this edition was deliberately downloaded. */
	fun isPinned(provider: String, setId: String, language: CardLanguage?): Boolean =
		mQueries.isSetPinned(provider, setId, language?.code ?: "-")
			.executeAsOneOrNull()
			?.let { it > 0 } == true

	/**
	 * What each game has downloaded, as one query.
	 *
	 * Distinct *sets*, not rows. A set held in two languages is two records and one set, and this
	 * project has printed the wrong side of that slash three times -- records against sets, sets
	 * against a catalogue, a dump's sets against `listSets`. Counting both here, separately and
	 * named, is how a caller stops having to choose the right one by accident.
	 */
	fun pinnedByGame(): List<GameStorageRow> = mQueries.pinnedByGame().executeAsList().map {
		GameStorageRow(
			game = it.game,
			sets = it.sets.toInt(),
			records = it.records.toInt(),
			cards = (it.cards ?: 0L).toInt(),
			bytes = it.bytes ?: 0L,
		)
	}

	/** What one game's download weighs per language, for a screen that breaks it down. */
	fun pinnedLanguagesForGame(game: String): Map<String, Long> =
		mQueries.pinnedLanguagesForGame(game).executeAsList()
			.associate { it.language to (it.bytes ?: 0L) }

	/** Deletes one game's downloads. Rows and records together, in one transaction. */
	fun deleteDownloadedGame(game: String): Int {
		var vRemoved = 0
		mDatabase.transaction {
			vRemoved = mQueries.pinnedByGame().executeAsList()
				.firstOrNull { it.game == game }?.records?.toInt() ?: 0
			mQueries.deletePrintingsForGame(game)
			mQueries.deletePinnedForGame(game)
		}
		return vRemoved
	}

	/** Empties the store. What "clear cached data" means when the cache is a database. */
	fun clear() {
		mDatabase.transaction {
			mQueries.clearAll()
			mQueries.clearAllSets()
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

/**
 * One game's downloaded weight.
 *
 * [sets] and [records] are both here and both named, because they count different populations: a
 * set held in two languages is two records and one set.
 */
data class GameStorageRow(
	val game: String,
	val sets: Int,
	val records: Int,
	val cards: Int,
	val bytes: Long,
)

/** A cached set's own record, without its cards. */
data class StoredSetMetadata(
	val label: String,
	val isPinned: Boolean,
	val fetchedAtEpochMillis: Long,
	val cardCount: Int,
	val isComplete: Boolean,
)

/** One downloaded set, named well enough for a screen to offer deleting it. */
data class PinnedSet(
	val provider: String,
	val setId: String,
	val language: String,
	val game: String,
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

/**
 * What joins a card's domains in the `domains` column.
 *
 * A character no domain contains, because the column is matched with `LIKE '%fury%'` and a
 * separator that could appear inside a name would make one domain match another.
 */
private const val DOMAIN_SEPARATOR = "|"

/**
 * What a game's stored cards contain, for the search screen's filters.
 *
 * Every list is what is *on this device*. An empty one means the sources served none of it, which
 * is a different thing from the game not having it -- and the screen draws that difference by
 * leaving the filter out rather than showing an empty menu.
 */
data class StoredFacets(
	val cardTypes: List<String>,
	val rarities: List<String>,
	val domains: List<String>,
	/** The costs actually present, or null where no card in the game publishes one. */
	val costRange: IntRange?,
)
