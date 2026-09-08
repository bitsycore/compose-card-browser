package com.bitsycore.cardbrowser.providers.wuwa

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The envelope every UCP endpoint wraps its payload in.
 *
 * `code` is an application-level status that is `1` on success -- **not** an HTTP status. The
 * transport can answer 200 while this says otherwise, which is why it is checked rather than
 * assumed.
 */
@Serializable
data class WuwaEnvelopeDto<T>(
	val code: Int = 0,
	val msg: String? = null,
	val data: T? = null,
)

/** A page of the card list. */
@Serializable
data class WuwaCardListDto(
	val list: List<WuwaCardBriefDto> = emptyList(),
	@SerialName("current_page")
	val currentPage: Int = 1,
	@SerialName("last_page")
	val lastPage: Int = 1,
	@SerialName("per_page")
	val perPage: Int = 20,
	val total: Int = 0,
	@SerialName("has_more")
	val hasMore: Boolean = false,
)

/**
 * A card as the list returns it.
 *
 * Six fields, and that is all. Rarity, attribute, cost and rules text exist only on the detail
 * endpoint, which is why a browsed set here shows names and images and the rest arrives when a card
 * is opened.
 */
@Serializable
data class WuwaCardBriefDto(
	val id: Long = 0,
	val code: String = "",
	val name: String = "",
	@SerialName("type_id")
	val typeId: Int? = null,
	@SerialName("card_type")
	val cardType: String? = null,
	val img: String? = null,
)

/** A card from `/web/card/info`. Every extra field is a localised display string. */
@Serializable
data class WuwaCardDetailDto(
	val id: Long = 0,
	val code: String = "",
	val name: String = "",
	@SerialName("type_id")
	val typeId: Int? = null,
	@SerialName("card_type")
	val cardType: String? = null,
	@SerialName("type_name")
	val typeName: String? = null,
	@SerialName("weapon_type_name")
	val weaponTypeName: String? = null,
	@SerialName("force_name")
	val forceName: String? = null,
	@SerialName("attr_name")
	val attrName: String? = null,
	@SerialName("feature_name")
	val featureName: String? = null,
	@SerialName("character_name")
	val characterName: String? = null,
	@SerialName("rarity_name")
	val rarityName: String? = null,
	@SerialName("color_name")
	val colorName: String? = null,
	/** Cost. A string, and empty rather than absent on cards that have none. */
	val fee: String? = null,
	val level: String? = null,
	val speed: String? = null,
	val damage: String? = null,
	/** The set this card comes in, as a bare code such as `SD01`. */
	val obtain: String? = null,
	val img: String? = null,
	val info: String? = null,
)
