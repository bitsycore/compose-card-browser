package com.bitsycore.tcgexplorer.data.repository

import com.bitsycore.tcgexplorer.sqlstore.StoredSetRow
import com.bitsycore.tcgexplorer.data.cache.SEARCH_LIMIT
import com.bitsycore.tcgexplorer.core.filter.CardFacets
import com.bitsycore.tcgexplorer.core.filter.CardFilterEngine
import com.bitsycore.tcgexplorer.core.model.CardLanguage
import kotlinx.datetime.LocalDate
import com.bitsycore.tcgexplorer.core.model.ArtworkTreatment
import com.bitsycore.tcgexplorer.core.model.CardPrinting
import com.bitsycore.tcgexplorer.core.model.CardSet
import com.bitsycore.tcgexplorer.core.model.SetCodeComparator
import com.bitsycore.tcgexplorer.core.game.GameProfile
import com.bitsycore.tcgexplorer.core.model.GameId
import com.bitsycore.tcgexplorer.core.model.SourceId
import com.bitsycore.tcgexplorer.core.provider.CardPage
import com.bitsycore.tcgexplorer.core.provider.CardPageRequest
import com.bitsycore.tcgexplorer.core.provider.CardProvider
import com.bitsycore.tcgexplorer.core.provider.CardQuery
import com.bitsycore.tcgexplorer.core.provider.ProviderError
import com.bitsycore.tcgexplorer.core.provider.ProviderRegistry
import com.bitsycore.tcgexplorer.core.provider.BulkCatalogue
import com.bitsycore.tcgexplorer.core.provider.BulkSummary
import okio.ByteString.Companion.encodeUtf8
import okio.buffer
import okio.use
import com.bitsycore.tcgexplorer.data.cache.AppStorage
import kotlinx.serialization.json.Json
import com.bitsycore.tcgexplorer.data.cache.CacheEnvelope
import com.bitsycore.tcgexplorer.data.cache.CacheKey
import com.bitsycore.tcgexplorer.data.cache.CacheScope
import com.bitsycore.tcgexplorer.data.cache.Completeness
import com.bitsycore.tcgexplorer.data.cache.MetadataStore
import com.bitsycore.tcgexplorer.data.cache.CardSearchFilter
import com.bitsycore.tcgexplorer.data.cache.SetRecordStore
import com.bitsycore.tcgexplorer.sqlstore.StoredFacets
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
	private val mCache: MetadataStore,
	/**
	 * Where complete sets live.
	 *
	 * This class still owns *what* is cached and when; the store owns how it is held. The split is
	 * deliberate and is written down only here: set lists, card detail, search pages and per-set
	 * languages stay in [mCache], because they are few and tiny and were never what any of this
	 * cost.
	 */
	private val mSetStore: SetRecordStore,
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

	/** The whole-game import, which has a lifecycle of its own. See [BulkImporter]. */
	private val mBulk = BulkImporter(
		mRegistry = mRegistry,
		mSetStore = mSetStore,
		mStorage = mStorage,
		mJson = mJson,
		mClock = mClock,
		setLabel = ::setLabel,
	)

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
		val vNow = mClock()
		// Reading marks the set used, which is what eviction orders by. The file cache could not
		// do that across launches -- it held access times in memory -- so a set you keep coming
		// back to sorted as old as one you opened once.
		val vCachedComplete = mSetStore.read(vProvider.id, setId, vLanguage, vNow)
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
						payload = vCachedComplete.cards,
						query = query,
						knownSetSize = knownSetSize,
						completeness = vCachedComplete.completenessValue,
						rarityLadder = vProvider.game.rarityLadder,
					),
					fetchedAt = vCachedComplete.fetchedAtEpochMillis,
					completeness = vCachedComplete.completenessValue,
					isStale = vIsStale,
				),
			)
			if (!vIsStale && vCachedComplete.isComplete) return@flow
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
			!vCachedComplete.isComplete

		try {
			if (vNeedsCompleteSet) {
				emitCompleteSet(
					provider = vProvider,
					setId = setId,
					language = vLanguage,
					query = query,
					knownSetSize = knownSetSize,
					previouslyCached = vCachedComplete?.cards,
					// Only an *incomplete* set is a prefix to carry on from. A complete one being
					// refetched is a refresh, and a refresh starts at the beginning.
					resumeFrom = vCachedComplete?.takeIf { !it.isComplete }?.cards,
				)
			} else {
				emitSinglePage(vProvider, setId, vLanguage, query, knownSetSize)
			}
		} catch (vError: ProviderError) {
			if (vCachedComplete != null) {
				emit(
					DataSnapshot(
						value = cachedSetCards(
						payload = vCachedComplete.cards,
						query = query,
						knownSetSize = knownSetSize,
						completeness = vCachedComplete.completenessValue,
						rarityLadder = vProvider.game.rarityLadder,
					),
						origin = DataOrigin.CACHE,
						completeness = vCachedComplete.completenessValue,
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
		resumeFrom: List<CardPrinting>? = null,
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
		// Where to start, given what a previous attempt already left on disk.
		//
		// Pages are requested in order and consumed in order, and collection stops at the first
		// failure -- so a partial set is a *prefix* of the set, and pages 1..floor(n/size) are
		// fully covered by it. Re-fetching them would be the whole set again for the sake of the
		// tail, which is exactly what made a sleep-interrupted download so expensive to put right.
		//
		// The boundary page is re-fetched rather than assumed: `n` is rarely a clean multiple of
		// the page size, because a preview of 24 may be mixed in. Overlap is harmless -- the merge
		// below de-duplicates by id.
		//
		// The prefix argument fails if a source re-orders a set between attempts. That is caught
		// rather than trusted: the guard further down marks the set incomplete when the collection
		// is short of the provider's own total, so a bad resume produces a partial set that will be
		// tried again, not a complete one that is missing cards.
		val vHeld = resumeFrom.orEmpty()
		val vStartPage = if (vHeld.isEmpty()) 1 else (vHeld.size / vPageSize) + 1
		val vIsResuming = vStartPage > 1

		val vWantsPreview = vPageSize > FIRST_PAGE_SIZE && !vIsResuming
		var vTotal: Int? = null
		val vCollected = mutableListOf<CardPrinting>()
		// What is already held goes in first, so page order is preserved across the join.
		if (vIsResuming) vCollected += vHeld
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
				// [vStartPage], not 1. When resuming, this is the first page not already held, and
				// it carries the provider's total like any other page -- so nothing extra has to be
				// fetched to work out how many pages remain.
				val vPage = provider.listCards(
					CardPageRequest(setId = setId, query = CardQuery(), page = vStartPage, pageSize = vPageSize, language = language),
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
				outer@ for (vBatch in ((vStartPage + 1)..vLastPage).chunked(MAX_CONCURRENT_PAGE_REQUESTS)) {
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
		// The de-duplicated list, not the raw one.
		//
		// Writing `vBest` here meant the cache held records that had been collapsed before being
		// shown, so the next launch read them back and drew them -- undoing the de-duplication for
		// every session after the first, which is the session that matters least. `vDeduped` is
		// what was displayed and it is what is stored.
		//
		// The card count is a column now rather than a second write to a sidecar file, so there is
		// no "only when complete" guard left to get wrong: completeness is stored beside the
		// count, and a caller wanting a set's real size asks for a complete one.
		mSetStore.write(
			provider = provider.id,
			setId = setId,
			language = language,
			game = provider.game.id,
			label = setLabel(provider, setId),
			cards = vDeduped,
			isComplete = vCompleteness == Completeness.COMPLETE,
			fetchedAtEpochMillis = vFetchedAt,
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
	 * The advanced search: one indexed query over every card of a game that is on this device.
	 *
	 * What [searchAllSets] cannot do. That one matches a name, because matching anything else meant
	 * loading every cached set off disk and filtering in memory; this is a single SQL statement over
	 * indexed columns, so it can narrow on type, rarity, a cost range, a domain -- and on what a
	 * name must *not* contain, which a name match has no way to express at all.
	 *
	 * **It is a local search and it says so.** The scope is always [SearchScope.LOCAL_CACHED_SETS],
	 * and `searchedSetCount` against `knownSetCount` is what the screen prints. That matters more
	 * here than it did for the name search: this one is fast enough over a hundred thousand rows to
	 * feel like a search of everything, and an empty result means "not in what you have downloaded"
	 * rather than "does not exist".
	 *
	 * An empty filter returns nothing rather than everything. "No criteria" is a screen nobody has
	 * filled in yet, not a request for the entire game.
	 */
	suspend fun searchStoredCards(
		game: GameId,
		filter: CardSearchFilter,
		knownSets: List<CardSet>,
	): CardSearchResults {
		if (filter.isEmpty) {
			return CardSearchResults(
				cards = emptyList(),
				searchedSetCount = 0,
				knownSetCount = knownSets.size,
			)
		}
		// The language the routed source actually answers in, not the one the user prefers.
		//
		// A record is written under the language it came back in: Riftcodex serves English whoever
		// is reading, so a French-preferring user's Riftbound cards are stored as `en`. Searching
		// for `fr` matched nothing at all, and the screen reported an honest-looking empty result
		// -- the same mismatch the download queue had, in the one place that reads what it wrote.
		//
		// Two nulls that mean opposite things, and writing this as `?.let { … } ?: filter.language`
		// merged them. A source that states no languages at all -- OPTCG states none -- resolves to
		// null, the elvis then read that as "nothing answered" and put the user's raw preference
		// back, and the search asked for `en` rows that had been written under none. Every One
		// Piece search returned empty while the set sat on disk.
		val vProvider = mRegistry.resolve(game, filter.language)
		val vLanguage =
			if (vProvider != null) effectiveLanguage(vProvider, filter.language) else filter.language
		val vCards = mSetStore.search(game, filter.copy(language = vLanguage))
		return CardSearchResults(
			cards = vCards,
			// How much of the game was actually searched, from what is stored rather than from
			// what the results happen to span -- a filter that matches three cards has not
			// searched three sets.
			searchedSetCount = storedSetCount(game, knownSets),
			knownSetCount = knownSets.size,
			// The store stops at `SEARCH_LIMIT` rows. A full page is the only evidence available
			// that it stopped early -- and a screen printing "200 cards" for a search that matched
			// four thousand is the kind of number this app must not present as a total.
			hasMore = vCards.size >= SEARCH_LIMIT,
		)
	}

	/** What a game's stored cards contain, so the filter list offers nothing that matches nothing. */
	suspend fun searchFacets(game: GameId): StoredFacets = mSetStore.facetsForGame(game)

	/** How many of [knownSets] are held in any language. The denominator of a stored search. */
	private suspend fun storedSetCount(game: GameId, knownSets: List<CardSet>): Int {
		val vProvider = mRegistry.resolve(game) ?: return 0
		return knownSets.count { mSetStore.languagesHeld(vProvider.id, it.id).isNotEmpty() }
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
			val vCached = mSetStore.read(vProvider.id, setId, vLanguage, mClock())
			val vHit = vCached?.cards?.firstOrNull { it.id == id }
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
	): Boolean = mSetStore.languagesHeld(provider.id, set.id).isNotEmpty()

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
	 *
	 * `null` is a member and means the edition held states no language, which for OPTCG and TCGCSV
	 * is the only edition there is. Dropping it reported four games as holding nothing, so the mark
	 * never appeared and the dialog re-offered a download that was already on disk.
	 */
	suspend fun savedLanguages(
		game: GameId,
		sets: List<CardSet>,
		language: CardLanguage? = null,
	): Map<String, Set<CardLanguage?>> {
		val vProvider = mRegistry.resolve(game, language) ?: return emptyMap()
		val vResult = mutableMapOf<String, Set<CardLanguage?>>()
		for (vSet in sets) {
			currentCoroutineContext().ensureActive()
			// The same candidates `isSaved` uses, and for the same reason: a set that states no
			// languages of its own may still hold a copy in one the user does not browse in --
			// which is exactly what a bulk import leaves behind. Every candidate is checked here
			// rather than short-circuited, because the question is *which* editions are held.
			// One query, where this was a file-existence check per candidate language -- up to
			// eleven per set, per row of the set list.
			val vHeld = mSetStore.languagesHeld(vProvider.id, vSet.id)
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
	 * cached **whole**. A set absent from the map has never been fetched whole in that language, and
	 * the caller should keep showing the source's figure -- that is still the only number available,
	 * and an absent entry is not a count of zero.
	 *
	 * A zero here is a measurement and belongs in the map: YGOPRODeck serves `Beyond the Brave` with
	 * no French cards at all, and a row that fell back to the English 8 would print it over an empty
	 * grid. What must not follow from a zero is *hiding the set* -- see `SetListContract.isEmptySet`,
	 * which is where a fetch that came back empty stopped being told apart from a set that has
	 * nothing in it.
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
			val vCount = mSetStore.cardCount(vProvider.id, vSet.id, vLanguage) ?: continue
			vResult[vSet.id.qualified] = vCount
		}
		return vResult
	}

	/**
	 * Which of [sets] are held whole *in the language the row would open in*.
	 *
	 * The question a download button has to answer, and not the same as "is any of this here?" --
	 * a set fetched part-way is saved and is not complete. Per edition, because a set held whole in
	 * English and part-way in French is finished in one and not the other, and the row is about the
	 * one it would open in.
	 *
	 * One query for the game rather than one per set; the per-row work is a map lookup.
	 */
	suspend fun completeSetIds(
		game: GameId,
		sets: List<CardSet>,
		language: CardLanguage? = null,
	): Set<String> {
		val vProvider = mRegistry.resolve(game, language) ?: return emptySet()
		val vComplete = mSetStore.completeSetsForGame(game)
		return sets.mapNotNullTo(mutableSetOf()) { vSet ->
			val vLanguage = effectiveLanguage(vProvider, vSet.languageFor(language))
			val vHeld = vComplete[vSet.id.qualified] ?: return@mapNotNullTo null
			vSet.id.qualified.takeIf { (vLanguage?.code ?: "-") in vHeld }
		}
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
			//
			// Records held under *no* language are dropped here, and only here. This answers a
			// language menu, and a source that names none has not told us the set is in one --
			// listing it would put a language on screen that nothing established. `savedLanguages`
			// keeps the null, because "is this edition already on disk" is a different question.
			val vHeld = mSetStore.languagesHeld(vProvider.id, vSet.id).filterNotNull()
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
	 * The languages [setId] is **known** to exist in, without asking the source anything.
	 *
	 * Facts only, and that is the whole point of it being separate from [knownLanguagesFor]:
	 *
	 * 1. a confirmation record, meaning the source was asked and answered, and
	 * 2. every language whose cards are on disk -- they are there only because the source served
	 *    them, which is the strongest evidence there is.
	 *
	 * A source's *claim* -- per set or per catalogue -- is deliberately not in here. A language
	 * menu built from a claim shows eleven entries and then collapses to four when the
	 * confirmation lands, which is the app telling the user something it did not know. What it
	 * knows is this; what it has merely been told is [knownLanguagesFor].
	 *
	 * Empty means "nothing established yet", never "one language".
	 */
	suspend fun confirmedLanguagesFor(setId: SourceId, game: GameId): Set<CardLanguage> {
		val vProvider = mRegistry.byId(setId.provider) ?: return emptySet()
		val vSerializer = CacheEnvelope.serializer(SetSerializer(serializer<CardLanguage>()))
		val vConfirmed = mCache.read(setLanguagesKey(vProvider, setId), vSerializer)
			?.takeIf { !it.isStale(mClock(), mSetListTtlMillis) }
			?.payload
			.orEmpty()
		val vSet = setRecord(setId, game)
		// The same candidate sweep the set list runs: file-existence checks, no requests.
		//
		// Records held under no language contribute nothing: this is a language menu, and a source
		// that names none has confirmed no language by serving them. See `availableLanguages`.
		return vConfirmed + mSetStore.languagesHeld(vProvider.id, setId).filterNotNull()
	}

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
		if (mSetStore.exists(vProvider.id, setId, vWanted)) return OpeningLanguage(vWanted)

		// 2. Not held in the wanted language, but downloaded in another.
		downloadedLanguageFor(vProvider, setId, vSet, preferred, except = vWanted)
			?.let {
				return OpeningLanguage(it, vWanted, LanguageSubstitution.NOT_DOWNLOADED)
			}

		// A confirmation already on disk answers without asking again, and is what step 4 leaves
		// behind -- so the expensive path is paid at most once per set per TTL.
		val vSerializer = CacheEnvelope.serializer(SetSerializer(serializer<CardLanguage>()))
		mCache.read(setLanguagesKey(vProvider, setId), vSerializer)
			?.takeIf { !it.isStale(mClock(), mSetListTtlMillis) }
			?.payload
			?.let { vConfirmed ->
				// A confirmation that does not list the wanted language is the source having said
				// it has no such edition -- the same answer step 4 pays for, already on disk.
				return substituted(
					opened = vConfirmed.firstOrNull { it == vWanted }
						?: vSet?.copy(languages = vConfirmed)?.languageFor(preferred)
						?: vConfirmed.firstOrNull(),
					wanted = vWanted,
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
		val vConfirmed = languagesFor(setId, game)
		return substituted(
			opened = vSet?.copy(languages = vConfirmed)?.languageFor(preferred)
				?: vConfirmed.firstOrNull(),
			wanted = vWanted,
		)
	}

	/**
	 * A result for the case where the source was *asked* about [wanted] and does not have it.
	 *
	 * Reported as [LanguageSubstitution.NOT_PUBLISHED], never as a missing download: the screen
	 * says the edition does not exist and offers nothing, because there is nothing to fetch. The
	 * silence this replaces was its own small lie -- a French-preferring user opened a Japan-only
	 * set, got Japanese, and was told nothing at all about why.
	 */
	private fun substituted(opened: CardLanguage?, wanted: CardLanguage): OpeningLanguage =
		if (opened == null || opened == wanted) {
			OpeningLanguage(opened)
		} else {
			OpeningLanguage(opened, wanted, LanguageSubstitution.NOT_PUBLISHED)
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
		return vOrdered.firstOrNull { mSetStore.isPinned(provider.id, setId, it) }
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
		val vCached = mSetStore.read(vProvider.id, setId, vLanguage, mClock()) ?: return CardFacets()
		if (!vCached.isComplete) return CardFacets()
		return CardFilterEngine.facetsOf(vCached.cards, vProvider.game.rarityLadder)
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
	private fun dedupePrintings(cards: List<CardPrinting>): List<CardPrinting> =
		dedupedPrintings(cards)

	// ============
	//  Keys

	/**
	 * The language [game]'s records are filed under, for a caller outside this class that has to
	 * key something the same way.
	 *
	 * The image-download record is the one such thing: it lives in preferences rather than in the
	 * store, so the queue writes it and the set list reads it without either going through a cache
	 * key. Both used `ProviderRegistry.effectiveLanguage`, whose `?: language` fallback hands back
	 * the *user's raw preference* for a source that states no language -- OPTCG and TCGCSV. The two
	 * sides agreed with each other and disagreed with the data: the record for One Piece was filed
	 * under whatever language the reader happened to prefer, so changing that preference in
	 * Settings orphaned it and the set offered its pictures for download again.
	 *
	 * `null` here means "nothing to file it under" and is a perfectly good key component -- see
	 * [imageDownloadKey], which spells it `-`. It does **not** mean the language is unknown, and it
	 * must not be replaced by the caller's own preference.
	 */
	fun storageLanguageFor(game: GameId, language: CardLanguage?): CardLanguage? =
		mRegistry.resolve(game, language)?.let { effectiveLanguage(it, language) }

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
	): CardLanguage? = resolvedLanguage(provider, requested)

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
	 * Imports a provider's whole catalogue from its bulk file. See [BulkImporter].
	 *
	 * Delegated rather than inlined: the import owns a scratch directory and sixty-four shard
	 * files, and that lifecycle is the only one in this class that is not "read a thing, cache a
	 * thing".
	 */
	suspend fun importBulk(
		game: GameId,
		variantId: String? = null,
		language: CardLanguage? = null,
		onProgress: (BulkImportProgress) -> Unit = {},
	): BulkImportResult? = mBulk.importBulk(game, variantId, language, onProgress)

	/** What dumps a game's source publishes, or empty where it publishes none. */
	suspend fun bulkVariants(game: GameId, language: CardLanguage? = null): List<BulkSummary> =
		mBulk.bulkVariants(game, language)


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
		val vStored = mSetStore.storedSets()
		return mSetStore.storedByGame().mapNotNull { vRow ->
			currentCoroutineContext().ensureActive()
			val vGame = GameId(vRow.game)
			val vProvider = mRegistry.resolve(vGame) ?: return@mapNotNull null
			// How many sets the game has, when its catalogue is still cached. Absent is a real
			// answer, and the screen renders it as no denominator rather than inventing one.
			val vCatalogue = setListOnDisk(vProvider, vGame, vSerializer)
			val vCatalogueIds = vCatalogue?.mapTo(mutableSetOf()) { it.id.qualified }
			val vMine = vStored.filter { it.game == vRow.game }
			val vHeldIds = vMine.mapTo(mutableSetOf()) { it.setId }
			GameStorage(
				game = vGame,
				downloadedSets = vMine.filter { it.isPinned }.map { it.setId }.distinct().size,
				completion = completionOf(vMine, vCatalogue),
				// Only the sets the catalogue lists, so the numerator and `knownSets` count the
				// same population. A bulk file does not: Scryfall's dump carries cards for sets
				// `listSets` filters out -- digital-only Alchemy and MTGO products, and anything
				// the source states holds no cards -- so an import of Magic pins 1044 set ids
				// against a catalogue of 988 and the row read "Card info 1044/988". The extras are
				// real and their bytes are counted; they are simply not part of "how much of this
				// game do I have", because they are not offered to browse.
				sets = if (vCatalogueIds == null) vRow.sets else vHeldIds.count { it in vCatalogueIds },
				extraSets = if (vCatalogueIds == null) 0 else vHeldIds.count { it !in vCatalogueIds },
				// How many *sets* each language covers, not merely which languages appear. One
				// Spanish set among a thousand English ones is a fact about the file, not a second
				// edition of the game, and a bare "11 languages" said the opposite: an
				// English-only import of Magic reads 11, because Scryfall's cheap dump carries the
				// handful of cards that have no English printing at all.
				languages = vMine
					.mapNotNull { vSet -> CardLanguage.fromCode(vSet.language)?.to(vSet.setId) }
					.groupBy({ it.first }, { it.second })
					.mapValues { (_, vSets) -> vSets.distinct().size },
				bytes = vRow.bytes,
				knownSets = vCatalogueIds?.size,
			)
		}
	}

	/**
	 * How much of a game is on the device, as a fraction of its cards.
	 *
	 * ## Cards rather than sets, and why the denominator has to be estimated
	 *
	 * Sets would be the easy answer and the wrong one: holding 8 of 10 sets is not 80% of a game
	 * when the two missing ones are the largest. So this counts cards -- but the total needs every
	 * set's size, and a set list states that for most sources and not all. OPTCG states none.
	 *
	 * The gap is filled with the mean of the sizes that *are* stated, and the result is marked
	 * [Completion.isEstimate] so the screen can draw it as an approximation rather than a count.
	 * Where nothing at all is stated, the held sets themselves supply the mean -- a complete set is
	 * a real measurement of one set's size, which is the best evidence available about the rest.
	 * Where there is not even that, the answer is null and the screen says nothing.
	 *
	 * The numerator counts each set once, taking the fullest edition held. A set downloaded in
	 * English and French is one set's worth of the game, not two -- the same slash trap as
	 * everywhere else here.
	 */
	private fun completionOf(held: List<StoredSetRow>, catalogue: List<CardSet>?): Completion? {
		if (catalogue.isNullOrEmpty()) return null

		// The fullest edition of each set. A partial French copy of a set held whole in English
		// says nothing extra about how much of the game is here.
		val vBySet = held.groupBy { it.setId }
		val vHeldBySet = vBySet.mapValues { (_, vRows) -> vRows.maxOf { it.cardCount } }
		val vCompleteSets = vBySet.filterValues { vRows -> vRows.any { it.isComplete } }.keys
		val vHeldCards = vHeldBySet.values.sum()

		val vStatedSizes = catalogue.mapNotNull { it.cardCount?.takeIf { vCount -> vCount > 0 } }
		// Failing that, what complete sets on disk actually turned out to hold.
		val vMeasuredSizes = held.filter { it.isComplete && it.cardCount > 0 }.map { it.cardCount }
		val vSample = vStatedSizes.ifEmpty { vMeasuredSizes }
		if (vSample.isEmpty()) return null

		val vMean = vSample.sum() / vSample.size
		var vGuessedSets = 0
		val vTotal = catalogue.sumOf { vSet ->
			val vId = vSet.id.qualified
			val vStated = vSet.cardCount
			when {
				// A set the source served every page of contributes exactly what it turned out to
				// hold, on both sides of the ratio. Otherwise a game whose every set is complete
				// could still read 97% -- against a set list that counts variants differently, or
				// one that has gone stale -- while each of its sets reads 100% on the screen one
				// tap away. The two rules have to agree, and this is the one that is measured.
				vId in vCompleteSets -> vHeldBySet.getValue(vId).toLong()
				vStated != null && vStated > 0 -> vStated.toLong()
				else -> {
					vGuessedSets++
					vMean.toLong()
				}
			}
		}
		if (vTotal <= 0L) return null

		return Completion(
			heldCards = vHeldCards,
			totalCards = vTotal.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
			// A guess only where a set had to be guessed at. A complete set is measured and a
			// stated one is quoted; neither is an estimate.
			isEstimate = vGuessedSets > 0,
		)
	}

	/**
	 * Deletes everything [game] is keeping, and stops keeping it.
	 *
	 * Unpinned as well as removed, so a record that survives -- because it is also the set the
	 * user is currently reading, say -- goes back to being ordinary browsing data rather than
	 * staying exempt from the ceiling forever.
	 */
	suspend fun deleteKept(game: GameId): Int = mSetStore.deleteDownloaded(game)

	/**
	 * Everything one game has downloaded, one entry per set per language.
	 *
	 * The breakdown behind a storage row, so a reader can drop one language or one set instead of
	 * the whole game. Sets the cached catalogue does not list are marked rather than hidden: a bulk
	 * import brings them, they take up real space, and a screen that cannot show them is a screen
	 * whose total does not add up.
	 */
	suspend fun keptSets(game: GameId): List<KeptSet> {
		val vSerializer = CacheEnvelope.serializer(ListSerializer(serializer<CardSet>()))
		val vCatalogue = mRegistry.resolve(game)?.let { setListOnDisk(it, game, vSerializer) }
		// The size each set states, for the per-set percentage. Absent for a source that states
		// none, and absent is rendered as no percentage rather than as zero.
		val vKnownSizes = vCatalogue
			?.associate { it.id.qualified to it.cardCount?.takeIf { vCount -> vCount > 0 } }
		val vCodes = vCatalogue?.associate { it.id.qualified to it.code }
		return mSetStore.storedSetsFor(game).map { vSet ->
			KeptSet(
				provider = vSet.provider,
				setId = vSet.setId,
				languageCode = vSet.language,
				label = vSet.label,
				// The catalogue's code where it is on disk; otherwise the local half of the id,
				// which is the printed code for most sources. Blank means the source has neither.
				code = (vCodes?.get(vSet.setId) ?: vSet.setId.substringAfterLast(':'))
					.takeIf { it.isNotBlank() },
				cardCount = vSet.cardCount,
				bytes = vSet.bytes,
				// No catalogue on disk is not evidence a set is absent from it, so everything is
				// left unmarked rather than marked as an extra.
				isInCatalogue = vKnownSizes == null || vSet.setId in vKnownSizes,
				isDownloaded = vSet.isPinned,
				isComplete = vSet.isComplete,
				knownCardCount = vKnownSizes?.get(vSet.setId),
			)
		}
	}

	/** Deletes one downloaded edition. Returns true when there was one to delete. */
	suspend fun deleteKeptSet(set: KeptSet): Boolean =
		mSetStore.deleteDownloadedSet(set.provider, set.setId, set.languageCode)

	/**
	 * Deletes every edition of [game] stored under [languageCode].
	 *
	 * @return how many went
	 */
	suspend fun deleteKeptLanguage(game: GameId, languageCode: String): Int =
		keptSets(game)
			.filter { it.languageCode == languageCode }
			.count { deleteKeptSet(it) }

	/**
	 * A game's catalogue if one is already on disk, in the language it would open in.
	 *
	 * **No network, ever.** `null` means nothing is cached, which is a different answer from an
	 * empty catalogue and is why this does not return an empty list. What it is for is warming the
	 * facts a set list states about what is held, before that list is opened -- see
	 * `LocalSetFacts`.
	 */
	suspend fun cachedSetList(game: GameId, language: CardLanguage? = null): List<CardSet>? {
		val vProvider = mRegistry.resolve(game, language) ?: return null
		val vSerializer = CacheEnvelope.serializer(ListSerializer(serializer<CardSet>()))
		val vKey = setListKey(vProvider, game, effectiveLanguage(vProvider, language))
		return mCache.read(vKey, vSerializer)?.payload?.takeIf { it.isNotEmpty() }
	}

	/**
	 * Everything a set list can say about what is held, in one lookup and with no requests.
	 *
	 * The five calls below are each answerable from disk. Gathered here so the set list and the
	 * warm-up that runs ahead of it ask exactly the same question -- two callers computing "is this
	 * downloaded" separately is how the two would come to disagree.
	 */
	suspend fun localSetFacts(
		game: GameId,
		sets: List<CardSet>,
		language: CardLanguage? = null,
	): LocalSetFacts = LocalSetFacts(
		savedSetIds = savedSetIds(game, sets, language),
		savedLanguages = savedLanguages(game, sets, language),
		confirmedCardCounts = confirmedCardCounts(game, sets, language),
		completeSetIds = completeSetIds(game, sets, language),
		availableLanguages = availableLanguages(game, sets, language),
	)

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
		mSetStore.setPinned(
			provider = vProvider.id,
			setId = setId,
			language = effectiveLanguage(vProvider, language),
			game = game,
			label = setLabel(vProvider, setId),
			isPinned = isPinned,
		)
	}

	/**
	 * What to call a cached set on a storage screen.
	 *
	 * A *name* now, not an encoded tuple. This used to be `game	setId	language` smuggled through
	 * a pin marker's contents, because a hashed filename says nothing about what it holds and the
	 * storage screen had to split it back apart -- two places parsing one string, one edit from
	 * disagreeing. Those three fields are columns; this is only the label.
	 *
	 * Falls back to the set's own id when its catalogue is not cached, which is the honest answer:
	 * the set list is ordinary browsing data, and clearing it must not leave a downloaded set
	 * anonymous.
	 */
	private suspend fun setLabel(provider: CardProvider<GameProfile>, setId: SourceId): String =
		setRecord(setId, provider.game.id)?.name ?: setId.local

	companion object {

		/** Scratch directory for a bulk import's shards. Deleted when the import ends. */
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
	/**
	 * Cards left out because their set is not in the catalogue, and how many sets that was.
	 *
	 * Reported rather than passed over in silence. The skip is deliberate -- a set the app cannot
	 * list is a set nothing can open, so keeping its cards costs disk for a row that will never
	 * exist -- but "most of the file was discarded" and "the file was imported" must not look the
	 * same from the outside. For Scryfall this is Arena and MTGO products.
	 */
	val skippedCards: Int = 0,
	val skippedSets: Int = 0,
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
