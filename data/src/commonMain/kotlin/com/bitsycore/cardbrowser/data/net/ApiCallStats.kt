package com.bitsycore.cardbrowser.data.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * How many requests this app has actually sent, per host, since it started.
 *
 * Exists to make the caching claims checkable. The repository is full of statements about what it
 * does and does not re-fetch -- a set list revalidated at most every five minutes, a complete set
 * cached for a day, a card read out of its set rather than requested -- and none of them were
 * observable. A counter that goes up when you browse a set twice is the difference between
 * believing the cache works and knowing it does.
 *
 * Counted per **host** rather than per provider on purpose. That is what actually leaves the device,
 * and it separates the two things a single provider does: `api.scryfall.com` for card data and a
 * CDN host for the images, which are cached by entirely different machinery. A host that climbs
 * while you sit still is the bug this is for.
 *
 * Since launch, not all time. Persisting it would mean a disk write per request, and the question
 * this answers -- "did that screen cost me anything?" -- is a question about this session.
 */
class ApiCallStats {

	private val mCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

	/** Requests sent per host, highest first. */
	val counts: StateFlow<Map<String, Int>> get() = mCounts.asStateFlow()

	/** The total across every host. */
	val total: Int get() = mCounts.value.values.sum()

	/**
	 * Records one request to [host].
	 *
	 * Called from a Ktor plugin on every client the app builds, so it counts retries as the
	 * separate requests they are -- a retried timeout really did cost two.
	 */
	fun record(host: String) {
		if (host.isBlank()) return
		mCounts.update { vCounts -> vCounts + (host to (vCounts[host] ?: 0) + 1) }
	}

	/** Back to zero, for measuring one interaction rather than a whole session. */
	fun reset() {
		mCounts.value = emptyMap()
	}
}
