package com.bitsycore.cardbrowser.providers.ygoprodeck

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==================
// MARK: Sets
// ==================

/** A set from `GET /cardsets.php`, which returns a bare array of these. */
@Serializable
data class YgoSetDto(
	@SerialName("set_name")
	val setName: String = "",
	@SerialName("set_code")
	val setCode: String? = null,
	@SerialName("num_of_cards")
	val numOfCards: Int? = null,
	@SerialName("tcg_date")
	val tcgDate: String? = null,
	@SerialName("set_image")
	val setImage: String? = null,
)

// ==================
// MARK: Cards
// ==================

/** The `cardinfo.php` envelope. `meta` is what makes this the one adapter here with real paging. */
@Serializable
data class YgoCardResponseDto(
	val data: List<YgoCardDto> = emptyList(),
	val meta: YgoMetaDto? = null,
)

/**
 * Paging metadata.
 *
 * [totalRows] is the match count for the whole query, which is exactly what the repository needs to
 * know whether it holds a complete set.
 */
@Serializable
data class YgoMetaDto(
	@SerialName("current_rows")
	val currentRows: Int? = null,
	@SerialName("total_rows")
	val totalRows: Int? = null,
	@SerialName("rows_remaining")
	val rowsRemaining: Int? = null,
	@SerialName("next_page_offset")
	val nextPageOffset: Int? = null,
)

@Serializable
data class YgoCardDto(
	val id: Long = 0,
	val name: String = "",
	val type: String? = null,
	@SerialName("humanReadableCardType")
	val humanReadableCardType: String? = null,
	@SerialName("frameType")
	val frameType: String? = null,
	val desc: String? = null,
	val race: String? = null,
	val attribute: String? = null,
	val atk: Int? = null,
	val def: Int? = null,
	val level: Int? = null,
	val typeline: List<String> = emptyList(),
	@SerialName("card_sets")
	val cardSets: List<YgoCardSetDto> = emptyList(),
	@SerialName("card_images")
	val cardImages: List<YgoCardImageDto> = emptyList(),
)

/**
 * One appearance of a card in one set.
 *
 * A card carries every set it has ever been printed in, which is why the collector number for the
 * set being browsed has to be found in this list by name rather than read off the card.
 */
@Serializable
data class YgoCardSetDto(
	@SerialName("set_name")
	val setName: String = "",
	@SerialName("set_code")
	val setCode: String = "",
	@SerialName("set_rarity")
	val setRarity: String? = null,
)

/** One artwork. A card with alternate art has several, each with its own id. */
@Serializable
data class YgoCardImageDto(
	val id: Long = 0,
	@SerialName("image_url")
	val imageUrl: String? = null,
	@SerialName("image_url_small")
	val imageUrlSmall: String? = null,
	@SerialName("image_url_cropped")
	val imageUrlCropped: String? = null,
)
