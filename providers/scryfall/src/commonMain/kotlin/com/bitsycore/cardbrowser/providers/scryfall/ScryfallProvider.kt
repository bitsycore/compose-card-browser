package com.bitsycore.cardbrowser.providers.scryfall

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
import com.bitsycore.cardbrowser.core.provider.BulkCatalogue
import com.bitsycore.cardbrowser.core.provider.BulkSummary
import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.data.net.ProviderHttpPolicy
import kotlin.time.Duration.Companion.milliseconds
import com.bitsycore.cardbrowser.data.net.mapProviderErrors
import com.bitsycore.cardbrowser.games.magic.MagicGame
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments

/**
 * The Scryfall adapter, serving [MagicGame].
 *
 * Scryfall (https://scryfall.com) is the reference Magic database. No key; it asks for an
 * identifying `User-Agent` and for roughly 50-100 ms between requests.
 *
 * Both are honoured, and the second one only recently: this doc claimed the gap was honoured "in
 * the shared HTTP stack" when no throttle existed anywhere, so a three-page set went out as three
 * back-to-back requests. It is real now -- `ProviderHttpPolicy.SCRYFALL` gives this adapter its own
 * client with a 100 ms minimum interval, which is why it does not share the app's default one.
 *
 * Scryfall also publishes [bulk data](https://scryfall.com/docs/api/bulk-data) and points at it
 * from the rate-limit page. This adapter deliberately does not use it: `all_cards`, the only file
 * covering the eleven languages the app offers, is 374 MB compressed and over 2 GB expanded,
 * against roughly 600 KB for the three requests a set actually costs. Bulk data is the right answer
 * for a client that crawls the whole corpus and the wrong one for a browser that fetches a set on
 * demand and caches it for a day.
 *
 * ## Coverage, as verified against the live API on 2026-09-08
 *
 * - **Languages**: eleven, all measured. `fr`, `ja`, `ko`, `de`, `es`, `it`, `pt`, `ru`, `zhs` and
 *   `zht` all return real printings with `printed_name` and `printed_type_line`, alongside English
 *   -- `lang:zhs` alone matches 20,592 creature printings. The record states its own `lang`, so the
 *   printing language here is a fact rather than an inference from which URL was called.
 *
 *   Scryfall spells Chinese `zhs`/`zht` where this app spells it `zh-cn`/`zh-tw`; [scryfallTag] is
 *   the whole of that translation, and `CardLanguage.fromCode` reads Scryfall's spelling back.
 *
 *   Not every set exists in every language, and Scryfall answers a language with no printings with
 *   **404**, not an empty list. That is handled explicitly -- see [listCards] -- and it falls back
 *   to English while labelling the records English.
 * - **Sets**: 1,049 in one 622 KB request, of which 988 are paper. Digital-only sets are excluded
 *   because this app browses printed cards.
 * - **Cards**: `GET /cards/search`, 175 per page, with `total_cards` and `has_more`. A 400-card set
 *   in one language is three requests.
 * - **Card identity**: `oracle_id`, and it is the only real one in this project. Every other
 *   provider here leaves `CardIdentity` null because it states nothing to build one from.
 * - **Finishes**: `finishes` is exhaustive per printing, so both sides of `FinishCoverage` are
 *   populated -- a finish missing from the array really is absent.
 * - **Images**: `thumb`, `grid` and `display` are WebP. The grid uses `grid` and the detail screen
 *   `display`.
 * - **Cardmarket**: `cardmarket_id` is a product id, and it is enough. `/en/Magic/Products?
 *   idProduct={n}` resolves to the card -- confirmed in a browser on 2026-09-11, which is the only
 *   way to confirm anything about Cardmarket, since it answers 403 to every scripted request. The
 *   note this replaces said no link could be built from a number; that was drawn from testing
 *   `/Products/Singles?idProduct=`, which is a different path and genuinely does not work.
 * - **TCGplayer**: `tcgplayer_id`, which becomes `tcgplayer.com/product/{n}`. See
 *   `TcgplayerLinkBuilder`, including what has *not* been confirmed about it.
 * - **Cross-set search**: the same `/cards/search` endpoint without a `set:` term.
 */
class ScryfallProvider(
	private val mClient: HttpClient,
	private val mBaseUrl: String = DEFAULT_BASE_URL,
	/**
	 * Where the bulk import writes its scratch file, or `null` on a caller that has no storage.
	 *
	 * Null makes [bulkSummary] answer null and [streamAll] do nothing, which is the same shape as
	 * a source with no dump at all -- so a test or a preview constructing this without storage gets
	 * a provider that simply does not offer bulk rather than one that throws when asked.
	 */
	private val mStorage: AppStorage? = null,
) : CardProvider<MagicGame>, BulkCatalogue {

	override val id: ProviderId = PROVIDER_ID

	override val displayName: String = "Scryfall"

	override val game: MagicGame = MagicGame

	override val capabilities: ProviderCapabilities = ProviderCapabilities(
		filtering = FilterSupport(
			// Scryfall's query language could express every one of these remotely, and they are
			// still declared local. A Magic set is at most three requests and the repository caches
			// it whole for offline use and instant re-filtering; pushing each filter change to the
			// server would mean a request per chip tap against an API that asks callers to slow
			// down. The complete set is fetched once and every filter after that is free.
			remote = emptySet(),
			localOnly = setOf(
				CardFilterField.TEXT,
				CardFilterField.DOMAIN,
				CardFilterField.CARD_TYPE,
				CardFilterField.RARITY,
				CardFilterField.COST,
				CardFilterField.ARTWORK_TREATMENT,
				CardFilterField.FINISH,
			),
		),
		sorting = setOf(
			CardSortField.COLLECTOR_NUMBER,
			CardSortField.NAME,
			CardSortField.RARITY,
			CardSortField.COST,
		),
		data = DataCapabilities(
			languages = setOf(
				CardLanguage.FRENCH,
				CardLanguage.JAPANESE,
				CardLanguage.ENGLISH,
				CardLanguage.KOREAN,
				CardLanguage.SIMPLIFIED_CHINESE,
				CardLanguage.TRADITIONAL_CHINESE,
				CardLanguage.GERMAN,
				CardLanguage.SPANISH,
				CardLanguage.ITALIAN,
				CardLanguage.PORTUGUESE,
				CardLanguage.RUSSIAN,
			),
			localizedText = true,
			localizedImages = true,
			cardIdentity = true,
			artworkVariants = true,
			finishes = true,
			// `cardmarket_id`, and a link *is* built from it now. The shape is
			// `/en/Magic/Products?idProduct={n}`, confirmed in a browser on 2026-09-11 -- see
			// `CardmarketLinkBuilder`, which had been building the `/Products/Singles/{n}` shape
			// that does not resolve.
			cardmarketProductMapping = true,
			crossSetSearch = true,
		),
		attribution = Attribution(
			text = "Magic card data and images from Scryfall. Not affiliated with or endorsed by " +
				"Wizards of the Coast.",
			url = "https://scryfall.com/",
		),
		maxPageSize = MAX_PAGE_SIZE,
	)

	// ============
	//  Sets

	override suspend fun listSets(language: CardLanguage?): List<CardSet> {
		// Set names are English on Scryfall whatever language the cards are asked for, so there is
		// no per-language catalogue and `language` has nothing to select.
		return mapProviderErrors("Scryfall.listSets") {
			val vResponse: ScryfallListDto<ScryfallSetDto> = mClient
				.get(mBaseUrl) {
					url { appendPathSegments("sets") }
					identify()
				}
				.body()
			vResponse.data
				// Digital-only sets are Arena and MTGO products with no printed cards. This app
				// browses printings, and an Alchemy set has none.
				.filter { !it.digital && it.cardCount > 0 }
				.mapNotNull { ScryfallMapper.toSet(it, id) }
		}
	}

	// ============
	//  Cards

	/**
	 * One page of a set in one language.
	 *
	 * The language fallback is the interesting part. Scryfall answers a search that matches nothing
	 * with a **404**, so `set:blb lang:ko` -- a set with no Korean printings -- is an error rather
	 * than an empty page. Treating that as a failure would show an error screen for a set that
	 * exists and is browsable; treating it as an empty set would tell the user the set has no cards.
	 *
	 * Neither is true, so the request is retried in English and the returned records are labelled
	 * **English**, which they are. The UI's `LanguageResolution` then does its job and says the
	 * user asked for Korean and is looking at English, rather than the app quietly relabelling an
	 * English printing as Korean.
	 */
	override suspend fun listCards(request: CardPageRequest): CardPage {
		val vRequested = resolveLanguage(request.language) ?: CardLanguage.ENGLISH
		return mapProviderErrors("Scryfall.listCards") {
			val vSetTerm = "set:${request.setId.local}"
			searchPage(vSetTerm, vRequested, request.page)
				?: run {
					if (vRequested == CardLanguage.ENGLISH) {
						// Genuinely no cards: an empty set, not a missing translation.
						CardPage(emptyList(), request.page, MAX_PAGE_SIZE, totalCount = 0, hasMore = false)
					} else {
						searchPage(vSetTerm, CardLanguage.ENGLISH, request.page)
							?: CardPage(emptyList(), request.page, MAX_PAGE_SIZE, totalCount = 0, hasMore = false)
					}
				}
		}
	}

	override suspend fun cardDetail(id: SourceId, language: CardLanguage?): CardPrinting? =
		mapProviderErrors("Scryfall.cardDetail") {
			try {
				val vCard: ScryfallCardDto = mClient
					.get(mBaseUrl) {
						url { appendPathSegments("cards", id.local) }
						identify()
					}
					.body()
				ScryfallMapper.toPrinting(
					dto = vCard,
					provider = this.id,
					set = null,
					// The record says what language it is; that is used rather than what was asked
					// for, because a card fetched by id comes back in whatever language it was
					// printed in regardless of the request.
					language = CardLanguage.fromCode(vCard.lang ?: "") ?: CardLanguage.ENGLISH,
				)
			} catch (vError: ClientRequestException) {
				if (vError.response.status == HttpStatusCode.NotFound) null else throw vError
			}
		}

	/**
	 * Which of [candidates] Magic was actually printed in for this set.
	 *
	 * The gap this closes is wide. Scryfall serves eleven languages, so the language menu offered
	 * eleven for every set -- including Alpha, printed in 1993 in English only. Nine of those
	 * eleven then fell back to English and the screen had to explain itself, once per language the
	 * user tried.
	 *
	 * The probe is `/cards/random?q=set:X lang:Y`, which answers 200 with one card when any such
	 * printing exists and 404 when none does. Chosen over the obvious `/cards/search` because the
	 * cost is not close: measured on Dominaria in Japanese, **5 KB against 949 KB**, and a
	 * language that does not exist costs 145 bytes. It is a strange thing to ask a `random`
	 * endpoint for, but "is there any card matching this query" is exactly the question it answers,
	 * and it answers it in one card rather than in a page of 175.
	 *
	 * Sequential rather than parallel, deliberately: Scryfall asks for no more than ten requests a
	 * second and this client already spaces them, so firing eleven at once would only queue behind
	 * the throttle. The whole thing happens once per set and is then cached.
	 *
	 * A probe that fails for any reason other than 404 keeps its language: absence has to be stated
	 * by the source, not inferred from a timeout.
	 */
	override suspend fun confirmLanguages(
		setId: SourceId,
		candidates: Set<CardLanguage>,
	): Set<CardLanguage> = mapProviderErrors("Scryfall.confirmLanguages") {
		candidates.filterTo(mutableSetOf()) { vLanguage -> hasPrintings(setId.local, vLanguage) }
	}

	/** True when Scryfall holds at least one printing of this set in this language, or cannot say. */
	private suspend fun hasPrintings(setLocal: String, language: CardLanguage): Boolean = try {
		mClient
			.get(mBaseUrl) {
				url { appendPathSegments("cards", "random") }
				parameter("q", "set:$setLocal lang:${scryfallTag(language)}")
				identify()
			}
			.let { true }
	} catch (vError: ClientRequestException) {
		// 404 is Scryfall's "nothing matches", and the only answer that removes a language.
		if (vError.response.status == HttpStatusCode.NotFound) false else throw vError
	} catch (vError: kotlinx.coroutines.CancellationException) {
		throw vError
	} catch (vError: Exception) {
		true
	}

	override suspend fun searchAllSets(request: CardSearchRequest): CardPage {
		val vLanguage = resolveLanguage(request.language) ?: CardLanguage.ENGLISH
		return mapProviderErrors("Scryfall.searchAllSets") {
			// Quoted, so a multi-word search is one name term rather than several loose ones that
			// Scryfall would AND together across different fields.
			val vTerm = "name:${quote(request.text)}"
			searchPage(vTerm, vLanguage, request.page)
				?: searchPage(vTerm, CardLanguage.ENGLISH, request.page)
				?: CardPage(emptyList(), request.page, MAX_PAGE_SIZE, totalCount = 0, hasMore = false)
		}
	}

	/**
	 * One search page, or `null` when Scryfall answered 404 because nothing matched.
	 *
	 * Null rather than an empty page, so the caller can tell "no such translation" from "no such
	 * card" and decide whether a fallback is warranted.
	 */
	private suspend fun searchPage(term: String, language: CardLanguage, page: Int): CardPage? = try {
		val vResponse: ScryfallListDto<ScryfallCardDto> = mClient
			.get(mBaseUrl) {
				url { appendPathSegments("cards", "search") }
				parameter("q", "$term lang:${scryfallTag(language)}")
				// One entry per printing rather than per card, which is what a set browser shows:
				// two artworks of the same card in a set are two tiles.
				parameter("unique", "prints")
				// Without this, Scryfall silently restricts results to English however the query
				// is written -- a `lang:ja` search returns nothing rather than Japanese cards.
				parameter("include_multilingual", true)
				parameter("order", "set")
				parameter("page", page)
				identify()
			}
			.body()
		CardPage(
			cards = vResponse.data.mapNotNull {
				ScryfallMapper.toPrinting(it, id, set = null, language = language)
			},
			page = page,
			pageSize = MAX_PAGE_SIZE,
			totalCount = vResponse.totalCards,
			hasMore = vResponse.hasMore,
		)
	} catch (vError: ClientRequestException) {
		if (vError.response.status == HttpStatusCode.NotFound) null else throw vError
	}

	/**
	 * Wraps a search term in quotes, escaping any the user typed.
	 *
	 * Scryfall's query syntax is real syntax, and an unescaped quote in a card name turns a search
	 * into a parse error rather than a miss.
	 */
	private fun quote(text: String): String = "\"" + text.replace("\"", "\\\"") + "\""

	/**
	 * The tag Scryfall's `lang:` filter wants for [language].
	 *
	 * Only Chinese differs. Scryfall predates BCP 47 script subtags and writes the two variants
	 * `zhs` and `zht`; asking it for `lang:zh-cn` is a query that matches nothing, which -- given
	 * Scryfall answers an empty match with a 404 -- would look exactly like a set that has no
	 * Chinese printings.
	 */
	private fun scryfallTag(language: CardLanguage): String = when (language) {
		CardLanguage.SIMPLIFIED_CHINESE -> "zhs"
		CardLanguage.TRADITIONAL_CHINESE -> "zht"
		else -> language.code
	}

	/** Scryfall asks every client to identify itself, and this is the only place that is done. */
	private fun io.ktor.client.request.HttpRequestBuilder.identify() {
		header("User-Agent", USER_AGENT)
		header("Accept", "application/json")
	}

	// ============
	//  Bulk

	private val mBulk: ScryfallBulk? by lazy {
		mStorage?.let { ScryfallBulk(mClient, it, mBaseUrl) }
	}

	override suspend fun bulkVariants(): List<BulkSummary> = mBulk?.variants().orEmpty()

	override suspend fun streamAll(
		variantId: String,
		onBytes: (Long, Long?) -> Unit,
		onCard: suspend (CardPrinting) -> Unit,
	) {
		mBulk?.stream(variantId, onBytes, onCard)
	}

	companion object {

		/**
		 * Scryfall's documented ask: an identifying User-Agent and 50-100 ms between requests.
		 *
		 * 150 ms rather than 100. Scryfall's wording is "less than 10 requests per second", and
		 * 100 ms is *exactly* ten -- no margin at all for scheduling jitter or for two throttled
		 * clients existing at once, and their 429 body is explicit that repeating it risks a
		 * network block. Verified the hard way: a run that sat on the boundary was rate-limited
		 * with a 60-second cooldown.
		 *
		 * Costs almost nothing. The heaviest thing the app asks of Scryfall is a set, which is
		 * three pages, and the widest is a per-set language check at one request per language --
		 * under two seconds either way, once, and then cached.
		 *
		 * Declared here rather than in the shared HTTP layer, where it used to live: a rate limit
		 * is a fact about this source, exactly like its endpoints and its bulk file, and `:data`
		 * naming a provider was the one place that layer knew any of them existed.
		 */
		val HTTP_POLICY: ProviderHttpPolicy = ProviderHttpPolicy(
			userAgent = "CardBrowser/1.0 (github.com/bitsycore)",
			minRequestInterval = 150.milliseconds,
		)

		/** Never changed: it is written into every id and every cache file this adapter produces. */
		val PROVIDER_ID: ProviderId = ProviderId("scryfall")

		const val DEFAULT_BASE_URL: String = "https://api.scryfall.com"

		/** Scryfall's own fixed page size for `/cards/search`. Not configurable by the caller. */
		const val MAX_PAGE_SIZE: Int = 175

		/** Scryfall's documentation asks for a client that identifies itself. */
		private const val USER_AGENT = "CardBrowser/1.0 (github.com/bitsycore)"
	}
}
