package com.bitsycore.cardbrowser.core.provider

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId

// ==================
// MARK: Contract
// ==================

/**
 * What every provider adapter implements, and the only thing the repositories know about a source.
 *
 * Small on purpose. An adapter owns its endpoints, its DTOs, its mapping and its quirks; what it
 * exposes upward is this interface and a [capabilities] description. Adding a provider is an
 * implementation of this, a Koin registration and a routing entry -- see docs/ARCHITECTURE.md.
 *
 * Every method suspends and must be cancellable. Cancellation is not a failure: implementations let
 * `CancellationException` propagate and never wrap it in a [ProviderError].
 */
interface CardProvider {

	/** Stable and never changed once shipped; it is baked into every id and every cache file. */
	val id: ProviderId

	/** For the UI and for attribution notices. */
	val displayName: String

	/** What this provider can supply. Provider-wide -- an individual set may still offer less. */
	val capabilities: ProviderCapabilities

	/**
	 * Every set this provider serves for [game], newest first where release dates are known.
	 *
	 * Not paginated: set catalogues are small (Riftbound has eight) and the set list wants them
	 * all to sort and search over. A provider whose catalogue is large enough to need paging should
	 * page internally and still return the whole list.
	 */
	suspend fun listSets(game: Game): List<CardSet>

	/**
	 * One page of the cards in [request]'s set.
	 *
	 * The provider applies only those parts of [CardQuery] that [ProviderCapabilities.filtering]
	 * says it supports remotely; the caller is responsible for everything else and knows from the
	 * same capabilities that it has to. A provider must never silently ignore a filter it claims.
	 */
	suspend fun listCards(request: CardPageRequest): CardPage

	/** One printing by its source-qualified id, or `null` when the provider has no such record. */
	suspend fun cardDetail(id: SourceId): CardPrinting?
}

// ==================
// MARK: Capabilities
// ==================

/**
 * What a provider can supply, stated once for the whole provider.
 *
 * This describes the *source*, not any particular set or printing. A provider that can carry French
 * says so here; whether a given printing exists in French is [com.bitsycore.cardbrowser.core.model.LanguageCoverage]
 * on that printing. Capability is the ceiling, coverage is the fact.
 */
data class ProviderCapabilities(
	val games: Set<Game>,
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
 * Which filters a provider applies itself, and which the caller must apply to a complete local set.
 *
 * The split is the point. A filter in [remote] costs one request. A filter in [localOnly] costs
 * every page of the set before a single correct result can be shown, which is why the repository
 * has to know the difference and why the UI has to be able to say "these results are partial".
 *
 * A field in neither set is not offered by this provider at all and the UI must not show it.
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
	ENERGY_COST,
	ARTWORK_TREATMENT,
	FINISH,
	LANGUAGE,
}

/** A sortable field. What a given provider supports is [ProviderCapabilities.sorting]. */
enum class CardSortField {
	COLLECTOR_NUMBER,
	NAME,
	RARITY,
	ENERGY_COST,
}

/** Ascending or descending. */
enum class SortDirection {
	ASCENDING,
	DESCENDING,
}

/**
 * A request for cards within one set.
 *
 * [text] matches name or collector number; the two are one field because that is how the search box
 * behaves. Every collection field is an OR within itself and an AND across fields.
 */
data class CardQuery(
	val text: String? = null,
	val domains: Set<String> = emptySet(),
	val cardTypes: Set<String> = emptySet(),
	val rarities: Set<String> = emptySet(),
	val energyCosts: Set<Int> = emptySet(),
	val treatments: Set<com.bitsycore.cardbrowser.core.model.ArtworkTreatment> = emptySet(),
	val finishes: Set<com.bitsycore.cardbrowser.core.model.Finish> = emptySet(),
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
		if (energyCosts.isNotEmpty()) add(CardFilterField.ENERGY_COST)
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
