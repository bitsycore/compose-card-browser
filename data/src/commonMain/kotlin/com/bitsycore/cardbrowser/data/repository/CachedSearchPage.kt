package com.bitsycore.cardbrowser.data.repository

import com.bitsycore.cardbrowser.core.model.CardPrinting
import kotlinx.serialization.Serializable

/**
 * A cross-set search result, as it is written to disk.
 *
 * The cards alone would not be enough. [totalCount] and [hasMore] are what let the screen say
 * "showing 60 of 412 matches" rather than implying it is showing everything, and a cached result
 * that lost them would come back claiming to be complete when it never was.
 *
 * Deliberately not `CardSearchResults`: that carries `knownSetCount`, which describes the *caller's*
 * situation rather than the result, and would go stale the moment another set was downloaded.
 */
@Serializable
internal data class CachedSearchPage(
	val cards: List<CardPrinting> = emptyList(),
	val totalCount: Int? = null,
	val hasMore: Boolean = false,
)
