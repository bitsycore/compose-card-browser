package com.bitsycore.toploader.data.repository

import com.bitsycore.toploader.core.provider.ProviderError
import com.bitsycore.toploader.data.cache.Completeness

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
	val cards: List<com.bitsycore.toploader.core.model.CardPrinting>,
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
	val cards: List<com.bitsycore.toploader.core.model.CardPrinting>,
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
	val language: com.bitsycore.toploader.core.model.CardLanguage?,
	val substitutedFor: com.bitsycore.toploader.core.model.CardLanguage? = null,
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
	val game: com.bitsycore.toploader.core.model.GameId,
	val sets: Int,
	val extraSets: Int = 0,
	val languages: Map<com.bitsycore.toploader.core.model.CardLanguage, Int> = emptyMap(),
	val bytes: Long,
	/**
	 * How many sets the game has in total, or `null` when its catalogue is not cached.
	 *
	 * Null is shown as "3 sets" rather than "3 of ?", because a denominator nobody can supply is
	 * not one worth inventing. It goes missing exactly when the cache has been cleared, which is
	 * also when the kept records are still there -- so the two must not depend on each other.
	 */
	val knownSets: Int? = null,
	/** Sets held because the user asked, of [sets]. The rest were left behind by browsing. */
	val downloadedSets: Int = sets,
	/**
	 * How much of the whole game is on the device, or null when nothing can be divided.
	 *
	 * See [Completion]: often an estimate, and it says so. The estimate is built in
	 * `CardRepository.keptByGame` from the set sizes the catalogue *does* state.
	 */
	val completion: Completion? = null,
)

/**
 * How much of something is held, against how much there is.
 *
 * ## Why an estimate is a first-class state here
 *
 * The denominator is often not knowable. A set list states a card count for most sources and for
 * some -- OPTCG -- states none at all, so "how many cards does this game have" can only be answered
 * for part of it. The choice is between saying nothing, inventing a number, and saying a number and
 * admitting what it is. The first is unhelpful for a screen whose whole job is to show how much is
 * on the device; the second is the thing this codebase will not do.
 *
 * So [isEstimate] travels with the figure and the screen renders it differently -- "62%" and "~62%"
 * are different claims and must not look the same.
 *
 * @property heldCards cards actually stored, counting each set once however many languages it is
 *   held in
 * @property totalCards what the whole population holds, exactly when [isEstimate] is false
 */
data class Completion(
	val heldCards: Int,
	val totalCards: Int,
	val isEstimate: Boolean,
) {

	/**
	 * 0..100, and only ever 100 when nothing is missing.
	 *
	 * Rounded *down*, deliberately. 99.6% rounds to 100 and a row reading "100%" beside a set that
	 * is one card short is exactly the kind of claim this app must not make. The reverse -- showing
	 * 99% for a complete set -- cannot happen, because a complete one takes the first branch.
	 */
	val percent: Int
		get() = when {
			totalCards <= 0 -> 0
			heldCards >= totalCards -> 100
			else -> ((heldCards * 100L) / totalCards).toInt().coerceIn(0, 99)
		}

	val fraction: Float
		get() = if (totalCards <= 0) 0f else (heldCards.toFloat() / totalCards).coerceIn(0f, 1f)

	/** "62%" or "~62%". The tilde is the difference between a count and a guess. */
	val label: String get() = if (isEstimate) "~$percent%" else "$percent%"
}

/**
 * One stored edition: a set, in one language.
 *
 * What the storage screen shows when a game is opened. A set held in English and Japanese is two of
 * these, because that is what is on disk and what deleting one of them removes.
 *
 * @property provider carried because deleting needs it. A set id is only unique within the source
 *   that issued it
 * @property languageCode as stored, so `"-"` where the source states no language at all. Not a
 *   [com.bitsycore.toploader.core.model.CardLanguage]: "the source never said" is a real state and
 *   has no enum value
 * @property isInCatalogue false for a set the game's cached set list does not offer -- a bulk import
 *   brings these, and they are real records the screen must be able to reach
 */
data class KeptSet(
	val provider: String,
	val setId: String,
	val languageCode: String,
	val label: String,
	/**
	 * The printed set code -- "OP-01", "SV08" -- or null where nothing on disk states one.
	 *
	 * From the cached set list where there is one, and otherwise the local half of [setId], which
	 * is the code itself for most sources and an opaque key for a few. Null rather than the
	 * qualified id: "optcg:OP-01" is this app's plumbing and not a thing printed on a card.
	 */
	val code: String? = null,
	val cardCount: Int,
	val bytes: Long,
	val isInCatalogue: Boolean = true,
	/** True when the user asked for this set; false when browsing left it behind. */
	val isDownloaded: Boolean = true,
	/** True when every page was fetched. A set browsed part-way is stored and is not this. */
	val isComplete: Boolean = true,
	/** What the set list says this set holds, or null where the source states no count. */
	val knownCardCount: Int? = null,
) {

	/** The language, or null where the source states none. */
	val language: com.bitsycore.toploader.core.model.CardLanguage?
		get() = com.bitsycore.toploader.core.model.CardLanguage.fromCode(languageCode)

	/**
	 * How much of this set is held, or null when there is no honest way to say.
	 *
	 * A complete set is 100% whatever the set list claims -- the source served every page, and a
	 * stale catalogue count is not evidence against that. Otherwise it needs a denominator, and
	 * where the source states none there is nothing to divide by: the screen says how many cards
	 * are held and stops, rather than guessing a percentage for one set out of thin air.
	 */
	val completion: Completion?
		get() = when {
			isComplete -> Completion(cardCount, cardCount.coerceAtLeast(1), isEstimate = false)
			knownCardCount != null && knownCardCount > 0 ->
				Completion(cardCount, knownCardCount, isEstimate = false)
			else -> null
		}
}
