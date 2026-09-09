package com.bitsycore.cardbrowser.providers.wuwa

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
import com.bitsycore.cardbrowser.games.wutheringwaves.WutheringWavesGame

/**
 * The Wuthering Waves TCG adapter, serving [WutheringWavesGame] from a bundled snapshot.
 *
 * ## Why there is no HTTP here
 *
 * There is no published API. What this adapter used to call is the undocumented backend of the
 * game's own card page: `https://wwcg.ucp-jp.com/jp/card` is a Vue application whose bundle declares
 * an axios instance with `baseURL: "https://mc-api.ucp-jp.com/api/"`, a request interceptor setting
 * an **`x-lang`** header, and three endpoints -- `/web/card/list`, `/web/card/info` and
 * `/web/card/search-options`.
 *
 * Assembling a set from those cost 120-odd requests per language, because the list endpoint returns
 * six fields per card and everything else -- rarity, cost, attribute, rules text -- had to be
 * fetched one card at a time. For a game that ships **128 printings in total**, across three
 * locales, that is 357 requests to describe something that fits in a 170 KB file.
 *
 * So the catalogue is scraped once and shipped. [WuwaCatalogue] is the reader,
 * `src/commonMain/composeResources/files/wuwa-cards.json` is the data, and `tools/scrape_wuwa.py` is
 * the committed script that regenerates it. The endpoints above are still documented here because
 * that script is what talks to them, and because it is the only record of how any of this was found.
 *
 * The honest cost: this is a **snapshot**, so a new set means re-running the script and rebuilding.
 * The game gets one perhaps twice a year. `WuwaSnapshotFreshnessTest` is an opt-in live check that
 * compares the file against UCP's current catalogue, so a stale snapshot is something the build can
 * be asked about rather than something a user discovers.
 *
 * ## Coverage
 *
 * - **Languages**: Japanese, Simplified Chinese and Korean -- `ja-jp`, `zh-cn`, `ko-kr`. Measured:
 *   `en-us` and `zh-tw` are accepted by the API and answer 200 with an *empty list*, and so do bare
 *   `ko` and `kr`. The three catalogues are different sizes (123, 127 and 107 records), so per-card
 *   language coverage is a real fact here and is recorded per printing.
 * - **Sets**: three, derived from the prefix of each card's printed code -- `SD01`, `SD02`, `BP01`.
 *   The API has no set entity and no endpoint that lists one.
 * - **Card identity**: stated. Two printings sharing a code are one card at two rarity tiers, and
 *   they carry genuinely different illustrations rather than one being a foil of the other.
 * - **Filters**: everything, applied locally against a catalogue that is always complete.
 * - **Images**: WebP, one size, from UCP's own Tencent COS bucket. The only thing still fetched.
 * - **Finishes, artist, Cardmarket**: no fields anywhere. Unstated, not absent -- Cardmarket has no
 *   section for this game at all, it being Japan-only so far.
 */
class WuwaProvider : CardProvider<WutheringWavesGame> {

	override val id: ProviderId = PROVIDER_ID

	override val displayName: String = "UCP Wuthering Waves TCG"

	override val game: WutheringWavesGame = WutheringWavesGame

	override val capabilities: ProviderCapabilities = ProviderCapabilities(
		filtering = FilterSupport(
			// Nothing is remote because nothing is a request. The whole catalogue is in memory and
			// complete, which is the one filtering situation with no trade-offs in it.
			remote = emptySet(),
			localOnly = setOf(
				CardFilterField.TEXT,
				CardFilterField.CARD_TYPE,
				CardFilterField.DOMAIN,
				CardFilterField.RARITY,
				CardFilterField.COST,
				CardFilterField.ARTWORK_TREATMENT,
				CardFilterField.LANGUAGE,
			),
		),
		sorting = setOf(
			CardSortField.COLLECTOR_NUMBER,
			CardSortField.NAME,
			CardSortField.RARITY,
			CardSortField.COST,
		),
		data = DataCapabilities(
			// Restated rather than read from the snapshot, because `capabilities` is a plain `val`
			// and reading a bundled asset suspends. `WuwaCatalogueTest` asserts this set equals the
			// one the asset actually holds, which is what stops the two drifting.
			languages = setOf(
				CardLanguage.JAPANESE,
				CardLanguage.SIMPLIFIED_CHINESE,
				CardLanguage.KOREAN,
			),
			localizedText = true,
			localizedImages = true,
			// Two rarity tiers of one code are one card, which UCP states by giving them one code.
			cardIdentity = true,
			artworkVariants = true,
			// No finish field exists. False means unknown here, not "no foil".
			finishes = false,
			cardmarketProductMapping = false,
			crossSetSearch = true,
		),
		attribution = Attribution(
			text = "Wuthering Waves TCG card data and images from UCP's official card list. " +
				"Not affiliated with UCP or Kuro Games.",
			url = "https://wwcg.ucp-jp.com/jp/card",
		),
		// One page holds the game.
		maxPageSize = MAX_PAGE_SIZE,
	)

	// ============
	//  Sets

	override suspend fun listSets(language: CardLanguage?): List<CardSet> {
		// The catalogue's set records carry no localised strings, so the requested language has
		// nothing to select here. See `WuwaCatalogue.sets`.
		return WuwaCatalogue.sets(id)
	}

	// ============
	//  Cards

	/**
	 * The cards of one set, complete, in one page.
	 *
	 * Complete is worth naming: the repository's completeness guard exists because a provider that
	 * quietly returns a partial set is indistinguishable from a short one, and here there is nothing
	 * to be partial about. The largest set is 79 printings.
	 */
	override suspend fun listCards(request: CardPageRequest): CardPage {
		val vCards = WuwaCatalogue
			.printings(languageFor(request.language), id)
			.filter { it.setId == request.setId }
		return page(vCards, request.page, request.pageSize)
	}

	override suspend fun cardDetail(id: SourceId, language: CardLanguage?): CardPrinting? =
		WuwaCatalogue.printing(id.local, languageFor(language), this.id)

	/**
	 * Text search across every set.
	 *
	 * Matches the name and the printed code, which is what the search box means. Local, like
	 * everything else here, so it is exhaustive rather than a page of whatever a server ranked
	 * first.
	 */
	override suspend fun searchAllSets(request: CardSearchRequest): CardPage {
		val vNeedle = request.text.trim()
		val vCards = WuwaCatalogue
			.printings(languageFor(request.language), id)
			.filter {
				it.displayName.contains(vNeedle, ignoreCase = true) ||
					it.providerRawCollectorNumber.contains(vNeedle, ignoreCase = true)
			}
		return page(vCards, request.page, request.pageSize)
	}

	// ============
	//  Internals

	/**
	 * The language the snapshot will really answer in.
	 *
	 * Japanese unless another language it holds was asked for. Japanese rather than English because
	 * English is not one of the three: UCP has never published an English catalogue, and defaulting
	 * to a language that does not exist would answer every request with nothing.
	 */
	private fun languageFor(language: CardLanguage?): CardLanguage =
		language?.takeIf { it in capabilities.data.languages } ?: CardLanguage.JAPANESE

	/** One page of an in-memory list, with the paging metadata a caller expects. */
	private fun page(cards: List<CardPrinting>, page: Int, pageSize: Int): CardPage {
		val vSize = pageSize.coerceAtLeast(1)
		val vFrom = (page - 1) * vSize
		val vWindow = if (vFrom >= cards.size) emptyList() else cards.subList(vFrom, minOf(vFrom + vSize, cards.size))
		return CardPage(
			cards = vWindow,
			page = page,
			pageSize = vSize,
			totalCount = cards.size,
			hasMore = vFrom + vWindow.size < cards.size,
		)
	}

	companion object {

		/** Never changed: it is written into every id and every cache file this adapter produces. */
		val PROVIDER_ID: ProviderId = ProviderId("ucp-wuwa")

		/**
		 * The base URL of the endpoints the *scraper* uses. Nothing in the app calls them.
		 *
		 * Kept here because it is the one place this project records where the data came from, and
		 * because the freshness check reads it.
		 */
		const val API_BASE_URL: String = "https://mc-api.ucp-jp.com"

		/** Larger than the biggest set, so a set is always one page. */
		const val MAX_PAGE_SIZE: Int = 200
	}
}
