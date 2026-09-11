package com.bitsycore.cardbrowser.data.cache

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.sqlstore.PinnedSet
import com.bitsycore.cardbrowser.sqlstore.SqlCardStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Where complete sets live, and the one place `:data` talks to the database.
 *
 * ## Why this exists rather than `CardRepository` calling the store
 *
 * Three reasons, in order of how much they matter.
 *
 * **It keeps the identity in one place.** A cached set is `(provider, set, language)` everywhere in
 * this app, and the store takes those as three strings. Building them at eighteen call sites is
 * how two of them end up disagreeing -- which is exactly the bug the file cache's
 * `completeSetKey` was extracted to prevent, and there is no reason to relearn it.
 *
 * **It keeps the dispatcher honest.** SQLDelight is synchronous. Every call here hops to IO, so a
 * query cannot land on the UI thread by being called from the wrong coroutine.
 *
 * **It draws the line the split needs.** Complete sets are here; set lists, card detail, search
 * pages and per-set languages stay in [MetadataCache]. That split is deliberate -- those scopes
 * are few and tiny, and the cost the migration was for was never in them -- but a split is only
 * safe if it is one line rather than a judgement made per call site.
 */
class SetRecordStore(
	private val mStore: SqlCardStore,
	private val mIoDispatcher: CoroutineDispatcher,
) {

	/**
	 * One cached set, or `null` when this edition is not held.
	 *
	 * Reading marks it used, which is what eviction orders by. A read that did not touch would
	 * make the set you keep coming back to look as old as the one you opened once -- which is the
	 * failure the file cache had permanently, since its access times died with the process.
	 */
	suspend fun read(
		provider: ProviderId,
		setId: SourceId,
		language: CardLanguage?,
		nowEpochMillis: Long,
	): StoredSet? = withContext(mIoDispatcher) {
		val vMeta = mStore.setMetadata(provider.value, setId.qualified, language) ?: return@withContext null
		mStore.touch(provider.value, setId.qualified, language, nowEpochMillis)
		StoredSet(
			cards = mStore.readSet(provider.value, setId.qualified, language),
			isComplete = vMeta.isComplete,
			fetchedAtEpochMillis = vMeta.fetchedAtEpochMillis,
		)
	}

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
		label: String,
		cards: List<CardPrinting>,
		isComplete: Boolean,
		fetchedAtEpochMillis: Long,
	) = withContext(mIoDispatcher) {
		mStore.writeSet(
			provider = provider.value,
			setId = setId.qualified,
			language = language,
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

	suspend fun exists(provider: ProviderId, setId: SourceId, language: CardLanguage?): Boolean =
		withContext(mIoDispatcher) { mStore.hasSet(provider.value, setId.qualified, language) }

	/**
	 * How many cards this edition holds, or `null` when it is not held.
	 *
	 * `null` is not zero and the difference is load-bearing: "never fetched in this language" and
	 * "fetched and there were none" are opposite facts, and the set list prints a different number
	 * for each.
	 */
	suspend fun cardCount(provider: ProviderId, setId: SourceId, language: CardLanguage?): Int? =
		withContext(mIoDispatcher) { mStore.cardCount(provider.value, setId.qualified, language) }

	/**
	 * Which languages of a set are on disk.
	 *
	 * One query where the file cache needed a file-existence check per candidate language -- up to
	 * eleven per set, per row of the set list.
	 */
	suspend fun languagesHeld(provider: ProviderId, setId: SourceId): Set<CardLanguage> =
		withContext(mIoDispatcher) {
			mStore.languagesHeld(provider.value, setId.qualified)
				.mapNotNullTo(mutableSetOf()) { CardLanguage.fromCode(it) }
		}

	suspend fun isPinned(provider: ProviderId, setId: SourceId, language: CardLanguage?): Boolean =
		withContext(mIoDispatcher) { mStore.isPinned(provider.value, setId.qualified, language) }

	suspend fun setPinned(
		provider: ProviderId,
		setId: SourceId,
		language: CardLanguage?,
		isPinned: Boolean,
	) = withContext(mIoDispatcher) {
		mStore.setPinned(provider.value, setId.qualified, language, isPinned)
	}

	/** Every downloaded set, for a screen that offers to delete them. */
	suspend fun pinnedSets(): List<PinnedSet> = withContext(mIoDispatcher) { mStore.pinnedSets() }

	suspend fun unpinnedBytes(): Long = withContext(mIoDispatcher) { mStore.unpinnedBytes() }

	suspend fun pinnedBytes(): Long = withContext(mIoDispatcher) { mStore.pinnedBytes() }

	/** Evicts least-recently-used unpinned sets until browsing fits. Returns how many went. */
	suspend fun trim(ceilingBytes: Long): Int =
		withContext(mIoDispatcher) { mStore.trim(ceilingBytes) }

	suspend fun clear() = withContext(mIoDispatcher) { mStore.clear() }

	/** Counts for a storage screen, in one read rather than a directory walk. */
	suspend fun snapshot(): StoredCounts = withContext(mIoDispatcher) {
		val vSnapshot = mStore.storageSnapshot()
		StoredCounts(
			sets = vSnapshot.sets,
			pinnedSets = vSnapshot.pinnedSets,
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
}

/** What the storage screen asks for. */
data class StoredCounts(
	val sets: Int,
	val pinnedSets: Int,
	val printings: Int,
	val byLanguage: Map<String, Int>,
	val unpinnedBytes: Long,
	val pinnedBytes: Long,
)
