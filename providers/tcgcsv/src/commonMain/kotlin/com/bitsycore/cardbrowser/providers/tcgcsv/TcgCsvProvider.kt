package com.bitsycore.cardbrowser.providers.tcgcsv

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardPage
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import com.bitsycore.cardbrowser.core.provider.CardProvider
import com.bitsycore.cardbrowser.data.net.mapProviderErrors
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.appendPathSegments
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * The shared engine behind the three TCGCSV adapters.
 *
 * ## What TCGCSV is
 *
 * https://tcgcsv.com -- a public mirror of TCGplayer's product catalogue, served as static JSON.
 * No key, no auth, no rate limit published. Two endpoints matter:
 *
 * ```
 * /tcgplayer/{category}/groups              every set in a game
 * /tcgplayer/{category}/{group}/products    every product in one set
 * ```
 *
 * A *category* is a game and a *group* is a set. That is the entire shape, which is why one class
 * serves three games: they differ in a number and in which `extendedData` fields their records
 * carry, and nothing else. The per-game part is a [TcgCsvMapping].
 *
 * ## What it costs to use a marketplace as a card database
 *
 * Three consequences, all of them declared rather than papered over:
 *
 * - **No search endpoint.** There is no query interface of any kind, so `crossSetSearch` is false
 *   and every filter is `localOnly`. A text search covers the sets already downloaded and the app
 *   says so, with a count -- which is a different answer from "no such card exists".
 * - **No per-product endpoint.** Products are only ever served a whole group at a time, so a card's
 *   id carries its group; see `TcgCsvMapper.cardId`.
 * - **Sealed product shares the catalogue.** Booster boxes and starter decks are products too, and
 *   are filtered out by the fields they do not have.
 *
 * ## What is deliberately not mapped
 *
 * Prices. TCGCSV's main purpose is to publish them and this app ignores them entirely -- it is a
 * browser rather than a price guide, and this is the one source here where taking them would be
 * easy enough to be tempting. The `url` field pointing at a TCGplayer listing is likewise unused.
 *
 * @property mCategoryId TCGplayer's category number for this game, read off the live catalogue
 * @property mMapping which of this game's `extendedData` fields mean what
 */
abstract class TcgCsvProvider<out G : GameProfile>(
	private val mClient: HttpClient,
	private val mBaseUrl: String = DEFAULT_BASE_URL,
) : CardProvider<G> {

	// `internal` rather than `protected`: a mapping is this module's own vocabulary and does not
	// leave it, and a protected member may not expose an internal type. Every subclass is in here.
	internal abstract val mCategoryId: Int

	internal abstract val mMapping: TcgCsvMapping

	override val displayName: String = "TCGCSV"

	// ============
	//  Sets

	override suspend fun listSets(language: CardLanguage?): List<CardSet> {
		// `language` is accepted and ignored. The catalogue states no language anywhere, so there
		// is no per-language edition to select and nothing here may claim one.
		return mapProviderErrors("TCGCSV.listSets") {
			val vResponse = fetch(TcgCsvGroupDto.serializer(), "tcgplayer", "$mCategoryId", "groups")
			vResponse.results.map { TcgCsvMapper.toSet(it, id, game) }
		}
	}

	// ============
	//  Cards

	/** The whole group arrives in one request, so page 2 is always empty and says so. */
	override suspend fun listCards(request: CardPageRequest): CardPage {
		if (request.page > 1) {
			return CardPage(emptyList(), request.page, request.pageSize, totalCount = null, hasMore = false)
		}
		return mapProviderErrors("TCGCSV.listCards") {
			val vCards = fetchGroup(request.setId.local, set = null)
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
	 * One card, by fetching its group and picking it out.
	 *
	 * A whole group for one card looks wasteful and is the only thing this source allows: there is
	 * no per-product endpoint. It is also rarer than it looks -- the repository answers from the
	 * cached set first, so this runs only for a card opened without its set in hand.
	 */
	override suspend fun cardDetail(id: SourceId, language: CardLanguage?): CardPrinting? {
		val vGroup = TcgCsvMapper.groupOf(id) ?: return null
		return mapProviderErrors("TCGCSV.cardDetail") {
			fetchGroup(vGroup.toString(), set = null).firstOrNull { it.id == id }
		}
	}

	/** Every card in one group, sealed product filtered out. */
	private suspend fun fetchGroup(group: String, set: CardSet?): List<CardPrinting> {
		val vResponse = fetch(TcgCsvProductDto.serializer(), "tcgplayer", "$mCategoryId", group, "products")
		return vResponse.results.mapNotNull {
			TcgCsvMapper.toPrinting(it, id, game, mMapping, set)
		}
	}

	/**
	 * Reads a response as text and parses it here, rather than letting content negotiation do it.
	 *
	 * **TCGCSV does not answer with one content type.** Measured on 2026-09-09: the products of
	 * category 13 group 1100 come back as `text/json`, while that same category's groups and every
	 * Lorcana response come back as `application/json`. Ktor's `ContentNegotiation` is registered
	 * for the latter only, so `body<T>()` succeeded for two of the three games and threw
	 * "Expected response body of the type ... but was SourceByteReadChannel" for the third -- a
	 * failure that looks like a broken DTO and is nothing of the kind.
	 *
	 * Parsing the text directly makes the adapter indifferent to which one arrives. The alternative
	 * -- teaching the shared client that `text/json` is JSON -- would change the transport for every
	 * other provider and for the image loader to accommodate one service's quirk, which is the sort
	 * of thing this module exists to keep to itself.
	 */
	private suspend fun <T> fetch(
		serializer: KSerializer<T>,
		vararg segments: String,
	): TcgCsvEnvelope<T> {
		val vBody: String = mClient
			.get(mBaseUrl) { url { appendPathSegments(*segments) } }
			.body()
		return JSON.decodeFromString(TcgCsvEnvelope.serializer(serializer), vBody)
	}

	companion object {

		const val DEFAULT_BASE_URL: String = "https://tcgcsv.com"

		/**
		 * Larger than the largest group any of the three catalogues serves.
		 *
		 * Not a server limit -- there is no paging. This tells the repository that one request is
		 * always enough, so it never plans a second page that would come back empty.
		 */
		const val MAX_PAGE_SIZE: Int = 1000

		/**
		 * This module's own parser -- see [fetch] for why the shared one is bypassed.
		 *
		 * `ignoreUnknownKeys` because the catalogue carries per-game fields this app has no use for
		 * and TCGplayer adds more of them over time; an unknown key is not a reason to fail a set.
		 */
		private val JSON: Json = Json { ignoreUnknownKeys = true }
	}
}
