package com.bitsycore.cardbrowser.providers.riftcodex

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
import com.bitsycore.cardbrowser.core.provider.CardSortField
import com.bitsycore.cardbrowser.core.provider.DataCapabilities
import com.bitsycore.cardbrowser.core.provider.FilterSupport
import com.bitsycore.cardbrowser.core.provider.ProviderCapabilities
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.core.provider.SortDirection
import com.bitsycore.cardbrowser.data.net.mapProviderErrors
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments

/**
 * The Riftcodex adapter.
 *
 * Riftcodex (https://riftcodex.com) is an unofficial community database for Riftbound. It is the
 * authoritative route for [Game.RIFTBOUND] in this app.
 *
 * ## Coverage, as verified against the live API
 *
 * Checked against the OpenAPI document (version 0.2.0) and against real responses for every set it
 * serves. What it gives and what it does not:
 *
 * - **Sets**: eight, with names, codes, card counts, publication dates and Cardmarket *expansion*
 *   ids. Complete and reliable.
 * - **Cards**: name, collector number, energy/might/power, type, supertype, rarity, domains, tags,
 *   rules text, flavour text, artist, orientation and one image URL.
 * - **Languages**: none. There is no language field anywhere in the schema. The data is English.
 *   French, Japanese and Korean are therefore reported as UNKNOWN, never as unavailable.
 * - **Finishes**: none. No finish field exists, so finish coverage is unstated for every printing.
 * - **Artwork variants**: three booleans -- `alternate_art`, `overnumbered`, `signature`.
 * - **Card identity**: none. Every record is one printing and nothing links two of them.
 * - **Cardmarket**: expansion ids on sets only; no per-card product ids.
 * - **Remote filtering**: set, free text and a "new cards" flag, and that is all. Domain, type,
 *   rarity and energy have no query parameter, so they are [FilterSupport.localOnly] and the
 *   repository must hold a complete set before it can honour them.
 * - **Paging**: 1-based `page` plus `size`, capped at 100 by the server (a `size` of 200 is
 *   rejected with 422). A 352-card set is four requests.
 * - **Rate limits**: none documented and none observed in the response headers. The shared HTTP
 *   stack still applies a small concurrency limit and bounded retries rather than assuming there is
 *   no ceiling.
 * - **Auth**: none required for reads.
 */
class RiftcodexProvider(
	private val mClient: HttpClient,
	private val mBaseUrl: String = DEFAULT_BASE_URL,
) : CardProvider {

	override val id: ProviderId = PROVIDER_ID

	override val displayName: String = "Riftcodex"

	override val capabilities: ProviderCapabilities = ProviderCapabilities(
		games = setOf(Game.RIFTBOUND),
		filtering = FilterSupport(
			// `query` is a full-text search the server runs, and `set_id` scopes it.
			remote = setOf(CardFilterField.TEXT),
			// No query parameter exists for any of these. Honouring one means fetching every page
			// of the set and filtering in the app, which the repository is told to do by this very
			// declaration rather than by a hardcoded assumption about Riftcodex.
			localOnly = setOf(
				CardFilterField.DOMAIN,
				CardFilterField.CARD_TYPE,
				CardFilterField.RARITY,
				CardFilterField.ENERGY_COST,
				CardFilterField.ARTWORK_TREATMENT,
			),
			// FINISH and LANGUAGE appear in neither set: this provider cannot filter on them at
			// all, so the UI does not offer them for Riftbound.
		),
		sorting = setOf(
			CardSortField.COLLECTOR_NUMBER,
			CardSortField.NAME,
			CardSortField.RARITY,
			CardSortField.ENERGY_COST,
		),
		data = DataCapabilities(
			// Not "English is all that exists" -- "English is all this provider describes".
			languages = setOf(CardLanguage.ENGLISH),
			localizedText = false,
			localizedImages = false,
			cardIdentity = false,
			artworkVariants = true,
			finishes = false,
			cardmarketProductMapping = false,
		),
		attribution = Attribution(
			text = "Card data from Riftcodex, an unofficial fan project not affiliated with Riot Games.",
			url = "https://riftcodex.com/",
		),
		maxPageSize = MAX_PAGE_SIZE,
	)

	// ============
	//  Sets

	override suspend fun listSets(game: Game): List<CardSet> {
		require(game == Game.RIFTBOUND) { "Riftcodex serves Riftbound only, not $game" }
		return mapProviderErrors("Riftcodex.listSets") {
			val vResponse: PageDto<SetDto> = mClient
				.get(mBaseUrl) {
					url { appendPathSegments("sets") }
					parameter("size", MAX_PAGE_SIZE)
				}
				.body()
			vResponse.items.mapNotNull { RiftcodexMapper.toSet(it, id) }
		}
	}

	// ============
	//  Cards

	override suspend fun listCards(request: CardPageRequest): CardPage {
		val vText = request.query.text?.takeIf { it.isNotBlank() }
		// The two endpoints differ only in whether a `query` is present. `/cards/search` with an
		// empty query is not the same as `/cards`, so the choice is made on the text rather than
		// always using the search route.
		val vPath = if (vText != null) listOf("cards", "search") else listOf("cards")
		val vSize = request.pageSize.coerceAtMost(MAX_PAGE_SIZE)

		return mapProviderErrors("Riftcodex.listCards") {
			val vResponse: PageDto<CardDto> = mClient
				.get(mBaseUrl) {
					url { appendPathSegments(vPath) }
					// `set_id` takes the short code, not the Mongo id. Documented case-insensitive.
					parameter("set_id", request.setId.local)
					parameter("page", request.page)
					parameter("size", vSize)
					vText?.let { parameter("query", it) }
					sortParameterFor(request.query.sortBy)?.let { parameter("sort", it) }
					parameter(
						"dir",
						if (request.query.sortDirection == SortDirection.DESCENDING) -1 else 1,
					)
				}
				.body()

			val vCards = vResponse.items.mapNotNull { RiftcodexMapper.toPrinting(it, id) }
			CardPage(
				cards = vCards,
				page = vResponse.page,
				pageSize = vSize,
				totalCount = vResponse.total,
				// `pages` is the server's own count of pages for this query, so this is its answer
				// rather than a guess from how full the returned page happened to be.
				hasMore = vResponse.page < vResponse.pages,
			)
		}
	}

	override suspend fun cardDetail(id: SourceId): CardPrinting? =
		mapProviderErrors("Riftcodex.cardDetail") {
			try {
				val vCard: CardDto = mClient
					.get(mBaseUrl) { url { appendPathSegments("cards", id.local) } }
					.body()
				RiftcodexMapper.toPrinting(vCard, this.id)
			} catch (vError: ClientRequestException) {
				// A 404 for a card that does not exist is an answer, not a failure. Caught here
				// rather than by reading the status, because `expectSuccess` turns it into this
				// exception before the response is ever returned.
				if (vError.response.status == HttpStatusCode.NotFound) null else throw vError
			}
		}

	/**
	 * Riftcodex's name for a sort field, or `null` to let the server use its default.
	 *
	 * Only the fields in [ProviderCapabilities.sorting] are mapped; anything else returns null
	 * rather than sending a value the server would reject with a 422.
	 */
	private fun sortParameterFor(field: CardSortField): String? = when (field) {
		CardSortField.COLLECTOR_NUMBER -> "collector_number"
		CardSortField.NAME -> "name"
		CardSortField.RARITY -> "rarity"
		CardSortField.ENERGY_COST -> "energy"
	}

	companion object {

		/** Never changed: it is written into every id and every cache file this adapter produces. */
		val PROVIDER_ID: ProviderId = ProviderId("riftcodex")

		const val DEFAULT_BASE_URL: String = "https://api.riftcodex.com"

		/** The server's own cap. `size=200` is answered with a 422 naming this limit. */
		const val MAX_PAGE_SIZE: Int = 100
	}
}
