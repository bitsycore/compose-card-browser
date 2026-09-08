package com.bitsycore.cardbrowser.core.model

import kotlinx.serialization.Serializable

// ==================
// MARK: Card language
// ==================

/**
 * The language a card is *printed* in, which is not the language the app's own UI is in.
 *
 * The app UI is English throughout. This type is about the physical printing and about the text and
 * image a provider can supply for it.
 *
 * @property code the BCP 47 primary subtag, used when talking to providers and in cache keys
 * @property displayName the English name shown in the UI, because the UI is English
 */
@Serializable
enum class CardLanguage(val code: String, val displayName: String) {
	FRENCH("fr", "French"),
	JAPANESE("ja", "Japanese"),
	ENGLISH("en", "English"),
	KOREAN("ko", "Korean"),
	;

	companion object {

		/**
		 * The user's preference order: French, then Japanese, then English, then Korean.
		 *
		 * A preference, not a claim. Nothing here asserts that any game or provider offers all
		 * four; [LanguageCoverage] is what says what is actually on offer.
		 */
		val PREFERENCE_ORDER: List<CardLanguage> = listOf(FRENCH, JAPANESE, ENGLISH, KOREAN)

		/** Looks a language up by [code], case-insensitively. `null` if it is not one of the four. */
		fun fromCode(code: String): CardLanguage? =
			entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
	}
}

// ==================
// MARK: Coverage
// ==================

/**
 * What a provider can say about the languages of one printing.
 *
 * Split three ways rather than held as a single set, so that "we know there is no Korean printing"
 * and "nobody has told us about Korean" stay distinguishable all the way to the screen. Any
 * language named in none of the three lists is treated as [Availability.UNKNOWN].
 *
 * @property confirmed languages this printing is known to exist in
 * @property absent languages this printing is known *not* to exist in
 * @property textOnlyFallback languages for which the provider has no localized text, so the app
 *   must display another language's text and say so
 */
@Serializable
data class LanguageCoverage(
	val confirmed: Set<CardLanguage> = emptySet(),
	val absent: Set<CardLanguage> = emptySet(),
	val textOnlyFallback: Set<CardLanguage> = emptySet(),
) {

	/** How [language] stands for this printing. */
	fun availabilityOf(language: CardLanguage): Availability = when (language) {
		in confirmed -> Availability.AVAILABLE
		in absent -> Availability.UNAVAILABLE
		else -> Availability.UNKNOWN
	}

	/**
	 * The language to actually display, given a preference order.
	 *
	 * Returns the first preferred language that is confirmed. If none is, falls back to any
	 * confirmed language at all -- and if the provider confirmed nothing, returns `null`, which the
	 * UI must render as "language not stated" rather than silently as English.
	 */
	fun resolve(preferences: List<CardLanguage> = CardLanguage.PREFERENCE_ORDER): CardLanguage? =
		preferences.firstOrNull { it in confirmed }
			?: CardLanguage.PREFERENCE_ORDER.firstOrNull { it in confirmed }

	/** True when the provider stated nothing at all about languages for this printing. */
	val isUnstated: Boolean get() = confirmed.isEmpty() && absent.isEmpty()

	companion object {

		/** The common case for an English-only source: English confirmed, the rest unknown. */
		val ENGLISH_ONLY: LanguageCoverage = LanguageCoverage(confirmed = setOf(CardLanguage.ENGLISH))
	}
}

/**
 * Which language's text and image the user is actually looking at, and whether that was their ask.
 *
 * Built by the UI layer, not by a provider. Selecting French must not relabel an English-only
 * record as a French printing, so when [requested] and [shown] differ the screen says so; that is
 * what [isFallback] drives.
 *
 * @property requested the language the user asked for, or their highest preference
 * @property shown the language actually rendered, or `null` when the provider stated none
 */
data class LanguageResolution(
	val requested: CardLanguage,
	val shown: CardLanguage?,
) {

	/** True when the user is being shown something other than what they asked for. */
	val isFallback: Boolean get() = shown != requested
}
