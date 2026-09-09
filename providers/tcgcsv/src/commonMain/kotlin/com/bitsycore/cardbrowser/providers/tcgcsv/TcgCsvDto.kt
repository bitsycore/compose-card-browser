package com.bitsycore.cardbrowser.providers.tcgcsv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * TCGCSV's wire types. None of these leaves this module.
 *
 * Every endpoint answers with the same envelope, so [TcgCsvEnvelope] is generic over its payload.
 * Field names are TCGplayer's rather than this app's, which is the point of keeping them in here.
 */
@Serializable
internal data class TcgCsvEnvelope<T>(
	val success: Boolean = true,
	val errors: List<String> = emptyList(),
	val results: List<T> = emptyList(),
)

/**
 * A TCGplayer "group", which is a set.
 *
 * @property abbreviation frequently empty -- every Cyberpunk group has none, and so do several WoW
 *   ones. The mapper falls back to the group id rather than inventing a short code
 * @property publishedOn an ISO timestamp with a `T00:00:00` tail, present on every group measured
 */
@Serializable
internal data class TcgCsvGroupDto(
	val groupId: Long,
	val name: String,
	val abbreviation: String? = null,
	val publishedOn: String? = null,
)

/**
 * A TCGplayer product, which is a card *or* a sealed box.
 *
 * The catalogue does not separate the two, so [extendedData] is what tells them apart: a card
 * carries game fields and a booster box carries a UPC and nothing else. See
 * `TcgCsvMapping.cardFields`.
 *
 * @property imageUrl always the `_200w.jpg` rendition. The other sizes are derived from it -- see
 *   `TcgCsvMapper.artworkFor`
 */
@Serializable
internal data class TcgCsvProductDto(
	val productId: Long,
	val name: String,
	val cleanName: String? = null,
	val imageUrl: String? = null,
	val groupId: Long,
	val url: String? = null,
	val extendedData: List<TcgCsvExtendedDto> = emptyList(),
)

/** One name/value pair from a product's `extendedData`. */
@Serializable
internal data class TcgCsvExtendedDto(
	val name: String,
	@SerialName("displayName") val displayName: String? = null,
	val value: String? = null,
)
