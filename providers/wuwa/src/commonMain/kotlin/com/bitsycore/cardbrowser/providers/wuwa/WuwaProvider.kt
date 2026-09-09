package com.bitsycore.cardbrowser.providers.wuwa

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.Attribution
import com.bitsycore.cardbrowser.core.provider.CardFilterField
import com.bitsycore.cardbrowser.core.provider.CardPage
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import com.bitsycore.cardbrowser.core.provider.CardProvider
import com.bitsycore.cardbrowser.core.provider.CardSearchRequest
import com.bitsycore.cardbrowser.core.provider.CardSortField
import com.bitsycore.cardbrowser.core.provider.DataCapabilities
import com.bitsycore.cardbrowser.core.provider.FilterSupport
import com.bitsycore.cardbrowser.core.provider.ProviderCapabilities
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.data.net.mapProviderErrors
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.appendPathSegments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The UCP adapter, serving [Game.WUTHERING_WAVES].
 *
 * ## How this was found
 *
 * There is no published API and no documentation. `https://wwcg.ucp-jp.com/jp/card` is a Vue
 * application; its bundle declares an axios instance with
 * `baseURL: "https://mc-api.ucp-jp.com/api/"` and a request interceptor that sets an **`x-lang`**
 * header, and names three card endpoints: `/web/card/list`, `/web/card/info` and
 * `/web/card/search-options`.
 *
 * So this is an undocumented internal endpoint belonging to a game that launched recently. Unlike
 * every other source in this app there is no stability promise behind it, and it may change or
 * close without notice. That is a real risk and it is stated rather than glossed over.
 *
 * ## Coverage, as verified on 2026-09-08
 *
 * - **Languages**: Japanese, which is the app's second preference. Measured, not assumed --
 *   `x-lang: ja-jp` and `zh-cn` each return 123 cards while `en-us` and `zh-tw` return **200 OK
 *   with an empty list**. That last shape is exactly what the repository's completeness guard
 *   exists to catch, and it is why this adapter only ever asks for `ja-jp`.
 * - **Sets**: **none.** The API has no set entity and no endpoint that lists one. Sets here are
 *   derived from the prefix of each card's printed code -- `SD01`, `SD02`, `BP01` -- and are named
 *   after that code. See `WuwaMapper.toSet` for why the product names from `/web/goods/list` are
 *   not used.
 * - **Cards**: 123 in total, all of which arrive in a single `size=200` request, so a set costs one
 *   request and completeness is definite.
 *
 *   The list carries only six fields per card, so each set's cards are then enriched one request
 *   each from `/web/card/info` -- see [detailsFor] for what that costs and why the provider's own
 *   filters cannot do the job more cheaply.
 * - **Filters**: complete, because of that enrichment. Rarity, attribute, cost, level, weapon and
 *   faction all reach the grid, where before only card type did.
 * - **Images**: already WebP and already compressed, on a Tencent COS bucket. One size only.
 * - **Finishes, artwork variants, card identity, Cardmarket**: no fields, all unstated. Cardmarket
 *   has no section for this game at all -- it is Japan-only so far.
 * - **Cross-set search**: `?keyword=` on the same list endpoint.
 */
class WuwaProvider(
	private val mClient: HttpClient,
	private val mBaseUrl: String = DEFAULT_BASE_URL,
) : CardProvider {

	override val id: ProviderId = PROVIDER_ID

	override val displayName: String = "UCP Wuthering Waves TCG"

	override val capabilities: ProviderCapabilities = ProviderCapabilities(
		games = setOf(Game.WUTHERING_WAVES),
		filtering = FilterSupport(
			// The endpoint does accept `type_id`, `rarity_id` and the rest, but they take numeric
			// ids from `/web/card/search-options` rather than the names the rest of the app filters
			// on. Mapping a display name back to an id would be a guess at a table the app does not
			// own, and the whole catalogue is 123 cards held in memory anyway.
			remote = emptySet(),
			localOnly = setOf(
				CardFilterField.TEXT,
				CardFilterField.CARD_TYPE,
				CardFilterField.DOMAIN,
				CardFilterField.RARITY,
			),
		),
		sorting = setOf(CardSortField.COLLECTOR_NUMBER, CardSortField.NAME),
		data = DataCapabilities(
			// Japanese only. Not "Japanese is all that exists" -- Simplified Chinese is served too
			// and is simply not one of this app's four -- but Japanese is all this app can ask for.
			languages = setOf(CardLanguage.JAPANESE),
			localizedText = true,
			localizedImages = true,
			cardIdentity = false,
			artworkVariants = false,
			finishes = false,
			cardmarketProductMapping = false,
			crossSetSearch = true,
		),
		attribution = Attribution(
			text = "Wuthering Waves TCG card data from UCP's official card list. Not affiliated " +
				"with UCP or Kuro Games.",
			url = "https://wwcg.ucp-jp.com/jp/card",
		),
		maxPageSize = MAX_PAGE_SIZE,
	)

	// ============
	//  Sets

	/**
	 * Sets, derived from the codes of every card.
	 *
	 * One request. There is no set endpoint to call, so the whole catalogue is fetched and grouped
	 * by code prefix -- which is cheap here precisely because the catalogue is 123 cards, and would
	 * be the wrong approach for any of the other providers in this app.
	 */
	override suspend fun listSets(game: Game, language: CardLanguage?): List<CardSet> {
		require(game == Game.WUTHERING_WAVES) {
			"This adapter serves Wuthering Waves TCG only, not $game"
		}
		return mapProviderErrors("Wuwa.listSets") {
			allCards()
				.groupBy { WuwaMapper.setCodeOf(it.code) }
				.mapNotNull { (vCode, vCards) ->
					vCode?.let { WuwaMapper.toSet(it, vCards.size, id) }
				}
				.sortedBy { it.code }
		}
	}

	// ============
	//  Cards

	/**
	 * The cards of one set.
	 *
	 * The endpoint has no set parameter -- there are no sets on its side -- so the full catalogue
	 * is fetched and filtered by code prefix here. 123 records in one request makes that the
	 * cheaper option by a wide margin over anything per-card.
	 */
	override suspend fun listCards(request: CardPageRequest): CardPage {
		if (request.page > 1) {
			return CardPage(emptyList(), request.page, request.pageSize, totalCount = null, hasMore = false)
		}
		val vSetCode = request.setId.local
		return mapProviderErrors("Wuwa.listCards") {
			val vSetCards = allCards().filter { WuwaMapper.setCodeOf(it.code) == vSetCode }
			val vSet = WuwaMapper.toSet(vSetCode, vSetCards.size, id)
			val vDetails = detailsFor(vSetCards)

			val vMapped = vSetCards.mapNotNull { vBrief ->
				// The detail record where it arrived, the six-field brief where it did not. A card
				// whose detail request failed is still shown, just with less on it -- losing it
				// entirely would make the set look short for no reason the user could see.
				vDetails[vBrief.id]?.let { WuwaMapper.toPrinting(it, id, vSet, LANGUAGE) }
					?: WuwaMapper.toPrinting(vBrief, id, vSet, LANGUAGE)
			}
			CardPage(
				cards = vMapped,
				page = 1,
				pageSize = vMapped.size.coerceAtLeast(1),
				totalCount = vMapped.size,
				hasMore = false,
			)
		}
	}

	/**
	 * Full records for a set's cards, fetched one request each.
	 *
	 * ## Why this is worth 25 to 74 extra requests
	 *
	 * `/web/card/list` returns six fields. Rarity, attribute, cost, level, weapon, faction and the
	 * rules text exist **only** on `/web/card/info`, one card at a time -- so without this the grid
	 * has names and pictures, the filter sheet has almost no facets to offer, and sorting by cost
	 * does nothing.
	 *
	 * The obvious alternative was to use the provider's own filters to tag cards in bulk, which
	 * would have been far cheaper. It does not work, and this was established by crawling the whole
	 * catalogue and comparing: `rarity_id` is accepted and silently ignored under every spelling
	 * tried, and `fee=0` and `level=0` mean "no filter" rather than "costs zero" -- so the 31 cards
	 * that genuinely cost 0 would be indistinguishable from the 67 that have no cost at all.
	 *
	 * ## Why the cost is acceptable
	 *
	 * Per *set*, not per catalogue, because that is the unit the repository caches. The largest set
	 * is 74 cards and the two starter decks are about 25 each, against 123 for everything. At
	 * [MAX_CONCURRENT_DETAILS] in flight that is roughly 4 seconds for a starter deck and 13 for the
	 * booster set, once, and then the complete set is on disk for a day and every later open is
	 * instant and works offline.
	 *
	 * Four at a time rather than as fast as possible: this is an undocumented endpoint belonging to
	 * someone else, and a burst of 74 simultaneous connections is not a reasonable way to treat it.
	 */
	private suspend fun detailsFor(cards: List<WuwaCardBriefDto>): Map<Long, WuwaCardDetailDto> {
		if (cards.isEmpty()) return emptyMap()
		val vResults = mutableMapOf<Long, WuwaCardDetailDto>()

		for (vBatch in cards.chunked(MAX_CONCURRENT_DETAILS)) {
			currentCoroutineContext().ensureActive()
			coroutineScope {
				val vPending = vBatch.map { vCard ->
					vCard.id to async {
						runCatching {
							unwrap(
								mClient
									.get(mBaseUrl) {
										url { appendPathSegments("api", "web", "card", "info") }
										parameter("id", vCard.id)
										localise()
									}
									.body<WuwaEnvelopeDto<WuwaCardDetailDto>>(),
							)
						}
					}
				}
				for ((vId, vDeferred) in vPending) {
					val vDetail = vDeferred.await().getOrElse {
						// Cancellation must unwind rather than be recorded as a missing card.
						if (it is CancellationException) throw it
						null
					}
					if (vDetail != null) vResults[vId] = vDetail
				}
			}
		}
		return vResults
	}

	/**
	 * One card, with everything the list does not carry.
	 *
	 * A direct lookup: [SourceId.local] *is* the API's numeric id, which is what `/web/card/info`
	 * takes, so no catalogue scan is needed to translate one into the other.
	 */
	override suspend fun cardDetail(id: SourceId, language: CardLanguage?): CardPrinting? =
		mapProviderErrors("Wuwa.cardDetail") {
			val vNumericId = id.local.toLongOrNull() ?: return@mapProviderErrors null
			val vDetail: WuwaCardDetailDto = unwrap(
				mClient
					.get(mBaseUrl) {
						url { appendPathSegments("api", "web", "card", "info") }
						parameter("id", vNumericId)
						localise()
					}
					.body<WuwaEnvelopeDto<WuwaCardDetailDto>>(),
			) ?: return@mapProviderErrors null
			WuwaMapper.toPrinting(vDetail, this.id, set = null, language = LANGUAGE)
		}

	override suspend fun searchAllSets(request: CardSearchRequest): CardPage {
		require(request.game == Game.WUTHERING_WAVES) {
			"This adapter serves Wuthering Waves TCG only, not ${request.game}"
		}
		return mapProviderErrors("Wuwa.searchAllSets") {
			val vPage = fetchPage(page = request.page, size = request.pageSize) {
				parameter("keyword", request.text)
			}
			val vCards = vPage.list.mapNotNull { vBrief ->
				val vSetCode = WuwaMapper.setCodeOf(vBrief.code) ?: return@mapNotNull null
				WuwaMapper.toPrinting(vBrief, id, WuwaMapper.toSet(vSetCode, 0, id), LANGUAGE)
			}
			CardPage(
				cards = vCards,
				page = vPage.currentPage,
				pageSize = request.pageSize,
				totalCount = vPage.total,
				hasMore = vPage.hasMore,
			)
		}
	}

	// ============
	//  Transport

	/** Every card, in one request. The catalogue is small enough that this is the cheap path. */
	private suspend fun allCards(): List<WuwaCardBriefDto> =
		fetchPage(page = 1, size = MAX_PAGE_SIZE) {}.list

	/**
	 * One page of `/web/card/list`.
	 *
	 * `size` is the page-size parameter, established by trying the plausible names against the live
	 * endpoint: `limit`, `page_size`, `pageSize`, `per_page` and `num` are all accepted and all
	 * silently ignored, leaving `per_page` at 20. Only `size` changes it.
	 */
	private suspend fun fetchPage(
		page: Int,
		size: Int,
		selector: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
	): WuwaCardListDto {
		val vEnvelope: WuwaEnvelopeDto<WuwaCardListDto> = mClient
			.get(mBaseUrl) {
				url { appendPathSegments("api", "web", "card", "list") }
				parameter("page", page)
				parameter("size", size)
				selector()
				localise()
			}
			.body()
		return unwrap(vEnvelope) ?: WuwaCardListDto()
	}

	/**
	 * Reads the payload out of the envelope, failing loudly when the API says it failed.
	 *
	 * The transport status is not the whole story here: this API answers `200 OK` with `code` set
	 * to something other than 1. Trusting the HTTP status alone would turn an application-level
	 * error into a silently empty card list.
	 */
	private fun <T> unwrap(envelope: WuwaEnvelopeDto<T>): T? {
		if (envelope.code != SUCCESS_CODE) {
			throw ProviderError.MalformedResponse(
				"UCP answered code ${envelope.code}: ${envelope.msg ?: "no message"}",
			)
		}
		return envelope.data
	}

	/**
	 * Sets the locale header the site's own client sets.
	 *
	 * Always `ja-jp`. `en-us` is accepted and answers with an empty list rather than an error,
	 * which would look exactly like a game with no cards in it.
	 */
	private fun io.ktor.client.request.HttpRequestBuilder.localise() {
		header("x-lang", LOCALE)
		header("Accept", "application/json")
	}

	companion object {

		/** Never changed: it is written into every id and every cache file this adapter produces. */
		val PROVIDER_ID: ProviderId = ProviderId("ucp-wuwa")

		const val DEFAULT_BASE_URL: String = "https://mc-api.ucp-jp.com"

		/** The only locale that returns cards. See [localise]. */
		private const val LOCALE = "ja-jp"

		/** The only language this provider can be asked for, so it is a constant rather than a lookup. */
		private val LANGUAGE = CardLanguage.JAPANESE

		/** The envelope's success value. Not an HTTP status. */
		private const val SUCCESS_CODE = 1

		/**
		 * How many card-detail requests may be in flight at once.
		 *
		 * Matches the repository's own page concurrency. This is an undocumented endpoint on
		 * somebody else's server and a burst of 74 connections is not a polite way to use it.
		 */
		private const val MAX_CONCURRENT_DETAILS = 4

		/**
		 * Comfortably above the 123 cards that exist, so the whole catalogue is one request.
		 *
		 * `size=200` was confirmed to be honoured -- `per_page` came back as 200 -- where every
		 * other spelling of the parameter was ignored.
		 */
		const val MAX_PAGE_SIZE: Int = 200
	}
}
