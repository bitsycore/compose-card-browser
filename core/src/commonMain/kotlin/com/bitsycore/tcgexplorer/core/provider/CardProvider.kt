package com.bitsycore.tcgexplorer.core.provider

import com.bitsycore.tcgexplorer.core.game.GameProfile
import com.bitsycore.tcgexplorer.core.model.CardLanguage
import com.bitsycore.tcgexplorer.core.model.CardPrinting
import com.bitsycore.tcgexplorer.core.model.CardSet
import com.bitsycore.tcgexplorer.core.model.ProviderId
import com.bitsycore.tcgexplorer.core.model.SourceId

// ==================
// MARK: Contract
// ==================

/**
 * One data source, adapted. The only thing the repository knows about a source.
 *
 * An adapter owns its endpoints, DTOs, mapping and quirks. Upward it shows this interface and its
 * [capabilities]. See docs/PROVIDERS.md to add one.
 *
 * The game is in the type: `RiftcodexProvider : CardProvider<RiftboundGame>`. Every adapter serves
 * exactly one game. [G] is covariant, so the registry can hold them all in one list.
 *
 * Every method suspends and must be cancellable. Let `CancellationException` through; never wrap it
 * in a [ProviderError].
 */
interface CardProvider<out G : GameProfile> {

	/** Stable and never changed once shipped; it is baked into every id and every cache file. */
	val id: ProviderId

	/** For the UI and for attribution notices. */
	val displayName: String

	/**
	 * The game this adapter serves.
	 *
	 * The routing table says which provider answers for a game; this says the reverse.
	 * `ProviderRegistry` checks the two agree at startup.
	 */
	val game: G

	/** What this provider can supply. Provider-wide -- an individual set may still offer less. */
	val capabilities: ProviderCapabilities

	/**
	 * Every set, newest first where release dates are known.
	 *
	 * Never paginated. The set list sorts and searches over the whole catalogue, so an adapter that
	 * needs paging must page internally and still return everything.
	 */
	suspend fun listSets(language: CardLanguage? = null): List<CardSet>

	/**
	 * One page of a set's cards.
	 *
	 * Apply only the filters [ProviderCapabilities.filtering] lists as remote. The caller applies
	 * the rest and knows from the same capabilities that it must. Never ignore a filter you claim.
	 */
	suspend fun listCards(request: CardPageRequest): CardPage

	/** One printing by its source-qualified id, or `null` when the provider has no such record. */
	suspend fun cardDetail(id: SourceId, language: CardLanguage? = null): CardPrinting?

	/**
	 * The language this source will really answer in when asked for [requested].
	 *
	 * Ask before requesting, so the user can be told "you asked for French, this source has only
	 * English" instead of getting English labelled French.
	 *
	 * The default takes the first language the source carries in preference order. `null` means the
	 * source states no language at all. That is silence, not English: do not invent one from it.
	 */
	fun resolveLanguage(requested: CardLanguage? = null): CardLanguage? {
		val vAvailable = capabilities.data.languages
		if (vAvailable.isEmpty()) return null
		if (requested != null && requested in vAvailable) return requested
		return CardLanguage.PREFERENCE_ORDER.firstOrNull { it in vAvailable } ?: vAvailable.first()
	}

	/**
	 * Which of [candidates] this source really has cards for, in one set.
	 *
	 * The default confirms all of them. Override where a source lists a set in a language it has no
	 * cards for -- the set list cannot see that, so the user would find out one language at a time.
	 *
	 * Only worth overriding when the check is cheap. TCGdex's is one small request per candidate.
	 *
	 * @param candidates the languages the set claims, already narrower than [DataCapabilities.languages]
	 */
	suspend fun confirmLanguages(setId: SourceId, candidates: Set<CardLanguage>): Set<CardLanguage> =
		candidates

}

// ==================
// MARK: Capabilities
// ==================

/**
 * What a source can supply, stated once for the whole source.
 *
 * Describes the source, never one set or printing. "This source can carry French" belongs here;
 * "this printing exists in French" is `LanguageCoverage` on the printing. Capability is the
 * ceiling, coverage is the fact.
 */
data class ProviderCapabilities(
	val filtering: FilterSupport,
	val sorting: Set<CardSortField>,
	val data: DataCapabilities,
	/** Shown wherever the provider's data is, when the provider's terms ask for it. */
	val attribution: Attribution?,
	/**
	 * The largest page the provider will serve. The repository uses this to work out how many
	 * requests a complete set costs before it decides whether local filtering is feasible.
	 */
	val maxPageSize: Int,
)

/** An attribution notice a provider's terms require, and where to send someone who taps it. */
data class Attribution(
	val text: String,
	val url: String?,
)

/**
 * Which filters the source applies, and which the app must apply to a whole set itself.
 *
 * [remote] costs one request. [localOnly] costs every page of the set before one correct result can
 * be shown -- so the repository has to know, and the UI has to be able to say "partial".
 *
 * A field in neither is not offered at all, and the UI must not draw it.
 */
data class FilterSupport(
	val remote: Set<CardFilterField>,
	val localOnly: Set<CardFilterField>,
) {

	/** Every filter this provider can honour, however it honours it. */
	val supported: Set<CardFilterField> get() = remote + localOnly

	/** True when honouring [query] needs every card in the set fetched first. */
	fun requiresCompleteSet(query: CardQuery): Boolean =
		query.activeFields().any { it in localOnly }
}

/** What kinds of data a provider carries at all. */
data class DataCapabilities(
	/** Printing languages the provider can describe. Empty means it never states a language. */
	val languages: Set<CardLanguage>,
	/** True when the provider serves text translated into [languages], not merely printings of them. */
	val localizedText: Boolean,
	/** True when the provider serves per-language images. */
	val localizedImages: Boolean,
	/** True when the provider states which printings are the same card. */
	val cardIdentity: Boolean,
	/** True when the provider distinguishes artwork variants. */
	val artworkVariants: Boolean,
	/** True when the provider states finishes. False means finish is unknown, not absent. */
	val finishes: Boolean,
	/** True when the provider maps individual printings to Cardmarket *products*. */
	val cardmarketProductMapping: Boolean,
	/**
	 * True when the card records ship inside the app.
	 *
	 * Nothing to download, keep or clear. A download button would do no work and a delete button
	 * would either lie or break the game.
	 *
	 * Records only. Artwork is still fetched.
	 */
	val bundledCardData: Boolean = false,
	/**
	 * True when the source publishes a small image as well as the full one.
	 *
	 * This decides what an image download costs. Measured 2026-09-09: TCGdex 19.5 KB against 63 KB
	 * full, Scryfall 47 against 67, YGOPRODeck 28 against 153. With no small image the app falls
	 * back to the full one -- Wuthering Waves' is 196 KB -- so calling that a thumbnail download
	 * would understate it about tenfold.
	 *
	 * False is the safe default: the dialog then shows the honest label and size.
	 */
	val thumbnailImages: Boolean = false,
	/**
	 * True when the source wants its *catalogue* taken from its dump rather than walked set by set.
	 *
	 * How the source wants to be used, not what it can do. A dump exists so clients stop walking the
	 * API for data already packaged; 988 sets fetched one at a time is the traffic that gets an app
	 * blocked.
	 *
	 * What it costs: the whole-game download takes the file and never fans out per set. One set is
	 * still fetched from the API like any other source's -- that is a handful of requests for
	 * something the reader is looking at, and it is the same traffic browsing the set already makes.
	 * The single-set dialog names the file so the cheaper route is visible, which is a steer rather
	 * than a refusal.
	 *
	 * It used to refuse card info per set outright. That was too blunt: it left the only way to hold
	 * one Magic set offline being a 78 MB import of all 988.
	 *
	 * Not implied by publishing a dump: a partial or rarely-rebuilt dump is an option, not the only
	 * route.
	 */
	val cardInfoFromBulkOnly: Boolean = false,
)

// ==================
// MARK: Query
// ==================

/** A filterable field. What a given provider actually supports is its [FilterSupport]. */
enum class CardFilterField {
	TEXT,
	DOMAIN,
	CARD_TYPE,
	RARITY,

	/** The single play cost. `GameVocabulary.cost` is what each game calls it. */
	COST,
	ARTWORK_TREATMENT,
	FINISH,
	LANGUAGE,
}

/** A sortable field. What a given provider supports is [ProviderCapabilities.sorting]. */
enum class CardSortField {
	COLLECTOR_NUMBER,
	NAME,
	RARITY,
	COST,
}

/** Ascending or descending. */
enum class SortDirection {
	ASCENDING,
	DESCENDING,
}

/**
 * A request for cards in one set.
 *
 * [text] matches name or collector number -- one field, because that is one search box. Each
 * collection is an OR within itself and an AND against the others.
 */
data class CardQuery(
	val text: String? = null,
	val domains: Set<String> = emptySet(),
	val cardTypes: Set<String> = emptySet(),
	val rarities: Set<String> = emptySet(),
	val costs: Set<Int> = emptySet(),
	val treatments: Set<com.bitsycore.tcgexplorer.core.model.ArtworkTreatment> = emptySet(),
	val finishes: Set<com.bitsycore.tcgexplorer.core.model.Finish> = emptySet(),
	val languages: Set<CardLanguage> = emptySet(),
	val sortBy: CardSortField = CardSortField.COLLECTOR_NUMBER,
	val sortDirection: SortDirection = SortDirection.ASCENDING,
) {

	/** Which filter fields this query actually constrains. */
	fun activeFields(): Set<CardFilterField> = buildSet {
		if (!text.isNullOrBlank()) add(CardFilterField.TEXT)
		if (domains.isNotEmpty()) add(CardFilterField.DOMAIN)
		if (cardTypes.isNotEmpty()) add(CardFilterField.CARD_TYPE)
		if (rarities.isNotEmpty()) add(CardFilterField.RARITY)
		if (costs.isNotEmpty()) add(CardFilterField.COST)
		if (treatments.isNotEmpty()) add(CardFilterField.ARTWORK_TREATMENT)
		if (finishes.isNotEmpty()) add(CardFilterField.FINISH)
		if (languages.isNotEmpty()) add(CardFilterField.LANGUAGE)
	}

	/** True when nothing is constrained, so the whole set is the answer. */
	val isEmpty: Boolean get() = activeFields().isEmpty()

	/** How many filters are on, for the "3 filters active" chip. */
	val activeCount: Int get() = activeFields().size
}

/** One page of a set, as asked for. [page] is 1-based, matching every provider seen so far. */
data class CardPageRequest(
	val setId: SourceId,
	val query: CardQuery = CardQuery(),
	val page: Int = 1,
	val pageSize: Int = 100,
	/**
	 * The printing language to ask the source for.
	 *
	 * A *request*, not an assertion. A provider that cannot serve it answers in the closest
	 * language it has and says so on each record's `LanguageCoverage`; it never relabels what it
	 * returned. `null` means the caller has no preference and the provider picks.
	 */
	val language: CardLanguage? = null,
) {

	init {
		require(page >= 1) { "Pages are 1-based" }
		require(pageSize >= 1) { "A page must hold at least one card" }
	}
}

/**
 * One page of results.
 *
 * @property totalCount how many cards match in total, or `null` when the provider does not say
 * @property hasMore whether another page exists. Derived by the adapter, because providers disagree
 *   about whether they report a page count, a total, or only a "next" link
 */
data class CardPage(
	val cards: List<CardPrinting>,
	val page: Int,
	val pageSize: Int,
	val totalCount: Int?,
	val hasMore: Boolean,
)

// ==================
// MARK: Errors
// ==================

/**
 * Everything a provider call can fail with, as a type rather than as an exception hierarchy the
 * caller has to guess at.
 *
 * Cancellation is deliberately absent. A cancelled request is not a failed one: implementations let
 * `CancellationException` propagate untouched so that a superseded set selection unwinds silently
 * instead of painting an error over the screen the user just navigated to.
 */
sealed class ProviderError(
	message: String,
	cause: Throwable? = null,
) : Exception(message, cause) {

	/** No usable connection. Retryable, and the one case where cached data matters most. */
	class Offline(cause: Throwable? = null) : ProviderError("No network connection", cause)

	/** The request went out and nothing came back in time. Retryable. */
	class Timeout(cause: Throwable? = null) : ProviderError("The provider did not answer in time", cause)

	/**
	 * The provider asked us to slow down.
	 *
	 * @property retryAfterSeconds what the provider said to wait, when it said anything
	 */
	class RateLimited(
		val retryAfterSeconds: Long?,
		cause: Throwable? = null,
	) : ProviderError("The provider is rate limiting this client", cause)

	/** A 5xx. Retryable, since it is the provider's problem and may pass. */
	class ServerError(val status: Int, cause: Throwable? = null) :
		ProviderError("The provider returned $status", cause)

	/** A 4xx other than 429. Not retryable: sending the same request again gets the same answer. */
	class BadRequest(val status: Int, cause: Throwable? = null) :
		ProviderError("The provider rejected the request with $status", cause)

	/** The provider answered, but not with something this adapter's mapping understands. */
	class MalformedResponse(val detail: String, cause: Throwable? = null) :
		ProviderError("The provider's response could not be read: $detail", cause)

	/** Anything not covered above. */
	class Unknown(val detail: String, cause: Throwable? = null) : ProviderError(detail, cause)

	/**
	 * Whether retrying this request unchanged could plausibly succeed.
	 *
	 * Only these are ever retried, and only with a bounded number of attempts: a [BadRequest] retry
	 * is wasted traffic against a provider whose limits we are asked to respect.
	 */
	val isTransient: Boolean
		get() = this is Offline || this is Timeout || this is RateLimited || this is ServerError
}
