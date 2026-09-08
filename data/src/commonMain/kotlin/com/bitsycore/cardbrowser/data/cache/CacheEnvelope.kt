package com.bitsycore.cardbrowser.data.cache

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.ProviderId
import kotlinx.serialization.Serializable

// ==================
// MARK: Envelope
// ==================

/**
 * A cached payload plus everything needed to judge whether it is still worth anything.
 *
 * The fields around [payload] are the whole point. A cache that stores only the data cannot answer
 * "is this stale", "did this come from the provider I am now asking", "was this the complete set or
 * one page of it", or "was this written by a version of the app that meant the same thing by these
 * fields" -- and getting any of those wrong shows up as the app lying to the user about what it has.
 *
 * @property schemaVersion what the *shape* of [payload] was when written. A record from an older
 *   version is discarded rather than parsed optimistically; see [CURRENT_SCHEMA_VERSION]
 * @property provider which source produced it. A record is never served for a different provider
 * @property language the printing language this was fetched for, or `null` when the request was not
 *   language-scoped. Part of the key, so a French fetch never answers an English one
 * @property scope what query and paging this covers, so a filtered page is never mistaken for the set
 * @property fetchedAtEpochMillis when it came off the wire, for freshness
 * @property completeness whether this is all of what it claims to be
 */
@Serializable
data class CacheEnvelope<T>(
	val schemaVersion: Int,
	val provider: ProviderId,
	val language: CardLanguage?,
	val scope: CacheScope,
	val fetchedAtEpochMillis: Long,
	val completeness: Completeness,
	val payload: T,
) {

	/** True when this was written by a build that meant something else by these fields. */
	val isSchemaCompatible: Boolean get() = schemaVersion == CURRENT_SCHEMA_VERSION

	/** Whether [nowEpochMillis] is past this record's [ttlMillis]. Stale is still usable. */
	fun isStale(nowEpochMillis: Long, ttlMillis: Long): Boolean =
		nowEpochMillis - fetchedAtEpochMillis > ttlMillis

	companion object {

		/**
		 * Bumped whenever a cached type changes shape in a way an old file cannot satisfy.
		 *
		 * Bumping it is the migration: every record at a lower version is treated as absent and
		 * re-fetched. That is cheap here -- the cache is disposable by construction -- and much
		 * safer than trying to read a payload whose fields moved.
		 */
		const val CURRENT_SCHEMA_VERSION: Int = 1
	}
}

/**
 * What a cached record actually covers.
 *
 * A set list, a whole set of cards, one page of a set, or one card. Held as a sealed type rather
 * than a string so a page can never be filed where a complete set is expected.
 */
@Serializable
sealed interface CacheScope {

	/** Every set for one game. */
	@Serializable
	data class SetList(val game: String) : CacheScope

	/**
	 * Every card in one set, unfiltered.
	 *
	 * The only scope local filtering may run against, because it is the only one that can answer a
	 * filter completely. Written only once every page has been fetched.
	 */
	@Serializable
	data class CompleteSet(val setId: String) : CacheScope

	/**
	 * One page of one set, for one query.
	 *
	 * [queryFingerprint] is part of the identity: a page of results for "fury, epic" is not a page
	 * of the set, and serving one as the other is exactly the "filtered partial page presented as
	 * complete" failure the app must not have.
	 */
	@Serializable
	data class CardPage(
		val setId: String,
		val queryFingerprint: String,
		val page: Int,
		val pageSize: Int,
	) : CacheScope

	/** One card's detail record. */
	@Serializable
	data class CardDetail(val cardId: String) : CacheScope
}

/**
 * How much of what was asked for this record holds.
 *
 * Carried through to the UI. A set browsed offline from three of its four pages is shown as
 * partial, with a count, and is never described as the whole set.
 */
@Serializable
enum class Completeness {

	/** Everything the scope names is present. */
	COMPLETE,

	/** Some of it. The UI must say so and must not present filtered results as exhaustive. */
	PARTIAL,
}
