package com.bitsycore.cardbrowser.providers.riftcodex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

// ==================
// MARK: DTOs
// ==================
//
// Riftcodex's wire format, and nowhere else. These types never leave this module: `RiftcodexMapper`
// turns them into core's model and the UI never sees a field named `riftbound_id`.
//
// Shapes taken from the live OpenAPI document at https://api.riftcodex.com/openapi.json (version
// 0.2.0) and checked against real responses for OGN, OGS and the promo sets. Every field the
// document marks nullable is nullable here, and every field it marks required is still given a
// default where a default is harmless -- the API is "an active work in progress" by its own
// description, and a required field going missing should degrade one card rather than fail a page.

/** A page envelope. Riftcodex returns the same shape for cards and for sets. */
@Serializable
internal data class PageDto<T>(
	val items: List<T> = emptyList(),
	val total: Int = 0,
	val page: Int = 1,
	val size: Int = 0,
	val pages: Int = 0,
)

/** A set. `cardmarket_id` is a string on some records and an array on others, hence [JsonElement]. */
@Serializable
internal data class SetDto(
	val id: String,
	val name: String = "",
	@SerialName("set_id") val setId: String = "",
	@SerialName("card_count") val cardCount: Int? = null,
	@SerialName("tcgplayer_id") val tcgplayerId: String? = null,
	@SerialName("cardmarket_id") val cardmarketId: JsonElement? = null,
	@SerialName("published_on") val publishedOn: String? = null,
) {

	/**
	 * The Cardmarket expansion ids as a flat list.
	 *
	 * The API documents this as "a string or a list of strings" and both really occur: `OGN` has
	 * `"6286"` and `OPP` has `["6322", "6483"]`. Anything else is treated as absent rather than
	 * failing the whole set list.
	 */
	fun cardmarketIds(): List<String> = when (val vRaw = cardmarketId) {
		is JsonPrimitive -> if (vRaw.isString) listOf(vRaw.content) else emptyList()
		is JsonArray -> vRaw.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
		else -> emptyList()
	}
}

/** A card. One record is one printing, not one card identity -- see [RiftcodexMapper]. */
@Serializable
internal data class CardDto(
	val id: String,
	val name: String = "",
	@SerialName("riftbound_id") val riftboundId: String = "",
	@SerialName("tcgplayer_id") val tcgplayerId: String? = null,
	@SerialName("collector_number") val collectorNumber: Int? = null,
	val attributes: AttributesDto = AttributesDto(),
	val classification: ClassificationDto = ClassificationDto(),
	val text: TextDto = TextDto(),
	val set: CardSetDto = CardSetDto(),
	val media: MediaDto = MediaDto(),
	val tags: List<String> = emptyList(),
	val orientation: String? = null,
	val metadata: MetadataDto = MetadataDto(),
)

@Serializable
internal data class AttributesDto(
	val energy: Int? = null,
	val might: Int? = null,
	val power: Int? = null,
)

@Serializable
internal data class ClassificationDto(
	val type: String? = null,
	val supertype: String? = null,
	val rarity: String? = null,
	val domain: List<String> = emptyList(),
)

@Serializable
internal data class TextDto(
	val rich: String? = null,
	val plain: String? = null,
	val flavour: String? = null,
)

/** The set a card belongs to, as embedded in a card record. Thinner than [SetDto]. */
@Serializable
internal data class CardSetDto(
	@SerialName("set_id") val setId: String = "",
	val label: String = "",
)

@Serializable
internal data class MediaDto(
	@SerialName("image_url") val imageUrl: String? = null,
	val artist: String? = null,
	@SerialName("accessibility_text") val accessibilityText: String? = null,
)

/**
 * Riftcodex's variant flags.
 *
 * These three booleans are the whole of what this provider says about treatments. There is no
 * finish field anywhere in the schema, which is why [RiftcodexMapper] reports finish coverage as
 * unstated rather than inventing a non-foil default.
 */
@Serializable
internal data class MetadataDto(
	@SerialName("clean_name") val cleanName: String? = null,
	@SerialName("updated_on") val updatedOn: String? = null,
	@SerialName("alternate_art") val alternateArt: Boolean = false,
	val overnumbered: Boolean = false,
	val signature: Boolean = false,
)
