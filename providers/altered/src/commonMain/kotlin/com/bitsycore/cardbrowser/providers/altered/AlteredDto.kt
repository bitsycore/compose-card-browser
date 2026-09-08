package com.bitsycore.cardbrowser.providers.altered

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==================
// MARK: Sets
// ==================

/**
 * The set index, `META/card_sets_{lang}.json`.
 *
 * Hydra/JSON-LD, because the mirror stores the official API's responses byte for byte rather than
 * reshaping them. That is the property that makes the mirror worth using: it is the real API's
 * output, just no longer served by the real API.
 */
@Serializable
data class AlteredSetIndexDto(
	@SerialName("hydra:member")
	val members: List<AlteredSetDto> = emptyList(),
)

@Serializable
data class AlteredSetDto(
	val reference: String = "",
	val code: String = "",
	val name: String = "",
	val id: String? = null,
	val createdAt: String? = null,
	val isActive: Boolean = true,
)

// ==================
// MARK: Cards
// ==================

/**
 * One card, from `SETS/{SET}/{SET}_{LANG}.json`.
 *
 * The set files are a bare JSON array rather than a Hydra collection, so there is no envelope here.
 */
@Serializable
data class AlteredCardDto(
	val reference: String = "",
	val id: String? = null,
	val name: String = "",
	val collectorNumber: String? = null,
	val imagePath: String? = null,
	val allImagePath: Map<String, String> = emptyMap(),
	val cardType: AlteredNamedRefDto? = null,
	val cardSubTypes: List<AlteredNamedRefDto> = emptyList(),
	val cardSet: AlteredNamedRefDto? = null,
	val rarity: AlteredNamedRefDto? = null,
	val mainFaction: AlteredFactionDto? = null,
	/**
	 * The card's numbers and rules text, as a string-keyed bag.
	 *
	 * Keys observed on real records: `MAIN_COST`, `RECALL_COST`, `OCEAN_POWER`, `FOREST_POWER`,
	 * `MOUNTAIN_POWER`, `MAIN_EFFECT`. Values are strings even when they are numbers.
	 */
	val elements: Map<String, String> = emptyMap(),
	val isSuspended: Boolean = false,
	val isBanned: Boolean = false,
	val isErrated: Boolean = false,
)

/** A reference with a localised display name. Altered spells every enum this way. */
@Serializable
data class AlteredNamedRefDto(
	val reference: String = "",
	val name: String = "",
	val id: String? = null,
)

/** A faction, which is Altered's colour-like axis. `color` is a hex string. */
@Serializable
data class AlteredFactionDto(
	val reference: String = "",
	val name: String = "",
	val color: String? = null,
)
