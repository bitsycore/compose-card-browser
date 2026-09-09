package com.bitsycore.cardbrowser.core.provider

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.GameId
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
 * Riftbound appears, it is added as a route for Riftbound + `KOREAN` without touching the one
 * already serving everything else.
 *
 * @property game the game's id. Taken from a profile at the call site -- `RiftboundGame.id` -- so a
 *   route cannot name a game no module declares
 * @property language `null` makes this the authoritative route for the whole game
 * @property priority lower wins. Only used to pick *one* route; see [ProviderRegistry]
 */
data class ProviderRoute(
	val game: GameId,
	val provider: ProviderId,
	val language: CardLanguage? = null,
	val priority: Int = 0,
)

// ==================
// MARK: Registry
// ==================

/**
 * The registered providers, the routing table over them, and the game profiles they carry.
 *
 * Resolution picks exactly one provider and stops. There is no automatic merging of two sources
 * into one result list, and no silent failover to a second provider when the first errors: both
 * would make it impossible to say honestly where a card record came from, and a failover would
 * quietly change the meaning of every id on screen. A failed request surfaces as a failed request.
 *
 * This is also the only place that knows which games exist. Nothing enumerates them: the set is
 * whatever the compiled-in providers happen to serve, which is why adding a game touches no shared
 * code. [GameProfile] instances are reached through here rather than through a lookup table, so a
 * profile can only be seen by the app if some provider actually serves it.
 *
 * @param providers every compiled-in adapter, in no particular order. Declared as
 *   `CardProvider<GameProfile>` and not `CardProvider<*>`, which the covariance on
 *   [CardProvider] is what makes possible
 * @param routes the routing table. A game with no route is a game the app does not offer
 */
class ProviderRegistry(
	providers: List<CardProvider<GameProfile>>,
	routes: List<ProviderRoute>,
) {

	private val mProvidersById: Map<ProviderId, CardProvider<GameProfile>> =
		providers.associateBy { it.id }
	private val mRoutes: List<ProviderRoute> = routes

	init {
		val vMissing = mRoutes.map { it.provider }.filter { it !in mProvidersById }
		require(vMissing.isEmpty()) {
			"Routing table names providers that are not registered: ${vMissing.joinToString()}"
		}
		// A route pointing a game at a provider that serves a different one is a wiring mistake
		// that would otherwise show as an empty set list. The provider's own type is the authority;
		// the table has to agree with it.
		val vMismatched = mRoutes.filter { vRoute ->
			mProvidersById[vRoute.provider]?.game?.id?.let { it != vRoute.game } ?: false
		}
		require(vMismatched.isEmpty()) {
			"Routes name a provider that serves another game: " +
				vMismatched.joinToString { "${it.game} -> ${it.provider}" }
		}
	}

	/** Every registered adapter. */
	val all: Collection<CardProvider<GameProfile>> get() = mProvidersById.values

	/**
	 * The games the app actually offers: those with a route to a registered provider.
	 *
	 * The UI reads this, which is what keeps a game with no adapter from appearing as a dead entry.
	 * Ordered by the routing table, so the table decides what the game switcher looks like.
	 */
	val games: List<GameProfile>
		get() = mRoutes
			.mapNotNull { vRoute -> mProvidersById[vRoute.provider]?.game }
			.distinctBy { it.id }

	/** One adapter by id, or `null`. */
	fun byId(id: ProviderId): CardProvider<GameProfile>? = mProvidersById[id]

	/**
	 * The game [provider] is routed for, or `null` when that is not a single answer.
	 *
	 * This is what lets a screen work out which game it is looking at from an id it was handed.
	 * Every id in this app is source-qualified, so a set id names its provider, and the provider
	 * names its game -- which beats carrying the game through every navigation argument and every
	 * view model as a second copy of information the id already contains.
	 */
	fun gameFor(provider: ProviderId): GameProfile? = mProvidersById[provider]?.game

	/** The profile for [game], or `null` when no registered provider serves it. */
	fun profileFor(game: GameId): GameProfile? =
		mProvidersById.values.firstOrNull { it.game.id == game }?.game

	/**
	 * The provider that serves [game], preferring one routed for [language] when one exists.
	 *
	 * A language-specific route only wins if it is present; otherwise the game-wide route answers,
	 * and the caller finds out what that provider can actually supply from its capabilities.
	 */
	fun resolve(game: GameId, language: CardLanguage? = null): CardProvider<GameProfile>? {
		val vCandidates = mRoutes.filter { it.game == game }
		val vRoute = vCandidates
			.filter { it.language == language && language != null }
			.minByOrNull { it.priority }
			?: vCandidates.filter { it.language == null }.minByOrNull { it.priority }
		return vRoute?.let { mProvidersById[it.provider] }
	}

	/** As [resolve], taking the profile rather than its id. */
	fun resolve(game: GameProfile, language: CardLanguage? = null): CardProvider<GameProfile>? =
		resolve(game.id, language)

	/**
	 * The provider serving [game], or a thrown error naming the gap.
	 *
	 * Used on paths where a missing route is a programming mistake rather than a user-facing state.
	 */
	fun require(game: GameId, language: CardLanguage? = null): CardProvider<GameProfile> =
		resolve(game, language)
			?: error("No provider is routed for $game${language?.let { " / $it" } ?: ""}")
}
