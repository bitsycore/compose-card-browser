package com.bitsycore.cardbrowser.data.repository

import com.bitsycore.cardbrowser.core.filter.CardFacets
import com.bitsycore.cardbrowser.core.filter.CardFilterEngine
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.SetCodeComparator
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import com.bitsycore.cardbrowser.core.provider.CardProvider
import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.provider.CardSearchRequest
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
	/**
	 * How old a set list may be before it is checked again, read afresh each time.
	 *
	 * A function so the settings toggle takes effect immediately: switching background checks off
	 * returns [Long.MAX_VALUE], which no cache entry is ever older than.
	 */
	private val mSetListRevalidateAfterMillis: () -> Long = { DEFAULT_SET_LIST_REVALIDATE_MILLIS },
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
	fun setList(game: GameId, language: CardLanguage? = null): Flow<DataSnapshot<List<CardSet>>> = flow {
		val vProvider = mRegistry.resolve(game, language)
			?: run {
				emit(DataSnapshot.failed<List<CardSet>>(ProviderError.Unknown("No provider serves $game")))
				return@flow
			}

		val vLanguage = effectiveLanguage(vProvider, language)
		val vKey = setListKey(vProvider, game, vLanguage)
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
			if (vAge < mSetListRevalidateAfterMillis()) return@flow
		}

		try {
			val vSets = vProvider.listSets(vLanguage).sortedWith(SET_ORDER)
			val vFetchedAt = mClock()
			mCache.write(
				key = vKey,
				envelope = CacheEnvelope(
					schemaVersion = CacheEnvelope.CURRENT_SCHEMA_VERSION,
					provider = vProvider.id,
					language = vLanguage,
					scope = CacheScope.SetList(game.value),
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
		game: GameId,
		query: CardQuery,
		language: CardLanguage? = null,
		knownSetSize: Int? = null,
	): Flow<DataSnapshot<SetCards>> = flow {
		val vProvider = mRegistry.resolve(game, language)
			?: run {
				emit(DataSnapshot.failed<SetCards>(ProviderError.Unknown("No provider serves $game")))
				return@flow
			}

		val vLanguage = effectiveLanguage(vProvider, language)
		val vCompleteKey = completeSetKey(vProvider, setId, vLanguage)
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
					value = cachedSetCards(
						payload = vCachedComplete.payload,
						query = query,
						knownSetSize = knownSetSize,
						completeness = vCachedComplete.completeness,
						rarityLadder = vProvider.game.rarityLadder,
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
				emitCompleteSet(vProvider, setId, vLanguage, query, knownSetSize, vCachedComplete?.payload)
			} else {
				emitSinglePage(vProvider, setId, vLanguage, query, knownSetSize)
			}
		} catch (vError: ProviderError) {
			if (vCachedComplete != null) {
				emit(
					DataSnapshot(
						value = cachedSetCards(
						payload = vCachedComplete.payload,
						query = query,
						knownSetSize = knownSetSize,
						completeness = vCachedComplete.completeness,
						rarityLadder = vProvider.game.rarityLadder,
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
	 * A cached set, filtered and with duplicates collapsed.
	 *
	 * The de-duplication is repeated on read rather than trusted from the write. Two reasons: a
	 * cache written by an earlier build holds the raw list and is sitting on users' disks right
	 * now, and a provider that starts issuing a card twice should not be able to crash the grid --
	 * `LazyVerticalGrid` throws outright on a repeated key rather than degrading.
	 */
	private fun cachedSetCards(
		payload: List<CardPrinting>,
		query: CardQuery,
		knownSetSize: Int?,
		completeness: Completeness,
		rarityLadder: List<String>,
	): SetCards {
		val vDeduped = dedupePrintings(payload)
		return SetCards(
			cards = CardFilterEngine.apply(vDeduped, query, rarityLadder),
			isCompleteSet = completeness == Completeness.COMPLETE,
			knownSetSize = knownSetSize,
			cachedCardCount = vDeduped.size,
		)
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
		provider: CardProvider<GameProfile>,
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
						cards = CardFilterEngine.apply(
							cards = dedupePrintings(cards),
							query = query,
							rarityLadder = provider.game.rarityLadder,
						),
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
				CardPageRequest(setId = setId, query = CardQuery(), page = 1, pageSize = FIRST_PAGE_SIZE, language = language),
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
				CardPageRequest(setId = setId, query = CardQuery(), page = 1, pageSize = vPageSize, language = language),
			)
			vCollected += vFirst.cards
			vTotal = vFirst.totalCount ?: vTotal

			// Straight to the screen, whether or not a preview preceded it: page one is a hundred
			// cards where the preview was two dozen, and the user should see the grid fill rather
			// than sit on the preview until the whole set lands.
			if (vFirst.hasMore && vFirst.cards.isNotEmpty()) {
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
					var vRanOut = false

					coroutineScope {
						// Requested together, but consumed in page order and emitted one at a time.
						// Awaiting the whole batch before emitting is what made the grid jump from
						// the preview straight to the finished set: every remaining page of a
						// Riftbound set fits in a single batch, so "per batch" and "at the end"
						// were the same thing.
						val vPending = vBatch.map { vPageNumber ->
							vPageNumber to async {
								runCatching {
									provider.listCards(
										CardPageRequest(
											setId = setId,
											query = CardQuery(),
											page = vPageNumber,
											pageSize = vPageSize,
											language = language,
										),
									)
								}
							}
						}

						for ((_, vDeferred) in vPending) {
							val vPage = vDeferred.await().getOrElse {
								// Cancellation is not a failed page; it must unwind rather than be
								// recorded as an incomplete set.
								if (it is CancellationException) throw it
								vComplete = false
								// Whatever is still in flight is abandoned with the scope.
								return@coroutineScope
							}
							vCollected += vPage.cards
							vTotal = vPage.totalCount ?: vTotal
							if (!vPage.hasMore) vRanOut = true

							// Ordered growth: 24, then 100, 200, 300, and finally the whole set.
							// Awaiting in page order means a page that answers early waits its turn,
							// which is what keeps the cached set independent of network timing.
							val vReachedEnd = vRanOut || (vTotal != null && vCollected.size >= vTotal)
							if (!vReachedEnd) emitProgress(vCollected, vTotal)
						}
					}

					if (!vComplete) break@outer
					if (vRanOut || (vTotal != null && vCollected.size >= vTotal)) break
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
				// The de-duplicated list, not the raw one.
				//
				// Writing `vBest` here meant the cache held records that had been collapsed before
				// being shown, so the next launch read them back and drew them -- undoing the
				// de-duplication for every session after the first, which is the session that
				// matters least. `vDeduped` is what was displayed and it is what is stored.
				payload = vDeduped,
			),
			serializer = CacheEnvelope.serializer(ListSerializer(serializer<CardPrinting>())),
		)

		emit(
			DataSnapshot(
				value = SetCards(
					cards = CardFilterEngine.apply(vDeduped, query, provider.game.rarityLadder),
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
		provider: CardProvider<GameProfile>,
		setId: SourceId,
		language: CardLanguage?,
		query: CardQuery,
		knownSetSize: Int?,
	) {
		val vPage = provider.listCards(
			CardPageRequest(
				setId = setId,
				query = query,
				page = 1,
				pageSize = provider.capabilities.maxPageSize,
				language = language,
			),
		)
		emit(
			DataSnapshot.fresh(
				value = SetCards(
					cards = CardFilterEngine.sort(
						cards = dedupePrintings(vPage.cards),
						query = query,
						rarityLadder = provider.game.rarityLadder,
					),
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
	//  Cross-set search

	/**
	 * Cards matching [text] anywhere in [game], cache first and then the provider.
	 *
	 * Emits up to twice, and the two emissions are not the same kind of answer:
	 *
	 * 1. Whatever the sets already on disk contain, marked [SearchScope.LOCAL_CACHED_SETS]. This is
	 *    instant, works offline, and on a fresh install is empty.
	 * 2. The provider's own answer, marked [SearchScope.REMOTE_ALL_SETS] -- but only when the
	 *    provider declares it can search across sets. When it cannot, the local answer is the only
	 *    answer and stays labelled as such, so the screen never implies a whole-game search ran.
	 *
	 * Nothing here is written to the cache. A search result is a slice of many sets under a query
	 * that will never be repeated verbatim; storing it under any key would either collide with the
	 * complete-set entries the rest of the app depends on being complete, or accumulate forever.
	 *
	 * @param knownSets the game's sets, which the caller already has from [setList]. Used both to
	 *   know which cached sets to look in and to say how much of the game a local search covered
	 */
	fun searchAllSets(
		game: GameId,
		text: String,
		knownSets: List<CardSet>,
		language: CardLanguage? = null,
	): Flow<DataSnapshot<CardSearchResults>> = flow {
		val vNeedle = text.trim()
		if (vNeedle.isEmpty()) return@flow

		val vProvider = mRegistry.resolve(game, language)
			?: run {
				emit(DataSnapshot.failed<CardSearchResults>(ProviderError.Unknown("No provider serves $game")))
				return@flow
			}

		val vLanguage = effectiveLanguage(vProvider, language)
		val vCanSearchRemotely = vProvider.capabilities.data.crossSetSearch

		// 1. The sets already on disk, always, and first.
		val vLocal = searchCachedSets(vProvider, vNeedle, knownSets, vLanguage)
		// Skipped only when it found nothing *and* a real search is about to run: an empty local
		// result flashed up before the network answers reads as "no matches" for a moment.
		if (vLocal.cards.isNotEmpty() || !vCanSearchRemotely) {
			emit(
				DataSnapshot(
					value = vLocal,
					origin = DataOrigin.CACHE,
					completeness = if (vLocal.isLimitedByCache) Completeness.PARTIAL else Completeness.COMPLETE,
					fetchedAtEpochMillis = mClock(),
					isStale = false,
				),
			)
		}

		if (!vCanSearchRemotely) return@flow

		// 2. The same search, if it has been run before and is still fresh.
		//
		// A search used to be the one path that always hit the network -- every submit, every
		// return to the screen, every back-navigation. Typing "dragon", opening a card and coming
		// back cost two identical requests.
		//
		// Held for the same 24 hours as a set, and for the same reason: a card's printings do not
		// change between one afternoon and the next, and a set released inside the window is
		// findable the moment its own list is refreshed. A stale entry is still emitted first and
		// then replaced, so the screen is never blank while the network is asked again.
		val vSearchKey = searchKey(vProvider, vNeedle, vLanguage)
		val vSearchSerializer = CacheEnvelope.serializer(serializer<CachedSearchPage>())
		val vCachedSearch = mCache.read(vSearchKey, vSearchSerializer)
		if (vCachedSearch != null) {
			val vIsStale = vCachedSearch.isStale(mClock(), mCardsTtlMillis)
			emit(
				DataSnapshot(
					value = searchResultsOf(vCachedSearch.payload, knownSets),
					origin = DataOrigin.CACHE,
					completeness = vCachedSearch.completeness,
					fetchedAtEpochMillis = vCachedSearch.fetchedAtEpochMillis,
					isStale = vIsStale,
				),
			)
			if (!vIsStale) return@flow
		}

		// 3. The provider's answer, which supersedes both.
		try {
			currentCoroutineContext().ensureActive()
			val vPage = vProvider.searchAllSets(
				CardSearchRequest(
					text = vNeedle,
					language = vLanguage,
					page = 1,
					pageSize = SEARCH_PAGE_SIZE.coerceAtMost(vProvider.capabilities.maxPageSize),
				),
			)
			val vCards = dedupePrintings(vPage.cards)
			val vFetchedAt = mClock()
			// One page of a match list is not the whole match list, and the screen says so rather
			// than letting the user assume they are looking at everything.
			val vCompleteness = if (vPage.hasMore) Completeness.PARTIAL else Completeness.COMPLETE
			val vPayload = CachedSearchPage(
				cards = vCards,
				totalCount = vPage.totalCount,
				hasMore = vPage.hasMore,
			)
			mCache.write(
				key = vSearchKey,
				envelope = CacheEnvelope(
					schemaVersion = CacheEnvelope.CURRENT_SCHEMA_VERSION,
					provider = vProvider.id,
					language = vLanguage,
					scope = CacheScope.Search(needle = vNeedle.lowercase(), page = 1),
					fetchedAtEpochMillis = vFetchedAt,
					completeness = vCompleteness,
					payload = vPayload,
				),
				serializer = vSearchSerializer,
			)
			emit(
				DataSnapshot.fresh(
					value = searchResultsOf(vPayload, knownSets),
					fetchedAt = vFetchedAt,
					completeness = vCompleteness,
				),
			)
		} catch (vError: ProviderError) {
			emit(
				DataSnapshot(
					value = vLocal,
					origin = DataOrigin.CACHE,
					completeness = Completeness.PARTIAL,
					fetchedAtEpochMillis = mClock(),
					isStale = true,
					error = vError,
				),
			)
		}
	}

	/**
	 * Searches the complete sets this device already holds.
	 *
	 * Reads only what is on disk and never issues a request, which is what makes it safe to run
	 * before every remote search and what makes search work with no network at all.
	 *
	 * A set cached as [Completeness.PARTIAL] is still searched -- part of a set is more than none
	 * of it -- but it does not count towards [CardSearchResults.searchedSetCount], so the coverage
	 * the UI reports stays a count of sets genuinely searched end to end.
	 */
	private suspend fun searchCachedSets(
		provider: CardProvider<GameProfile>,
		text: String,
		knownSets: List<CardSet>,
		language: CardLanguage?,
	): CardSearchResults {
		val vSerializer = CacheEnvelope.serializer(ListSerializer(serializer<CardPrinting>()))
		val vQuery = CardQuery(text = text)
		val vHits = mutableListOf<CardPrinting>()
		var vComplete = 0

		for (vSet in knownSets) {
			currentCoroutineContext().ensureActive()
			val vCached = mCache.read(completeSetKey(provider, vSet.id, language), vSerializer) ?: continue
			if (vCached.completeness == Completeness.COMPLETE) vComplete++
			vHits += CardFilterEngine.apply(vCached.payload, vQuery, provider.game.rarityLadder)
		}

		return CardSearchResults(
			cards = dedupePrintings(vHits),
			scope = SearchScope.LOCAL_CACHED_SETS,
			searchedSetCount = vComplete,
			knownSetCount = knownSets.size,
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
		game: GameId,
		setId: SourceId?,
		language: CardLanguage? = null,
	): DataSnapshot<CardPrinting> {
		val vProvider = mRegistry.resolve(game, language)
			?: return DataSnapshot.failed(ProviderError.Unknown("No provider serves $game"))

		val vLanguage = effectiveLanguage(vProvider, language)

		if (setId != null) {
			val vCached = mCache.read(
				completeSetKey(vProvider, setId, vLanguage),
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

		// Its own cache entry, which it did not used to have. `CacheScope.CardDetail` was declared
		// and never written, so this path -- a card opened without its set in hand, which is what a
		// cold start into a deep link or a restored back stack does -- went to the network every
		// single time, including immediately after the last time.
		val vDetailKey = cardDetailKey(vProvider, id, vLanguage)
		val vSerializer = CacheEnvelope.serializer(serializer<CardPrinting>())
		mCache.read(vDetailKey, vSerializer)?.let { vCached ->
			return DataSnapshot.cached(
				value = vCached.payload,
				fetchedAt = vCached.fetchedAtEpochMillis,
				completeness = Completeness.COMPLETE,
				isStale = vCached.isStale(mClock(), mCardsTtlMillis),
			)
		}

		return try {
			val vCard = vProvider.cardDetail(id, vLanguage)
				?: return DataSnapshot.failed(ProviderError.BadRequest(404))
			val vFetchedAt = mClock()
			mCache.write(
				key = vDetailKey,
				envelope = CacheEnvelope(
					schemaVersion = CacheEnvelope.CURRENT_SCHEMA_VERSION,
					provider = vProvider.id,
					language = vLanguage,
					scope = CacheScope.CardDetail(id.qualified),
					fetchedAtEpochMillis = vFetchedAt,
					completeness = Completeness.COMPLETE,
					payload = vCard,
				),
				serializer = vSerializer,
			)
			DataSnapshot.fresh(vCard, vFetchedAt)
		} catch (vError: ProviderError) {
			DataSnapshot.failed(vError)
		}
	}

	// ============
	//  Offline availability

	/**
	 * Which of [sets] are saved on this device.
	 *
	 * A file-existence check per set, so it stays cheap even for a catalogue of several hundred.
	 * The answer is "saved", not "complete": a set fetched partly and then interrupted has a file
	 * too, and the set list labels the mark accordingly rather than promising the whole set.
	 *
	 * Returns qualified ids so the UI can match without reconstructing [SourceId] values.
	 */
	suspend fun savedSetIds(
		game: GameId,
		sets: List<CardSet>,
		language: CardLanguage? = null,
	): Set<String> {
		val vProvider = mRegistry.resolve(game, language) ?: return emptySet()
		val vLanguage = effectiveLanguage(vProvider, language)
		return sets
			.filter { mCache.exists(completeSetKey(vProvider, it.id, vLanguage)) }
			.map { it.id.qualified }
			.toSet()
	}

	// ============
	//  Facets

	/**
	 * The filter values present in a set, from the cached complete set.
	 *
	 * Returns empty facets when the set has not been fully fetched: offering filters derived from
	 * three of four pages would hide values that exist in the fourth.
	 */
	suspend fun facetsFor(setId: SourceId, game: GameId, language: CardLanguage? = null): CardFacets {
		val vProvider = mRegistry.resolve(game, language) ?: return CardFacets()
		val vLanguage = effectiveLanguage(vProvider, language)
		val vCached = mCache.read(
			completeSetKey(vProvider, setId, vLanguage),
			CacheEnvelope.serializer(ListSerializer(serializer<CardPrinting>())),
		) ?: return CardFacets()
		if (vCached.completeness != Completeness.COMPLETE) return CardFacets()
		return CardFilterEngine.facetsOf(vCached.payload, vProvider.game.rarityLadder)
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


	/**
	 * The language a provider will really answer in, given what a caller asked for.
	 *
	 * Every cache key in this file embeds a language, so the *requested* one cannot be the one that
	 * keys it: two callers asking the same question differently would write and read different
	 * files. That is exactly what happened. The card grid passed `null` whenever the user's
	 * preferred language was not one the provider carried, while the set list passed the preference
	 * unconditionally -- so opening a Riftbound set wrote `…/set/riftcodex:OGN/-` and the set list
	 * then looked for `…/set/riftcodex:OGN/fr`, found nothing, and never marked the set saved. With
	 * nothing marked saved, search had nothing to search.
	 *
	 * Normalising here fixes it for every caller at once, and makes the key honest besides: a set
	 * is filed under the language it actually holds rather than the one somebody hoped for.
	 */
	private fun effectiveLanguage(
		provider: CardProvider<GameProfile>,
		requested: CardLanguage?,
	): CardLanguage? = provider.resolveLanguage(requested)

	private fun setListKey(provider: CardProvider<GameProfile>, game: GameId, language: CardLanguage?) =
		CacheKey.of("v${CacheEnvelope.CURRENT_SCHEMA_VERSION}", provider.id.value, "sets", game.value, language?.code ?: "-")

	/**
	 * Turns a cached search page back into what the screen wants.
	 *
	 * `knownSetCount` is recomputed rather than stored: it describes how many sets the *caller*
	 * has on disk right now, which changes as sets are downloaded and would be a lie if it came
	 * out of a file written yesterday.
	 */
	private fun searchResultsOf(page: CachedSearchPage, knownSets: List<CardSet>) = CardSearchResults(
		cards = page.cards,
		scope = SearchScope.REMOTE_ALL_SETS,
		searchedSetCount = page.cards.map { it.setId }.distinct().size,
		knownSetCount = knownSets.size,
		totalCount = page.totalCount,
		hasMore = page.hasMore,
	)

	/**
	 * The key for one search.
	 *
	 * Lower-cased and trimmed, so "Fury", "fury" and " fury " are one entry rather than three --
	 * the provider is being asked the same question in each case. The language is in the key
	 * because the answer is in that language.
	 */
	private fun searchKey(provider: CardProvider<GameProfile>, needle: String, language: CardLanguage?) =
		CacheKey.of(
			"v${CacheEnvelope.CURRENT_SCHEMA_VERSION}",
			provider.id.value,
			"search",
			needle.trim().lowercase(),
			language?.code ?: "-",
		)

	private fun cardDetailKey(provider: CardProvider<GameProfile>, id: SourceId, language: CardLanguage?) =
		CacheKey.of(
			"v${CacheEnvelope.CURRENT_SCHEMA_VERSION}",
			provider.id.value,
			"card",
			id.qualified,
			language?.code ?: "-",
		)

	private fun completeSetKey(provider: CardProvider<GameProfile>, setId: SourceId, language: CardLanguage?) =
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
			// Undated sets have no chronology to sort by, so they go in code order. This used to
			// fall back to the *name*, which for One Piece -- where no set carries a date -- listed
			// "Awakening of the New Era" before "Romance Dawn" and buried OP-14 in the middle.
			.then(SetCodeComparator)
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

		/**
		 * How many search hits to ask a provider for.
		 *
		 * Deliberately one page and no more. A search across Scryfall's whole catalogue can match
		 * thousands of cards, and paging through all of them to show a count nobody scrolls to
		 * would cost the provider dozens of requests per keystroke-settled query. The screen shows
		 * the first page, says how many matched in total, and asks the user to narrow it.
		 */
		private const val SEARCH_PAGE_SIZE = 60
	}
}
