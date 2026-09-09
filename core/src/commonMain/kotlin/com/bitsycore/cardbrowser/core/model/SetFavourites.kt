package com.bitsycore.cardbrowser.core.model

/**
 * Pinning sets to the top of the list, and ordering the pinned ones.
 *
 * The mechanism only, like `GameOrder`: the list of favourites is a preference and the sets come
 * from a provider, and neither is this object's business.
 *
 * ## Why one ordered list rather than a set plus an order
 *
 * Because they are the same fact. A favourite has a position by virtue of being in the list, so
 * there is no way for the two to disagree -- no favourite missing from the order, no order naming
 * something that is not a favourite, and no code needed to reconcile them.
 *
 * ## Why favourites are not per game
 *
 * A [SourceId.qualified] is unique across the whole app, so one list serves every game and the set
 * list simply never sees another game's ids: [pinned] only returns sets it was actually given. That
 * also means a favourite survives a trip to another game and back, and an id belonging to a game
 * whose adapter has since been removed sits in the list doing nothing rather than being an error.
 */
object SetFavourites {

	/**
	 * The favourites among [sets], in the user's order.
	 *
	 * Driven by the stored order rather than by the set list, because the stored order is the thing
	 * the user arranged. An id naming nothing in [sets] is skipped -- it belongs to another game, or
	 * to a set the provider has stopped serving.
	 */
	fun pinned(sets: List<CardSet>, favouriteIds: List<String>): List<CardSet> {
		if (favouriteIds.isEmpty()) return emptyList()
		val vById = sets.associateBy { it.id.qualified }
		return favouriteIds.mapNotNull { vById[it] }
	}

	/** Everything else, in whatever order it arrived. */
	fun unpinned(sets: List<CardSet>, favouriteIds: List<String>): List<CardSet> {
		if (favouriteIds.isEmpty()) return sets
		val vFavourites = favouriteIds.toSet()
		return sets.filterNot { it.id.qualified in vFavourites }
	}

	/**
	 * [favouriteIds] with [setId] added or removed.
	 *
	 * A new favourite goes on the end, which is the only position that is not a guess: the user
	 * has expressed no opinion about where it belongs yet, and putting it first would silently
	 * demote whatever they *had* arranged.
	 */
	fun toggled(favouriteIds: List<String>, setId: String): List<String> =
		if (setId in favouriteIds) favouriteIds - setId else favouriteIds + setId

	/**
	 * [favouriteIds] with [setId] moved to [toIndex], or unchanged if it is not a favourite.
	 *
	 * Indexed against the stored list rather than what is on screen. Unlike the game picker, the two
	 * can differ here for a reason that is not a bug: a search narrows the set list, so the
	 * favourites *shown* may be a subset. Callers pass the index within the full favourites list --
	 * see the note on the set list screen about why dragging is disabled while a search is active.
	 */
	fun movedTo(favouriteIds: List<String>, setId: String, toIndex: Int): List<String> {
		val vFrom = favouriteIds.indexOf(setId)
		if (vFrom < 0) return favouriteIds
		val vTo = toIndex.coerceIn(0, favouriteIds.lastIndex)
		if (vTo == vFrom) return favouriteIds
		return favouriteIds.toMutableList().apply { add(vTo, removeAt(vFrom)) }
	}

	// Deliberately no "prune unknown ids". It would have to be given one game's sets, and the list
	// holds every game's favourites, so it would quietly delete the lot every time a set list
	// loaded. An id naming nothing is already inert -- see the class note.
}
