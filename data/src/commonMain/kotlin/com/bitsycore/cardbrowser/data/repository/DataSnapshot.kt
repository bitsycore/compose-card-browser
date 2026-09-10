package com.bitsycore.cardbrowser.data.repository

import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.data.cache.Completeness

/**
 * A value, where it came from, how complete it is, and what went wrong -- all at once.
 *
 * Deliberately not a `Result`. The state this app spends most of its time in is "here is cached
 * data *and* the refresh failed", and a type that forces a choice between a value and an error
 * cannot express it. Collapsing that state either throws away usable data or hides a failure, and
 * the brief requires neither happen.
 *
 * @property value `null` only when there was nothing cached and the fetch failed
 * @property error set when the most recent refresh failed, whether or not [value] is present
 */
data class DataSnapshot<T>(
	val value: T?,
	val origin: DataOrigin,
	val completeness: Completeness,
	val fetchedAtEpochMillis: Long?,
	val isStale: Boolean,
	val error: ProviderError? = null,
) {

	/** True when the screen should show a retry affordance beside whatever it is already showing. */
	val hasRecoverableError: Boolean get() = error != null && value != null

	companion object {

		/** A fresh network result. */
		fun <T> fresh(value: T, fetchedAt: Long, completeness: Completeness = Completeness.COMPLETE) =
			DataSnapshot(value, DataOrigin.NETWORK, completeness, fetchedAt, isStale = false)

		/** A cached result, possibly stale, possibly partial. */
		fun <T> cached(
			value: T,
			fetchedAt: Long,
			completeness: Completeness,
			isStale: Boolean,
		) = DataSnapshot(value, DataOrigin.CACHE, completeness, fetchedAt, isStale)

		/** A failure with nothing cached to fall back on. */
		fun <T> failed(error: ProviderError) = DataSnapshot<T>(
			value = null,
			origin = DataOrigin.NONE,
			completeness = Completeness.PARTIAL,
			fetchedAtEpochMillis = null,
			isStale = false,
			error = error,
		)
	}
}

/** Where a snapshot's value came from. Surfaced in the UI, so a user can tell cached from live. */
enum class DataOrigin {
	NETWORK,
	CACHE,

	/** No value at all. */
	NONE,
}

/**
 * The cards of one set, with an honest account of how much of the set that is.
 *
 * [isCompleteSet] is the field that stops the app lying. When it is false, the results are drawn
 * from part of the set and the UI must say so -- a filter applied to three of four pages is not
 * "the Fury cards in Origins", it is "the Fury cards among the 300 of Origins we have".
 *
 * @property knownSetSize the provider's own count for the whole set, when it states one
 * @property cachedCardCount how many cards of the set the app actually holds
 */
data class SetCards(
	val cards: List<com.bitsycore.cardbrowser.core.model.CardPrinting>,
	val isCompleteSet: Boolean,
	val knownSetSize: Int?,
	val cachedCardCount: Int,
) {

	/** True when the app holds part of a set whose full size it knows. */
	val isPartial: Boolean
		get() = !isCompleteSet && knownSetSize != null && cachedCardCount < knownSetSize
}

// ==================
// MARK: Cross-set search
// ==================

/**
 * How much of a game a search actually covered.
 *
 * The two are not interchangeable and the difference is not cosmetic. A remote search asks the
 * provider about every card it has; a local one looks only at the sets this device has already
 * downloaded, which on a fresh install is none. Presenting the second as though it were the first
 * would tell a user that a card does not exist when what happened is that they have never opened
 * the set it is in.
 */
enum class SearchScope {

	/** The provider searched its whole catalogue. */
	REMOTE_ALL_SETS,

	/** Only the sets already on disk were searched, because the provider cannot search remotely. */
	LOCAL_CACHED_SETS,
}

/**
 * The results of a cross-set search, with an account of what was searched.
 *
 * @property scope which of the two kinds of search produced [cards]
 * @property searchedSetCount how many sets were actually looked at. Meaningful for
 *   [SearchScope.LOCAL_CACHED_SETS]; for a remote search it is the number the provider spanned,
 *   which it does not report, so it is the count of sets the results happen to come from
 * @property knownSetCount how many sets the game has in total, so the UI can say "12 of 87"
 * @property totalCount the provider's own match count, when it states one
 * @property hasMore whether the provider has further pages
 */
data class CardSearchResults(
	val cards: List<com.bitsycore.cardbrowser.core.model.CardPrinting>,
	val scope: SearchScope,
	val searchedSetCount: Int,
	val knownSetCount: Int,
	val totalCount: Int? = null,
	val hasMore: Boolean = false,
) {

	/** True when a local search could not see the whole game. */
	val isLimitedByCache: Boolean
		get() = scope == SearchScope.LOCAL_CACHED_SETS && searchedSetCount < knownSetCount
}
