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
	/**
	 * A string, because the API sends one -- and not always a number.
	 *
	 * Declared `Int?` originally, which worked until it did not: the client parses leniently, so a
	 * quoted `"5"` was accepted, but 17 Leader records across six sets carry the literal string
	 * `"NULL"`. That is not a number under any leniency, so deserialisation threw and **OP-02,
	 * OP-03, OP-04, OP-05, OP-07 and OP-08 failed to load at all**, reported as a server error when
	 * the server was fine.
	 *
	 * Kept as the raw string here and interpreted in the mapper, which is where a sentinel belongs.
	 */
	val life: String? = null,
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
