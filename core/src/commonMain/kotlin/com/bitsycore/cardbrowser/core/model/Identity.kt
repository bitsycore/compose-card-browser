package com.bitsycore.cardbrowser.core.model

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

// ==================
// MARK: Game identity
// ==================

/**
 * A game's stable identity.
 *
 * A string rather than an enum, and that is the point: core does not name a single game. What games
 * exist is decided by which `:games:*` modules are compiled in and routed, each of which declares a
 * `GameProfile` carrying one of these. A closed enum here would mean every new game edited core,
 * and every table keyed by game would live in core to stay exhaustive -- which is exactly the
 * arrangement this replaced.
 *
 * The string is written into cache keys and into every `CardPrinting`, so a game picks one and keeps
 * it: renaming it orphans every cached record for that game.
 */
@JvmInline
@Serializable
value class GameId(val value: String) {

	init {
		require(value.isNotBlank()) { "A game id cannot be blank" }
	}

	override fun toString(): String = value
}

// ==================
// MARK: Provider identity
// ==================

/**
 * A provider's stable identity.
 *
 * The string is part of every id this provider hands out and is written into cache files, so it is
 * chosen once and never changed: renaming it invalidates every cached record sourced from it.
 */
@JvmInline
@Serializable
value class ProviderId(val value: String) {

	init {
		require(value.isNotBlank()) { "A provider id cannot be blank" }
	}

	override fun toString(): String = value
}

// ==================
// MARK: Source-qualified ids
// ==================

/**
 * An id that carries the provider that issued it.
 *
 * Two providers may both call a set `OGN` and both call a card `1`. Nothing in this app compares
 * ids across providers, and this type is why that is enforceable rather than merely intended: a
 * bare provider-local string never escapes an adapter.
 *
 * @property provider the provider that issued [local]
 * @property local the identifier as the provider stated it, unmodified
 */
@Serializable
data class SourceId(
	val provider: ProviderId,
	val local: String,
) {

	init {
		require(local.isNotBlank()) { "A source id cannot have a blank local part" }
	}

	/** A single string safe to use as a cache key or a navigation argument. */
	val qualified: String get() = "${provider.value}:$local"

	override fun toString(): String = qualified

	companion object {

		/**
		 * Parses a [qualified] string produced by [SourceId.qualified].
		 *
		 * Returns `null` rather than throwing, because the input is usually a navigation argument
		 * or a cache filename and a malformed one is a recoverable condition, not a bug.
		 */
		fun parse(qualified: String): SourceId? {
			val vSeparator = qualified.indexOf(':')
			if (vSeparator <= 0 || vSeparator == qualified.lastIndex) return null
			return SourceId(
				provider = ProviderId(qualified.substring(0, vSeparator)),
				local = qualified.substring(vSeparator + 1),
			)
		}
	}
}

// ==================
// MARK: Availability
// ==================

/**
 * Whether something is offered, is known not to be offered, or has never been stated.
 *
 * The third case is the reason this type exists. A provider that has no language field at all is
 * not telling us that a French printing does not exist -- it is telling us nothing. Collapsing
 * [UNKNOWN] into [UNAVAILABLE] would let the UI claim knowledge the data does not support, and
 * collapsing it into [AVAILABLE] would offer the user a selection that cannot be honoured.
 */
@Serializable
enum class Availability {

	/** The provider states this exists. Safe to offer as a selectable option. */
	AVAILABLE,

	/** The provider states this does not exist. Safe to show as ruled out. */
	UNAVAILABLE,

	/** The provider says nothing either way. Show as unknown; never offer as confirmed. */
	UNKNOWN,
}
