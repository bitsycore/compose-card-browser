package com.bitsycore.cardbrowser.providers.ygoprodeck

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
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments

/**
 * The YGOPRODeck adapter, serving [Game.YU_GI_OH].
 *
 * https://ygoprodeck.com -- a long-running community database. No key; a documented ceiling of
 * 20 requests per second, which this app is nowhere near.
 *
 * ## Coverage, as verified against the live API on 2026-09-08
 *
 * - **Languages**: `language=fr`, `ja` and `ko` all return translated names and text, and English
 *   is the default and takes no parameter. All four of the app's preferences.
 * - **Sets**: every set in one 175 KB request, with card counts and TCG release dates.
 * - **Cards**: `cardinfo.php?cardset={name}`, with real offset paging and a `meta` block carrying
 *   `total_rows` -- so, unlike most sources here, completeness is checkable against the provider's
 *   own count rather than inferred.
 * - **Images**: `image_url` and a genuine `image_url_small` on the same CDN.
 * - **Finishes and card identity**: no usable fields. Both unstated.
 * - **Cross-set search**: `cardinfo.php?fname={text}`, a fuzzy name match, with the same paging.
 *
 * ## Two quirks worth knowing about
 *
 * **Sets are addressed by name.** There is no parameter that takes a set code, so a set's id here
 * *is* its name. See `YgoprodeckMapper.toSet`.
 *
 * **Rarity belongs to a printing, not a card.** A card record carries every set it has appeared in,
 * each with its own code and rarity, so the mapper picks the entry for the set being browsed. In a
 * cross-set search there is no set to pick, and the first listed printing's rarity is shown.
 *
 * ## On images
 *
 * YGOPRODeck asks that its images not be hotlinked by websites. This is a client application that
 * caches to the user's own device only what that user actually looks at -- the behaviour that
 * guidance is asking for rather than against -- and every request identifies itself.
 */
class YgoprodeckProvider(
	private val mClient: HttpClient,
	private val mBaseUrl: String = DEFAULT_BASE_URL,
) : CardProvider {

	override val id: ProviderId = PROVIDER_ID

	override val displayName: String = "YGOPRODeck"

	override val capabilities: ProviderCapabilities = ProviderCapabilities(
		games = setOf(Game.YU_GI_OH),
		filtering = FilterSupport(
			// `cardinfo.php` has parameters for type, attribute and level, and they are still not
			// used. The repository fetches and caches a set whole for offline use, and filtering
			// that in memory is instant where a server round trip per filter chip is not.
			remote = emptySet(),
			localOnly = setOf(
				CardFilterField.TEXT,
				CardFilterField.DOMAIN,
				CardFilterField.CARD_TYPE,
				CardFilterField.RARITY,
				CardFilterField.ENERGY_COST,
			),
		),
		sorting = setOf(
			CardSortField.COLLECTOR_NUMBER,
			CardSortField.NAME,
			CardSortField.RARITY,
			CardSortField.ENERGY_COST,
		),
		data = DataCapabilities(
			languages = setOf(
				CardLanguage.FRENCH,
				CardLanguage.JAPANESE,
				CardLanguage.ENGLISH,
				CardLanguage.KOREAN,
			),
			localizedText = true,
			// One image per card, in English, whatever language the text is requested in.
			localizedImages = false,
			cardIdentity = false,
			artworkVariants = false,
			finishes = false,
			cardmarketProductMapping = false,
			crossSetSearch = true,
		),
		attribution = Attribution(
			text = "Yu-Gi-Oh! card data and images from YGOPRODeck. Not affiliated with Konami.",
			url = "https://ygoprodeck.com/",
		),
		maxPageSize = MAX_PAGE_SIZE,
	)

	// ============
	//  Sets

	override suspend fun listSets(game: Game, language: CardLanguage?): List<CardSet> {
		require(game == Game.YU_GI_OH) { "YGOPRODeck serves Yu-Gi-Oh! only, not $game" }
		// Set names are English whatever language the cards are requested in, so there is no
		// per-language catalogue to select.
		return mapProviderErrors("YGOPRODeck.listSets") {
			val vSets: List<YgoSetDto> = mClient
				.get(mBaseUrl) {
					url { appendPathSegments("api", "v7", "cardsets.php") }
					identify()
				}
				.body()
			vSets
				// A set with no cards cannot be browsed, and a couple in the catalogue have none.
				.filter { (it.numOfCards ?: 0) > 0 }
				.mapNotNull { YgoprodeckMapper.toSet(it, id) }
		}
	}

	// ============
	//  Cards

	override suspend fun listCards(request: CardPageRequest): CardPage {
		val vLanguage = resolveLanguage(request.language) ?: CardLanguage.ENGLISH
		val vSize = request.pageSize.coerceAtMost(MAX_PAGE_SIZE)
		return mapProviderErrors("YGOPRODeck.listCards") {
			// The set id *is* the set name -- see the class doc.
			fetchPage(
				vLanguage = vLanguage,
				vPage = request.page,
				vSize = vSize,
				vSet = CardSet(
					id = request.setId,
					game = Game.YU_GI_OH,
					code = request.setId.local,
					name = request.setId.local,
					cardCount = null,
					releaseDate = null,
				),
			) { parameter("cardset", request.setId.local) }
		}
	}

	override suspend fun cardDetail(id: SourceId, language: CardLanguage?): CardPrinting? {
		val vLanguage = resolveLanguage(language) ?: CardLanguage.ENGLISH
		return mapProviderErrors("YGOPRODeck.cardDetail") {
			try {
				val vResponse: YgoCardResponseDto = mClient
					.get(mBaseUrl) {
						url { appendPathSegments("api", "v7", "cardinfo.php") }
						parameter("id", id.local)
						languageParameter(vLanguage)
						identify()
					}
					.body()
				vResponse.data.firstOrNull()?.let {
					YgoprodeckMapper.toPrinting(it, this.id, set = null, language = vLanguage)
				}
			} catch (vError: ClientRequestException) {
				// An unknown passcode answers 400, not 404.
				val vStatus = vError.response.status
				if (vStatus == HttpStatusCode.NotFound || vStatus == HttpStatusCode.BadRequest) {
					null
				} else {
					throw vError
				}
			}
		}
	}

	override suspend fun searchAllSets(request: CardSearchRequest): CardPage {
		require(request.game == Game.YU_GI_OH) {
			"YGOPRODeck serves Yu-Gi-Oh! only, not ${request.game}"
		}
		val vLanguage = resolveLanguage(request.language) ?: CardLanguage.ENGLISH
		return mapProviderErrors("YGOPRODeck.searchAllSets") {
			fetchPage(
				vLanguage = vLanguage,
				vPage = request.page,
				vSize = request.pageSize.coerceAtMost(MAX_PAGE_SIZE),
				vSet = null,
			) { parameter("fname", request.text) }
		}
	}

	/**
	 * One page of `cardinfo.php`, with whatever selector [selector] adds.
	 *
	 * Shared by the set listing and the search because the two differ only in that one parameter,
	 * and because the paging, the language handling and the "no match answers 400" behaviour are
	 * identical for both and should not be written twice.
	 */
	private suspend fun fetchPage(
		vLanguage: CardLanguage,
		vPage: Int,
		vSize: Int,
		vSet: CardSet?,
		selector: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
	): CardPage {
		val vOffset = (vPage - 1) * vSize
		val vResponse: YgoCardResponseDto = try {
			mClient
				.get(mBaseUrl) {
					url { appendPathSegments("api", "v7", "cardinfo.php") }
					selector()
					parameter("num", vSize)
					parameter("offset", vOffset)
					languageParameter(vLanguage)
					identify()
				}
				.body()
		} catch (vError: ClientRequestException) {
			// A query matching nothing answers 400 with an error body rather than an empty list.
			// That is an answer -- "no such cards" -- not a failure, and treating it as one would
			// put an error screen over a search that simply found nothing.
			if (vError.response.status == HttpStatusCode.BadRequest) {
				YgoCardResponseDto()
			} else {
				throw vError
			}
		}

		val vCards = vResponse.data.mapNotNull {
			YgoprodeckMapper.toPrinting(it, id, vSet, vLanguage)
		}
		val vTotal = vResponse.meta?.totalRows
		return CardPage(
			cards = vCards,
			page = vPage,
			pageSize = vSize,
			totalCount = vTotal,
			// The provider's own remaining-rows count where it gives one, rather than a guess from
			// how full this page happened to be.
			hasMore = vResponse.meta?.rowsRemaining?.let { it > 0 }
				?: (vTotal != null && vOffset + vCards.size < vTotal),
		)
	}

	/**
	 * Adds `language` -- except for English, which must not be sent.
	 *
	 * `language=en` is not a valid value; English is the default and is selected by *omitting* the
	 * parameter. Sending it produces an error rather than English.
	 */
	private fun io.ktor.client.request.HttpRequestBuilder.languageParameter(language: CardLanguage) {
		if (language != CardLanguage.ENGLISH) parameter("language", language.code)
	}

	private fun io.ktor.client.request.HttpRequestBuilder.identify() {
		header("User-Agent", USER_AGENT)
		header("Accept", "application/json")
	}

	companion object {

		/** Never changed: it is written into every id and every cache file this adapter produces. */
		val PROVIDER_ID: ProviderId = ProviderId("ygoprodeck")

		const val DEFAULT_BASE_URL: String = "https://db.ygoprodeck.com"

		/**
		 * How many cards to ask for at once.
		 *
		 * `num` is capped by the server at 100. A 250-card set is three requests, which against a
		 * documented 20-per-second ceiling is not close to anything.
		 */
		const val MAX_PAGE_SIZE: Int = 100

		private const val USER_AGENT = "CardBrowser/1.0 (github.com/bitsycore)"
	}
}
