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

	/** True when there is something to draw. */
	val hasValue: Boolean get() = value != null

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
 * @property matchedCount how many cards matched after filtering
 * @property knownSetSize the provider's own count for the whole set, when it states one
 * @property cachedCardCount how many cards of the set the app actually holds
 */
data class SetCards(
	val cards: List<com.bitsycore.cardbrowser.core.model.CardPrinting>,
	val isCompleteSet: Boolean,
	val knownSetSize: Int?,
	val cachedCardCount: Int,
) {

	val matchedCount: Int get() = cards.size

	/** True when the app holds part of a set whose full size it knows. */
	val isPartial: Boolean
		get() = !isCompleteSet && knownSetSize != null && cachedCardCount < knownSetSize
}
