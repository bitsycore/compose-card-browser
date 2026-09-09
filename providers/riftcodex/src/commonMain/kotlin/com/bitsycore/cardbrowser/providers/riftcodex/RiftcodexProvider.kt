package com.bitsycore.cardbrowser.providers.riftcodex

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
import com.bitsycore.cardbrowser.core.provider.CardSortField
import com.bitsycore.cardbrowser.core.provider.DataCapabilities
import com.bitsycore.cardbrowser.core.provider.FilterSupport
import com.bitsycore.cardbrowser.core.provider.ProviderCapabilities
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.core.provider.SortDirection
import com.bitsycore.cardbrowser.data.net.mapProviderErrors
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
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
 * authoritative route for [RiftboundGame] in this app.
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
 * - **Remote filtering**: none that can be relied on. `set_id` scopes a listing, but every actual
 *   *filter* is applied locally. Domain, type, rarity and energy have no query parameter at all,
 *   and `/cards/search` -- which does exist and does take a `query` -- was re-checked on 2026-09-08
 *   and is not a name search: `query=Cull` matches nothing while `query=Cull the Weak` returns
 *   "Aspirant's Climb", a card sharing neither name nor text with it. A search box wired to that
 *   returns "no results" for most of what a user types and something irrelevant for the rest.
 *
 *   So TEXT is declared [FilterSupport.localOnly] with the others and matched by the app's own
 *   filter engine against the complete set. That is not a downgrade in practice: the repository
 *   already fetches and caches every set whole, so the local match costs no extra request and is
 *   an accent-folded name-and-collector-number match rather than whatever the server is doing.
 * - **Cross-set search**: not offered, for the same reason. `/cards/search` without a `set_id` is
 *   accepted and answers, but it answers with the same unusable matching, so
 *   [DataCapabilities.crossSetSearch] is false and the search screen falls back to the sets already
 *   on disk and says so.
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
) : CardProvider<RiftboundGame> {

	override val id: ProviderId = PROVIDER_ID

	override val displayName: String = "Riftcodex"

	override val game: RiftboundGame = RiftboundGame

	override val capabilities: ProviderCapabilities = ProviderCapabilities(
		filtering = FilterSupport(
			// Nothing is filtered remotely, including text -- see the note on `/cards/search` in
			// the class doc. Every filter is honoured locally against the complete set, which the
			// repository already holds for every other filter anyway.
			remote = emptySet(),
			// No query parameter exists for any of these. Honouring one means fetching every page
			// of the set and filtering in the app, which the repository is told to do by this very
			// declaration rather than by a hardcoded assumption about Riftcodex.
			localOnly = setOf(
				CardFilterField.TEXT,
				CardFilterField.DOMAIN,
				CardFilterField.CARD_TYPE,
				CardFilterField.RARITY,
				CardFilterField.COST,
				CardFilterField.ARTWORK_TREATMENT,
			),
			// FINISH and LANGUAGE appear in neither set: this provider cannot filter on them at
			// all, so the UI does not offer them for Riftbound.
		),
		sorting = setOf(
			CardSortField.COLLECTOR_NUMBER,
			CardSortField.NAME,
			CardSortField.RARITY,
			CardSortField.COST,
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
			crossSetSearch = false,
		),
		attribution = Attribution(
			text = "Card data from Riftcodex, an unofficial fan project not affiliated with Riot Games.",
			url = "https://riftcodex.com/",
		),
		maxPageSize = MAX_PAGE_SIZE,
	)

	// ============
	//  Sets

	override suspend fun listSets(language: CardLanguage?): List<CardSet> {
		// `language` is accepted and ignored on purpose: Riftcodex has no language dimension, so
		// there is no per-language catalogue to ask for. Every record it returns says English.
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
		// Always the plain listing. `/cards/search` is deliberately unused -- see the class doc --
		// so a query carrying text still fetches the unfiltered page and the caller, which has
		// already been told TEXT is local-only, matches it itself.
		val vPath = listOf("cards")
		val vSize = request.pageSize.coerceAtMost(MAX_PAGE_SIZE)

		return mapProviderErrors("Riftcodex.listCards") {
			val vResponse: PageDto<CardDto> = mClient
				.get(mBaseUrl) {
					url { appendPathSegments(vPath) }
					// `set_id` takes the short code, not the Mongo id. Documented case-insensitive.
					parameter("set_id", request.setId.local)
					parameter("page", request.page)
					parameter("size", vSize)
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

	override suspend fun cardDetail(id: SourceId, language: CardLanguage?): CardPrinting? =
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
		CardSortField.COST -> "energy"
	}

	companion object {

		/** Never changed: it is written into every id and every cache file this adapter produces. */
		val PROVIDER_ID: ProviderId = ProviderId("riftcodex")

		const val DEFAULT_BASE_URL: String = "https://api.riftcodex.com"

		/** The server's own cap. `size=200` is answered with a 422 naming this limit. */
		const val MAX_PAGE_SIZE: Int = 100
	}
}
