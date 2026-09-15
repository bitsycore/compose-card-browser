package com.bitsycore.tcgexplorer.data.cache

import com.bitsycore.tcgexplorer.core.model.ArtworkTreatment
import com.bitsycore.tcgexplorer.core.model.CardLanguage
import com.bitsycore.tcgexplorer.core.model.CardPrinting
import com.bitsycore.tcgexplorer.core.model.GameId
import com.bitsycore.tcgexplorer.core.model.ProviderId
import com.bitsycore.tcgexplorer.core.model.SourceId
import com.bitsycore.tcgexplorer.sqlstore.GameStorageRow
import com.bitsycore.tcgexplorer.sqlstore.StoredSetRow
import com.bitsycore.tcgexplorer.sqlstore.SqlCardStore
import com.bitsycore.tcgexplorer.sqlstore.StoredFacets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Where complete sets live, and the one seam `:data` has onto the database.
 *
 * ## Why this exists rather than `CardRepository` calling the store
 *
 * Three reasons, in order of how much they matter.
 *
 * **It keeps the identity in one place.** A cached set is `(provider, set, language)` everywhere in
 * this app, and the store takes those as three strings. Building them at eighteen call sites is
 * how two of them end up disagreeing -- which is exactly the bug the file cache's `completeSetKey`
 * was extracted to prevent, and there is no reason to relearn it.
 *
 * **It keeps the dispatcher honest.** SQLDelight is synchronous. Every call in the real
 * implementation hops to IO, so a query cannot land on the UI thread by being called from the
 * wrong coroutine.
 *
 * **It draws the line the split needs.** Complete sets are here; set lists, card detail, search
 * pages and per-set languages stay in [MetadataCache]. That split is deliberate -- those scopes are
 * few and tiny, and the cost the migration was for was never in them -- but a split is only safe if
 * it is one line rather than a judgement made per call site.
 *
 * ## Why an interface
 *
 * Not for swappable back ends; there is one. It is so the repository's tests can run everywhere the
 * shared tests run. Every platform's SQLite driver needs something the platform supplies -- Android
 * a `Context` most of all -- so a real database in `commonTest` would mean Robolectric on the
 * Android host tests, for tests that are about routing and cache policy and have no opinion about
 * SQL.
 *
 * That split is drawn on purpose and is the reason it is safe: the rules that are genuinely the
 * store's -- eviction order, the pin budget, identity, a cost filter's treatment of unknown -- are
 * tested against the real store in `SqlCardStoreTest`, never against a fake.
 */
interface SetRecordStore {

	/**
	 * True when the database was recreated at startup -- see `CardStoreFactory.open`.
	 *
	 * Carried here rather than left in the factory because the thing that has to *act* on it is
	 * startup, and startup already holds this. [CacheReconciler] is what acts.
	 */
	val wasRecovered: Boolean

	/**
	 * One cached set, or `null` when this edition is not held.
	 *
	 * Reading marks it used, which is what eviction orders by. A read that did not touch would make
	 * the set you keep coming back to look as old as the one you opened once -- which is the failure
	 * the file cache had permanently, since its access times died with the process.
	 */
	suspend fun read(
		provider: ProviderId,
		setId: SourceId,
		language: CardLanguage?,
		nowEpochMillis: Long,
	): StoredSet?

	/**
	 * Stores a set whole.
	 *
	 * @param label what a storage screen should call this, since a downloaded set has to be
	 *   nameable after everything else is gone
	 * @param isComplete false when the fetch could not finish. A partial set is stored on purpose
	 *   -- it is still worth showing -- and the flag is what stops it being presented as the set
	 */
	suspend fun write(
		provider: ProviderId,
		setId: SourceId,
		language: CardLanguage?,
		game: GameId,
		label: String,
		cards: List<CardPrinting>,
		isComplete: Boolean,
		fetchedAtEpochMillis: Long,
	)

	suspend fun exists(provider: ProviderId, setId: SourceId, language: CardLanguage?): Boolean

	/**
	 * How many cards this edition holds, or `null` when it is not held.
	 *
	 * `null` is not zero and the difference is load-bearing: "never fetched in this language" and
	 * "fetched and there were none" are opposite facts, and the set list prints a different number
	 * for each.
	 */
	suspend fun cardCount(provider: ProviderId, setId: SourceId, language: CardLanguage?): Int?

	/**
	 * Which languages of a set are on disk.
	 *
	 * One query where the file cache needed a file-existence check per candidate language -- up to
	 * eleven per set, per row of the set list.
	 *
	 * `null` is a member, not an absence: a source that states no languages -- OPTCG and TCGCSV --
	 * writes its records under none, and those rows are held just as firmly as any other. Dropping
	 * it made every One Piece set read as not downloaded while it sat on disk, so the row never got
	 * its mark and the dialog offered the same download for ever. An **empty** set is the only way
	 * to say nothing is held.
	 */
	suspend fun languagesHeld(provider: ProviderId, setId: SourceId): Set<CardLanguage?>

	suspend fun isPinned(provider: ProviderId, setId: SourceId, language: CardLanguage?): Boolean

	/**
	 * Marks a set as deliberately downloaded, or releases it.
	 *
	 * Takes [game] and [label] because a pin can arrive *before* the set does -- the download queue
	 * pins first, so that a set large enough to breach the ceiling is not evicted by the very write
	 * that stores it -- and a row created by that pin still has to be nameable on the storage
	 * screen and attributable to a game.
	 */
	suspend fun setPinned(
		provider: ProviderId,
		setId: SourceId,
		language: CardLanguage?,
		game: GameId,
		label: String,
		isPinned: Boolean,
	)

	/** Every stored set, browsed ones included, for a screen that offers to delete them. */
	suspend fun storedSets(): List<StoredSetRow>

	/** What each game holds, in one query rather than a directory walk. */
	suspend fun storedByGame(): List<GameStorageRow>

	/** Deletes everything one game holds, browsed sets included. Returns how many records went. */
	suspend fun deleteDownloaded(game: GameId): Int

	/** One game's stored editions, one entry per set per language. */
	suspend fun storedSetsFor(game: GameId): List<StoredSetRow>

	/** Deletes one stored edition. Returns true when there was one. */
	suspend fun deleteDownloadedSet(provider: String, setId: String, language: String): Boolean

	/** Evicts least-recently-used unpinned sets until browsing fits. Returns how many went. */
	suspend fun trim(ceilingBytes: Long): Int

	suspend fun clear()

	/** Counts for a storage screen, in one read rather than a directory walk. */
	suspend fun snapshot(): StoredCounts

	/**
	 * The advanced search: one indexed query over every stored card of a game.
	 *
	 * The thing the file cache could not do at all. Its equivalent was loading every cached set off
	 * disk and matching names in memory, which is why the app's cross-set search offered a name and
	 * nothing else.
	 *
	 * Still a search of what is **downloaded**, and the screen has to keep saying so. A query that
	 * runs in 15 ms over a hundred thousand rows feels like a search of everything, and that is
	 * precisely the reading this codebase must not allow.
	 */
	suspend fun search(game: GameId, filter: CardSearchFilter, limit: Int = SEARCH_LIMIT): List<CardPrinting>

	/** What a game's stored cards contain, so a filter cannot offer something that matches nothing. */
	suspend fun facetsForGame(game: GameId): StoredFacets

	/**
	 * Which of a game's sets are held *whole*, by set id, to the languages each is complete in.
	 *
	 * Complete rather than present, which is a distinction this app makes everywhere: a set fetched
	 * part-way is in the store and is not in here.
	 */
	suspend fun completeSetsForGame(game: GameId): Map<String, Set<String>>
}

/**
 * Everything the advanced search can narrow on, as one value.
 *
 * Null means "not filtering on this" throughout, which is what lets the SQL be one statement with a
 * NULL guard per predicate rather than a query built by string concatenation.
 *
 * @property text what a name must contain
 * @property excludeText what a name must *not* contain -- the half a name match cannot express, and
 *   the reason this exists rather than a longer search box
 */
data class CardSearchFilter(
	val text: String? = null,
	val excludeText: String? = null,
	/** Any of these, not all: chips on one axis are an OR, as they are in the card grid. */
	val cardTypes: Set<String> = emptySet(),
	val rarities: Set<String> = emptySet(),
	/**
	 * The costs to match, exactly. Empty means every cost.
	 *
	 * A set and not a `min..max` span, because the filter sheet offers values rather than a range.
	 * It *was* a span, filled by collapsing the chosen chips to their extremes -- so choosing 1 and
	 * 5 searched 1 through 5 and returned 2, 3 and 4 as well, while the same chips inside a set
	 * matched exactly. One control, two meanings. The span is still on `SqlCardStore.search`, which
	 * is a real thing to be able to ask; nothing asks it through here.
	 */
	val costs: Set<Int> = emptySet(),
	val domains: Set<String> = emptySet(),
	/** Artwork treatments, as `ArtworkTreatment` names them. Alternate art, and its relatives. */
	val treatments: Set<ArtworkTreatment> = emptySet(),
	val language: CardLanguage? = null,
	/** The sets to look in, or empty for every set that has anything stored. */
	val setIds: Set<String> = emptySet(),
) {

	/** True when nothing is set, which is a request to show nothing rather than everything. */
	val isEmpty: Boolean
		get() = text.isNullOrBlank() && excludeText.isNullOrBlank() && cardTypes.isEmpty() &&
			rarities.isEmpty() && costs.isEmpty() && domains.isEmpty() &&
			treatments.isEmpty()
}

/** How many rows one search returns. A screenful many times over; not a paging story yet. */
const val SEARCH_LIMIT: Int = 200

/** The real one: SQLite, off the UI thread. */
class SqlSetRecordStore(
	private val mStore: SqlCardStore,
	private val mIoDispatcher: CoroutineDispatcher,
	override val wasRecovered: Boolean = false,
) : SetRecordStore {

	override suspend fun read(
		provider: ProviderId,
		setId: SourceId,
		language: CardLanguage?,
		nowEpochMillis: Long,
	): StoredSet? = withContext(mIoDispatcher) {
		val vMeta = mStore.setMetadata(provider.value, setId.qualified, language)
			?: return@withContext null
		mStore.touch(provider.value, setId.qualified, language, nowEpochMillis)
		StoredSet(
			cards = mStore.readSet(provider.value, setId.qualified, language),
			isComplete = vMeta.isComplete,
			fetchedAtEpochMillis = vMeta.fetchedAtEpochMillis,
		)
	}

	override suspend fun write(
		provider: ProviderId,
		setId: SourceId,
		language: CardLanguage?,
		game: GameId,
		label: String,
		cards: List<CardPrinting>,
		isComplete: Boolean,
		fetchedAtEpochMillis: Long,
	) = withContext(mIoDispatcher) {
		mStore.writeSet(
			provider = provider.value,
			setId = setId.qualified,
			language = language,
			game = game.value,
			label = label,
			// Preserved rather than assumed: the download queue pins *before* the fetch, so the
			// row may already exist and be pinned, and a write that reset the flag would hand a
			// downloaded set back to the evictor mid-download.
			isPinned = mStore.isPinned(provider.value, setId.qualified, language),
			fetchedAt = fetchedAtEpochMillis,
			printings = cards,
			isComplete = isComplete,
		)
	}

	override suspend fun exists(
		provider: ProviderId,
		setId: SourceId,
		language: CardLanguage?,
	): Boolean = withContext(mIoDispatcher) {
		mStore.hasSet(provider.value, setId.qualified, language)
	}

	override suspend fun cardCount(
		provider: ProviderId,
		setId: SourceId,
		language: CardLanguage?,
	): Int? = withContext(mIoDispatcher) {
		mStore.cardCount(provider.value, setId.qualified, language)
	}

	override suspend fun languagesHeld(provider: ProviderId, setId: SourceId): Set<CardLanguage?> =
		withContext(mIoDispatcher) {
			buildSet {
				for (vCode in mStore.languagesHeld(provider.value, setId.qualified)) {
					// A null code is a row written under no language and is kept. A tag this build
					// has no entry for is dropped, because nothing here could fetch it -- and it
					// must not arrive as the same null that means "the source states none".
					if (vCode == null) add(null) else CardLanguage.fromCode(vCode)?.let(::add)
				}
			}
		}

	override suspend fun isPinned(
		provider: ProviderId,
		setId: SourceId,
		language: CardLanguage?,
	): Boolean = withContext(mIoDispatcher) {
		mStore.isPinned(provider.value, setId.qualified, language)
	}

	override suspend fun setPinned(
		provider: ProviderId,
		setId: SourceId,
		language: CardLanguage?,
		game: GameId,
		label: String,
		isPinned: Boolean,
	) = withContext(mIoDispatcher) {
		mStore.setPinned(provider.value, setId.qualified, language, game.value, label, isPinned)
	}

	override suspend fun storedSets(): List<StoredSetRow> =
		withContext(mIoDispatcher) { mStore.storedSets() }

	override suspend fun storedByGame(): List<GameStorageRow> =
		withContext(mIoDispatcher) { mStore.storedByGame() }

	override suspend fun deleteDownloaded(game: GameId): Int =
		withContext(mIoDispatcher) { mStore.deleteDownloadedGame(game.value) }

	override suspend fun storedSetsFor(game: GameId): List<StoredSetRow> =
		withContext(mIoDispatcher) { mStore.storedSetsForGame(game.value) }

	override suspend fun deleteDownloadedSet(
		provider: String,
		setId: String,
		language: String,
	): Boolean = withContext(mIoDispatcher) {
		mStore.deleteDownloadedSet(provider, setId, language)
	}

	override suspend fun trim(ceilingBytes: Long): Int =
		withContext(mIoDispatcher) { mStore.trim(ceilingBytes) }

	override suspend fun clear() = withContext(mIoDispatcher) { mStore.clear() }

	override suspend fun search(
		game: GameId,
		filter: CardSearchFilter,
		limit: Int,
	): List<CardPrinting> = withContext(mIoDispatcher) {
		mStore.search(
			game = game.value,
			language = filter.language,
			text = filter.text?.takeIf { it.isNotBlank() },
			excludeText = filter.excludeText?.takeIf { it.isNotBlank() },
			cardTypes = filter.cardTypes,
			rarities = filter.rarities,
			costs = filter.costs,
			domains = filter.domains,
			treatments = filter.treatments.mapTo(mutableSetOf()) { it.name },
			setIds = filter.setIds,
			limit = limit,
		)
	}

	override suspend fun facetsForGame(game: GameId): StoredFacets = withContext(mIoDispatcher) {
		// The candidates are the enum's, and the store answers which of them it actually holds.
		mStore.facetsForGame(game.value, ArtworkTreatment.entries.map { it.name })
	}

	override suspend fun completeSetsForGame(game: GameId): Map<String, Set<String>> =
		withContext(mIoDispatcher) { mStore.completeSetsForGame(game.value) }

	override suspend fun snapshot(): StoredCounts = withContext(mIoDispatcher) {
		val vSnapshot = mStore.storageSnapshot()
		StoredCounts(
			sets = vSnapshot.sets,
			storedSets = vSnapshot.pinnedSets,
			printings = vSnapshot.printings,
			byLanguage = vSnapshot.byLanguage,
			unpinnedBytes = mStore.unpinnedBytes(),
			pinnedBytes = mStore.pinnedBytes(),
		)
	}
}

/**
 * A set read back out of the store.
 *
 * Shaped like the `CacheEnvelope` it replaces, so the repository's staleness and completeness
 * reasoning is unchanged -- that reasoning is the part users see and was not what the migration
 * was for.
 */
data class StoredSet(
	val cards: List<CardPrinting>,
	val isComplete: Boolean,
	val fetchedAtEpochMillis: Long,
) {

	fun isStale(nowEpochMillis: Long, ttlMillis: Long): Boolean =
		nowEpochMillis - fetchedAtEpochMillis > ttlMillis

	/**
	 * The same fact as [isComplete], in the vocabulary the screens speak.
	 *
	 * `Completeness` is what `DataSnapshot` carries and what the grid renders "Partial set: 100 of
	 * 358" from, so the boolean is converted once here rather than at every emission -- two
	 * spellings of one fact is how they end up disagreeing.
	 */
	val completenessValue: Completeness
		get() = if (isComplete) Completeness.COMPLETE else Completeness.PARTIAL
}

/** What the storage screen asks for. */
data class StoredCounts(
	val sets: Int,
	val storedSets: Int,
	val printings: Int,
	val byLanguage: Map<String, Int>,
	val unpinnedBytes: Long,
	val pinnedBytes: Long,
)
