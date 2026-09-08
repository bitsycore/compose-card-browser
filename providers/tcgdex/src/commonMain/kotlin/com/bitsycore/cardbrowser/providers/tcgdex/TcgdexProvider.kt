package com.bitsycore.cardbrowser.providers.tcgdex

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
import com.bitsycore.cardbrowser.data.net.mapProviderErrors
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * The TCGdex adapter, serving [Game.POKEMON].
 *
 * TCGdex (https://tcgdex.dev) is an open, community-maintained Pokémon TCG database. No key, no
 * auth, no published rate limit.
 *
 * ## Coverage, as verified against the live API on 2026-09-08
 *
 * - **Languages**: all four the app prefers, which no other source in this project manages. Set
 *   counts per locale were measured rather than assumed: `en` 218, `fr` 200, `ja` 184, `ko` 95.
 *   Each locale is a genuinely separate catalogue -- a card returned by `/fr/` is a French printing
 *   with a French scan, not an English record with translated text bolted on -- so the requested
 *   language is [com.bitsycore.cardbrowser.core.model.LanguageCoverage.confirmed] on each record.
 *
 *   A locale not carrying a card is never recorded as that language being *absent*. TCGdex's Korean
 *   catalogue stopping in 2022 is a fact about TCGdex, not about what Pokémon printed in Korean.
 * - **Sets**: one request returns the whole catalogue. It does not carry release dates -- 0 of 218
 *   entries have one -- so a second request recovers them; see [listSets].
 * - **Cards**: one request per set returns the set *and every card in it*, so this adapter never
 *   pages and the repository's "is this the complete set?" question is answered definitively rather
 *   than inferred from a page count.
 * - **Finishes**: real, and two-sided. `variants` is an object of booleans, so `holo: false` is
 *   TCGdex stating that no holo printing exists rather than declining to say.
 * - **Cardmarket**: `variants_detailed[].thirdParty.cardmarket` is an actual product id. The only
 *   provider here that maps a single printing to a Cardmarket product.
 * - **Card identity**: none. Nothing links two printings of the same Pokémon across sets.
 * - **Images**: a base URL with quality and extension appended. WebP at 19 KB (`low`) and 63 KB
 *   (`high`) against 55 KB and 257 KB for the PNG equivalents, so WebP throughout.
 * - **Rarity**: over a hundred distinct strings, varying by era *and* by locale. No ladder is
 *   defined for Pokémon in `RarityLadder` for exactly that reason.
 * - **Cross-set search**: `GET /{lang}/cards?name=like:{text}`, returning brief cards.
 */
class TcgdexProvider(
	private val mClient: HttpClient,
	private val mBaseUrl: String = DEFAULT_BASE_URL,
) : CardProvider {

	override val id: ProviderId = PROVIDER_ID

	override val displayName: String = "TCGdex"

	override val capabilities: ProviderCapabilities = ProviderCapabilities(
		games = setOf(Game.POKEMON),
		filtering = FilterSupport(
			// A set arrives whole in one request, so filtering it locally costs nothing and behaves
			// identically for every field. Declaring TEXT as remote would send a second request to
			// re-fetch a subset of what is already in memory.
			remote = emptySet(),
			localOnly = setOf(
				CardFilterField.TEXT,
				CardFilterField.DOMAIN,
				CardFilterField.CARD_TYPE,
				CardFilterField.RARITY,
				CardFilterField.FINISH,
			),
			// ENERGY_COST is in neither: a Pokémon card's cost is per attack, not one number on the
			// card, so there is nothing honest to filter on. ARTWORK_TREATMENT likewise -- TCGdex
			// issues one record per card and states no variant relationship.
		),
		sorting = setOf(
			CardSortField.COLLECTOR_NUMBER,
			CardSortField.NAME,
			CardSortField.RARITY,
		),
		data = DataCapabilities(
			languages = setOf(
				CardLanguage.FRENCH,
				CardLanguage.JAPANESE,
				CardLanguage.ENGLISH,
				CardLanguage.KOREAN,
			),
			localizedText = true,
			localizedImages = true,
			cardIdentity = false,
			artworkVariants = false,
			finishes = true,
			cardmarketProductMapping = true,
			crossSetSearch = true,
		),
		attribution = Attribution(
			text = "Pokémon card data from TCGdex, a community project not affiliated with " +
				"Nintendo, Creatures or GAME FREAK.",
			url = "https://tcgdex.dev/",
		),
		// Not a server limit. A set is one unpaged request, and this is the largest set TCGdex
		// serves rounded up, so the repository never plans a second page it would not get.
		maxPageSize = MAX_PAGE_SIZE,
	)

	// ============
	//  Sets

	/**
	 * Every Pokémon set in the requested locale, with release dates.
	 *
	 * Two requests, and the second one needs justifying. `GET /{lang}/sets` returns the catalogue
	 * but omits `releaseDate` entirely -- confirmed by counting, 0 of 218 entries carry it -- which
	 * would leave every set with a null date. The app sorts undated sets last and alphabetically,
	 * so a 218-set list would arrive in an order with no relationship to when anything came out.
	 *
	 * The alternative to fixing that is 218 requests for the per-set detail. Instead one GraphQL
	 * POST returns every id and date together. It is English-only, which is why it is used for
	 * *dates* and not for the catalogue itself: names and card counts still come from the locale
	 * REST endpoint, and a set that exists only in a non-English catalogue simply keeps a null date
	 * rather than being given one that does not apply to it.
	 *
	 * A failed date request is not a failed set list. The catalogue is returned regardless.
	 */
	override suspend fun listSets(game: Game, language: CardLanguage?): List<CardSet> {
		require(game == Game.POKEMON) { "TCGdex serves Pokémon only, not $game" }
		val vLocale = localeFor(language)
		return mapProviderErrors("TCGdex.listSets") {
			val vSets: List<TcgdexSetBriefDto> = mClient
				.get(mBaseUrl) { url { appendPathSegments("v2", vLocale, "sets") } }
				.body()
			val vDates = releaseDates()
			vSets.mapNotNull { TcgdexMapper.toSet(it, id, vDates) }
		}
	}

	/**
	 * Set id to release date, from the one GraphQL query this adapter makes.
	 *
	 * Returns an empty map on any failure rather than throwing: dates are an improvement to the
	 * ordering of the set list, not a precondition for having one.
	 */
	private suspend fun releaseDates(): Map<String, LocalDate> = try {
		val vResponse: TcgdexGraphQlResponse = mClient
			.post(mBaseUrl) {
				url { appendPathSegments("v2", "graphql") }
				contentType(ContentType.Application.Json)
				setBody(GraphQlQuery(RELEASE_DATE_QUERY))
			}
			.body()
		vResponse.data?.sets.orEmpty()
			.mapNotNull { vSet ->
				TcgdexMapper.parseDate(vSet.releaseDate)?.let { vSet.id to it }
			}
			.toMap()
	} catch (vError: kotlinx.coroutines.CancellationException) {
		throw vError
	} catch (vError: Exception) {
		emptyMap()
	}

	// ============
	//  Cards

	/**
	 * The whole set, every time.
	 *
	 * `GET /{lang}/sets/{id}` returns the set with its complete card list, so paging is not
	 * something this adapter does badly -- it is something the source makes unnecessary. Page 2 is
	 * therefore always empty with `hasMore` false, which is the truthful answer and is what stops
	 * the repository asking for it twice.
	 */
	override suspend fun listCards(request: CardPageRequest): CardPage {
		val vLocale = localeFor(request.language)
		if (request.page > 1) {
			return CardPage(emptyList(), request.page, request.pageSize, totalCount = null, hasMore = false)
		}
		return mapProviderErrors("TCGdex.listCards") {
			val vSet: TcgdexSetDto = mClient
				.get(mBaseUrl) { url { appendPathSegments("v2", vLocale, "sets", request.setId.local) } }
				.body()
			val vMapped = TcgdexMapper.toSet(vSet, id)
			val vLanguage = languageFor(request.language)
			val vCards = if (vMapped == null) {
				emptyList()
			} else {
				vSet.cards.mapNotNull { TcgdexMapper.toPrinting(it, id, vMapped, vLanguage) }
			}
			CardPage(
				cards = vCards,
				page = 1,
				pageSize = vCards.size.coerceAtLeast(1),
				totalCount = vCards.size,
				hasMore = false,
			)
		}
	}

	override suspend fun cardDetail(id: SourceId, language: CardLanguage?): CardPrinting? {
		val vLocale = localeFor(language)
		return mapProviderErrors("TCGdex.cardDetail") {
			try {
				val vCard: TcgdexCardDto = mClient
					.get(mBaseUrl) { url { appendPathSegments("v2", vLocale, "cards", id.local) } }
					.body()
				TcgdexMapper.toPrinting(vCard, this.id, set = null, language = languageFor(language))
			} catch (vError: ClientRequestException) {
				if (vError.response.status == HttpStatusCode.NotFound) null else throw vError
			}
		}
	}

	/**
	 * Name search across every set in the locale.
	 *
	 * `name=like:{text}` is TCGdex's own substring operator. The results are *brief* cards, so they
	 * carry a name, a number and an image and nothing else -- which is what a search result row
	 * shows. The set name is derived from the card id's prefix rather than fetched, because
	 * resolving 60 results to 60 set names would be 60 requests to fill in a subtitle.
	 */
	override suspend fun searchAllSets(request: CardSearchRequest): CardPage {
		require(request.game == Game.POKEMON) { "TCGdex serves Pokémon only, not ${request.game}" }
		val vLocale = localeFor(request.language)
		val vLanguage = languageFor(request.language)
		return mapProviderErrors("TCGdex.searchAllSets") {
			val vResults: List<TcgdexCardBriefDto> = mClient
				.get(mBaseUrl) {
					url { appendPathSegments("v2", vLocale, "cards") }
					parameter("name", "like:${request.text}")
					parameter("pagination:page", request.page)
					parameter("pagination:itemsPerPage", request.pageSize)
				}
				.body()
			val vCards = vResults.mapNotNull { vBrief ->
				val vSetLocal = vBrief.id.substringBeforeLast('-', missingDelimiterValue = "")
				if (vSetLocal.isBlank()) return@mapNotNull null
				TcgdexMapper.toPrinting(
					dto = vBrief,
					provider = id,
					// A placeholder set carrying only what the id proves. Its name is the id, which
					// the search row shows as-is rather than inventing a prettier one.
					set = CardSet(
						id = SourceId(id, vSetLocal),
						game = Game.POKEMON,
						code = vSetLocal.uppercase(),
						name = vSetLocal,
						cardCount = null,
						releaseDate = null,
					),
					language = vLanguage,
				)
			}
			CardPage(
				cards = vCards,
				page = request.page,
				pageSize = request.pageSize,
				// TCGdex returns a bare array with no envelope, so there is no total to report and
				// none is guessed from the page being full.
				totalCount = null,
				hasMore = vCards.size >= request.pageSize,
			)
		}
	}

	// ============
	//  Locale

	/**
	 * The locale segment for a requested language, falling back down the app's preference order.
	 *
	 * Every value this can produce is one TCGdex actually serves, so a request never 404s on a
	 * locale that does not exist.
	 */
	private fun localeFor(language: CardLanguage?): String = languageFor(language).code

	/** The language this adapter will really answer in. Never null: all four locales exist. */
	private fun languageFor(language: CardLanguage?): CardLanguage =
		resolveLanguage(language) ?: CardLanguage.ENGLISH

	/** The GraphQL request envelope. One field, because one query is all this adapter sends. */
	@Serializable
	private data class GraphQlQuery(val query: String)

	companion object {

		/** Never changed: it is written into every id and every cache file this adapter produces. */
		val PROVIDER_ID: ProviderId = ProviderId("tcgdex")

		const val DEFAULT_BASE_URL: String = "https://api.tcgdex.net"

		/**
		 * Larger than the largest set TCGdex serves.
		 *
		 * Not a server limit -- there is no paging here at all. The repository uses this to decide
		 * whether a complete set is affordable, and a value above every real set size tells it the
		 * truth: one request is always enough.
		 */
		const val MAX_PAGE_SIZE: Int = 1000

		/** Ids and dates for every set, which the REST catalogue does not carry. */
		private const val RELEASE_DATE_QUERY = "{ sets { id releaseDate } }"
	}
}
