package com.bitsycore.cardbrowser.data.repository

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.GameId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Works out what each game holds on disk *before* its set list is opened.
 *
 * The set list used to answer that question on arrival, and the answer arrives late enough to see:
 * rows appear, then their download buttons and their "saved" marks land a moment later. None of it
 * needs a network -- it is the store and the cache -- so it can be done while the user is still
 * reading the game picker, which is what [warm] is for.
 *
 * ## What it is not
 *
 * Not a source of truth, and not a subscription. A warmed answer is a *seed*: the set list still
 * resolves the facts for itself once its catalogue lands, and re-resolves whenever a download
 * finishes. If this cache is empty, or stale, the screen behaves exactly as it did before -- so
 * nothing here can make the app state something wrong, only state it sooner.
 *
 * Games with no cached catalogue are skipped rather than fetched: the whole point is that warming
 * costs nothing anyone is waiting for.
 */
class SetFactsWarmer(private val mRepository: CardRepository) {

	private val mLock = Mutex()
	private val mWarmed = mutableMapOf<String, Warmed>()

	/** The facts, and the catalogue they were read over: a different catalogue is a different fact. */
	private data class Warmed(val setIds: Set<String>, val facts: LocalSetFacts)

	/**
	 * Reads a game's local facts and keeps them, or does nothing if its catalogue is not cached.
	 *
	 * Safe to call repeatedly; the cost is a handful of store queries and one file check per set.
	 */
	suspend fun warm(game: GameId, language: CardLanguage?) {
		val vSets = mRepository.cachedSetList(game, language) ?: return
		val vFacts = mRepository.localSetFacts(game, vSets, language)
		val vWarmed = Warmed(vSets.mapTo(mutableSetOf()) { it.id.qualified }, vFacts)
		mLock.withLock { mWarmed[keyOf(game, language)] = vWarmed }
	}

	/**
	 * What was warmed for exactly [sets], or `null` -- nothing warmed, or warmed over a different
	 * catalogue.
	 *
	 * The set check is the whole safety of this class. A catalogue that has gained a set since the
	 * warm-up would otherwise be described by facts that never looked at it, and the new row would
	 * claim to hold nothing rather than admitting nobody had checked.
	 */
	suspend fun peek(
		game: GameId,
		language: CardLanguage?,
		sets: List<CardSet>,
	): LocalSetFacts? {
		val vIds = sets.mapTo(mutableSetOf()) { it.id.qualified }
		return mLock.withLock { mWarmed[keyOf(game, language)] }
			?.takeIf { it.setIds == vIds }
			?.facts
	}

	/**
	 * Drops what was warmed for a game.
	 *
	 * Called when a download finishes: the facts describe a device that has since changed, and a
	 * seed that is wrong is worse than no seed at all -- the screen would show the old answer until
	 * its own resolve landed, which is the flicker this class exists to remove.
	 */
	suspend fun invalidate(game: GameId) {
		mLock.withLock { mWarmed.keys.removeAll { it.startsWith("${game.value}/") } }
	}

	private fun keyOf(game: GameId, language: CardLanguage?) = "${game.value}/${language?.code ?: "-"}"
}
