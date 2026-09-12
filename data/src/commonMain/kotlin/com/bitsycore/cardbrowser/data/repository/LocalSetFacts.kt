package com.bitsycore.cardbrowser.data.repository

import com.bitsycore.cardbrowser.core.model.CardLanguage

/**
 * What a set list can state about what this device holds, without asking anyone.
 *
 * Every field is answerable from the store and the cache, which is what makes it warmable: the
 * game picker can have it ready before a set list is opened, so "downloaded" is not a thing the
 * screen has to go and find out while the user is looking at it.
 *
 * @see CardRepository.localSetFacts
 */
data class LocalSetFacts(
	/** Sets with any edition on disk. */
	val savedSetIds: Set<String> = emptySet(),
	/** Which editions, per set: the question the download dialog asks. */
	val savedLanguages: Map<String, Set<CardLanguage>> = emptyMap(),
	/** What each set really holds in the language it opens in, where a fetch established it. */
	val confirmedCardCounts: Map<String, Int> = emptyMap(),
	/** Sets with nothing left to fetch. Stricter than [savedSetIds]. */
	val completeSetIds: Set<String> = emptySet(),
	/** Languages each set is known to exist in, from records and from what is on disk. */
	val availableLanguages: Map<String, Set<CardLanguage>> = emptyMap(),
)
