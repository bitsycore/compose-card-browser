package com.bitsycore.cardbrowser.providers.scryfall

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==================
// MARK: Envelopes
// ==================

/** Scryfall's list envelope. Paging is `has_more` plus a fully-formed `next_page` URL. */
@Serializable
data class ScryfallListDto<T>(
	val data: List<T> = emptyList(),
	@SerialName("has_more")
	val hasMore: Boolean = false,
	@SerialName("next_page")
	val nextPage: String? = null,
	@SerialName("total_cards")
	val totalCards: Int? = null,
)

// ==================
// MARK: Sets
// ==================

@Serializable
data class ScryfallSetDto(
	val id: String,
	val code: String,
	val name: String,
	@SerialName("released_at")
	val releasedAt: String? = null,
	@SerialName("set_type")
	val setType: String? = null,
	@SerialName("card_count")
	val cardCount: Int = 0,
	val digital: Boolean = false,
	@SerialName("icon_svg_uri")
	val iconSvgUri: String? = null,
	@SerialName("tcgplayer_id")
	val tcgplayerId: Long? = null,
	@SerialName("parent_set_code")
	val parentSetCode: String? = null,
)

// ==================
// MARK: Cards
// ==================

/**
 * One printing.
 *
 * The `printed_*` fields are the localised ones and are absent on English cards, where `name` and
 * `type_line` already are the printed text. That asymmetry is why the mapper prefers `printed_name`
 * and falls back rather than the other way round.
 */
@Serializable
data class ScryfallCardDto(
	val id: String,
	@SerialName("oracle_id")
	val oracleId: String? = null,
	val lang: String? = null,
	val name: String,
	@SerialName("printed_name")
	val printedName: String? = null,
	@SerialName("flavor_text")
	val flavorText: String? = null,
	@SerialName("oracle_text")
	val oracleText: String? = null,
	@SerialName("printed_text")
	val printedText: String? = null,
	@SerialName("type_line")
	val typeLine: String? = null,
	@SerialName("printed_type_line")
	val printedTypeLine: String? = null,
	@SerialName("collector_number")
	val collectorNumber: String = "",
	val rarity: String? = null,
	val set: String = "",
	@SerialName("set_name")
	val setName: String = "",
	@SerialName("set_id")
	val setId: String? = null,
	val colors: List<String> = emptyList(),
	@SerialName("color_identity")
	val colorIdentity: List<String> = emptyList(),
	val cmc: Double? = null,
	val power: String? = null,
	val toughness: String? = null,
	val loyalty: String? = null,
	val artist: String? = null,
	val layout: String? = null,
	val finishes: List<String> = emptyList(),
	@SerialName("frame_effects")
	val frameEffects: List<String> = emptyList(),
	@SerialName("promo_types")
	val promoTypes: List<String> = emptyList(),
	@SerialName("full_art")
	val fullArt: Boolean = false,
	val textless: Boolean = false,
	val promo: Boolean = false,
	val variation: Boolean = false,
	val keywords: List<String> = emptyList(),
	@SerialName("image_uris")
	val imageUris: ScryfallImageUrisDto? = null,
	@SerialName("card_faces")
	val cardFaces: List<ScryfallCardFaceDto> = emptyList(),
	@SerialName("cardmarket_id")
	val cardmarketId: Long? = null,
	@SerialName("tcgplayer_id")
	val tcgplayerId: Long? = null,
)

/**
 * One face of a multi-faced card.
 *
 * A transforming card carries no top-level `image_uris`; each face has its own. Only the front is
 * shown, because the app's model has one artwork per printing and inventing a second tile for the
 * back would double the set's apparent size.
 */
@Serializable
data class ScryfallCardFaceDto(
	val name: String? = null,
	@SerialName("printed_name")
	val printedName: String? = null,
	@SerialName("type_line")
	val typeLine: String? = null,
	@SerialName("oracle_text")
	val oracleText: String? = null,
	@SerialName("flavor_text")
	val flavorText: String? = null,
	val colors: List<String> = emptyList(),
	val artist: String? = null,
	@SerialName("image_uris")
	val imageUris: ScryfallImageUrisDto? = null,
)

/**
 * The image variants Scryfall renders for a card.
 *
 * `thumb`, `grid` and `display` are WebP; `small`, `normal` and `large` are JPEG. The mapper uses
 * the WebP pair, which is both smaller and what the rest of this app already prefers.
 */
@Serializable
data class ScryfallImageUrisDto(
	val small: String? = null,
	val normal: String? = null,
	val large: String? = null,
	val png: String? = null,
	val thumb: String? = null,
	val grid: String? = null,
	val display: String? = null,
)
