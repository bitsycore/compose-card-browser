package com.bitsycore.cardbrowser.providers.ygoprodeck

import com.bitsycore.cardbrowser.core.model.Artwork
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardAttributes
import com.bitsycore.cardbrowser.core.model.CardClassification
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.ExternalIdKey
import com.bitsycore.cardbrowser.core.model.FinishCoverage
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.model.LanguageCoverage
import com.bitsycore.cardbrowser.core.model.LocalizedText
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SetSymbol
import com.bitsycore.cardbrowser.core.model.SourceId
import kotlinx.datetime.LocalDate

/** Turns YGOPRODeck's wire format into core's model. */
internal object YgoprodeckMapper {

	// ============
	//  Sets

	fun toSet(dto: YgoSetDto, provider: ProviderId): CardSet? {
		if (dto.setName.isBlank()) return null
		return CardSet(
			// The set **name**, not the code, and that is not a slip.
			//
			// `cardinfo.php` filters by `cardset={name}`; there is no parameter that takes a code.
			// Making the code the id would mean carrying a name-to-code map purely to undo it on
			// every request. Names are unique in `cardsets.php` and are what the API keys on.
			id = SourceId(provider, dto.setName),
			game = Game.YU_GI_OH,
			code = dto.setCode?.ifBlank { null } ?: dto.setName,
			name = dto.setName,
			cardCount = dto.numOfCards,
			releaseDate = parseDate(dto.tcgDate),
			// Not every set has one, and those that do are full-colour JPEGs of the set's box art.
			symbol = dto.setImage?.ifBlank { null }?.let {
				SetSymbol(url = it, isMonochrome = false)
			},
		)
	}

	/** `tcg_date` is a plain `YYYY-MM-DD`, and is absent for sets never released in the TCG. */
	fun parseDate(raw: String?): LocalDate? {
		if (raw.isNullOrBlank()) return null
		return try {
			LocalDate.parse(raw.substringBefore('T'))
		} catch (vError: IllegalArgumentException) {
			null
		}
	}

	// ============
	//  Cards

	/**
	 * Maps one card as it appears in [set].
	 *
	 * @param set the set being browsed, or `null` for a cross-set search result. When present, the
	 *   card's collector number and rarity are taken from its entry for *that* set rather than from
	 *   whichever printing happens to be listed first
	 * @param language the localisation requested, which YGOPRODeck applies to names and text
	 */
	fun toPrinting(
		dto: YgoCardDto,
		provider: ProviderId,
		set: CardSet?,
		language: CardLanguage,
	): CardPrinting? {
		if (dto.id == 0L) return null

		// The card's entry for this set. A card can appear several times under one set name --
		// "MRD-098", "MRD-E098" and "MRD-EN098" are the same card in three regional printings --
		// and the first is taken rather than all three being shown as separate tiles.
		val vAppearance = set?.let { vSet ->
			dto.cardSets.firstOrNull { it.setName.equals(vSet.name, ignoreCase = true) }
		} ?: dto.cardSets.firstOrNull()

		val vSetId = set?.id
			?: vAppearance?.setName?.ifBlank { null }?.let { SourceId(provider, it) }
			?: SourceId(provider, UNKNOWN_SET)

		// A card's `card_images` can hold several artworks with different ids. Only the first is
		// mapped: the app's model is one artwork per printing, and expanding them here would show
		// tiles the set's own card count does not account for.
		val vImage = dto.cardImages.firstOrNull()

		return CardPrinting(
			id = SourceId(provider, dto.id.toString()),
			printingKey = null,
			game = Game.YU_GI_OH,
			setId = vSetId,
			setCode = set?.code ?: vAppearance?.setCode?.substringBefore('-') ?: vSetId.local,
			setName = set?.name ?: vAppearance?.setName?.ifBlank { null } ?: vSetId.local,
			collectorNumber = collectorNumberOf(vAppearance?.setCode, dto.id),
			providerRawCollectorNumber = vAppearance?.setCode ?: dto.id.toString(),
			// YGOPRODeck's `id` is the card's passcode, shared by every printing -- but this
			// adapter emits one record per card rather than per printing, so there is no set of
			// printings for an identity to group. Left null rather than pointing a card at itself.
			identity = null,
			text = LocalizedText(
				language = language,
				name = dto.name,
				rules = dto.desc?.ifBlank { null },
				flavour = null,
				isProviderStated = true,
			),
			artwork = Artwork(
				id = SourceId(provider, (vImage?.id ?: dto.id).toString()),
				imageUrl = vImage?.imageUrl.orEmpty(),
				// A real small variant, served from the same CDN.
				thumbnailUrl = vImage?.imageUrlSmall,
				displayUrl = vImage?.imageUrl,
				// YGOPRODeck does not credit illustrators.
				artist = null,
				treatment = ArtworkTreatment.STANDARD,
				language = language,
				accessibilityText = "Yu-Gi-Oh! card: ${dto.name}.",
			),
			attributes = CardAttributes(
				// Level, which is the closest thing to a single cost number on a Yu-Gi-Oh card and
				// is what `GameVocabulary` labels it. Spells and traps have none.
				energy = dto.level,
				might = dto.atk,
				power = dto.def,
			),
			classification = CardClassification(
				type = dto.humanReadableCardType?.ifBlank { null } ?: dto.type?.ifBlank { null },
				supertype = dto.race?.ifBlank { null },
				// Rarity is a property of a *printing*, not of a card, which is why it comes from
				// the set appearance rather than from the card. A cross-set search result therefore
				// shows the rarity of whichever printing the API listed first, and nothing better
				// is available without knowing which set the user meant.
				rarity = vAppearance?.setRarity?.ifBlank { null },
				// Attribute -- DARK, LIGHT, WATER -- is Yu-Gi-Oh's colour-like axis.
				domains = listOfNotNull(dto.attribute?.ifBlank { null }),
			),
			tags = dto.typeline,
			languages = LanguageCoverage(confirmed = setOf(language)),
			finishes = FinishCoverage(),
			externalIds = mapOf(ExternalIdKey.PUBLISHER_CARD to listOf(dto.id.toString())),
		)
	}

	/**
	 * The number a card is printed with in a set.
	 *
	 * Set codes look like `MRD-098` or `SBC1-ENC09`: a set prefix, a hyphen, then a region-and-
	 * number tail. The tail is taken whole rather than having its letters stripped, because
	 * `ENC09` and `EN009` are different printings and reducing both to `9` would collapse them.
	 */
	private fun collectorNumberOf(setCode: String?, cardId: Long): String {
		val vTail = setCode?.substringAfter('-', missingDelimiterValue = "")?.trim()
		return vTail?.ifBlank { null } ?: cardId.toString()
	}

	/** Used only when a search result names no set at all, which the API does for a few records. */
	private const val UNKNOWN_SET = "Unlisted"
}
