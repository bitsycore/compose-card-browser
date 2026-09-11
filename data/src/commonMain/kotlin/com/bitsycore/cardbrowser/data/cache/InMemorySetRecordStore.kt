package com.bitsycore.cardbrowser.data.cache

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.sqlstore.GameStorageRow
import com.bitsycore.cardbrowser.sqlstore.PinnedSet

/**
 * A [SetRecordStore] held in maps, for tests and previews.
 *
 * ## What this is and is not for
 *
 * The repository's tests are about routing, staleness, language resolution and what the app claims
 * on screen. They need somewhere to put a set and get it back; they have no opinion about SQL. A
 * real database in `commonTest` would mean Robolectric on the Android host tests for exactly that
 * -- see [SetRecordStore]'s KDoc.
 *
 * It lives in the main source set rather than a test one because two test source sets need it --
 * `:data`'s and `:composeApp`'s, the latter to assemble the real Koin graph without a platform
 * driver -- and two copies of a double is how the two stop agreeing.
 *
 * **Eviction, the byte budget and the pin budget are not modelled here**, and that is deliberate
 * rather than an omission to fill in later. Those are the store's own rules, they are the three
 * things the file cache got wrong across three separate bug-fixes, and a second implementation of
 * them would be a second thing to get wrong. They are tested against the real database in
 * `SqlCardStoreTest`. [trim] here removes unpinned sets wholesale when asked for a zero ceiling and
 * otherwise does nothing, which is enough for "clear browsing data" and honest about the rest.
 */
class InMemorySetRecordStore(
	override val wasRecovered: Boolean = false,
	/**
	 * Called on every [write], so a test can observe *when* sets are stored.
	 *
	 * The bulk import's interleaving test needs that ordering, and it used to read it off the file
	 * system because a set was a file. It is a row now, so the observation has to be here.
	 */
	private val mOnWrite: () -> Unit = {},
) : SetRecordStore {

	private data class Key(val provider: String, val setId: String, val language: String?)

	private data class Row(
		val game: String,
		val label: String,
		val cards: List<CardPrinting>,
		val isComplete: Boolean,
		val fetchedAt: Long,
		val isPinned: Boolean,
	)

	private val mRows = mutableMapOf<Key, Row>()

	/** How many times [write] ran, so a test can assert a fetch was not repeated. */
	var writeCount: Int = 0
		private set

	private fun key(provider: ProviderId, setId: SourceId, language: CardLanguage?) =
		Key(provider.value, setId.qualified, language?.code)

	override suspend fun read(
		provider: ProviderId,
		setId: SourceId,
		language: CardLanguage?,
		nowEpochMillis: Long,
	): StoredSet? = mRows[key(provider, setId, language)]?.let {
		StoredSet(cards = it.cards, isComplete = it.isComplete, fetchedAtEpochMillis = it.fetchedAt)
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
	) {
		val vKey = key(provider, setId, language)
		writeCount++
		mOnWrite()
		mRows[vKey] = Row(
			game = game.value,
			label = label,
			cards = cards,
			isComplete = isComplete,
			fetchedAt = fetchedAtEpochMillis,
			// Preserved, like the real store: the download queue pins before it fetches.
			isPinned = mRows[vKey]?.isPinned == true,
		)
	}

	override suspend fun exists(provider: ProviderId, setId: SourceId, language: CardLanguage?) =
		key(provider, setId, language) in mRows

	override suspend fun cardCount(provider: ProviderId, setId: SourceId, language: CardLanguage?) =
		mRows[key(provider, setId, language)]?.cards?.size

	override suspend fun languagesHeld(provider: ProviderId, setId: SourceId): Set<CardLanguage> =
		mRows.keys
			.filter { it.provider == provider.value && it.setId == setId.qualified }
			.mapNotNullTo(mutableSetOf()) { it.language?.let(CardLanguage::fromCode) }

	override suspend fun isPinned(provider: ProviderId, setId: SourceId, language: CardLanguage?) =
		mRows[key(provider, setId, language)]?.isPinned == true

	override suspend fun setPinned(
		provider: ProviderId,
		setId: SourceId,
		language: CardLanguage?,
		game: GameId,
		label: String,
		isPinned: Boolean,
	) {
		val vKey = key(provider, setId, language)
		val vRow = mRows[vKey]
		mRows[vKey] = vRow?.copy(isPinned = isPinned)
			// Pinned before the set exists, which is the order a download uses.
			?: Row(game.value, label, emptyList(), isComplete = false, fetchedAt = 0L, isPinned = isPinned)
	}

	override suspend fun pinnedSets(): List<PinnedSet> = mRows.entries
		.filter { it.value.isPinned }
		.map { (vKey, vRow) ->
			PinnedSet(
				provider = vKey.provider,
				setId = vKey.setId,
				language = vKey.language ?: "-",
				game = vRow.game,
				label = vRow.label,
				cardCount = vRow.cards.size,
				bytes = vRow.cards.size.toLong(),
			)
		}

	override suspend fun downloadedByGame(): List<GameStorageRow> = mRows.entries
		.filter { it.value.isPinned }
		.groupBy { it.value.game }
		.map { (vGame, vEntries) ->
			GameStorageRow(
				game = vGame,
				sets = vEntries.map { it.key.setId }.distinct().size,
				records = vEntries.size,
				cards = vEntries.sumOf { it.value.cards.size },
				bytes = vEntries.sumOf { it.value.cards.size.toLong() },
			)
		}

	override suspend fun deleteDownloaded(game: GameId): Int {
		val vGoing = mRows.filterValues { it.isPinned && it.game == game.value }.keys
		vGoing.forEach { mRows.remove(it) }
		return vGoing.size
	}

	override suspend fun trim(ceilingBytes: Long): Int {
		if (ceilingBytes > 0L) return 0
		val vGoing = mRows.filterValues { !it.isPinned }.keys.toList()
		vGoing.forEach { mRows.remove(it) }
		return vGoing.size
	}

	override suspend fun clear() = mRows.clear()

	override suspend fun snapshot(): StoredCounts = StoredCounts(
		sets = mRows.size,
		pinnedSets = mRows.count { it.value.isPinned },
		printings = mRows.values.sumOf { it.cards.size },
		byLanguage = mRows.keys.groupingBy { it.language ?: "-" }.eachCount(),
		unpinnedBytes = mRows.filterValues { !it.isPinned }.values.sumOf { it.cards.size.toLong() },
		pinnedBytes = mRows.filterValues { it.isPinned }.values.sumOf { it.cards.size.toLong() },
	)
}
