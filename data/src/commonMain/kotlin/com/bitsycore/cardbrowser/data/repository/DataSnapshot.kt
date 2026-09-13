package com.bitsycore.cardbrowser.data.repository

import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.cardbrowser.data.cache.Completeness

/**
 * A value, where it came from, how complete it is, and what went wrong -- all at once.
 *
 * Deliberately not a `Result`. The state this app spends most of its time in is "here is cached
 * data *and* the refresh failed", and a type that forces a choice between a value and an error
 * cannot express it. Collapsing that state either throws away usable data or hides a failure, and
 * the brief requires neither happen.
 *
 * @property value `null` only when there was nothing cached and the fetch failed
 * @property error set when the most recent refresh failed, whether or not [value] is present
 */
data class DataSnapshot<T>(
	val value: T?,
	val origin: DataOrigin,
	val completeness: Completeness,
	val fetchedAtEpochMillis: Long?,
	val isStale: Boolean,
	val error: ProviderError? = null,
) {

	/** True when the screen should show a retry affordance beside whatever it is already showing. */
	val hasRecoverableError: Boolean get() = error != null && value != null

	companion object {

		/** A fresh network result. */
		fun <T> fresh(value: T, fetchedAt: Long, completeness: Completeness = Completeness.COMPLETE) =
			DataSnapshot(value, DataOrigin.NETWORK, completeness, fetchedAt, isStale = false)

		/** A cached result, possibly stale, possibly partial. */
		fun <T> cached(
			value: T,
			fetchedAt: Long,
			completeness: Completeness,
			isStale: Boolean,
		) = DataSnapshot(value, DataOrigin.CACHE, completeness, fetchedAt, isStale)

		/** A failure with nothing cached to fall back on. */
		fun <T> failed(error: ProviderError) = DataSnapshot<T>(
			value = null,
			origin = DataOrigin.NONE,
			completeness = Completeness.PARTIAL,
			fetchedAtEpochMillis = null,
			isStale = false,
			error = error,
		)
	}
}

/** Where a snapshot's value came from. Surfaced in the UI, so a user can tell cached from live. */
enum class DataOrigin {
	NETWORK,
	CACHE,

	/** No value at all. */
	NONE,
}

/**
 * The cards of one set, with an honest account of how much of the set that is.
 *
 * [isCompleteSet] is the field that stops the app lying. When it is false, the results are drawn
 * from part of the set and the UI must say so -- a filter applied to three of four pages is not
 * "the Fury cards in Origins", it is "the Fury cards among the 300 of Origins we have".
 *
 * @property knownSetSize the provider's own count for the whole set, when it states one
 * @property cachedCardCount how many cards of the set the app actually holds
 */
data class SetCards(
	val cards: List<com.bitsycore.cardbrowser.core.model.CardPrinting>,
	val isCompleteSet: Boolean,
	val knownSetSize: Int?,
	val cachedCardCount: Int,
) {

	/** True when the app holds part of a set whose full size it knows. */
	val isPartial: Boolean
		get() = !isCompleteSet && knownSetSize != null && cachedCardCount < knownSetSize
}

// ==================
// MARK: Cross-set search
// ==================

/**
 * What a search across a game's cards found, and how much of the game it could see.
 *
 * A search here reads the card store, which holds the sets this device has downloaded. That is the
 * only kind of search the app does: no source is asked to search its own catalogue, because the one
 * that could was implemented five times and called from nowhere -- see `docs/PROVIDERS.md`.
 *
 * So the counts are not decoration. An empty result from a catalogue and an empty result from an
 * empty device mean opposite things, and the screen has to be able to tell them apart.
 *
 * @property searchedSetCount how many of the game's sets were actually looked at -- the ones held
 * @property knownSetCount how many the game has in total, so the UI can say "12 of 87"
 * @property totalCount how many matched before any limit was applied, when that is known
 * @property hasMore whether the result was truncated
 */
data class CardSearchResults(
	val cards: List<com.bitsycore.cardbrowser.core.model.CardPrinting>,
	val searchedSetCount: Int,
	val knownSetCount: Int,
	val totalCount: Int? = null,
	val hasMore: Boolean = false,
) {

	/** True when the search could not see the whole game, because not all of it is downloaded. */
	val isLimitedByCache: Boolean get() = searchedSetCount < knownSetCount
}

/**
 * Which language a set opens in, and whether that is the one the user asked for.
 *
 * Two different reasons a set can open in a language nobody chose, and a screen has to tell them
 * apart because it says opposite things about them:
 *
 * - **The preferred language has no printing of this set.** Nothing to do about it. There is no
 *   Korean edition of Pokemon's Base Set and no button will produce one.
 * - **The preferred language is simply not downloaded, and another one is.** Entirely fixable, and
 *   the screen offers to fetch it.
 *
 * [substitutedFor] names the language that was wanted and [reason] says which of the two happened.
 * Both are null in the ordinary case, where the wanted language is the one that opened.
 */
data class OpeningLanguage(
	val language: com.bitsycore.cardbrowser.core.model.CardLanguage?,
	val substitutedFor: com.bitsycore.cardbrowser.core.model.CardLanguage? = null,
	val reason: LanguageSubstitution? = null,
)

/** Why a set opened in a language the user did not ask for. Two different answers to give. */
enum class LanguageSubstitution {

	/** The source has it; this device does not. Fixable, and the screen offers to fetch it. */
	NOT_DOWNLOADED,

	/**
	 * The source was asked and says it has no such edition of this set.
	 *
	 * Nothing to offer. There is no Korean printing of Pokemon's Base Set, and a button promising
	 * to fetch one would be the app inventing a card. Said, not actioned.
	 */
	NOT_PUBLISHED,
}

/**
 * What one game is keeping on disk that the cache ceiling will not reclaim.
 *
 * @property sets how many distinct *sets* have a pinned record. Not how many records: a record is
 *   per set **and** language, so a set held in English and French is two records and one set. This
 *   counted records once, and the storage screen divided it by [knownSets] -- which is sets -- and
 *   reported "Card info 1111/988" for a Magic import and "1075/486" for Pokemon. Two different
 *   units either side of a slash is always wrong, however plausible the numbers look
 * @property extraSets sets held that the game's catalogue does not list -- on disk, not browsable,
 *   and not part of "how much of this game do I have". Counted apart rather than folded in, which
 *   is what made the row read 1044 of 988. An import no longer creates them: `importBulk` skips
 *   cards whose set the catalogue does not list. This stays because records written by an earlier
 *   build are still there until they are deleted, and because a catalogue can shrink -- a source
 *   withdrawing a set does not delete what was downloaded of it
 * @property languages how many *sets* each language covers. A count per language rather than a
 *   set of them, because "11 languages" for an English-only import is true and useless: Scryfall's
 *   cheap dump carries a handful of cards with no English printing, so ten of those eleven are one
 *   or two sets apiece
 * @property bytes what those records occupy
 */
data class GameStorage(
	val game: com.bitsycore.cardbrowser.core.model.GameId,
	val sets: Int,
	val extraSets: Int = 0,
	val languages: Map<com.bitsycore.cardbrowser.core.model.CardLanguage, Int> = emptyMap(),
	val bytes: Long,
	/**
	 * How many sets the game has in total, or `null` when its catalogue is not cached.
	 *
	 * Null is shown as "3 sets" rather than "3 of ?", because a denominator nobody can supply is
	 * not one worth inventing. It goes missing exactly when the cache has been cleared, which is
	 * also when the kept records are still there -- so the two must not depend on each other.
	 */
	val knownSets: Int? = null,
)
