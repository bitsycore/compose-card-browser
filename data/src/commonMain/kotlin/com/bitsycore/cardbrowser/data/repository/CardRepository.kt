package com.bitsycore.cardbrowser.data.repository

import com.bitsycore.cardbrowser.core.filter.CardFacets
import com.bitsycore.cardbrowser.core.filter.CardFilterEngine
import com.bitsycore.cardbrowser.core.model.CardLanguage
import kotlinx.datetime.LocalDate
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.SetCodeComparator
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardPage
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import com.bitsycore.cardbrowser.core.provider.CardProvider
import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.provider.CardSearchRequest
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.core.provider.BulkCatalogue
import com.bitsycore.cardbrowser.core.provider.BulkSummary
import okio.ByteString.Companion.encodeUtf8
import okio.buffer
import okio.use
import com.bitsycore.cardbrowser.data.cache.AppStorage
import kotlinx.serialization.json.Json
import com.bitsycore.cardbrowser.data.cache.CacheEnvelope
import com.bitsycore.cardbrowser.data.cache.CacheKey
import com.bitsycore.cardbrowser.data.cache.CacheScope
import com.bitsycore.cardbrowser.data.cache.Completeness
import com.bitsycore.cardbrowser.data.cache.MetadataCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
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
	/**
	 * Where a bulk import writes its scratch buckets, or `null` for a caller that has no storage.
	 *
	 * Optional because bulk is: a repository built without it simply reports no bulk support, the
	 * same answer a game whose source publishes no dump gets. Every ordinary path is unaffected,
	 * which is why the existing callers did not have to change.
	 */
	private val mStorage: AppStorage? = null,
	/** Serialises the scratch buckets. The cache has its own; this is not shared with it. */
	private val mJson: Json = Json { ignoreUnknownKeys = true },
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
		// Hoisted out of the block below, because the refresh decision needs it too: a stale set is
		// refreshed as a whole set, not as a single page.
		val vIsStale = vCachedComplete?.isStale(vNow, mCardsTtlMillis) ?: true

		// 1. Answer from a cached complete set whenever one exists. Even when it is stale it is
		//    emitted first, because a filtered screen that draws instantly and then refreshes beats
		//    a spinner over data we already have.
		if (vCachedComplete != null) {
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

		// Anything that is not "a fresh complete set, already answered above" is refreshed by
		// fetching the whole set.
		//
		// This condition used to be `requiresCompleteSet(query) || vCachedComplete == null`, and the
		// gap between the two was a permanent bug. `requiresCompleteSet` is false for an *empty*
		// query, so reopening a fully-downloaded set a day later -- stale but complete, no filters,
		// the single most ordinary thing a user does -- took the single-page branch. That fetched
		// page 1 only, emitted it as fresh with `isCompleteSet = false`, and wrote nothing. So a
		// 358-card grid was replaced by 100 cards labelled "Partial set: 100 of 358 downloaded"
		// while the whole set sat on disk, `facetsFor` stopped returning facets because the set no
		// longer read as complete, and `fetchedAt` never advanced -- so it happened again on every
		// single open, forever. A cached PARTIAL set could never be repaired for the same reason.
		val vNeedsCompleteSet = vProvider.capabilities.filtering.requiresCompleteSet(query) ||
			// No cached complete set and no local filter needed still warrants fetching the whole
			// set here: sets are at most a few hundred cards, the pages are needed for scrolling
			// anyway, and holding the complete set is what makes every later filter instant and
			// every later launch offline-capable.
			vCachedComplete == null ||
			// Stale, or never finished. Either way the answer is the whole set, not a page of it.
			vIsStale ||
			vCachedComplete.completeness != Completeness.COMPLETE

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
			// De-duplicated once and used for both the list and the count. Counting the raw records
			// here reported a different unit from the one on screen -- 100 held against 62 drawn --
			// and the two numbers are read side by side.
			val vDeduped = dedupePrintings(cards)
			emit(
				DataSnapshot(
					value = SetCards(
						cards = CardFilterEngine.apply(
							cards = vDeduped,
							query = query,
							rarityLadder = provider.game.rarityLadder,
						),
						isCompleteSet = false,
						knownSetSize = knownSetSize ?: total,
						cachedCardCount = vDeduped.size,
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
		// True when the preview came back as a whole page, so the page-one request is redundant.
		var vPreviewIsPageOne = false

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
			} else if (vPreview.cards.size >= vPageSize) {
				// The provider ignored the size we asked for and sent a full page anyway, so this
				// *is* page one -- adopt it rather than asking for the same thing again.
				//
				// Scryfall does exactly this: `/cards/search` has a fixed page of 175 and takes no
				// size parameter, so a request for 24 returns 175. The preview and the page-one
				// request that followed it were byte-identical URLs returning byte-identical
				// bodies, which cost every Magic set above 175 cards an extra ~200 KB request --
				// and an extra 100 ms of Scryfall's own request throttle on top.
				vCollected += vPreview.cards
				vPreviewIsPageOne = true
				emitProgress(vPreview.cards, vTotal)
			} else if (vPreview.cards.isNotEmpty()) {
				emitProgress(vPreview.cards, vTotal)
			}
		}

		if (!vPreviewWasWholeSet) {
			// Page one at the provider's own size, on its own, because nothing can be planned until
			// it answers: it carries the total and therefore how many more pages there are. When a
			// preview ran, this overlaps it and supersedes it -- unless the preview already *was*
			// page one, in which case asking again would fetch the same bytes twice.
			val vFirst = if (vPreviewIsPageOne) {
				CardPage(
					cards = vCollected.toList(),
					page = 1,
					pageSize = vPageSize,
					totalCount = vTotal,
					hasMore = true,
				)
			} else {
				currentCoroutineContext().ensureActive()
				val vPage = provider.listCards(
					CardPageRequest(setId = setId, query = CardQuery(), page = 1, pageSize = vPageSize, language = language),
				)
				vCollected += vPage.cards
				vTotal = vPage.totalCount ?: vTotal

				// Straight to the screen, whether or not a preview preceded it: page one is a
				// hundred cards where the preview was two dozen, and the user should see the grid
				// fill rather than sit on the preview until the whole set lands.
				if (vPage.hasMore && vPage.cards.isNotEmpty()) emitProgress(vCollected, vTotal)
				vPage
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

		// If the refresh collected less than what was already cached, `vBest` *is* the cached
		// payload -- so it is as complete as it ever was, and re-filing it as PARTIAL because this
		// attempt failed would be a lie about the data. That downgrade was silent and permanent:
		// a set that fell back like this stopped offering filter chips, stopped counting towards
		// "sets searched", and could never short-circuit again.
		val vKeptPreviousPayload = vBest === previouslyCached
		val vDeduped = dedupePrintings(vBest)

		val vFetchedAt = mClock()
		val vCompleteness = if (vComplete || vKeptPreviousPayload) {
			Completeness.COMPLETE
		} else {
			Completeness.PARTIAL
		}
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
		// Only when the fetch proved complete: a partial set's size is not the set's size, and the
		// set list would then under-count instead of over-counting.
		if (vCompleteness == Completeness.COMPLETE) {
			mCache.recordCardCount(completeSetKey(provider, setId, language), vDeduped.size)
		}

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
		val vDeduped = dedupePrintings(vPage.cards)
		emit(
			DataSnapshot.fresh(
				value = SetCards(
					cards = CardFilterEngine.sort(
						cards = vDeduped,
						query = query,
						rarityLadder = provider.game.rarityLadder,
					),
					// One page of a provider-filtered query covers the query completely only when
					// the provider says there is no more.
					isCompleteSet = !vPage.hasMore,
					knownSetSize = knownSetSize ?: vPage.totalCount,
					// The de-duplicated count, so it matches the list beside it.
					cachedCardCount = vDeduped.size,
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
			// The cached page for *this* search where there is one, and only the local sets
			// otherwise. Falling straight back to `vLocal` meant a failed refresh replaced 60
			// visible cached results with an empty list, and the screen drew a full-page "No
			// connection" over results the user could see a moment earlier.
			val vFallback = vCachedSearch?.let { searchResultsOf(it.payload, knownSets) } ?: vLocal
			emit(
				DataSnapshot(
					value = vFallback,
					origin = DataOrigin.CACHE,
					completeness = vCachedSearch?.completeness ?: Completeness.PARTIAL,
					fetchedAtEpochMillis = vCachedSearch?.fetchedAtEpochMillis ?: mClock(),
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
		return sets
			.filter { vSet -> isSaved(vProvider, vSet, language) }
			.map { it.id.qualified }
			.toSet()
	}

	/**
	 * True when any edition of [set] is on disk.
	 *
	 * *Any*, rather than the caller's preferred one, because "saved" is a statement about this
	 * device holding the set and not about which translation it holds. Insisting on one language
	 * was a real bug three times over: it looked for a French copy of a set published only in
	 * Japanese, so a fully downloaded Japan-line set never showed as saved; it had to agree
	 * exactly with whichever language the grid chose, which is two rules in two layers that could
	 * only ever drift; and -- the one that brought this back -- a bulk import writes each set
	 * under the language its *records* state, which for Scryfall's `default_cards` is
	 * overwhelmingly English. A user browsing in French imported the whole of Magic and not one
	 * set showed as saved, because nothing ever looked for the English copy that had just been
	 * written.
	 *
	 * So the candidates are [languageCandidatesFor], and the language the set would actually open
	 * in is checked first: the ordinary case is still one file-existence check and stops there.
	 */
	private suspend fun isSaved(
		provider: CardProvider<GameProfile>,
		set: CardSet,
		preferred: CardLanguage?,
	): Boolean = languageCandidatesFor(provider, set, preferred)
		.any { mCache.exists(completeSetKey(provider, set.id, it)) }

	/**
	 * Every language a copy of [set] could plausibly be filed under, best guess first.
	 *
	 * Three sources, in order: the one the set would open in, the ones the set itself states, and
	 * -- only when the set states none -- everything the provider can serve.
	 *
	 * That last fallback is the point. Most sources say nothing per set, and without it the only
	 * candidate is the language being browsed in, which silently assumes every copy on disk was
	 * put there by this user's own preference. A bulk import breaks that assumption by design: it
	 * files cards under whatever language they state.
	 *
	 * The cost is bounded and lazy. It is a sequence, callers use `any`, and the likeliest answer
	 * is first -- so a set held in the browsing language is one stat, and only a set held in
	 * *some other* language pays for the rest. Eleven stats is the worst case per set.
	 */
	private fun languageCandidatesFor(
		provider: CardProvider<GameProfile>,
		set: CardSet,
		preferred: CardLanguage?,
	): Sequence<CardLanguage?> {
		val vStated = set.languages.ifEmpty { provider.capabilities.data.languages }
		return (listOf(set.languageFor(preferred)) + vStated)
			.asSequence()
			.map { effectiveLanguage(provider, it) }
			.distinct()
	}

	/**
	 * Which languages of [sets] have their card records on disk, per set.
	 *
	 * The finer-grained answer that [savedSetIds] deliberately does not give. Both are wanted, for
	 * different questions: a row badge asks "is any of this set here?", while the download dialog
	 * asks "do I already have the edition I am about to fetch?" -- and answering the second with
	 * the first was a real fault. Downloading Base Set fetched card info in all six languages it is
	 * published in; reopening the dialog then showed "Card info" ticked and locked as already held
	 * after only French had finished, so the other five could not be asked for again without
	 * "Download again".
	 *
	 * Keyed by qualified set id, and only the languages a set actually states -- plus whichever it
	 * would open in, which is the one a source that states none will have written under. A set with
	 * no entry at all is absent from the map rather than mapping to an empty set, so "nothing
	 * downloaded" and "nothing known" stay distinguishable.
	 */
	suspend fun savedLanguages(
		game: GameId,
		sets: List<CardSet>,
		language: CardLanguage? = null,
	): Map<String, Set<CardLanguage>> {
		val vProvider = mRegistry.resolve(game, language) ?: return emptyMap()
		val vResult = mutableMapOf<String, Set<CardLanguage>>()
		for (vSet in sets) {
			currentCoroutineContext().ensureActive()
			// The same candidates `isSaved` uses, and for the same reason: a set that states no
			// languages of its own may still hold a copy in one the user does not browse in --
			// which is exactly what a bulk import leaves behind. Every candidate is checked here
			// rather than short-circuited, because the question is *which* editions are held.
			val vHeld = languageCandidatesFor(vProvider, vSet, language)
				.filterNotNull()
				.filterTo(mutableSetOf()) {
					mCache.exists(completeSetKey(vProvider, vSet.id, it))
				}
			if (vHeld.isNotEmpty()) vResult[vSet.id.qualified] = vHeld
		}
		return vResult
	}

	/**
	 * How many cards each of [sets] really holds in the language it would open in, where that has
	 * been measured.
	 *
	 * A source states one size per set and it is the size of the English printing. Ask for the same
	 * set in another language and it serves only what has been translated -- measured on YGOPRODeck
	 * on 2026-09-10, Magnificent Maestros is 24 cards and 4 of them in French, and Beyond the Brave
	 * is 8 and none at all. So the number a set list prints beside a row is not the number of cards
	 * that row will open onto, and printing it anyway is the app claiming something it never checked.
	 *
	 * This is the part it *has* checked: what a source actually served, recorded when the set was
	 * cached whole. A set absent from the map has never been fetched in that language, and the
	 * caller should keep showing the source's figure -- that is still the only number available, and
	 * an absent entry is not a count of zero.
	 *
	 * Cheap for the same reason [savedSetIds] is: one tiny sibling file per set, never the record.
	 */
	suspend fun confirmedCardCounts(
		game: GameId,
		sets: List<CardSet>,
		language: CardLanguage? = null,
	): Map<String, Int> {
		val vProvider = mRegistry.resolve(game, language) ?: return emptyMap()
		val vResult = mutableMapOf<String, Int>()
		for (vSet in sets) {
			currentCoroutineContext().ensureActive()
			// The language the row would actually open in, which is not always the one asked for:
			// a set published only in Japanese opens in Japanese whatever the preference says.
			val vLanguage = effectiveLanguage(vProvider, vSet.languageFor(language))
			val vCount = mCache.cardCount(completeSetKey(vProvider, vSet.id, vLanguage)) ?: continue
			vResult[vSet.id.qualified] = vCount
		}
		return vResult
	}

	/**
	 * Which languages each of [sets] is known to exist in, for a set list to show.
	 *
	 * Deliberately **free of requests**. [languagesFor] gives the authoritative answer and costs a
	 * probe per candidate; running it for every row would be a request per language per set, which
	 * for Yu-Gi-Oh's catalogue is thousands of them to draw a list. This reads only what is already
	 * known, and unions three sources:
	 *
	 * 1. the confirmed record left behind by [languagesFor] the last time that set was opened,
	 * 2. **whatever is on disk** -- a set whose cards are cached in a language is confirmed in it,
	 *    because they are there only because the source served them, and
	 * 3. failing both, whatever the set itself claims -- which for TCGdex and Wuthering Waves
	 *    comes with the catalogue and for everything else is empty.
	 *
	 * Point 2 is what makes the pin useful after a download. Without it the pin read only what a
	 * *source* had stated per set, and Scryfall states nothing -- so downloading the whole of
	 * Magic left every row blank, and the pin appeared only on sets whose language menu had been
	 * opened by hand. Cards on disk are the strongest evidence there is and they were being
	 * ignored.
	 *
	 * So the answer improves as sets are downloaded or opened, and a set nobody has touched is
	 * simply absent rather than guessed at. Absent means "not known", never "only one language" --
	 * a source that says nothing about a set's languages has not told us it has one.
	 */
	suspend fun availableLanguages(
		game: GameId,
		sets: List<CardSet>,
		language: CardLanguage? = null,
	): Map<String, Set<CardLanguage>> {
		val vProvider = mRegistry.resolve(game, language) ?: return emptyMap()
		val vSerializer = CacheEnvelope.serializer(SetSerializer(serializer<CardLanguage>()))
		val vResult = mutableMapOf<String, Set<CardLanguage>>()
		for (vSet in sets) {
			currentCoroutineContext().ensureActive()
			val vConfirmed = mCache.read(setLanguagesKey(vProvider, vSet.id), vSerializer)?.payload
			val vStated = vConfirmed?.takeIf { it.isNotEmpty() } ?: vSet.languages
			// Plus every language this device actually holds cards in. The same candidate sweep
			// `savedLanguages` runs -- one file-existence check per candidate, no requests.
			val vHeld = languageCandidatesFor(vProvider, vSet, language)
				.filterNotNull()
				.filterTo(mutableSetOf()) { mCache.exists(completeSetKey(vProvider, vSet.id, it)) }
			val vKnown = vStated + vHeld
			if (vKnown.isNotEmpty()) vResult[vSet.id.qualified] = vKnown
		}
		return vResult
	}

	// ============
	//  One set's record

	/**
	 * The cached record for one set, or `null` when no set list holding it is on disk.
	 *
	 * Reads only what is cached, and deliberately searches *every* language's set list rather than
	 * the caller's own. The record is what states which languages a set is published in, and that
	 * answer must not depend on which language the asker happens to be in -- otherwise a Japanese
	 * set opened by a French user would report no languages at all and its language menu would
	 * come up empty.
	 *
	 * Set lists are small and there are at most a handful of them per provider, so this is a few
	 * file reads and no requests. It returns `null` rather than fetching because every caller
	 * reached the set by tapping it in a list that had just been loaded.
	 */
	suspend fun setRecord(setId: SourceId, game: GameId): CardSet? {
		// The set id names its own provider, so there is nothing to search for.
		val vProvider = mRegistry.byId(setId.provider) ?: return null
		val vSerializer = CacheEnvelope.serializer(ListSerializer(serializer<CardSet>()))
		val vLanguages = listOf(null) + vProvider.capabilities.data.languages
			.map { effectiveLanguage(vProvider, it) }
			.distinct()
		for (vLanguage in vLanguages) {
			val vCached = mCache.read(setListKey(vProvider, game, vLanguage), vSerializer) ?: continue
			vCached.payload.firstOrNull { it.id == setId }?.let { return it }
		}
		return null
	}

	/**
	 * The languages a menu should offer for [setId], **without asking the source anything**.
	 *
	 * The best answer already on hand, in order:
	 *
	 * 1. the confirmed list, if [languagesFor] has run for this set and its record is still fresh
	 * 2. what the set itself claims, for the two sources that state it per set
	 * 3. everything the source can serve
	 *
	 * Every screen that offers a language menu uses this, and that is the point: the card grid and
	 * the card detail screen have to agree, and they did not. Detail briefly listed only the
	 * languages whose cards were *on disk* -- two, for a set opened in English and Japanese --
	 * while the grid beside it offered eleven. Neither number was the same question: what is
	 * cached is not what exists, and a language you have not downloaded is exactly the one you
	 * would open the menu to ask for.
	 *
	 * Never a request, so it costs nothing on the path that opens a card. The confirmation that
	 * narrows step 3 to the truth is paid once, when the grid's menu is opened, and both screens
	 * read it from then on.
	 */
	suspend fun knownLanguagesFor(setId: SourceId, game: GameId): Set<CardLanguage> {
		val vProvider = mRegistry.byId(setId.provider) ?: return emptySet()
		val vSerializer = CacheEnvelope.serializer(SetSerializer(serializer<CardLanguage>()))
		mCache.read(setLanguagesKey(vProvider, setId), vSerializer)
			?.takeIf { !it.isStale(mClock(), mSetListTtlMillis) }
			?.payload
			?.takeIf { it.isNotEmpty() }
			?.let { return it }
		return setRecord(setId, game)?.languages?.takeIf { it.isNotEmpty() }
			?: vProvider.capabilities.data.languages
	}

	/**
	 * The language to open [setId] in, for the cheapest evidence available.
	 *
	 * Three steps, and it stops at the first that answers:
	 *
	 * 1. **What is on disk.** A set held in the language it would open in opens in it, with no
	 *    request at all. This is the case for anything downloaded or previously browsed.
	 * 2. **What was downloaded, in another language.** A record the user deliberately asked for --
	 *    a downloaded set or a bulk import -- is used rather than fetching the preferred language
	 *    over the network. See below.
	 * 3. **One probe.** Otherwise the source is asked about *that one language*, which is one
	 *    request rather than the eleven a full confirmation costs.
	 * 4. **The full confirmation**, only when the preferred language turns out to have nothing --
	 *    the case a Japan-only set or an untranslated new set falls into, where a menu has to be
	 *    built anyway.
	 *
	 * The rule this protects is unchanged: a set is never opened in a language with no cards. It
	 * is the cost of establishing that which changes, from eleven requests per set open to zero
	 * for the ordinary case. See [languagesFor] for what step 4 does and why it is cached.
	 *
	 * ## Why step 2 exists, and why only pinned records count
	 *
	 * Scryfall's cheap dump is 78 MB of overwhelmingly English cards, and a cache key embeds a
	 * language -- so a user who imported it while preferring French held every Magic set on disk
	 * and still paid a request to open each one. The import bought nothing, which is not a
	 * defensible outcome for 78 MB.
	 *
	 * Only *pinned* records qualify. Pinned means downloaded or imported -- something the user
	 * asked for by name -- and using one is honouring that request. Ordinary browsing cache is not
	 * the same thing: a set glanced at in English last week is no reason to stop showing a
	 * French-preferring user French today, and treating it as one would make the app's language
	 * drift with its history rather than follow its setting.
	 *
	 * The substitution is never silent. [OpeningLanguage.substitutedFor] carries what was wanted,
	 * and the grid says so with a way to fetch it after all.
	 */
	suspend fun openingLanguageFor(
		setId: SourceId,
		game: GameId,
		preferred: CardLanguage?,
	): OpeningLanguage {
		val vProvider = mRegistry.byId(setId.provider) ?: return OpeningLanguage(preferred)
		val vSet = setRecord(setId, game)
		val vWanted = effectiveLanguage(vProvider, vSet?.languageFor(preferred) ?: preferred)
			?: return OpeningLanguage(null)

		// 1. Held on disk, so the source has already served it. No request.
		if (mCache.exists(completeSetKey(vProvider, setId, vWanted))) return OpeningLanguage(vWanted)

		// 2. Not held in the wanted language, but downloaded in another.
		downloadedLanguageFor(vProvider, setId, vSet, preferred, except = vWanted)
			?.let { return OpeningLanguage(it, substitutedFor = vWanted) }

		// A confirmation already on disk answers without asking again, and is what step 4 leaves
		// behind -- so the expensive path is paid at most once per set per TTL.
		val vSerializer = CacheEnvelope.serializer(SetSerializer(serializer<CardLanguage>()))
		mCache.read(setLanguagesKey(vProvider, setId), vSerializer)
			?.takeIf { !it.isStale(mClock(), mSetListTtlMillis) }
			?.payload
			?.let { vConfirmed ->
				return OpeningLanguage(
					vConfirmed.firstOrNull { it == vWanted }
						?: vSet?.copy(languages = vConfirmed)?.languageFor(preferred)
						?: vConfirmed.firstOrNull(),
				)
			}

		// 3. One probe, for the one language that matters.
		val vHasWanted = runCatching { vProvider.confirmLanguages(setId, setOf(vWanted)) }
			.getOrNull()
			// Unconfirmable is not absent -- a timeout says nothing about the printing. Opening
			// it and letting the grid report an empty set is the honest outcome.
			?: return OpeningLanguage(vWanted)
		if (vWanted in vHasWanted) return OpeningLanguage(vWanted)

		// 4. It really has nothing. Now the full list is worth its cost, and it is cached.
		//
		// No `substitutedFor` from here down: the wanted language has been *asked about* and the
		// source says it has no such edition. That is not a missing download and must not be
		// offered as one.
		val vConfirmed = languagesFor(setId, game)
		return OpeningLanguage(
			vSet?.copy(languages = vConfirmed)?.languageFor(preferred) ?: vConfirmed.firstOrNull(),
		)
	}

	/**
	 * A language this set was deliberately downloaded in, best first, or `null` if none was.
	 *
	 * Pinned only -- see [openingLanguageFor]'s note on why browsing cache does not count -- and
	 * in the user's own preference order, so someone who reads French then English gets the
	 * English copy rather than whichever language happens to hash first.
	 */
	private suspend fun downloadedLanguageFor(
		provider: CardProvider<GameProfile>,
		setId: SourceId,
		set: CardSet?,
		preferred: CardLanguage?,
		except: CardLanguage,
	): CardLanguage? {
		// Without a cached set record there is nothing set-specific to go on, so the source's own
		// list is the candidate list. It over-offers, which costs only a few `exists` checks: a
		// language nothing was downloaded in simply never matches.
		val vCandidates = set
			?.let { languageCandidatesFor(provider, it, preferred) }
			?: provider.capabilities.data.languages.asSequence()
				.map { effectiveLanguage(provider, it) }
		val vUsable = vCandidates
			.filterNotNull()
			.filter { it != except }
			.toSet()
		if (vUsable.isEmpty()) return null
		// Preference order first, then anything else the candidates turned up, so a language the
		// app does not rank is still usable rather than invisible.
		val vOrdered = CardLanguage.PREFERENCE_ORDER.filter { it in vUsable } +
			vUsable.filterNot { it in CardLanguage.PREFERENCE_ORDER }
		return vOrdered.firstOrNull { mCache.isPinned(completeSetKey(provider, setId, it)) }
	}

	/**
	 * The languages [setId] can really be browsed in, cached.
	 *
	 * Two narrowings, and both are needed:
	 *
	 * 1. The set's own claimed languages, from its cached record, rather than everything the source
	 *    can serve. TCGdex offers eleven locales and Pokemon's Base Set exists in six of them.
	 * 2. Of those, the ones the provider confirms it holds cards for -- see
	 *    [CardProvider.confirmLanguages]. The claim in a set list is not reliable: TCGdex's Korean
	 *    catalogue names 95 sets, states a card count for each, and serves no cards at all.
	 *
	 * The result is cached under [CacheScope.SetLanguages], because the second step costs a request
	 * per candidate and the answer changes only when a source backfills a translation. Falls back
	 * to the provider's own languages when no set record is on disk: a menu of one entry is not
	 * evidence that one language exists.
	 */
	suspend fun languagesFor(setId: SourceId, game: GameId): Set<CardLanguage> {
		val vProvider = mRegistry.byId(setId.provider) ?: return emptySet()
		// A set that states its own languages narrows the candidates; one that states none starts
		// from everything the source can serve. Either way the provider still gets to confirm them.
		//
		// It used to return early in the second case, which quietly meant only a provider that
		// described its sets per language could ever be confirmed -- so Scryfall went on offering
		// ten languages for a 1993 set printed only in English, even once it could say otherwise.
		val vCandidates = setRecord(setId, game)?.languages?.takeIf { it.isNotEmpty() }
			?: vProvider.capabilities.data.languages
		if (vCandidates.isEmpty()) return emptySet()

		val vKey = setLanguagesKey(vProvider, setId)
		val vSerializer = CacheEnvelope.serializer(SetSerializer(serializer<CardLanguage>()))
		val vCached = mCache.read(vKey, vSerializer)
		// Only re-confirmed once the record itself would be refetched anyway. A language a source
		// has never had is not going to appear between two openings of the same set.
		if (vCached != null && !vCached.isStale(mClock(), mSetListTtlMillis)) return vCached.payload

		val vConfirmed = try {
			vProvider.confirmLanguages(setId, vCandidates)
		} catch (vError: ProviderError) {
			// Unconfirmed is not empty. Falling back to the claim leaves a menu that may contain an
			// entry that fails, which the grid already handles by saying so.
			return vCached?.payload ?: vCandidates
		}
		// Never empty: a set no language can serve would leave the grid with no language to load
		// at all, and the honest report of that is an empty set rather than an absent control.
		val vResult = vConfirmed.ifEmpty { vCandidates }
		mCache.write(
			key = vKey,
			envelope = CacheEnvelope(
				schemaVersion = CacheEnvelope.CURRENT_SCHEMA_VERSION,
				provider = vProvider.id,
				language = null,
				scope = CacheScope.SetLanguages(setId.qualified),
				fetchedAtEpochMillis = mClock(),
				completeness = Completeness.COMPLETE,
				payload = vResult,
			),
			serializer = vSerializer,
		)
		return vResult
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

	/**
	 * No language in the key: the answer is *about* languages, so keying it by one would store the
	 * same fact once per language and let the copies disagree.
	 */
	private fun setLanguagesKey(provider: CardProvider<GameProfile>, setId: SourceId) =
		CacheKey.of(
			"v${CacheEnvelope.CURRENT_SCHEMA_VERSION}",
			provider.id.value,
			"set-languages",
			setId.qualified,
		)

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

	// ==================
	// MARK: Bulk import
	// ==================

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
		val vLanguage = effectiveLanguage(vProvider, language)

		// One request, for the names and codes. The bulk records carry a set *code* but not the
		// catalogue's own metadata, and a set list written from cards alone would lose release
		// dates and symbols that the ordinary path has.
		val vSets = runCatching { vProvider.listSets(vLanguage) }.getOrDefault(emptyList())
		val vSetsById = vSets.associateBy { it.id.qualified }

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

					// Pinned before the write, for the same reason a download is: writing runs a
					// trim, and a set large enough to breach the ceiling would otherwise be
					// evicted by the very write that stored it.
					val vKey = completeSetKey(vProvider, vSetId, vLanguage)
					val vDedupedBucket = dedupePrintings(vPrintings)
					mCache.pin(vKey, pinLabel(game, vSetId, vLanguage))
					mCache.write(
						key = vKey,
						envelope = CacheEnvelope(
							schemaVersion = CacheEnvelope.CURRENT_SCHEMA_VERSION,
							provider = vProvider.id,
							language = vLanguage,
							scope = CacheScope.CompleteSet(vSetId.qualified),
							fetchedAtEpochMillis = mClock(),
							// The bulk file is the whole catalogue by definition, so a set drawn
							// from it is complete in a way a paged fetch has to prove.
							completeness = Completeness.COMPLETE,
							payload = vDedupedBucket,
						),
						serializer = CacheEnvelope.serializer(ListSerializer(serializer<CardPrinting>())),
					)
					mCache.recordCardCount(vKey, vDedupedBucket.size)
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

	// ============
	//  Storage, per game

	/**
	 * What each game is keeping on disk, for the storage screen.
	 *
	 * "Kept" means pinned: records the ceiling will not reclaim because the user asked for them,
	 * by downloading a set or importing a catalogue. They are the reason a storage screen has to
	 * exist at all -- the limit in settings bounds browsing, and this is the part that only a
	 * decision can remove.
	 *
	 * Measured from each game's *cached* set list, since that is what maps a set to a cache key.
	 * A game whose set list has never been fetched reports nothing, which is right: nothing of it
	 * can have been downloaded either.
	 *
	 * Every language a set states is checked, because a bulk import can write eleven records for
	 * one set and a screen that counted only the browsing language would under-report by a factor
	 * of ten.
	 */
	suspend fun keptByGame(): List<GameStorage> {
		val vSerializer = CacheEnvelope.serializer(ListSerializer(serializer<CardSet>()))
		// From the pin markers, not from each game's set list.
		//
		// The set list is ordinary browsing data, so clearing the cache deleted it -- and with it
		// the only route from a hashed filename back to a set. Kept records then disappeared from
		// this screen while still being on disk and still being reported as downloaded by the
		// download dialog, which is exactly the contradiction a storage screen must not produce.
		// Kept as parsed labels rather than raw entries, because both counts below are about the
		// *parts* -- which set, and which language -- and re-splitting per count is how the two
		// would drift apart.
		val vByGame = mCache.pinnedEntries()
			.mapNotNull { vEntry ->
				val vParts = vEntry.label.split(TAB)
				if (vParts.size < 2 || vParts[0].isEmpty()) null else vParts[0] to (vParts to vEntry)
			}
			.groupBy({ it.first }, { it.second })

		return vByGame.mapNotNull { (vGameId, vEntries) ->
			currentCoroutineContext().ensureActive()
			val vGame = GameId(vGameId)
			// How many sets the game has, when its catalogue is still cached. Absent is a real
			// answer -- the screen says "3 sets" rather than inventing a denominator.
			val vKnownSets = mRegistry.resolve(vGame)
				?.let { setListOnDisk(it, vGame, vSerializer) }
				?.size
			GameStorage(
				game = vGame,
				// Distinct set ids, so this is comparable with `knownSets`. A set held in eleven
				// languages is one set here and eleven records on disk.
				sets = vEntries.mapNotNull { (vParts, _) -> vParts.getOrNull(1) }.distinct().size,
				languages = vEntries
					.mapNotNull { (vParts, _) -> vParts.getOrNull(2)?.ifEmpty { null } }
					.mapNotNullTo(mutableSetOf()) { CardLanguage.fromCode(it) },
				bytes = vEntries.sumOf { (_, vEntry) -> vEntry.bytes },
				knownSets = vKnownSets,
			)
		}
	}

	/**
	 * Deletes everything [game] is keeping, and stops keeping it.
	 *
	 * Unpinned as well as removed, so a record that survives -- because it is also the set the
	 * user is currently reading, say -- goes back to being ordinary browsing data rather than
	 * staying exempt from the ceiling forever.
	 */
	suspend fun deleteKept(game: GameId): Int {
		// Rebuilt from the labels rather than from the set list, so this deletes what the screen
		// showed even when the catalogue that named it has been cleared.
		val vProvider = mRegistry.resolve(game) ?: return 0
		var vRemoved = 0
		for (vEntry in mCache.pinnedEntries()) {
			currentCoroutineContext().ensureActive()
			val vParts = vEntry.label.split(TAB)
			if (vParts.size < 3 || vParts[0] != game.value) continue
			val vSetId = SourceId.parse(vParts[1]) ?: continue
			val vLanguage = vParts[2].ifEmpty { null }?.let(CardLanguage::fromCode)
			val vKey = completeSetKey(vProvider, vSetId, vLanguage)
			mCache.unpin(vKey)
			mCache.remove(vKey)
			vRemoved++
		}
		return vRemoved
	}

	/** A game's set list if one is cached in any language, without fetching. */
	private suspend fun setListOnDisk(
		provider: CardProvider<GameProfile>,
		game: GameId,
		serializer: KSerializer<CacheEnvelope<List<CardSet>>>,
	): List<CardSet>? {
		val vLanguages = listOf(null) + provider.capabilities.data.languages
			.map { effectiveLanguage(provider, it) }
			.distinct()
		for (vLanguage in vLanguages) {
			val vCached = mCache.read(setListKey(provider, game, vLanguage), serializer) ?: continue
			if (vCached.payload.isNotEmpty()) return vCached.payload
		}
		return null
	}

	/**
	 * Protects a downloaded set's records from cache eviction, or releases them.
	 *
	 * Here rather than on the cache because the key is built here -- it folds in the schema version,
	 * the provider and the resolved language, none of which the download queue knows or should.
	 *
	 * Silently does nothing for a game with no routed provider, which is the same thing every other
	 * path here does with one.
	 */
	suspend fun setPinned(game: GameId, setId: SourceId, language: CardLanguage?, isPinned: Boolean) {
		val vProvider = mRegistry.resolve(game, language) ?: return
		val vKey = completeSetKey(vProvider, setId, effectiveLanguage(vProvider, language))
		if (isPinned) mCache.pin(vKey, pinLabel(game, setId, effectiveLanguage(vProvider, language))) else mCache.unpin(vKey)
	}

	/**
	 * What a pinned record is, written into its marker.
	 *
	 * Three fields and a separator rather than JSON: it is read once per record by the storage
	 * screen and written once per download, and a tab-separated line is cheaper than a parser at
	 * both ends. The language may be absent, which is a real state -- a source that states none.
	 */
	private fun pinLabel(game: GameId, setId: SourceId, language: CardLanguage?): String =
		listOf(game.value, setId.qualified, language?.code.orEmpty()).joinToString(TAB.toString())

	private fun completeSetKey(provider: CardProvider<GameProfile>, setId: SourceId, language: CardLanguage?) =
		CacheKey.of("v${CacheEnvelope.CURRENT_SCHEMA_VERSION}", provider.id.value, "set", setId.qualified, language?.code ?: "-")

	companion object {

		/** Scratch directory for a bulk import's shards. Deleted when the import ends. */
		/**
		 * Separates the fields of a pin marker's label. See `pinLabel`.
		 *
		 * A tab because none of the three fields can contain one: a game id and a language code are
		 * both slugs, and a qualified set id is a provider and a local id joined by a colon.
		 */
		private const val TAB: Char = '\t'

		private const val BULK_SCRATCH_DIR = "bulk-scratch"

		/**
		 * How many scratch files a bulk import streams into.
		 *
		 * Two limits, pulling opposite ways. Every shard is open at once during the stream, so
		 * this is a floor on file descriptors -- iOS allows 256 for the whole process and Android
		 * commonly 1024, and the app is already using some. And a shard is read back whole into
		 * memory, so this is also a ceiling on how much that costs.
		 *
		 * 64 sits comfortably inside both: descriptors are a rounding error, and the biggest dump
		 * here -- Scryfall's every-language file, roughly half a million printings -- lands at a
		 * few megabytes a shard.
		 */
		private const val BULK_SHARDS = 64

		/** How often to report while sorting cards into sets. Often enough to move, rarely enough not to thrash. */
		private const val BULK_PROGRESS_EVERY = 2_000

		/**
		 * Newest first, and sets with no stated release date last.
		 *
		 * Last rather than first: a set whose date the provider does not know is more likely to be
		 * a promo grab-bag than this week's release.
		 */
		val SET_ORDER: Comparator<CardSet> = compareBy<CardSet> { it.releaseDate == null }
			.thenByDescending { it.releaseDate }
			// A dated set is ordered by its date; an undated one by its line and its rank within
			// it. Grouping by line first is what makes the rank meaningful: it is a position in one
			// catalogue's chronology, so comparing a Japanese set's rank with a Chinese set's would
			// interleave two unrelated release schedules.
			//
			// This is what orders Pokemon's Japanese line, where TCGdex publishes the chronology
			// without publishing the dates -- see `CardSet.releaseOrder`. Before it, 265 of the
			// game's 486 sets fell through to code order, which puts the 2013 XY sets after the
			// 2023 SV ones.
			.thenBy { it.region ?: "" }
			.thenByDescending { it.releaseOrder ?: Int.MIN_VALUE }
			// Nothing left to go on. This used to fall back to the *name*, which for One Piece --
			// where no set carries a date -- listed "Awakening of the New Era" before "Romance
			// Dawn" and buried OP-14 in the middle.
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
		internal const val FIRST_PAGE_SIZE = 24

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

/** What a bulk import did. */
data class BulkImportResult(
	val cards: Int,
	val sets: Int,
	/** How many sets the catalogue lists, so a screen can say what share was covered. */
	val knownSets: Int,
	/**
	 * Which dump was read, by [BulkSummary.id], and the day the source last rebuilt it.
	 *
	 * Reported so the queue can record what was imported. That record is the only way to answer
	 * "have I already done this?" -- the sets on disk cannot, because a dump holds no cards for
	 * every set a catalogue lists.
	 */
	val variantId: String? = null,
	val dumpUpdatedAt: LocalDate? = null,
)

/** Where a bulk import has got to. Three phases, because they have very different durations. */
sealed interface BulkImportProgress {

	/** Fetching the file. [total] is null until the source states a length. */
	data class Downloading(val bytes: Long, val total: Long?) : BulkImportProgress

	/** Reading it back and sorting cards into sets. */
	data class Reading(val cards: Int) : BulkImportProgress

	/** Writing the cached sets. */
	data class Writing(val sets: Int, val total: Int) : BulkImportProgress
}
