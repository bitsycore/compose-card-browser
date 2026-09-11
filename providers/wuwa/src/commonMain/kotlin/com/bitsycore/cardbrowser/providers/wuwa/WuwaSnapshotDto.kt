package com.bitsycore.cardbrowser.providers.wuwa

import kotlinx.serialization.Serializable

/**
 * The bundled catalogue's wire format -- `src/commonMain/composeResources/files/wuwa-cards.json`.
 *
 * Deliberately not the API's shape. UCP's endpoints answer with one record per card *per locale*,
 * each carrying localised display strings and its own numeric id, and this is the aligned form of
 * that: one entry per printing, gathering the locales that carry it, with every localised label
 * lifted into [vocabulary] and referenced by the stable ids UCP's own filter options publish.
 *
 * `providers/wuwa/tools/scrape_wuwa.py` is what produces it, and its module comment is where the
 * alignment rules and their justification live.
 */
@Serializable
internal data class WuwaSnapshotDto(
	/** Bumped when the shape changes, so a snapshot older than the reader is a loud failure. */
	val schemaVersion: Int = 0,
	val source: WuwaSourceDto = WuwaSourceDto(),
	/**
	 * The image directories in use, so a printing carries a filename rather than a 150-character
	 * URL repeated once per locale. Two of them, both on the same Tencent COS bucket.
	 */
	val imageBases: List<String> = emptyList(),
	/** Facet name to its terms: `attribute`, `cardType`, `weaponType`, `faction`, and so on. */
	val vocabulary: Map<String, List<WuwaTermDto>> = emptyMap(),
	val cards: List<WuwaSnapshotCardDto> = emptyList(),
)

/** Where the snapshot came from and when, so a stale file is visible rather than merely suspected. */
@Serializable
internal data class WuwaSourceDto(
	val api: String = "",
	val scrapedOn: String = "",
	/** Locale tag to how many records that catalogue held when the snapshot was taken. */
	val locales: Map<String, Int> = emptyMap(),
	val note: String = "",
	/**
	 * Fields UCP's own locales disagreed about, and what the scraper wrote instead.
	 *
	 * Declared rather than left to `ignoreUnknownKeys`, so it is visible to a reader and assertable
	 * by a test. A number in this file that is one of two the source published is worth being able
	 * to find, and a *new* disagreement is worth noticing rather than absorbing.
	 */
	val disagreements: List<WuwaDisagreementDto> = emptyList(),
)

/**
 * One language-free field the locales did not agree on.
 *
 * Resolved by majority and recorded -- see `scrape_wuwa.py`. On 2026-09-11 there was exactly one:
 * `BP01-049` at ★★★, whose damage UCP gives as 6 in Simplified Chinese and 5 in both Japanese and
 * Korean. Type, level, cost and speed all match, so the locales plainly mean the same card and one
 * of them has a typo.
 *
 * @property values what each locale claimed, by locale tag
 * @property taken what the snapshot carries
 */
@Serializable
internal data class WuwaDisagreementDto(
	val code: String = "",
	val tier: Int? = null,
	val field: String = "",
	val values: Map<String, Int> = emptyMap(),
	val taken: Int? = null,
)

/**
 * One term of one facet: UCP's own numeric id, and its label in each locale.
 *
 * The id is what makes a facet comparable across locales. Attribute 2 is 焦熱, 热熔 and 용융, and
 * without the id there would be nothing to say those are one thing rather than three.
 */
@Serializable
internal data class WuwaTermDto(
	val id: Int = 0,
	/** Locale tag to label. Keyed by [com.bitsycore.cardbrowser.core.model.CardLanguage.code]. */
	val labels: Map<String, String> = emptyMap(),
)

/**
 * One printing: a printed code at one rarity tier.
 *
 * Not "one card". 36 codes ship at two rarities -- `SD01-003` is 秧秧 at ★★★ and again at ★★★★ --
 * and the two are **different illustrations of the same card**, verified by looking at them, not one
 * card with a foil version. So they are two printings sharing a [code], which is what
 * `CardIdentity` is for.
 *
 * @property stars the rarity as a count, which carries no language and is therefore the key that
 *   distinguishes tiers of one code. Null for a tier that has no stars
 * @property rarity a rarity that is a label rather than a count. Only "Collection" so far
 * @property set the product a card is *numbered* under, which is its code prefix
 * @property products where it is actually found. Six cards state a product that disagrees with
 *   their own number, so this is not a restatement of [set]
 * @property printings the locales that carry it, keyed by `CardLanguage.code`. A card absent from a
 *   locale is simply missing a key -- that is a gap in UCP's catalogue for that language, not a
 *   claim that no such printing was ever made
 */
@Serializable
internal data class WuwaSnapshotCardDto(
	val code: String = "",
	val stars: Int? = null,
	val rarity: String? = null,
	val set: String = "",
	val products: List<String> = emptyList(),
	val cardTypeId: Int? = null,
	val level: Int? = null,
	val cost: Int? = null,
	val speed: Int? = null,
	val damage: Int? = null,
	val attributeId: Int? = null,
	val weaponTypeId: Int? = null,
	val factionId: Int? = null,
	val colorId: Int? = null,
	val characterId: Int? = null,
	val featureIds: List<Int> = emptyList(),
	val printings: Map<String, WuwaSnapshotPrintingDto> = emptyMap(),
) {

	/**
	 * What distinguishes this printing from every other, in a way that does not depend on a locale.
	 *
	 * The API's own ids cannot do this job: it issues a different one per locale for the same card,
	 * so `651` means nothing without knowing it came from `ja-jp`. This is stable across locales and
	 * across re-scrapes, which is what lets a cached id keep working and lets the detail screen
	 * switch language without having to re-find a card by its collector number.
	 */
	val key: String get() = "$code#${stars ?: rarity ?: "?"}"
}

/** One locale's version of a printing: its text, its own numeric id, and its own scan. */
@Serializable
internal data class WuwaSnapshotPrintingDto(
	/** UCP's numeric id *within this locale*. Kept for provenance and for debugging against the API. */
	val providerId: Long = 0,
	val name: String? = null,
	val rules: String? = null,
	/** An index into [WuwaSnapshotDto.imageBases]. */
	val imageBase: Int? = null,
	val image: String? = null,
)
