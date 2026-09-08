package com.bitsycore.cardbrowser.ui.browse

import com.bitsycore.cardbrowser.core.model.CardPrinting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the card grid is currently showing, so the detail screen can move through the same list.
 *
 * Swiping from one card to the next has to walk *what the user was looking at* -- filtered, sorted,
 * in that order -- not the raw set. Two other ways of arranging that were rejected:
 *
 * - **Through the navigation route.** A route has to survive being saved and restored, and 352
 *   source-qualified ids is roughly ten kilobytes of it. Routes carry keys, not payloads.
 * - **Re-deriving it in the detail view model.** That means the detail screen knowing the grid's
 *   query and sort, which is the grid's business, and re-running work already done.
 *
 * So the grid publishes its result list here and the detail screen reads it. This is ephemeral view
 * state, deliberately in memory only: it is a copy of what the cache already holds, it is worthless
 * once the process ends, and persisting it would create a second thing that can disagree with the
 * cache about what a set contains.
 *
 * When it is empty -- a cold start straight into a card, or a process death -- the detail screen
 * falls back to the cached set in collector order. Swiping still works; it just walks the whole set
 * rather than a filtered view of it.
 */
class BrowseSession {

	private val mState = MutableStateFlow(BrowsingList())

	/** The list the grid last published. */
	val current: StateFlow<BrowsingList> get() = mState.asStateFlow()

	/**
	 * Records what the grid is showing.
	 *
	 * Called on every result the grid accepts, so it stays in step as filters change. Cheap: the
	 * list is the same instance the grid already holds, not a copy.
	 */
	fun publish(setId: String, cards: List<CardPrinting>) {
		mState.value = BrowsingList(setId = setId, cards = cards)
	}

	/**
	 * The cards for [setId], or an empty list when the session holds a different set.
	 *
	 * Guarded by set id rather than handed over blindly: navigating to a card of one set must never
	 * be given another set's neighbours because that is what happened to be on screen last.
	 */
	fun cardsFor(setId: String?): List<CardPrinting> {
		val vState = mState.value
		return if (setId != null && vState.setId == setId) vState.cards else emptyList()
	}
}

/** One published list, and the set it belongs to. */
data class BrowsingList(
	val setId: String? = null,
	val cards: List<CardPrinting> = emptyList(),
)
