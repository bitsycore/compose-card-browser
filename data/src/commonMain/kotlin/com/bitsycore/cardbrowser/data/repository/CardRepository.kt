package com.bitsycore.cardbrowser.data.repository

import com.bitsycore.cardbrowser.core.filter.CardFacets
import com.bitsycore.cardbrowser.core.filter.CardFilterEngine
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import com.bitsycore.cardbrowser.core.provider.CardProvider
import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.cache.CacheEnvelope
import com.bitsycore.cardbrowser.data.cache.CacheKey
import com.bitsycore.cardbrowser.data.cache.CacheScope
import com.bitsycore.cardbrowser.data.cache.Completeness
import com.bitsycore.cardbrowser.data.cache.MetadataCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer

/**
 * Decides between cache and provider, and is the only place that decision is made.
 *
 * Everything above it -- the view models, the screens -- asks for a set list or a set's cards and
 * gets a [DataSnapshot] describing what it got and how much to trust it. Everything below it is one
 * provider chosen by the [ProviderRegistry]. There is no merging of two providers and no failover
 * between them; a route names one source and that source's answer is the answer.
 *
 * ## The completeness rule
 *
 * The single most important behaviour here. Riftcodex can filter remotely by text and by nothing
 * else, so a filter on domain or rarity has to be applied locally -- and applying it to one page
 * would produce results the user would reasonably read as "the whole set", which they are not.
 *
 * So: when a query needs a filter the provider cannot apply, this repository fetches *every* page
 * of the set before filtering, and caches the result as a [CacheScope.CompleteSet]. When it cannot
 * finish -- offline partway through, or a page fails -- it returns what it has with
 * [SetCards.isCompleteSet] false and the true set size beside it, and the UI says so.
 */
class CardRepository(
	private val mRegistry: ProviderRegistry,
	private val mCache: MetadataCache,
	private val mClock: () -> Long,
	private val mSetListTtlMillis: Long = DEFAULT_SET_LIST_TTL_MILLIS,
	private val mCardsTtlMillis: Long = DEFAULT_CARDS_TTL_MILLIS,
	private val mSetListRevalidateAfterMillis: Long = DEFAULT_SET_LIST_REVALIDATE_MILLIS,
) {

	// ============
	//  Sets

	/**
	 * The sets of [game], cache first and then refreshed.
	 *
	 * Emits at most twice: once with whatever is cached, if anything, and once with the network
	 * result. A failed refresh emits the cached value again with the error attached rather than
	 * replacing it -- losing a good set list because a request timed out would be a regression the
	 * user feels immediately.
	 */
	fun setList(game: Game, language: CardLanguage? = null): Flow<DataSnapshot<List<CardSet>>> = flow {
		val vProvider = mRegistry.resolve(game, language)
			?: run {
				emit(DataSnapshot.failed<List<CardSet>>(ProviderError.Unknown("No provider serves $game")))
				return@flow
			}

		val vKey = setListKey(vProvider, game, language)
		val vSerializer = CacheEnvelope.serializer(ListSerializer(serializer<CardSet>()))

		val vCached = mCache.read(vKey, vSerializer)
		val vNow = mClock()
		val vIsStale = vCached?.isStale(vNow, mSetListTtlMillis) ?: true
		if (vCached != null) {
			emit(
				DataSnapshot.cached(
					value = vCached.payload,
					fetchedAt = vCached.fetchedAtEpochMillis,
					completeness = vCached.completeness,
					isStale = vIsStale,
				),
			)
			// Very recently checked: do not ask again. This is the only case that skips the
			// network, and the window is minutes rather than the full freshness TTL -- a set list
			// is one small request, and "is there a new set?" is a question worth asking on
			// launch rather than once a day.
			val vAge = vNow - vCached.fetchedAtEpochMillis
			if (vAge < mSetListRevalidateAfterMillis) return@flow
		}

		try {
			val vSets = vProvider.listSets(game).sortedWith(SET_ORDER)
			val vFetchedAt = mClock()
			mCache.write(
				key = vKey,
				envelope = CacheEnvelope(
					schemaVersion = CacheEnvelope.CURRENT_SCHEMA_VERSION,
					provider = vProvider.id,
					language = language,
					scope = CacheScope.SetList(game.name),
					fetchedAtEpochMillis = vFetchedAt,
					completeness = Completeness.COMPLETE,
					payload = vSets,
				),
				serializer = vSerializer,
			)
			emit(DataSnapshot.fresh(vSets, vFetchedAt))
		} catch (vError: ProviderError) {
			// Cancellation never reaches here: `mapProviderErrors` rethrows it untouched.
			if (vCached != null) {
				emit(
					DataSnapshot(
						value = vCached.payload,
						origin = DataOrigin.CACHE,
						completeness = vCached.completeness,
						fetchedAtEpochMillis = vCached.fetchedAtEpochMillis,
						isStale = true,
						error = vError,
					),
				)
			} else {
				emit(DataSnapshot.failed(vError))
			}
		}
	}

	// ============
	//  Cards

	/**
	 * The cards of [setId] matching [query], cache first and then refreshed.
	 *
	 * Routing, in order:
	 *
	 * 1. A complete set is already cached and fresh -- filter it locally and answer without a
	 *    request, whatever the query asks for.
	 * 2. The query needs a filter the provider cannot apply remotely -- fetch every page, cache the
	 *    complete set, then filter. Partial progress is still returned, marked partial.
	 * 3. Otherwise -- one page from the provider, with its filters applied by the provider.
	 *
	 * @param knownSetSize the provider's card count for the set, used to describe partial coverage
	 */
	fun cards(
		setId: SourceId,
		game: Game,
		query: CardQuery,
		language: CardLanguage? = null,
		knownSetSize: Int? = null,
	): Flow<DataSnapshot<SetCards>> = flow {
		val vProvider = mRegistry.resolve(game, language)
			?: run {
				emit(DataSnapshot.failed<SetCards>(ProviderError.Unknown("No provider serves $game")))
				return@flow
			}

		val vCompleteKey = completeSetKey(vProvider, setId, language)
		val vSerializer = CacheEnvelope.serializer(ListSerializer(serializer<CardPrinting>()))
		val vCachedComplete = mCache.read(vCompleteKey, vSerializer)
		val vNow = mClock()

		// 1. Answer from a cached complete set whenever one exists. Even when it is stale it is
		//    emitted first, because a filtered screen that draws instantly and then refreshes beats
		//    a spinner over data we already have.
		if (vCachedComplete != null) {
			val vIsStale = vCachedComplete.isStale(vNow, mCardsTtlMillis)
			emit(
				DataSnapshot.cached(
					value = SetCards(
						cards = CardFilterEngine.apply(vCachedComplete.payload, query),
						isCompleteSet = vCachedComplete.completeness == Completeness.COMPLETE,
						knownSetSize = knownSetSize,
						cachedCardCount = vCachedComplete.payload.size,
					),
					fetchedAt = vCachedComplete.fetchedAtEpochMillis,
					completeness = vCachedComplete.completeness,
					isStale = vIsStale,
				),
			)
			if (!vIsStale && vCachedComplete.completeness == Completeness.COMPLETE) return@flow
		}

		val vNeedsCompleteSet = vProvider.capabilities.filtering.requiresCompleteSet(query) ||
			// No cached complete set and no local filter needed still warrants fetching the whole
			// set here: sets are at most a few hundred cards, the pages are needed for scrolling
			// anyway, and holding the complete set is what makes every later filter instant and
			// every later launch offline-capable.
			vCachedComplete == null

		try {
			if (vNeedsCompleteSet) {
				emitCompleteSet(vProvider, setId, language, query, knownSetSize, vCachedComplete?.payload)
			} else {
				emitSinglePage(vProvider, setId, query, knownSetSize)
			}
		} catch (vError: ProviderError) {
			if (vCachedComplete != null) {
				emit(
					DataSnapshot(
						value = SetCards(
							cards = CardFilterEngine.apply(vCachedComplete.payload, query),
							isCompleteSet = vCachedComplete.completeness == Completeness.COMPLETE,
							knownSetSize = knownSetSize,
							cachedCardCount = vCachedComplete.payload.size,
						),
						origin = DataOrigin.CACHE,
						completeness = vCachedComplete.completeness,
						fetchedAtEpochMillis = vCachedComplete.fetchedAtEpochMillis,
						isStale = true,
						error = vError,
					),
				)
			} else {
				emit(DataSnapshot.failed(vError))
			}
		}
	}

	/**
	 * Fetches every page of a set, caches it, and emits the filtered result.
	 *
	 * A page that fails part-way through does not throw when earlier pages succeeded: what has been
	 * collected is cached as [Completeness.PARTIAL] and emitted marked partial, which is strictly
	 * more useful than nothing and is honest about being incomplete. Only a failure on the *first*
	 * page propagates, because then there is nothing to be partial about.
	 */
	private suspend fun kotlinx.coroutines.flow.FlowCollector<DataSnapshot<SetCards>>.emitCompleteSet(
		provider: CardProvider,
		setId: SourceId,
		language: CardLanguage?,
		query: CardQuery,
		knownSetSize: Int?,
		previouslyCached: List<CardPrinting>?,
	) {
		val vPageSize = provider.capabilities.maxPageSize
		var vComplete = true

		/** Emits what has been collected so far, marked partial. */
		suspend fun emitProgress(cards: List<CardPrinting>, total: Int?) {
			emit(
				DataSnapshot(
					value = SetCards(
						cards = CardFilterEngine.apply(dedupePrintings(cards), query),
						isCompleteSet = false,
						knownSetSize = knownSetSize ?: total,
						cachedCardCount = cards.size,
					),
					origin = DataOrigin.NETWORK,
					completeness = Completeness.PARTIAL,
					fetchedAtEpochMillis = mClock(),
					isStale = false,
				),
			)
		}

		// A deliberately small first request, purely to put something on screen.
		//
		// This API's transfer time tracks payload closely and varies wildly: a 100-card page is
		// ~116 KB and measured between 1.6 s and 11.8 s, while a 24-card page is ~27 KB and lands
		// in about a second. Paying for one extra small request buys a first paint that does not
		// depend on the worst case.
		// Skipped entirely for a provider whose own pages are already this small: the extra request
		// would cost a round trip and save nothing.
		val vWantsPreview = vPageSize > FIRST_PAGE_SIZE
		var vTotal: Int? = null
		val vCollected = mutableListOf<CardPrinting>()
		var vPreviewWasWholeSet = false

		if (vWantsPreview) {
			currentCoroutineContext().ensureActive()
			val vPreview = provider.listCards(
				// Deliberately unfiltered: what is cached under a complete-set key must be the whole
				// set. Caching a filtered page there is precisely the bug that would make later
				// filters silently wrong.
				CardPageRequest(setId = setId, query = CardQuery(), page = 1, pageSize = FIRST_PAGE_SIZE),
			)
			vTotal = vPreview.totalCount
			if (!vPreview.hasMore) {
				// A set small enough to arrive whole in the preview needs nothing further.
				vCollected += vPreview.cards
				vPreviewWasWholeSet = true
			} else if (vPreview.cards.isNotEmpty()) {
				emitProgress(vPreview.cards, vTotal)
			}
		}

		if (!vPreviewWasWholeSet) {
			// Page one at the provider's own size, on its own, because nothing can be planned until
			// it answers: it carries the total and therefore how many more pages there are. When a
			// preview ran, this overlaps it and supersedes it.
			currentCoroutineContext().ensureActive()
			val vFirst = provider.listCards(
				CardPageRequest(setId = setId, query = CardQuery(), page = 1, pageSize = vPageSize),
			)
			vCollected += vFirst.cards
			vTotal = vFirst.totalCount ?: vTotal

			// Without a preview this is the first thing the user could possibly see, so it goes to
			// the screen before the remaining pages are requested.
			if (!vWantsPreview && vFirst.hasMore && vFirst.cards.isNotEmpty()) {
				emitProgress(vCollected, vTotal)
			}

			if (vFirst.hasMore) {
				// How many pages remain. Taken from the provider's own total where it gives one, so
				// the rest can go out together rather than one after another: four sequential pages
				// of Origins cost about seven seconds, four concurrent ones about two.
				val vLastPage = when {
					vTotal != null -> ((vTotal + vPageSize - 1) / vPageSize).coerceAtMost(MAX_PAGES_PER_SET)
					else -> MAX_PAGES_PER_SET
				}

				// Bounded, and small. These are volunteer-run APIs; "as fast as possible" is not a
				// licence to open a hundred sockets at a stranger's server.
				outer@ for (vBatch in (2..vLastPage).chunked(MAX_CONCURRENT_PAGE_REQUESTS)) {
					currentCoroutineContext().ensureActive()
					val vResults = coroutineScope {
						vBatch.map { vPageNumber ->
							async {
								vPageNumber to runCatching {
									provider.listCards(
										CardPageRequest(
											setId = setId,
											query = CardQuery(),
											page = vPageNumber,
											pageSize = vPageSize,
										),
									)
								}
							}
						}.awaitAll()
					}

					// Reassembled in page order, never in completion order, so the cached set does
					// not depend on which request happened to come back first.
					var vRanOut = false
					for ((_, vOutcome) in vResults.sortedBy { it.first }) {
						val vPage = vOutcome.getOrElse {
							// Cancellation is not a failed page; it must unwind rather than be
							// recorded as an incomplete set.
							if (it is CancellationException) throw it
							vComplete = false
							break@outer
						}
						vCollected += vPage.cards
						vTotal = vPage.totalCount ?: vTotal
						if (!vPage.hasMore) vRanOut = true
					}

					val vDone = vRanOut || (vTotal != null && vCollected.size >= vTotal)
					// The grid fills in batch by batch rather than jumping straight to the end.
					if (!vDone) emitProgress(vCollected, vTotal)
					if (vDone) break
				}
			}
		}

		// A partial fetch that collected less than a previous cache is not an improvement; keep the
		// better of the two rather than downgrading what the user already had.
		val vBest = if (!vComplete && previouslyCached != null && previouslyCached.size > vCollected.size) {
			previouslyCached
		} else {
			vCollected
		}

		// A collection short of what the provider says the set holds is not complete, however
		// cleanly the paging ended. This guard exists because a wrong `set_id` once produced 200 OK
		// with an empty page and `hasMore` false -- which paged "successfully" to zero cards and was
		// then cached as a complete set, so the empty set was served from disk forever after.
		if (vTotal != null && vBest.size < vTotal) vComplete = false

		val vDeduped = dedupePrintings(vBest)

		val vFetchedAt = mClock()
		val vCompleteness = if (vComplete) Completeness.COMPLETE else Completeness.PARTIAL
		mCache.write(
			key = completeSetKey(provider, setId, language),
			envelope = CacheEnvelope(
				schemaVersion = CacheEnvelope.CURRENT_SCHEMA_VERSION,
				provider = provider.id,
				language = language,
				scope = CacheScope.CompleteSet(setId.qualified),
				fetchedAtEpochMillis = vFetchedAt,
				completeness = vCompleteness,
				payload = vBest,
			),
			serializer = CacheEnvelope.serializer(ListSerializer(serializer<CardPrinting>())),
		)

		emit(
			DataSnapshot(
				value = SetCards(
					cards = CardFilterEngine.apply(vDeduped, query),
					isCompleteSet = vComplete,
					knownSetSize = knownSetSize ?: vTotal,
					cachedCardCount = vDeduped.size,
				),
				origin = DataOrigin.NETWORK,
				completeness = vCompleteness,
				fetchedAtEpochMillis = vFetchedAt,
				isStale = false,
			),
		)
	}

	/** One provider-filtered page. Only used when the provider can honour the whole query. */
	private suspend fun kotlinx.coroutines.flow.FlowCollector<DataSnapshot<SetCards>>.emitSinglePage(
		provider: CardProvider,
		setId: SourceId,
		query: CardQuery,
		knownSetSize: Int?,
	) {
		val vPage = provider.listCards(
			CardPageRequest(
				setId = setId,
				query = query,
				page = 1,
				pageSize = provider.capabilities.maxPageSize,
			),
		)
		emit(
			DataSnapshot.fresh(
				value = SetCards(
					cards = CardFilterEngine.sort(dedupePrintings(vPage.cards), query),
					// One page of a provider-filtered query covers the query completely only when
					// the provider says there is no more.
					isCompleteSet = !vPage.hasMore,
					knownSetSize = knownSetSize ?: vPage.totalCount,
					cachedCardCount = vPage.cards.size,
				),
				fetchedAt = mClock(),
				completeness = if (vPage.hasMore) Completeness.PARTIAL else Completeness.COMPLETE,
			),
		)
	}

	// ============
	//  Detail

	/**
	 * One printing.
	 *
	 * Looks in the cached complete set first, which is almost always a hit because the user reached
	 * detail by tapping a tile in a grid drawn from that very list. That makes the detail screen
	 * open instantly and work offline.
	 */
	suspend fun cardDetail(
		id: SourceId,
		game: Game,
		setId: SourceId?,
		language: CardLanguage? = null,
	): DataSnapshot<CardPrinting> {
		val vProvider = mRegistry.resolve(game, language)
			?: return DataSnapshot.failed(ProviderError.Unknown("No provider serves $game"))

		if (setId != null) {
			val vCached = mCache.read(
				completeSetKey(vProvider, setId, language),
				CacheEnvelope.serializer(ListSerializer(serializer<CardPrinting>())),
			)
			val vHit = vCached?.payload?.firstOrNull { it.id == id }
			if (vHit != null) {
				return DataSnapshot.cached(
					value = vHit,
					fetchedAt = vCached.fetchedAtEpochMillis,
					completeness = Completeness.COMPLETE,
					isStale = vCached.isStale(mClock(), mCardsTtlMillis),
				)
			}
		}

		return try {
			val vCard = vProvider.cardDetail(id)
				?: return DataSnapshot.failed(ProviderError.BadRequest(404))
			DataSnapshot.fresh(vCard, mClock())
		} catch (vError: ProviderError) {
			DataSnapshot.failed(vError)
		}
	}

	// ============
	//  Facets

	/**
	 * The filter values present in a set, from the cached complete set.
	 *
	 * Returns empty facets when the set has not been fully fetched: offering filters derived from
	 * three of four pages would hide values that exist in the fourth.
	 */
	suspend fun facetsFor(setId: SourceId, game: Game, language: CardLanguage? = null): CardFacets {
		val vProvider = mRegistry.resolve(game, language) ?: return CardFacets()
		val vCached = mCache.read(
			completeSetKey(vProvider, setId, language),
			CacheEnvelope.serializer(ListSerializer(serializer<CardPrinting>())),
		) ?: return CardFacets()
		if (vCached.completeness != Completeness.COMPLETE) return CardFacets()
		return CardFilterEngine.facetsOf(vCached.payload)
	}

	/**
	 * Collapses records the provider has issued more than once for the same printing.
	 *
	 * Not a guess and not name matching: two records collapse only when they carry the same
	 * [CardPrinting.dedupeKey], which is the provider's *own* per-printing identifier, and a
	 * provider that declares none is never de-duplicated at all.
	 *
	 * It is needed because a real provider really does this. Riftcodex's Vendetta returns 358 card
	 * records for 227 distinct `riftbound_id`s -- 37% of the set is sent twice, each copy under its
	 * own database id, so nothing downstream could tell them apart. The grid showed every one.
	 *
	 * Where copies disagree, the one asserting the most wins: `alternate_art: true` is a statement
	 * and `false` is indistinguishable from a field nobody filled in, so a copy carrying a treatment
	 * beats a copy carrying none. Order is otherwise preserved.
	 */
	private fun dedupePrintings(cards: List<CardPrinting>): List<CardPrinting> {
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
		artwork.treatment != ArtworkTreatment.STANDARD && other.artwork.treatment == ArtworkTreatment.STANDARD

	// ============
	//  Keys

	private fun setListKey(provider: CardProvider, game: Game, language: CardLanguage?) =
		CacheKey.of("v${CacheEnvelope.CURRENT_SCHEMA_VERSION}", provider.id.value, "sets", game.name, language?.code ?: "-")

	private fun completeSetKey(provider: CardProvider, setId: SourceId, language: CardLanguage?) =
		CacheKey.of("v${CacheEnvelope.CURRENT_SCHEMA_VERSION}", provider.id.value, "set", setId.qualified, language?.code ?: "-")

	companion object {

		/**
		 * Newest first, and sets with no stated release date last.
		 *
		 * Last rather than first: a set whose date the provider does not know is more likely to be
		 * a promo grab-bag than this week's release.
		 */
		val SET_ORDER: Comparator<CardSet> = compareBy<CardSet> { it.releaseDate == null }
			.thenByDescending { it.releaseDate }
			.thenBy { it.name }

		/**
		 * How old a set list may be before it is *shown as stale*.
		 *
		 * Distinct from the revalidate window below: this decides whether the UI says "saved copy",
		 * not whether a request goes out.
		 */
		const val DEFAULT_SET_LIST_TTL_MILLIS: Long = 24L * 60 * 60 * 1000

		/**
		 * How old a set list may be before it is checked again in the background.
		 *
		 * Five minutes, which in practice means every launch. The cached list is always drawn first
		 * and the check never blocks it, so the cost of being wrong here is one 2 KB request, and
		 * the cost of *not* asking is a set the game released this morning not appearing until
		 * tomorrow.
		 */
		const val DEFAULT_SET_LIST_REVALIDATE_MILLIS: Long = 5L * 60 * 1000

		/**
		 * Card data changes when a provider corrects a record, so a day is generous but not silly.
		 * Stale data is still shown immediately; this only controls when a refresh is attempted.
		 */
		const val DEFAULT_CARDS_TTL_MILLIS: Long = 24L * 60 * 60 * 1000

		/** A stop against a provider whose paging never terminates. 100 pages is 10,000 cards. */
		private const val MAX_PAGES_PER_SET = 100

		/**
		 * The size of the throwaway first request, chosen for time-to-first-card.
		 *
		 * Enough to fill the visible part of a grid on any screen this app runs on, and small
		 * enough that it arrives while a full page is still transferring.
		 */
		private const val FIRST_PAGE_SIZE = 24

		/**
		 * How many pages of a set may be in flight at once.
		 *
		 * Four covers every Riftbound set in a single batch while staying a polite number of
		 * simultaneous connections to a free community API.
		 */
		private const val MAX_CONCURRENT_PAGE_REQUESTS = 4
	}
}
