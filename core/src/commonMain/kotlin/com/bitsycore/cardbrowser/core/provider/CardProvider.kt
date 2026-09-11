package com.bitsycore.cardbrowser.core.provider

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
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
 * ## The game is in the type
 *
 * `class RiftcodexProvider : CardProvider<RiftboundGame>` -- an adapter names the game it serves in
 * its own declaration, and [game] hands the profile back. Every adapter in this project serves
 * exactly one game, which used to be expressed by a `Set<Game>` in the capabilities plus a
 * `require(game == RIFTBOUND)` guard at the top of every method: around twenty runtime checks
 * restating something already true at compile time. Those are gone, along with the `game` parameter
 * they were checking.
 *
 * [G] is covariant, so a `CardProvider<RiftboundGame>` *is* a `CardProvider<GameProfile>` and the
 * registry can hold them all in one list without a star projection anywhere.
 *
 * Every method suspends and must be cancellable. Cancellation is not a failure: implementations let
 * `CancellationException` propagate and never wrap it in a [ProviderError].
 */
interface CardProvider<out G : GameProfile> {

	/** Stable and never changed once shipped; it is baked into every id and every cache file. */
	val id: ProviderId

	/** For the UI and for attribution notices. */
	val displayName: String

	/**
	 * The game this adapter serves, and everything the app knows about it.
	 *
	 * The one authority on the pairing. The routing table says which provider answers for a game;
	 * this says which game a provider is for, and `ProviderRegistry` checks the two agree at
	 * startup rather than letting them drift.
	 */
	val game: G

	/** What this provider can supply. Provider-wide -- an individual set may still offer less. */
	val capabilities: ProviderCapabilities

	/**
	 * Every set this provider serves, newest first where release dates are known.
	 *
	 * Not paginated: set catalogues are small (Riftbound has eight) and the set list wants them
	 * all to sort and search over. A provider whose catalogue is large enough to need paging should
	 * page internally and still return the whole list.
	 */
	suspend fun listSets(language: CardLanguage? = null): List<CardSet>

	/**
	 * One page of the cards in [request]'s set.
	 *
	 * The provider applies only those parts of [CardQuery] that [ProviderCapabilities.filtering]
	 * says it supports remotely; the caller is responsible for everything else and knows from the
	 * same capabilities that it has to. A provider must never silently ignore a filter it claims.
	 */
	suspend fun listCards(request: CardPageRequest): CardPage

	/** One printing by its source-qualified id, or `null` when the provider has no such record. */
	suspend fun cardDetail(id: SourceId, language: CardLanguage? = null): CardPrinting?

	/**
	 * The language this provider will actually serve when asked for [requested].
	 *
	 * The gap between what a user asks for and what a source has is the whole reason this app
	 * models languages the way it does, and this is where the gap is measured rather than papered
	 * over. A caller can ask before making a request and tell the user "French was asked for, this
	 * source only has English" -- which is the truth -- instead of requesting French, receiving
	 * English, and labelling it French.
	 *
	 * The default walks the preference order and takes the first language the provider carries,
	 * which is right for every adapter here. Returns `null` for a provider that states no language
	 * at all; that is not English, it is silence, and [CardLanguage] must not be invented from it.
	 */
	fun resolveLanguage(requested: CardLanguage? = null): CardLanguage? {
		val vAvailable = capabilities.data.languages
		if (vAvailable.isEmpty()) return null
		if (requested != null && requested in vAvailable) return requested
		return CardLanguage.PREFERENCE_ORDER.firstOrNull { it in vAvailable } ?: vAvailable.first()
	}

	/**
	 * Which of [candidates] this source can really serve cards for, for one set.
	 *
	 * The default confirms all of them, which is the right answer for a source whose catalogue is
	 * the same in every language it offers. Override it where a source *lists* a set in a language
	 * it has no cards for -- a distinction that cannot be seen from a set list and that the user
	 * would otherwise discover one language at a time.
	 *
	 * It is worth overriding only when the check is cheap. TCGdex's is a request per candidate,
	 * returning about a hundred bytes each; downloading each language's set to find out would not
	 * be worth knowing.
	 *
	 * @param candidates the languages the set claims, which is already a narrower set than
	 *   [DataCapabilities.languages]
	 */
	suspend fun confirmLanguages(setId: SourceId, candidates: Set<CardLanguage>): Set<CardLanguage> =
		candidates

	/**
	 * One page of cards matching [request]'s text, across every set of the game.
	 *
	 * Only called when [DataCapabilities.crossSetSearch] is true. The default throws rather than
	 * returning nothing, because an adapter that declares the capability and then quietly answers
	 * with an empty page is indistinguishable from a search that genuinely found nothing -- and the
	 * caller would report "no cards match" for a search that never ran.
	 */
	suspend fun searchAllSets(request: CardSearchRequest): CardPage =
		throw UnsupportedOperationException("$displayName does not support cross-set search")
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
 *
 * Which *game* a provider serves is not here. That is `CardProvider.game`, and it is in the type.
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
	/**
	 * True when the provider can search text across every set in one request.
	 *
	 * False does not mean the app cannot search across sets for this game -- it means the *server*
	 * cannot, so the search screen falls back to the sets already held on disk and says exactly
	 * that. The two produce very different result sets and the user is told which one they got.
	 */
	val crossSetSearch: Boolean = false,
	/**
	 * True when the card records ship inside the app rather than being fetched.
	 *
	 * A source whose catalogue is a bundled file has nothing to download, nothing to keep and
	 * nothing to clear: the records are in the installed app and are as available offline on the
	 * first launch as they will ever be. Offering to download them is offering work that cannot be
	 * done, and offering to delete them is worse -- there is no state to remove, so the button
	 * either lies or breaks the game.
	 *
	 * Only the *records*. Artwork is a separate question and is normally still fetched: a bundled
	 * catalogue that also bundled its pictures would be a very large app.
	 *
	 * False for every source that answers over the network, which is almost all of them.
	 */
	val bundledCardData: Boolean = false,
	/**
	 * True when the source publishes a small rendition of its art, distinct from the full image.
	 *
	 * The difference decides both what a bulk image download *is* and what it costs. Measured
	 * across sources on 2026-09-09: TCGdex 19.5 KB against 63 KB full, Scryfall 47 against 67,
	 * YGOPRODeck 28 against 153. Where there is no small rendition the app falls back to the full
	 * image -- Wuthering Waves' single rendition is 196 KB, measured 2026-09-11 -- so calling that
	 * a thumbnail download understates it by roughly ten times and names the wrong thing.
	 *
	 * False is the safe default: a source that has not said gets offered the honest label and the
	 * honest size, rather than a promise of a cheap fetch it cannot keep.
	 */
	val thumbnailImages: Boolean = false,
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
	val costs: Set<Int> = emptySet(),
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
 * A text search over every set of one game.
 *
 * Separate from [CardPageRequest] rather than a nullable `setId` on it, because the two are not the
 * same operation and conflating them is how a cross-set result ends up cached under a set key. A
 * set page is complete-able and cacheable; a search is neither, and nothing here pretends otherwise.
 *
 * No game is carried: the provider being asked serves exactly one, and it is in that provider's
 * type. Only [text] is carried besides. The structured filters exist to narrow a set the app
 * already holds whole, and applying them to a page of results drawn from a hundred sets would
 * produce a list the user would read as "every Fury card in the game" when it is nothing of the
 * sort.
 */
data class CardSearchRequest(
	val text: String,
	val language: CardLanguage? = null,
	val page: Int = 1,
	val pageSize: Int = 60,
) {

	init {
		require(text.isNotBlank()) { "A search needs something to search for" }
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
