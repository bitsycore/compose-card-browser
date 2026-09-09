package com.bitsycore.cardbrowser.core.game

/**
 * Applying a user's own game order and hidden list to whatever games a build offers.
 *
 * The mechanism only, like [RarityLadder]: this file names no game and holds no preference. What
 * the order *is* comes from the preferences file, and which games exist comes from the routing
 * table, and neither is this object's business.
 *
 * ## Why the stored order is a list of ids and not a list of games
 *
 * Because the two lists disagree, routinely and in both directions. A preferences file written by
 * a build that offered ten games is read by one that offers eleven, or nine; a game gains a route
 * or loses one between releases. If the stored order were treated as the definitive list, a game
 * added after the user last reordered would silently never appear -- which is exactly the class of
 * bug that makes a picker "lose" a game with no way to get it back.
 *
 * So the stored order is a *hint*, applied over the real list:
 *
 * - a game the order names keeps the position the order gives it;
 * - a game the order does not name goes after those, in the order the registry gave it;
 * - an id in the order matching no game this build offers is ignored rather than being an error.
 *
 * The same reasoning applies to [hiddenIds]: an id that names nothing is inert, so hiding a game
 * and then installing a build without it does not corrupt anything, and installing it again brings
 * the setting back.
 */
object GameOrder {

	/**
	 * Every game, in the user's order: named games first as named, then the rest as they arrived.
	 *
	 * Includes hidden games. The editor needs them, and [visible] filters them out for everyone
	 * else, so there is one ordering rule rather than two that can drift apart.
	 */
	fun sorted(games: List<GameProfile>, order: List<String>): List<GameProfile> {
		if (order.isEmpty()) return games
		val vRanks = order.withIndex().associate { (vIndex, vId) -> vId to vIndex }
		// `sortedBy` is stable, so everything the order does not name keeps its registry position
		// behind everything it does.
		return games.sortedBy { vRanks[it.id.value] ?: Int.MAX_VALUE }
	}

	/** The games to show in the picker: [sorted], minus anything hidden. */
	fun visible(
		games: List<GameProfile>,
		order: List<String>,
		hiddenIds: Set<String>,
	): List<GameProfile> = sorted(games, order).filterNot { it.id.value in hiddenIds }

	/** The games the user has hidden, in the same order. Shown only while editing. */
	fun hidden(
		games: List<GameProfile>,
		order: List<String>,
		hiddenIds: Set<String>,
	): List<GameProfile> = sorted(games, order).filter { it.id.value in hiddenIds }

	/**
	 * The order that results from moving [game] by [delta] places, or the order unchanged.
	 *
	 * Returns a *complete* list of the ids this build offers rather than a patch of the old one.
	 * Storing the whole thing is what makes the result stable: a partial order would leave the
	 * moved game's new neighbours unpinned, so a later build that reordered its routing table could
	 * shuffle them back out from under the move the user just made.
	 *
	 * Moves across the whole list including hidden games, so a game hidden between two visible ones
	 * does not swallow a press. At either end the move is a no-op rather than a wrap.
	 */
	fun moved(
		games: List<GameProfile>,
		order: List<String>,
		game: GameProfile,
		delta: Int,
	): List<String> {
		val vCurrent = sorted(games, order).map { it.id.value }.toMutableList()
		val vFrom = vCurrent.indexOf(game.id.value)
		if (vFrom < 0) return vCurrent
		val vTo = vFrom + delta
		if (vTo !in vCurrent.indices) return vCurrent
		vCurrent.add(vTo, vCurrent.removeAt(vFrom))
		return vCurrent
	}

	/**
	 * [hiddenIds] with [game] flipped, refusing to hide the last visible game.
	 *
	 * A picker with nothing in it is a dead end: every route into the app goes through this screen,
	 * and the control that would undo it is on the screen that just emptied. Refusing the last one
	 * is a smaller cost than that, and the caller disables the control rather than letting it fail
	 * silently -- see `canHide`.
	 */
	fun withVisibilityToggled(
		games: List<GameProfile>,
		hiddenIds: Set<String>,
		game: GameProfile,
	): Set<String> {
		val vId = game.id.value
		if (vId !in hiddenIds && !canHide(games, hiddenIds)) return hiddenIds
		return if (vId in hiddenIds) hiddenIds - vId else hiddenIds + vId
	}

	/** Whether one more game may be hidden, i.e. whether more than one is currently visible. */
	fun canHide(games: List<GameProfile>, hiddenIds: Set<String>): Boolean =
		games.count { it.id.value !in hiddenIds } > 1
}
