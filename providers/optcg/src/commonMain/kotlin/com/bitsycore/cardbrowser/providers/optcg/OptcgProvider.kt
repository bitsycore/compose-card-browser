package com.bitsycore.cardbrowser.providers.optcg

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
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
import com.bitsycore.cardbrowser.data.net.mapProviderErrors
import com.bitsycore.cardbrowser.games.onepiece.OnePieceGame
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments

/**
 * The OPTCG API adapter, serving [OnePieceGame].
 *
 * https://optcgapi.com -- a community database for the One Piece Card Game. No key, no auth.
 *
 * ## Coverage, as verified against the live API on 2026-09-08
 *
 * - **Languages**: none stated. The data is plainly English, and each record says so with
 *   `isProviderStated = false` -- an observation about the text rather than a claim the source
 *   made. Japanese printings certainly exist for this game; this source does not carry them, which
 *   is why Japanese is UNKNOWN here and never *absent*.
 * - **Sets**: 18, from one 1.2 KB request, carrying a name and a code and nothing else. No release
 *   dates and no card counts, so both are null and the set list orders by code.
 * - **Cards**: `GET /api/sets/{setId}/` returns the whole set unpaged -- 154 cards for `OP-01` --
 *   so, like TCGdex, this adapter never pages and completeness is definite rather than inferred.
 * - **Finishes and artwork variants**: no fields exist. Both are left unstated.
 * - **Card identity**: none.
 * - **Images**: one JPEG per card at a fixed size, with no CDN resizing and no small variant. The
 *   grid therefore loads the full image. That is a real cost and it is declared rather than hidden
 *   by pointing a "thumbnail" at the same file.
 * - **Prices**: `market_price` and `inventory_price` are present and deliberately not mapped. They
 *   carry no currency and no source, and this is a card browser rather than a price guide.
 * - **Cross-set search**: `GET /api/sets/filtered/?card_name={text}`, a case-insensitive substring
 *   match. Unpaged, so this adapter takes the first page's worth and says there may be more.
 *
 * Coverage note: only the numbered expansion sets are served. The API has separate endpoints for
 * starter decks, promos and Don!! cards, each with a different shape, and folding them into the set
 * list would put entries there that the set endpoint cannot then serve cards for.
 */
class OptcgProvider(
	private val mClient: HttpClient,
	private val mBaseUrl: String = DEFAULT_BASE_URL,
) : CardProvider<OnePieceGame> {

	override val id: ProviderId = PROVIDER_ID

	override val displayName: String = "OPTCG API"

	override val game: OnePieceGame = OnePieceGame

	override val capabilities: ProviderCapabilities = ProviderCapabilities(
		filtering = FilterSupport(
			// A set arrives whole in one request, so every filter is applied to data already held.
			remote = emptySet(),
			localOnly = setOf(
				CardFilterField.TEXT,
				CardFilterField.DOMAIN,
				CardFilterField.CARD_TYPE,
				CardFilterField.RARITY,
				CardFilterField.COST,
			),
		),
		sorting = setOf(
			CardSortField.COLLECTOR_NUMBER,
			CardSortField.NAME,
			CardSortField.RARITY,
			CardSortField.COST,
		),
		data = DataCapabilities(
			// Empty, not `setOf(ENGLISH)`. The provider states no language anywhere, and this field
			// is what the provider can *describe*, not what a reader can infer from the text.
			languages = emptySet(),
			localizedText = false,
			localizedImages = false,
			cardIdentity = false,
			artworkVariants = false,
			finishes = false,
			cardmarketProductMapping = false,
			crossSetSearch = true,
		),
		attribution = Attribution(
			text = "One Piece card data from the OPTCG API, a community project not affiliated " +
				"with Bandai or Eiichiro Oda.",
			url = "https://optcgapi.com/",
		),
		maxPageSize = MAX_PAGE_SIZE,
	)

	// ============
	//  Sets

	override suspend fun listSets(language: CardLanguage?): List<CardSet> {
		// `language` is accepted and ignored on purpose. This source states no language at all --
		// `DataCapabilities.languages` is empty -- so there is no per-language catalogue to select
		// and nothing here may claim one. Silence is not a claim of English; see the class doc.
		return mapProviderErrors("OPTCG.listSets") {
			val vSets: List<OptcgSetDto> = mClient
				.get(mBaseUrl) { url { appendPathSegments("api", "allSets", "") } }
				.body()
			vSets.mapNotNull { OptcgMapper.toSet(it, id) }
		}
	}

	// ============
	//  Cards

	/** The whole set in one request, so page 2 is always empty and says so. */
	override suspend fun listCards(request: CardPageRequest): CardPage {
		if (request.page > 1) {
			return CardPage(emptyList(), request.page, request.pageSize, totalCount = null, hasMore = false)
		}
		return mapProviderErrors("OPTCG.listCards") {
			val vCards: List<OptcgCardDto> = mClient
				.get(mBaseUrl) { url { appendPathSegments("api", "sets", request.setId.local, "") } }
				.body()
			val vMapped = vCards.mapNotNull { OptcgMapper.toPrinting(it, id, set = null) }
			CardPage(
				cards = vMapped,
				page = 1,
				pageSize = vMapped.size.coerceAtLeast(1),
				totalCount = vMapped.size,
				hasMore = false,
			)
		}
	}

	override suspend fun cardDetail(id: SourceId, language: CardLanguage?): CardPrinting? =
		mapProviderErrors("OPTCG.cardDetail") {
			try {
				val vCards: List<OptcgCardDto> = mClient
					.get(mBaseUrl) { url { appendPathSegments("api", "sets", "card", id.local, "") } }
					.body()
				// The endpoint returns an array even for a single card.
				vCards.firstOrNull()?.let { OptcgMapper.toPrinting(it, this.id, set = null) }
			} catch (vError: ClientRequestException) {
				if (vError.response.status == HttpStatusCode.NotFound) null else throw vError
			}
		}

	/**
	 * Name search across every expansion.
	 *
	 * `/api/sets/filtered/` has no paging of its own, so the whole match list arrives at once and is
	 * trimmed here. `hasMore` is set from whether the trim actually removed anything, which is a
	 * fact about the response rather than a guess.
	 */
	override suspend fun searchAllSets(request: CardSearchRequest): CardPage {
		return mapProviderErrors("OPTCG.searchAllSets") {
			val vCards: List<OptcgCardDto> = try {
				mClient
					.get(mBaseUrl) {
						url { appendPathSegments("api", "sets", "filtered", "") }
						parameter("card_name", request.text)
					}
					.body()
			} catch (vError: ClientRequestException) {
				// A search matching nothing answers 404 rather than with an empty array.
				if (vError.response.status == HttpStatusCode.NotFound) emptyList() else throw vError
			}
			val vMapped = vCards.mapNotNull { OptcgMapper.toPrinting(it, id, set = null) }
			val vFrom = (request.page - 1) * request.pageSize
			val vWindow = vMapped.drop(vFrom).take(request.pageSize)
			CardPage(
				cards = vWindow,
				page = request.page,
				pageSize = request.pageSize,
				totalCount = vMapped.size,
				hasMore = vFrom + vWindow.size < vMapped.size,
			)
		}
	}

	companion object {

		/** Never changed: it is written into every id and every cache file this adapter produces. */
		val PROVIDER_ID: ProviderId = ProviderId("optcg")

		const val DEFAULT_BASE_URL: String = "https://optcgapi.com"

		/**
		 * Larger than the largest set the API serves.
		 *
		 * Not a server limit -- there is no paging. This tells the repository that one request is
		 * always enough, so it never plans a second page that would come back empty.
		 */
		const val MAX_PAGE_SIZE: Int = 1000
	}
}
