package com.bitsycore.cardbrowser.providers.altered

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
import com.bitsycore.cardbrowser.data.net.mapProviderErrors
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments

/**
 * The Altered adapter, serving [Game.ALTERED].
 *
 * ## Why this reads from a mirror rather than an API
 *
 * Altered's official API is gone. Checked on 2026-09-08:
 *
 * - `api.altered.gg` has **no DNS A record at all** -- verified against Cloudflare's resolver, not
 *   just this machine's, so it is not a local network problem.
 * - The official art bucket, `altered-prod-eu.s3.amazonaws.com`, answers **403** for every card
 *   image the data itself links to.
 *
 * What survives is `PolluxTroy0/Altered-TCG-Card-Database`, a community mirror that stores the
 * official API's own JSON **unmodified** -- same Hydra envelopes, same field names, same enum
 * references -- alongside a copy of the card images. Served through jsDelivr, which is a real CDN
 * rather than raw file hosting.
 *
 * That makes this the one adapter here reading static files instead of an API, and it is stated
 * plainly rather than dressed up: the data is a snapshot maintained by a volunteer, and a card
 * printed after the mirror's last update will not appear.
 *
 * ## Coverage, as verified on 2026-09-08
 *
 * - **Languages**: the mirror carries `de`, `en`, `es`, `fr`, `it`. Of the app's four, French and
 *   English. French matters here: Altered is a French game, French is the app's first preference,
 *   and this is the one game where the top preference is genuinely served. Japanese and Korean are
 *   UNKNOWN, not absent.
 * - **Sets**: 20, from `META/card_sets_{lang}.json`, with localised names and printed codes.
 * - **Cards**: one file per set per language, complete and unpaged -- 327 cards, about 1 MB, for
 *   `ALIZE_FR`. Like TCGdex, completeness here is a fact rather than an inference.
 * - **Release dates**: none that mean anything. See `AlteredMapper.toSet` for why `createdAt` is
 *   not used as one.
 * - **Images**: **full-size JPEGs only, 200–300 KB each, with no thumbnail variant and no resizing
 *   CDN.** The grid loads the same file the detail screen does. This is the one place in the app
 *   where a set's tiles are expensive, and it is declared on the artwork rather than hidden by
 *   pointing a "thumbnail" at the full image.
 * - **Finishes and card identity**: no fields exist. Both unstated.
 * - **Cross-set search**: not possible. These are static files with no query layer, and searching
 *   across sets would mean downloading all twenty. [DataCapabilities.crossSetSearch] is therefore
 *   false and the search screen falls back to the sets already on disk, labelled as such.
 */
class AlteredProvider(
	private val mClient: HttpClient,
	private val mBaseUrl: String = DEFAULT_BASE_URL,
) : CardProvider {

	override val id: ProviderId = PROVIDER_ID

	override val displayName: String = "Altered TCG Card Database"

	override val capabilities: ProviderCapabilities = ProviderCapabilities(
		games = setOf(Game.ALTERED),
		filtering = FilterSupport(
			// Static files. There is no server to ask, so everything is local by construction
			// rather than by choice.
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
			languages = setOf(CardLanguage.FRENCH, CardLanguage.ENGLISH),
			localizedText = true,
			localizedImages = true,
			cardIdentity = false,
			artworkVariants = false,
			finishes = false,
			cardmarketProductMapping = false,
			crossSetSearch = false,
		),
		attribution = Attribution(
			text = "Altered card data and images from the community Altered TCG Card Database. " +
				"Altered is a trademark of Equinox; this app is not affiliated with them.",
			url = "https://github.com/PolluxTroy0/Altered-TCG-Card-Database",
		),
		maxPageSize = MAX_PAGE_SIZE,
	)

	// ============
	//  Sets

	override suspend fun listSets(game: Game, language: CardLanguage?): List<CardSet> {
		require(game == Game.ALTERED) { "This adapter serves Altered only, not $game" }
		val vLocale = localeFor(language)
		return mapProviderErrors("Altered.listSets") {
			val vIndex: AlteredSetIndexDto = mClient
				.get(mBaseUrl) { url { appendPathSegments("META", "card_sets_$vLocale.json") } }
				.body()
			vIndex.members.mapNotNull { AlteredMapper.toSet(it, id) }
		}
	}

	// ============
	//  Cards

	/** One file is one whole set, so page 2 is always empty and says so. */
	override suspend fun listCards(request: CardPageRequest): CardPage {
		if (request.page > 1) {
			return CardPage(emptyList(), request.page, request.pageSize, totalCount = null, hasMore = false)
		}
		val vLanguage = languageFor(request.language)
		val vSetRef = request.setId.local
		return mapProviderErrors("Altered.listCards") {
			val vCards: List<AlteredCardDto> = try {
				mClient
					.get(mBaseUrl) {
						url {
							// `SETS/ALIZE/ALIZE_FR.json` -- the locale suffix is upper case in the
							// filename while the directory under IMAGES is lower case.
							appendPathSegments("SETS", vSetRef, "${vSetRef}_${vLanguage.code.uppercase()}.json")
						}
					}
					.body()
			} catch (vError: ClientRequestException) {
				// A set the mirror lists but has not published a file for yet. An empty page with
				// `hasMore` false is the truth, and the repository records it as a complete set of
				// zero cards rather than as a failure -- which it then re-checks on the next TTL.
				if (vError.response.status == HttpStatusCode.NotFound) emptyList() else throw vError
			}

			// The card records name their own set, but the request already established which set
			// this is, so a minimal one is built rather than trusting a per-card field to agree.
			val vSet = CardSet(
				id = request.setId,
				game = Game.ALTERED,
				code = vCards.firstOrNull()?.collectorNumber?.substringBefore('-')?.ifBlank { null }
					?: vSetRef.uppercase(),
				name = vCards.firstOrNull()?.cardSet?.name?.ifBlank { null } ?: vSetRef,
				cardCount = vCards.size,
				releaseDate = null,
			)

			val vMapped = vCards.mapNotNull {
				AlteredMapper.toPrinting(it, id, vSet, vLanguage, imageBaseUrl())
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
	 * Always `null`.
	 *
	 * There is no per-card file in the mirror -- a card exists only inside its set's file -- so
	 * there is nothing to fetch by id. That is fine in practice: the repository looks a card up in
	 * the cached complete set first, and for this provider the complete set is always what was
	 * downloaded. Returning null rather than downloading the whole set again is the honest answer
	 * to "can you fetch just this one card?".
	 */
	override suspend fun cardDetail(id: SourceId, language: CardLanguage?): CardPrinting? = null

	// ============
	//  Locale

	/** The uppercase locale suffix used in set filenames, from the app's preference order. */
	private fun localeFor(language: CardLanguage?): String = languageFor(language).code

	private fun languageFor(language: CardLanguage?): CardLanguage =
		resolveLanguage(language) ?: CardLanguage.FRENCH

	/** Where the mirrored card images live, since the official bucket answers 403. */
	private fun imageBaseUrl(): String = "$mBaseUrl/IMAGES"

	companion object {

		/** Never changed: it is written into every id and every cache file this adapter produces. */
		val PROVIDER_ID: ProviderId = ProviderId("altered-db")

		/**
		 * jsDelivr rather than `raw.githubusercontent.com`.
		 *
		 * Both serve the same bytes -- verified, identical content length -- but raw GitHub is file
		 * hosting with request limits and short cache headers, while jsDelivr is a CDN built for
		 * exactly this. Pinned to `@main` rather than a commit so the mirror's updates arrive.
		 */
		const val DEFAULT_BASE_URL: String =
			"https://cdn.jsdelivr.net/gh/PolluxTroy0/Altered-TCG-Card-Database@main"

		/** Larger than the largest set file, because there is no paging to bound. */
		const val MAX_PAGE_SIZE: Int = 1000
	}
}
