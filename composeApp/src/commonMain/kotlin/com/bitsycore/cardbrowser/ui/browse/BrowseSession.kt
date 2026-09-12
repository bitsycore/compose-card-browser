package com.bitsycore.cardbrowser.ui.browse

import com.bitsycore.cardbrowser.core.model.CardPrinting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the user is currently looking at, so the detail screen can move through the same list.
 *
 * The card grid publishes a set's cards; the search publishes its results. Swiping from one card to
 * the next has to walk *what the user was looking at* -- filtered, sorted, in that order -- not the
 * raw set, and a search result list is not a set at all. Two other ways of arranging that were rejected:
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
	private val mFocusedCardId = MutableStateFlow<String?>(null)

	/**
	 * The card the user is looking at, or last looked at.
	 *
	 * Written by the detail screen as it swipes and read by the grid when it comes back, so returning
	 * from a card you swiped to lands on that card rather than on the one you originally opened. The
	 * shared-element transition depends on it: the tile it flies back to has to actually be composed,
	 * and in a lazy grid that means scrolled into view.
	 */
	val focusedCardId: StateFlow<String?> get() = mFocusedCardId.asStateFlow()

	/** Records which card is on screen in detail. */
	fun focus(cardId: String?) {
		mFocusedCardId.value = cardId
	}

	/** The list last published, whoever published it. */
	val current: StateFlow<BrowsingList> get() = mState.asStateFlow()

	/**
	 * Records what a screen is showing, under a key the route can carry back.
	 *
	 * A set's key is its id; a search's is [searchKey]. Called on every result the screen accepts,
	 * so it stays in step as filters or terms change. Cheap: the list is the same instance the
	 * screen already holds, not a copy.
	 */
	fun publish(key: String, cards: List<CardPrinting>) {
		mState.value = BrowsingList(key = key, cards = cards)
	}

	companion object {

		/**
		 * The key a search publishes under.
		 *
		 * Per game rather than per query: the results are replaced as the terms change, and a key
		 * that changed with them would leave a route pointing at a list nobody holds any more.
		 */
		fun searchKey(game: String) = "search:$game"
	}

	/**
	 * The cards for [setId], or an empty list when the session holds a different set.
	 *
	 * Guarded by set id rather than handed over blindly: navigating to a card of one set must never
	 * be given another set's neighbours because that is what happened to be on screen last.
	 */
	fun cardsFor(key: String?): List<CardPrinting> {
		val vState = mState.value
		return if (key != null && vState.key == key) vState.cards else emptyList()
	}
}

/** One published list, and the key -- a set id, or a search -- it belongs to. */
data class BrowsingList(
	val key: String? = null,
	val cards: List<CardPrinting> = emptyList(),
)
