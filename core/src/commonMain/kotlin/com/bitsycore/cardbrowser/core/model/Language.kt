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
 * Every entry here is served by at least one provider in this app, verified against the live APIs.
 * A language no source can answer for would be a promise the app cannot keep, so it is not offered.
 *
 * @property code this app's own tag for the language, used in cache keys and as the default value to
 *   send a provider. It is not universal: sources disagree about Chinese in particular -- Scryfall
 *   says `zhs`, TCGdex says `zh-cn` -- so an adapter whose API differs maps it and the mismatch
 *   stays inside that adapter rather than leaking into this enum
 * @property displayName the English name shown in the UI, because the UI is English
 * @property aliases other tags a provider may use for the same language, matched by [fromCode]
 */
@Serializable
enum class CardLanguage(
	val code: String,
	val displayName: String,
	val aliases: List<String> = emptyList(),
) {
	FRENCH("fr", "French"),
	JAPANESE("ja", "Japanese"),
	ENGLISH("en", "English"),
	KOREAN("ko", "Korean"),
	SIMPLIFIED_CHINESE("zh-cn", "Simplified Chinese", aliases = listOf("zhs", "zh-hans", "zh")),
	TRADITIONAL_CHINESE("zh-tw", "Traditional Chinese", aliases = listOf("zht", "zh-hant", "zh-hk")),
	GERMAN("de", "German"),
	SPANISH("es", "Spanish", aliases = listOf("es-mx")),
	ITALIAN("it", "Italian"),
	PORTUGUESE("pt", "Portuguese", aliases = listOf("pt-br", "pt-pt")),
	RUSSIAN("ru", "Russian"),
	;

	companion object {

		/**
		 * The user's preference order, most wanted first.
		 *
		 * A preference, not a claim. Nothing here asserts that any game or provider offers all of
		 * them; [LanguageCoverage] is what says what is actually on offer for one printing, and
		 * `DataCapabilities.languages` what a whole source can be asked for.
		 */
		val PREFERENCE_ORDER: List<CardLanguage> = listOf(
			FRENCH,
			JAPANESE,
			ENGLISH,
			KOREAN,
			SIMPLIFIED_CHINESE,
			TRADITIONAL_CHINESE,
			GERMAN,
			SPANISH,
			ITALIAN,
			PORTUGUESE,
			RUSSIAN,
		)

		/**
		 * Looks a language up by [code] or by any of its [aliases], case-insensitively.
		 *
		 * `null` when no entry claims the tag, which is the honest answer: a provider that reports
		 * Hebrew is reporting something this app has no entry for, and inventing one from the tag
		 * would put a language on screen that nothing here can actually fetch.
		 */
		fun fromCode(code: String): CardLanguage? = entries.firstOrNull { vLanguage ->
			vLanguage.code.equals(code, ignoreCase = true) ||
				vLanguage.aliases.any { it.equals(code, ignoreCase = true) }
		}
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
