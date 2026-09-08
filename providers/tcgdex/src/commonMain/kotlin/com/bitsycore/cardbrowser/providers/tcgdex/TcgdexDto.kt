package com.bitsycore.cardbrowser.providers.tcgdex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==================
// MARK: Sets
// ==================

/**
 * A set as it appears in `GET /v2/{lang}/sets`.
 *
 * Deliberately thin: the list endpoint returns a "brief" object. Notably it has **no release date**
 * -- confirmed by counting, 0 of 218 entries carry one -- which is why [TcgdexSetDateDto] exists.
 */
@Serializable
data class TcgdexSetBriefDto(
	val id: String,
	val name: String,
	val logo: String? = null,
	val symbol: String? = null,
	val cardCount: TcgdexCardCountDto? = null,
)

/** How many cards a set holds. `official` excludes secret rares; `total` includes them. */
@Serializable
data class TcgdexCardCountDto(
	val total: Int? = null,
	val official: Int? = null,
)

/**
 * A set with every one of its cards, from `GET /v2/{lang}/sets/{setId}`.
 *
 * The reason this adapter never pages. One request returns the complete set, so the "is this the
 * whole set?" question the repository spends so much care on has a trivially correct answer here.
 */
@Serializable
data class TcgdexSetDto(
	val id: String,
	val name: String,
	val logo: String? = null,
	val symbol: String? = null,
	val releaseDate: String? = null,
	val cardCount: TcgdexCardCountDto? = null,
	val serie: TcgdexSerieDto? = null,
	val abbreviation: TcgdexAbbreviationDto? = null,
	val cards: List<TcgdexCardBriefDto> = emptyList(),
)

/** The era a set belongs to, e.g. `swsh` / "Sword & Shield". */
@Serializable
data class TcgdexSerieDto(
	val id: String,
	val name: String? = null,
)

/** A set's printed short code, where one exists. */
@Serializable
data class TcgdexAbbreviationDto(
	val official: String? = null,
)

// ==================
// MARK: GraphQL
// ==================

/**
 * The response to the one GraphQL query this adapter makes.
 *
 * It exists solely to recover release dates, which the REST set list omits. See
 * [TcgdexProvider.listSets] for why that is worth a second request.
 */
@Serializable
data class TcgdexGraphQlResponse(
	val data: TcgdexGraphQlData? = null,
)

@Serializable
data class TcgdexGraphQlData(
	val sets: List<TcgdexSetDateDto> = emptyList(),
)

/** An id and the date the REST catalogue does not carry. */
@Serializable
data class TcgdexSetDateDto(
	val id: String,
	val releaseDate: String? = null,
)

// ==================
// MARK: Cards
// ==================

/**
 * A card inside a set response, or in the cross-set search results.
 *
 * "Brief" is TCGdex's own term and it means what it says: id, number, name and an image base, and
 * nothing else. Everything the detail screen shows beyond those comes from [TcgdexCardDto].
 */
@Serializable
data class TcgdexCardBriefDto(
	val id: String,
	val localId: String? = null,
	val name: String,
	val image: String? = null,
)

/** A full card from `GET /v2/{lang}/cards/{cardId}`. */
@Serializable
data class TcgdexCardDto(
	val id: String,
	val localId: String? = null,
	val name: String,
	val image: String? = null,
	val category: String? = null,
	val illustrator: String? = null,
	val rarity: String? = null,
	val hp: Int? = null,
	val types: List<String> = emptyList(),
	val stage: String? = null,
	val suffix: String? = null,
	val description: String? = null,
	val effect: String? = null,
	val trainerType: String? = null,
	val energyType: String? = null,
	val set: TcgdexSetBriefDto? = null,
	val variants: TcgdexVariantsDto? = null,
	@SerialName("variants_detailed")
	val variantsDetailed: List<TcgdexVariantDetailDto> = emptyList(),
)

/**
 * Which physical variants of a card were printed.
 *
 * These are booleans rather than a list, which makes them a genuine two-sided statement: `false`
 * here is TCGdex saying a variant was not printed, not TCGdex declining to answer. That is what
 * lets the mapper populate both sides of `FinishCoverage` instead of leaving it unstated.
 */
@Serializable
data class TcgdexVariantsDto(
	val normal: Boolean? = null,
	val holo: Boolean? = null,
	val reverse: Boolean? = null,
	val firstEdition: Boolean? = null,
	val wPromo: Boolean? = null,
)

/** One variant, with the marketplace ids attached to that specific printing. */
@Serializable
data class TcgdexVariantDetailDto(
	val type: String? = null,
	val size: String? = null,
	val thirdParty: TcgdexThirdPartyDto? = null,
)

/** Marketplace ids. `cardmarket` is a real product id, which is rare among these providers. */
@Serializable
data class TcgdexThirdPartyDto(
	val cardmarket: Long? = null,
	val tcgplayer: Long? = null,
)
