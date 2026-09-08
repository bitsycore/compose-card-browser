package com.bitsycore.cardbrowser.core.provider

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.model.ProviderId

// ==================
// MARK: Routing
// ==================

/**
 * A developer-written rule saying which provider serves a game, optionally for one language.
 *
 * Routes are code, registered through Koin at startup. There are no user-installed plugins, no
 * runtime code loading and no marketplace: a provider is compiled in or it does not exist.
 *
 * The [language] slot is what lets a later provider fill a gap -- if a Korean-capable source for
 * Riftbound appears, it is added as a route for `RIFTBOUND` + `KOREAN` without touching the one
 * already serving everything else.
 *
 * @property language `null` makes this the authoritative route for the whole game
 * @property priority lower wins. Only used to pick *one* route; see [ProviderRegistry]
 */
data class ProviderRoute(
	val game: Game,
	val provider: ProviderId,
	val language: CardLanguage? = null,
	val priority: Int = 0,
)

// ==================
// MARK: Registry
// ==================

/**
 * The registered providers and the routing table over them.
 *
 * Resolution picks exactly one provider and stops. There is no automatic merging of two sources
 * into one result list, and no silent failover to a second provider when the first errors: both
 * would make it impossible to say honestly where a card record came from, and a failover would
 * quietly change the meaning of every id on screen. A failed request surfaces as a failed request.
 *
 * @param providers every compiled-in adapter, in no particular order
 * @param routes the routing table. A game with no route is a game the app does not offer
 */
class ProviderRegistry(
	providers: List<CardProvider>,
	routes: List<ProviderRoute>,
) {

	private val mProvidersById: Map<ProviderId, CardProvider> = providers.associateBy { it.id }
	private val mRoutes: List<ProviderRoute> = routes

	init {
		val vMissing = mRoutes.map { it.provider }.filter { it !in mProvidersById }
		require(vMissing.isEmpty()) {
			"Routing table names providers that are not registered: ${vMissing.joinToString()}"
		}
	}

	/** Every registered adapter. */
	val all: Collection<CardProvider> get() = mProvidersById.values

	/**
	 * The games the app actually offers: those with a route to a registered provider.
	 *
	 * The UI reads this rather than [Game.entries], which is what keeps a game with no adapter from
	 * appearing as a dead entry.
	 */
	val games: Set<Game>
		get() = mRoutes.map { it.game }.toSet()

	/** One adapter by id, or `null`. */
	fun byId(id: ProviderId): CardProvider? = mProvidersById[id]

	/**
	 * The game [provider] is routed for, or `null` when that is not a single answer.
	 *
	 * This is what lets a screen work out which game it is looking at from an id it was handed.
	 * Every id in this app is source-qualified, so a set id names its provider, and the routing
	 * table names that provider's game -- which beats carrying the game through every navigation
	 * argument and every view model as a second copy of information the id already contains.
	 *
	 * `null` when a provider is routed for more than one game, because then the id genuinely does
	 * not determine the game and a caller must be told rather than given a guess. No provider in
	 * this app is, but the routing table permits it and this must not quietly pick the first.
	 */
	fun gameFor(provider: ProviderId): Game? {
		val vGames = mRoutes.filter { it.provider == provider }.map { it.game }.distinct()
		return vGames.singleOrNull()
	}

	/**
	 * The provider that serves [game], preferring one routed for [language] when one exists.
	 *
	 * A language-specific route only wins if it is present; otherwise the game-wide route answers,
	 * and the caller finds out what that provider can actually supply from its capabilities.
	 */
	fun resolve(game: Game, language: CardLanguage? = null): CardProvider? {
		val vCandidates = mRoutes.filter { it.game == game }
		val vRoute = vCandidates
			.filter { it.language == language && language != null }
			.minByOrNull { it.priority }
			?: vCandidates.filter { it.language == null }.minByOrNull { it.priority }
		return vRoute?.let { mProvidersById[it.provider] }
	}

	/**
	 * The provider serving [game], or a thrown error naming the gap.
	 *
	 * Used on paths where a missing route is a programming mistake rather than a user-facing state.
	 */
	fun require(game: Game, language: CardLanguage? = null): CardProvider =
		resolve(game, language)
			?: error("No provider is routed for $game${language?.let { " / $it" } ?: ""}")
}
