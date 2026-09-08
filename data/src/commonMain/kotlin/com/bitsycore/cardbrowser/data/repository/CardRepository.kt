package com.bitsycore.cardbrowser.data.repository

import com.bitsycore.cardbrowser.core.filter.CardFacets
import com.bitsycore.cardbrowser.core.filter.CardFilterEngine
import com.bitsycore.cardbrowser.core.model.CardLanguage
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
			// Fresh enough: no request at all. Respecting a provider's limits starts with not
			// asking for what we already have.
			if (!vIsStale) return@flow
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
		val vCollected = mutableListOf<CardPrinting>()
		var vPage = 1
		var vComplete = true
		var vTotal: Int? = null

		while (true) {
			// Checked every iteration so a superseded set selection stops paging immediately rather
			// than finishing four requests nobody is waiting for.
			currentCoroutineContext().ensureActive()

			val vResult = try {
				provider.listCards(
					// Deliberately unfiltered: this is the *whole set*, and it is cached as such.
					// Caching a filtered page under a complete-set key is precisely the bug that
					// would make later filters silently wrong.
					CardPageRequest(setId = setId, query = CardQuery(), page = vPage, pageSize = vPageSize),
				)
			} catch (vError: ProviderError) {
				if (vPage == 1) throw vError
				// Partial progress is worth keeping.
				vComplete = false
				break
			}

			vCollected += vResult.cards
			vTotal = vResult.totalCount ?: vTotal
			if (!vResult.hasMore || vResult.cards.isEmpty()) break
			vPage++

			if (vPage > MAX_PAGES_PER_SET) {
				// A guard against a provider whose `hasMore` never goes false. Better to stop and
				// say the set is partial than to loop against someone else's server forever.
				vComplete = false
				break
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
					cards = CardFilterEngine.apply(vBest, query),
					isCompleteSet = vComplete,
					knownSetSize = knownSetSize ?: vTotal,
					cachedCardCount = vBest.size,
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
					cards = CardFilterEngine.sort(vPage.cards, query),
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

		/** Set catalogues change when a set is announced, which is not often. */
		const val DEFAULT_SET_LIST_TTL_MILLIS: Long = 24L * 60 * 60 * 1000

		/**
		 * Card data changes when a provider corrects a record, so a day is generous but not silly.
		 * Stale data is still shown immediately; this only controls when a refresh is attempted.
		 */
		const val DEFAULT_CARDS_TTL_MILLIS: Long = 24L * 60 * 60 * 1000

		/** A stop against a provider whose paging never terminates. 100 pages is 10,000 cards. */
		private const val MAX_PAGES_PER_SET = 100
	}
}
