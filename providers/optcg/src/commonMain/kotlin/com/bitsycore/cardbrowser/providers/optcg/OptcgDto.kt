package com.bitsycore.cardbrowser.providers.optcg

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A set from `GET /api/allSets/`.
 *
 * Two fields, and that is the whole record. No release date, no card count -- which is why One
 * Piece sets carry a null date and are ordered by their code instead.
 */
@Serializable
data class OptcgSetDto(
	@SerialName("set_name")
	val setName: String = "",
	@SerialName("set_id")
	val setId: String = "",
)

/**
 * A card from `GET /api/sets/{setId}/`, `/api/sets/card/{cardId}/` or `/api/sets/filtered/`.
 *
 * All three return the same shape, which is why one DTO covers the set listing, the detail lookup
 * and the cross-set search.
 *
 * The price fields are deliberately unmapped. They are a marketplace snapshot with no stated
 * currency, source or timestamp beyond `date_scraped`, and presenting a number like that as a card
 * price would be a claim this app has no basis for.
 */
@Serializable
data class OptcgCardDto(
	@SerialName("card_name")
	val cardName: String = "",
	@SerialName("card_text")
	val cardText: String? = null,
	@SerialName("set_name")
	val setName: String = "",
	@SerialName("set_id")
	val setId: String = "",
	@SerialName("card_set_id")
	val cardSetId: String = "",
	val rarity: String? = null,
	@SerialName("card_color")
	val cardColor: String? = null,
	@SerialName("card_type")
	val cardType: String? = null,
	val life: Int? = null,
	@SerialName("card_cost")
	val cardCost: String? = null,
	@SerialName("card_power")
	val cardPower: String? = null,
	@SerialName("sub_types")
	val subTypes: String? = null,
	@SerialName("counter_amount")
	val counterAmount: Int? = null,
	val attribute: String? = null,
	@SerialName("card_image")
	val cardImage: String? = null,
	@SerialName("card_image_id")
	val cardImageId: String? = null,
)
