package com.bitsycore.cardbrowser.providers.tcgdex

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
import com.bitsycore.cardbrowser.data.net.ProviderHttpPolicy
import kotlin.time.Duration.Companion.milliseconds
import com.bitsycore.cardbrowser.data.net.mapProviderErrors
import com.bitsycore.cardbrowser.games.pokemon.PokemonGame
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.minutes

/**
 * The TCGdex adapter, serving [PokemonGame].
 *
 * TCGdex (https://tcgdex.dev) is an open, community-maintained Pokémon TCG database. No key, no
 * auth, no published rate limit.
 *
 * ## Coverage, as verified against the live API on 2026-09-08
 *
 * - **Languages**: every one the app knows, which no other source in this project manages. Set
 *   counts per locale were measured rather than assumed: `en` 218, `fr` 200, `ja` 184, `ko` 95, and
 *   `de`, `es`, `it`, `pt`, `ru`, `zh-cn` and `zh-tw` each return a catalogue of their own.
 *   TCGdex's locale tags are this app's own tags unchanged, so no mapping is needed here.
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
 *   defined for Pokémon in `PokemonGame` for exactly that reason.
 * - **Cross-set search**: `GET /{lang}/cards?name=like:{text}`, returning brief cards.
 */
class TcgdexProvider(
	private val mClient: HttpClient,
	private val mBaseUrl: String = DEFAULT_BASE_URL,
) : CardProvider<PokemonGame> {

	override val id: ProviderId = PROVIDER_ID

	override val displayName: String = "TCGdex"

	override val game: PokemonGame = PokemonGame

	private val mCatalogueLock = Mutex()

	/** Guarded by [mCatalogueLock]. See [catalogues]. */
	private var mCatalogues: Catalogues? = null

	override val capabilities: ProviderCapabilities = ProviderCapabilities(
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
			// COST is in neither: a Pokémon card's cost is per attack, not one number on the
			// card, so there is nothing honest to filter on. ARTWORK_TREATMENT likewise -- TCGdex
			// issues one record per card and states no variant relationship.
		),
		sorting = setOf(
			CardSortField.COLLECTOR_NUMBER,
			CardSortField.NAME,
			CardSortField.RARITY,
		),
		data = DataCapabilities(
			languages = CardLanguage.entries.toSet(),
			localizedText = true,
			localizedImages = true,
			cardIdentity = false,
			artworkVariants = false,
			finishes = true,
			cardmarketProductMapping = true,
			thumbnailImages = true,
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
	 * Every Pokémon set TCGdex knows, from every locale catalogue, merged.
	 *
	 * ## Why every locale and not just the requested one
	 *
	 * TCGdex's locales are not translations of one catalogue -- they are separate catalogues of
	 * separate products, and asking for one meant the other lines did not exist. Measured across all
	 * eleven: 486 distinct sets, of which the English catalogue holds 218. So browsing in English
	 * hid Japan's 180 sets, and choosing Japanese as a favourite language hid the international ones
	 * instead. The set list swapped product lines as a side effect of a language preference, which
	 * is what it was reported as: the two lines looked merged into one list that could only ever
	 * show half of itself.
	 *
	 * Merging them costs one request per locale instead of one, and they are the cheapest requests
	 * this adapter makes: the response cache holds them, the repository revalidates a set list at
	 * most every few minutes, and they are issued in parallel. A locale that fails is skipped rather
	 * than losing the list; only a total failure propagates.
	 *
	 * ## Ids are case-sensitive, and this is the one place that matters
	 *
	 * The international `sm10` is Unbroken Bonds; the Japanese `SM10` is ダブルブレイズ. Different
	 * products, 234 cards against 116, distinguished by nothing but the case of the id. And the
	 * detail endpoint does *not* respect the distinction -- `GET /en/sets/SM10` happily returns
	 * international Unbroken Bonds -- so a set's languages can only be established from exact-case
	 * catalogue membership. Nineteen ids collide when case is folded, so folding it would offer
	 * English for a Japanese set and then show a different set's cards under its name.
	 *
	 * ## Dates
	 *
	 * `GET /{lang}/sets` omits `releaseDate` entirely -- 0 of 218 entries carry one -- so one
	 * GraphQL POST recovers them. That endpoint serves the English catalogue only (218 sets, and
	 * `/v2/ja/graphql` is a 404), so sets outside the international line keep a null date and are
	 * ordered by code, which for `SV1a`, `SV2a`, `SV3a` is very nearly release order anyway. The
	 * alternative is one request per set, 265 of them, to fill in a subtitle.
	 */
	override suspend fun listSets(language: CardLanguage?): List<CardSet> {
		val vRequested = languageFor(language)
		return mapProviderErrors("TCGdex.listSets") {
			val vSnapshot = catalogues()
			// Only the merge depends on the requested language, and it is pure.
			merge(vSnapshot.byLanguage, vRequested, vSnapshot.dates)
		}
	}

	/**
	 * The eleven catalogues and the date table, fetched at most once per [CATALOGUE_MEMO].
	 *
	 * Worth holding for two reasons, both measured. The inputs are **202 KB across 12 requests**,
	 * and TCGdex answers every one of them `Cache-Control: no-cache, no-store, must-revalidate` --
	 * it sends an `ETag` too, but `no-store` makes it unusable, so the HTTP cache cannot help and
	 * every request re-downloads in full.
	 *
	 * And none of it depends on the language asked for. The repository keys its set-list cache by
	 * language, quite rightly since the *names* differ, so switching preferred language used to pay
	 * the whole 202 KB again to re-merge identical inputs. Now it costs nothing.
	 *
	 * The window is deliberately short -- no longer than the repository's own set-list revalidate
	 * interval -- so this never hides a newly published set for any longer than the layer above
	 * would have anyway.
	 *
	 * Fetching under the lock also coalesces concurrent callers: two screens asking at once make
	 * one set of requests rather than two.
	 */
	private suspend fun catalogues(): Catalogues = mCatalogueLock.withLock {
		mCatalogues
			?.takeIf { it.readAt.elapsedNow() < CATALOGUE_MEMO }
			?: fetchCatalogues().also { mCatalogues = it }
	}

	private suspend fun fetchCatalogues(): Catalogues = coroutineScope {
		val vByLanguage = CATALOGUE_LINES
			.flatMap { vLine -> vLine.languages }
			.map { vLanguage -> async { vLanguage to catalogueOf(vLanguage) } }
			.awaitAll()
			.toMap()
		// Every locale failing is a failure; one of eleven failing is not. Not memoised either --
		// `fetchCatalogues` throws before there is anything to hold, so the next call retries.
		if (vByLanguage.values.all { it == null }) {
			throw ProviderError.Unknown("TCGdex served no set catalogue in any locale")
		}
		Catalogues(
			byLanguage = vByLanguage,
			dates = releaseDates(),
			lineById = linesById(vByLanguage),
			readAt = TimeSource.Monotonic.markNow(),
		)
	}

	/**
	 * Set id to the line that publishes it, first line wins.
	 *
	 * Exact-case membership, like everything else keyed on a TCGdex set id: `set=` is matched
	 * case-insensitively by the API, so `SM10` and `sm10` are two different sets in two different
	 * lines and folding their case would merge them.
	 */
	private fun linesById(
		catalogues: Map<CardLanguage, List<TcgdexSetBriefDto>?>,
	): Map<String, CatalogueLine> {
		val vById = mutableMapOf<String, CatalogueLine>()
		for (vLine in CATALOGUE_LINES) {
			for (vLanguage in vLine.languages) {
				catalogues[vLanguage].orEmpty().forEach { vBrief ->
					if (vBrief.id.isNotBlank()) vById.getOrPut(vBrief.id) { vLine }
				}
			}
		}
		return vById
	}

	/**
	 * One locale's catalogue, or `null` when it could not be had.
	 *
	 * `null` rather than an empty list, so a locale that failed is distinguishable from a locale
	 * that is genuinely empty -- the difference between "unknown" and "this set is not published in
	 * Portuguese", which is exactly the claim a language menu is built from.
	 */
	private suspend fun catalogueOf(language: CardLanguage): List<TcgdexSetBriefDto>? = try {
		mClient
			.get(mBaseUrl) {
				url { appendPathSegments("v2", language.code, "sets") }
				// Sorted by the server, oldest first, and the order is the only reason this is
				// asked for. The response still omits `releaseDate` -- so this does not recover the
				// dates -- but the *sequence* is authoritative: checked against the 218 dates the
				// GraphQL endpoint does publish, the sorted English catalogue has zero
				// out-of-order pairs. That is what gives the Japanese and Chinese lines a
				// chronology, since no endpoint will state their dates. See
				// `CardSet.releaseOrder`.
				parameter("sort:field", "releaseDate")
				parameter("sort:order", "ASC")
			}
			.body()
	} catch (vError: kotlinx.coroutines.CancellationException) {
		throw vError
	} catch (vError: Exception) {
		null
	}

	/**
	 * The catalogues, folded into one set per distinct id.
	 *
	 * Three things are decided per set, and each has a reason to prefer one locale over another:
	 *
	 * - **Region** -- the first line, in [CATALOGUE_LINES] order, whose locales carry the id. The
	 *   order is what resolves the 4 genuine collisions: `neo1` is in both the English and Japanese
	 *   catalogues because Japan's Neo Genesis shares the international id, and it is filed under
	 *   the international line rather than appearing twice.
	 * - **Languages** -- every locale that carries the id, which is the whole point of merging.
	 * - **Name and logo** -- the requested language when it has this set, then the set's own line in
	 *   order, then anything. So a French user sees "Édition Base" for an international set and
	 *   「トリプレットビート」 for a Japanese one, which has no French name to show. Logo is resolved
	 *   separately from name because the non-English catalogues frequently omit it: Spanish `base1`
	 *   carries no logo at all, and falling back to English's keeps the tile from going blank.
	 */
	private fun merge(
		catalogues: Map<CardLanguage, List<TcgdexSetBriefDto>?>,
		requested: CardLanguage,
		dates: Map<String, LocalDate>,
	): List<CardSet> {
		val vLanguagesById = mutableMapOf<String, MutableSet<CardLanguage>>()
		val vRegionById = mutableMapOf<String, String>()
		val vBriefsById = mutableMapOf<String, MutableMap<CardLanguage, TcgdexSetBriefDto>>()
		val vRankById = mutableMapOf<String, Int>()

		for (vLine in CATALOGUE_LINES) {
			for (vLanguage in vLine.languages) {
				catalogues[vLanguage].orEmpty().forEachIndexed { vIndex, vBrief ->
					if (vBrief.id.isBlank()) return@forEachIndexed
					vLanguagesById.getOrPut(vBrief.id) { mutableSetOf() }.add(vLanguage)
					vRegionById.getOrPut(vBrief.id) { vLine.region }
					vBriefsById.getOrPut(vBrief.id) { mutableMapOf() }[vLanguage] = vBrief
					// The rank from the first catalogue that carries the set, which by the loop
					// order is the set's own line's own language. A rank is a position within one
					// catalogue, so taking a later locale's would mix two chronologies -- Korean
					// holds 95 of Japan's 184 sets, and position 40 of 95 is not position 40 of
					// 184.
					vRankById.getOrPut(vBrief.id) { vIndex }
				}
			}
		}

		return vBriefsById.mapNotNull { (vId, vBriefs) ->
			val vRegion = vRegionById[vId] ?: return@mapNotNull null
			val vOrder = listOfNotNull(requested) +
				CATALOGUE_LINES.first { it.region == vRegion }.languages +
				vBriefs.keys
			val vPrimary = vOrder.firstNotNullOfOrNull { vBriefs[it] } ?: return@mapNotNull null
			val vWithLogo = vOrder.firstNotNullOfOrNull { vLanguage ->
				vBriefs[vLanguage]?.takeIf { !it.logo.isNullOrBlank() }
			}
			TcgdexMapper.toSet(
				dto = vPrimary,
				provider = id,
				releaseDates = dates,
				region = vRegion,
				languages = vLanguagesById[vId].orEmpty(),
				logo = vWithLogo?.logo,
				releaseOrder = vRankById[vId],
			)
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
		if (request.page > 1) {
			return CardPage(emptyList(), request.page, request.pageSize, totalCount = null, hasMore = false)
		}
		// The set's line, not the user's preference -- see `languageForSet`.
		val vLanguage = languageForSet(request.setId.local, request.language)
		val vLocale = vLanguage.code
		return mapProviderErrors("TCGdex.listCards") {
			val vSet: TcgdexSetDto = mClient
				.get(mBaseUrl) { url { appendPathSegments("v2", vLocale, "sets", request.setId.local) } }
				.body()
			val vMapped = TcgdexMapper.toSet(vSet, id)
			// What the REST response cannot say, asked for once. See `fullCards`.
			val vFull = fullCards(request.setId.local)
			val vCards = if (vMapped == null) {
				emptyList()
			} else {
				vSet.cards.mapNotNull { vBrief ->
					val vDetail = vFull[vBrief.id]
					if (vDetail == null) {
						TcgdexMapper.toPrinting(vBrief, id, vMapped, vLanguage)
					} else {
						// The name and the picture stay the locale's; only the facts the brief
						// does not carry come from the other request. A French set keeps its
						// French names and gains its rarities.
						TcgdexMapper.toPrinting(
							vDetail.copy(name = vBrief.name, image = vBrief.image ?: vDetail.image),
							id,
							vMapped,
							vLanguage,
						)
					}
				}
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

	/**
	 * A set's cards with their rarity, category and types, by card id, or empty if that failed.
	 *
	 * `GET /{lang}/sets/{id}` returns **brief** cards -- `{id, localId, name, image}` and nothing
	 * else. So every Pokémon card in the grid had no rarity, no category and no type, which is why
	 * the filter sheet showed none of those sections: they are drawn from the facets of what is on
	 * screen, and what was on screen knew nothing. The alternative is `GET /cards/{id}` per card,
	 * 252 requests for one set.
	 *
	 * One GraphQL query instead. The root `cards` resolver returns full cards and a card id is
	 * `{setId}-{localId}`, so `id: "sv08-"` asks for the set and answered all 252 in one request
	 * on 2026-09-13.
	 *
	 * **The `id` filter is a `contains` match, not a prefix match.** Measured on 2026-09-13:
	 * `id: "M2-"` returns 250 cards, 125 from `sm2` and 125 from `gym2`, and **none** from `M2`.
	 * The comment here used to say prefix, which is where the trailing dash came from -- the dash
	 * is still right, because without it `sv08` also matches `sv08.5`, but it was right by
	 * accident. So the answer is filtered to ids that really belong to the set: without that, a
	 * short set code silently fills its page with another set's cards and can crowd its own out.
	 *
	 * **English only, which is about the product *line* rather than the reader's language.**
	 * `/v2/ja/graphql` is a 404 and both `?lang=` and `Accept-Language` are ignored. But the seven
	 * western locales share one id space, so a French `base1` card has the same id as the English
	 * one and this answers perfectly for it -- the test that pins that asks in French. What it
	 * cannot answer for is a set whose line English does not carry at all: the Japanese line, and
	 * the Simplified Chinese one. Those are not asked, which is what stops a Japanese set
	 * downloading 250 cards of somebody else's every time it is opened. They keep the "unknown"
	 * rarity they had before, which is the source's gap rather than this adapter's.
	 *
	 * Empty on any failure. A rarity nobody could fetch is a filter the sheet does not offer,
	 * which is the honest outcome; it is not a reason to fail a set that has already arrived.
	 */
	private suspend fun fullCards(setLocal: String): Map<String, TcgdexCardDto> {
		// Unknown means ask. A set the catalogue lookup cannot place is the state this adapter was
		// already in before the line grouping existed, and a wasted request is better than a
		// silently unfiltered set.
		val vLine = runCatching { catalogues().lineById[setLocal] }.getOrNull()
		if (vLine != null && CardLanguage.ENGLISH !in vLine.languages) return emptyMap()
		return try {
			val vResponse: TcgdexGraphQlResponse = mClient
				.post(mBaseUrl) {
					url { appendPathSegments("v2", "graphql") }
					contentType(ContentType.Application.Json)
					setBody(GraphQlQuery(fullCardsQuery(setLocal)))
				}
				.body()
			vResponse.data?.cards.orEmpty()
				// Only this set's. See the `contains` note above.
				.filter { it.id.startsWith("$setLocal-") }
				.associateBy { it.id }
		} catch (vError: kotlinx.coroutines.CancellationException) {
			throw vError
		} catch (vError: Exception) {
			emptyMap()
		}
	}

	/**
	 * Which of a set's claimed languages TCGdex actually holds cards for.
	 *
	 * Worth the requests because the catalogue over-claims, and not by a little. A set brief
	 * carries a card count in every locale that lists it -- Spanish `base1` says 102 -- and the set
	 * endpoint then returns an empty card list. Measured: French, German, Spanish, Italian and
	 * Portuguese all list `base4` and none has a card of it; Spanish and Italian list `col1` and
	 * neither has one; and **the entire Korean catalogue** is 95 named sets with claimed counts and
	 * no card data at all, as is Simplified Chinese. Modern sets are complete in every locale, so
	 * this is a fact about how far back each translation was backfilled and there is no rule to
	 * infer it from.
	 *
	 * The probe is `GET /{lang}/cards?set={id}` asking for one item: about 100 bytes for a hit and
	 * 2 bytes for a miss, one per candidate, all in parallel. That is the difference between a menu
	 * whose entries all work and a menu the user has to test by hand.
	 *
	 * `set=` is matched case-insensitively, which is why this only ever probes the candidates the
	 * caller already established from exact-case catalogue membership. Probing English for the
	 * Japanese `SM10` would answer yes -- with international Unbroken Bonds.
	 *
	 * A probe that fails keeps its language, because a language is dropped only on evidence that
	 * the set is not published in it, and a timeout is not that.
	 */
	override suspend fun confirmLanguages(
		setId: SourceId,
		candidates: Set<CardLanguage>,
	): Set<CardLanguage> = coroutineScope {
		candidates
			.map { vLanguage -> async { vLanguage to hasCards(vLanguage, setId.local) } }
			.awaitAll()
			.filter { it.second }
			.mapTo(mutableSetOf()) { it.first }
	}

	/** True when TCGdex holds at least one card of this set in this locale, or could not say. */
	private suspend fun hasCards(language: CardLanguage, setLocal: String): Boolean = try {
		val vProbe: List<TcgdexCardBriefDto> = mClient
			.get(mBaseUrl) {
				url { appendPathSegments("v2", language.code, "cards") }
				parameter("set", setLocal)
				parameter("pagination:page", 1)
				parameter("pagination:itemsPerPage", 1)
			}
			.body()
		vProbe.isNotEmpty()
	} catch (vError: kotlinx.coroutines.CancellationException) {
		throw vError
	} catch (vError: Exception) {
		// Unknown, not absent. Keeping the language leaves the user a switch that may fail and
		// says so; dropping it hides an edition that probably exists.
		true
	}

	override suspend fun cardDetail(id: SourceId, language: CardLanguage?): CardPrinting? {
		// A card id is `{setId}-{localId}`, so the set it belongs to -- and therefore its line --
		// is in the id. Same reason as `listCards`: the wrong locale is a 404, not a translation.
		val vLanguage = languageForSet(id.local.substringBeforeLast('-'), language)
		return mapProviderErrors("TCGdex.cardDetail") {
			try {
				val vCard: TcgdexCardDto = mClient
					.get(mBaseUrl) { url { appendPathSegments("v2", vLanguage.code, "cards", id.local) } }
					.body()
				TcgdexMapper.toPrinting(vCard, this.id, set = null, language = vLanguage)
			} catch (vError: ClientRequestException) {
				if (vError.response.status == HttpStatusCode.NotFound) null else throw vError
			}
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

	/** The language this adapter will really answer in. Never null: every locale exists. */
	private fun languageFor(language: CardLanguage?): CardLanguage =
		resolveLanguage(language) ?: CardLanguage.ENGLISH

	/**
	 * The language a *particular set* can be asked for, which is not always the one requested.
	 *
	 * A TCGdex set id exists only inside the locales of its own product line: `/en/sets/SV1a` and
	 * `/fr/sets/SV1a` are 404, and so is `/en/sets/SC1D`. Resolving the locale from the user's
	 * preference alone therefore left every Japanese, Taiwanese and Chinese set listed with a card
	 * count and opening to nothing at all -- no cards, no images -- for anyone not browsing in one
	 * of those languages. Measured 2026-09-13.
	 *
	 * So the set's line decides. The requested language wins when the line publishes it, which is
	 * what keeps a Korean-preferring user reading the Japan line in Korean; otherwise the line's
	 * own first language answers. The record is then stamped with what was *served*, not what was
	 * asked for, so a Japanese-line set browsed in English says Japanese on the card and in the
	 * grid's language menu -- which is the truth and is the whole point of the distinction.
	 *
	 * Falls back to [languageFor] when the catalogues cannot be read or do not know the id. That is
	 * the old behaviour, which is right for a set this adapter has never seen listed: guessing a
	 * line for it would be inventing one.
	 */
	private suspend fun languageForSet(setLocal: String, language: CardLanguage?): CardLanguage {
		val vLine = runCatching { catalogues().lineById[setLocal] }.getOrNull()
			?: return languageFor(language)
		val vRequested = resolveLanguage(language)
		return if (vRequested != null && vRequested in vLine.languages) {
			vRequested
		} else {
			vLine.languages.first()
		}
	}

	/** The GraphQL request envelope. One field, because one query is all this adapter sends. */
	@Serializable
	private data class GraphQlQuery(val query: String)

	/**
	 * @param lineById which product line each set id belongs to, first line to carry it winning --
	 *   the same order [merge] assigns a region in, so the two cannot disagree about a set
	 */
	private class Catalogues(
		val byLanguage: Map<CardLanguage, List<TcgdexSetBriefDto>?>,
		val dates: Map<String, LocalDate>,
		val lineById: Map<String, CatalogueLine>,
		val readAt: TimeMark,
	)

	companion object {

		/**
		 * TCGdex publishes no rate limit, and this paces anyway.
		 *
		 * It is the one adapter here that fans out: `fetchCatalogues` puts eleven locale requests
		 * in flight at once and `confirmLanguages` one per candidate, so a single `listSets` is an
		 * eleven-wide burst at one host rather than a request. Nothing about that is hostile at
		 * app scale -- it happens once per five minutes and is then memoised -- but a live test
		 * sweep repeats it, and an unpaced burst is not a thing to send at a free API on the
		 * grounds that nobody has asked us not to.
		 *
		 * 50 ms, matching YGOPRODeck's. The throttle serialises the fan-out, which costs about
		 * half a second on a `listSets` that is made once.
		 */
		val HTTP_POLICY: ProviderHttpPolicy = ProviderHttpPolicy(minRequestInterval = 50.milliseconds)

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

		/**
		 * How long the fetched catalogues stand in for a fresh read.
		 *
		 * Matched to `CardRepository.DEFAULT_SET_LIST_REVALIDATE_MILLIS`, so holding them cannot
		 * delay a new set appearing by longer than the repository already does.
		 */
		private val CATALOGUE_MEMO: Duration = 5.minutes

		/** Ids and dates for every set, which the REST catalogue does not carry. */
		private const val RELEASE_DATE_QUERY = "{ sets { id releaseDate } }"

		/**
		 * Every card of one set, with the fields the REST set endpoint leaves out.
		 *
		 * `itemsPerPage` is [MAX_PAGE_SIZE], which is above every real set size, so this is one
		 * page by construction -- the same reasoning that makes the REST call unpaged.
		 *
		 * Only the fields that are used: asking for a non-nullable field a brief card cannot fill
		 * makes the server answer the whole query with errors, which is how `set { cards { rarity
		 * } }` was ruled out.
		 */
		private fun fullCardsQuery(setLocal: String): String =
			"{ cards(filters: { id: \"$setLocal-\" }, " +
				"pagination: { page: 1, itemsPerPage: $MAX_PAGE_SIZE }) " +
				"{ id localId name rarity category types hp illustrator stage suffix " +
				"trainerType energyType } }"

		/**
		 * TCGdex's locales, grouped into the product lines they publish, highest priority first.
		 *
		 * The grouping is measured, not assumed. Exact-case id overlap across the eleven
		 * catalogues:
		 *
		 * - the seven western locales share one id space entirely -- German, Spanish, Italian and
		 *   Portuguese are subsets of English's 218, French adds 3 McDonald's sets, Russian holds 9
		 *   XY sets -- so they are one line, 221 sets
		 * - Japanese holds 184 and Korean 95, and **every Korean id is a Japanese id**: Korea prints
		 *   the Japanese line, so Korean is a language of it rather than a line of its own
		 * - Traditional Chinese holds 98, of which 62 are Japanese ids and 36 are an exclusive
		 *   `SC*` Sword & Shield line
		 * - Simplified Chinese holds 56, 49 of them its own
		 *
		 * A locale appears once. Korean sits under Japan, and Traditional Chinese under its own
		 * line, because its Japanese-id sets are picked up as a language of the Japan line by
		 * priority -- which is what leaves exactly its 36 exclusives behind.
		 *
		 * ## A set id lives inside its own line
		 *
		 * `/en/sets/SV1a`, `/fr/sets/SV1a` and `/en/sets/SC1D` are all 404, checked on 2026-09-13.
		 * So a line is not decoration on the set list: it decides which locale a set's *cards* can
		 * be asked for, which is why [languageForSet] exists and why asking in the user's own
		 * language alone left every Japanese, Taiwanese and Chinese set listed and empty.
		 */
		private val CATALOGUE_LINES: List<CatalogueLine> = listOf(
			CatalogueLine(
				region = PokemonGame.REGION_INTERNATIONAL,
				languages = listOf(
					CardLanguage.ENGLISH,
					CardLanguage.FRENCH,
					CardLanguage.GERMAN,
					CardLanguage.SPANISH,
					CardLanguage.ITALIAN,
					CardLanguage.PORTUGUESE,
					CardLanguage.RUSSIAN,
				),
			),
			CatalogueLine(
				region = PokemonGame.REGION_JAPAN,
				languages = listOf(CardLanguage.JAPANESE, CardLanguage.KOREAN),
			),
			CatalogueLine(
				region = PokemonGame.REGION_TAIWAN,
				languages = listOf(CardLanguage.TRADITIONAL_CHINESE),
			),
			CatalogueLine(
				region = PokemonGame.REGION_CHINA,
				languages = listOf(CardLanguage.SIMPLIFIED_CHINESE),
			),
		)
	}

	/**
	 * One product line and the locales that publish it.
	 *
	 * @param region a `GameRegion` key declared by [PokemonGame]
	 * @param languages in fallback order, so the first is the line's own language -- English for the
	 *   international line, Japanese for Japan -- and is what names a set the requested language has
	 *   no name for
	 */
	private data class CatalogueLine(
		val region: String,
		val languages: List<CardLanguage>,
	)
}
