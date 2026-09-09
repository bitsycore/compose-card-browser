package com.bitsycore.cardbrowser.providers.altered

import com.bitsycore.cardbrowser.core.model.Artwork
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardAttributes
import com.bitsycore.cardbrowser.core.model.CardClassification
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.ExternalIdKey
import com.bitsycore.cardbrowser.core.model.FinishCoverage
import com.bitsycore.cardbrowser.core.model.LanguageCoverage
import com.bitsycore.cardbrowser.core.model.LocalizedText
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.games.altered.AlteredGame

/** Turns the Altered mirror's wire format into core's model. */
internal object AlteredMapper {

	// ============
	//  Sets

	fun toSet(dto: AlteredSetDto, provider: ProviderId): CardSet? {
		if (dto.reference.isBlank()) return null
		return CardSet(
			// The set *reference* -- `ALIZE`, `CORE` -- because it is the path segment every data
			// file and every image is stored under. The printed `code` is shown instead.
			id = SourceId(provider, dto.reference),
			game = AlteredGame.id,
			code = dto.code.ifBlank { dto.reference }.uppercase(),
			name = dto.name.ifBlank { dto.reference },
			// Not in the index, and there is no way to know without downloading the set.
			cardCount = null,
			// Deliberately null even though `createdAt` is right there.
			//
			// `createdAt` is when the record was created in Altered's database, which for CORE is
			// 2024-01-04 against a September 2024 release. It correlates with release order and is
			// not a release date, and putting it in this field would make the UI print "Released
			// 4 January 2024" under a set that was not. It is kept below as provenance instead.
			releaseDate = null,
			externalIds = buildMap {
				dto.id?.ifBlank { null }?.let { put(ExternalIdKey.PROVIDER_RECORD, listOf(it)) }
			},
		)
	}

	// ============
	//  Cards

	/**
	 * Maps one card.
	 *
	 * @param language which localisation was downloaded. The mirror stores a separate file *and a
	 *   separate image* per language, so this is a confirmed printing language rather than a guess
	 * @param imageBaseUrl where the mirrored images live, since the official CDN no longer serves
	 *   them -- see [AlteredProvider]
	 */
	fun toPrinting(
		dto: AlteredCardDto,
		provider: ProviderId,
		set: CardSet,
		language: CardLanguage,
		imageBaseUrl: String,
	): CardPrinting? {
		if (dto.reference.isBlank()) return null

		return CardPrinting(
			// The reference, which is the only unique key here. `collectorNumber` is not: a set
			// contains both `ALT_ALIZE_A_AX_35_C` and `ALT_ALIZE_B_AX_35_C`, two separate print
			// runs of the same card, and both state collector number `TBF-005-C-FR`. 291 distinct
			// collector numbers across 327 cards in ALIZE alone.
			id = SourceId(provider, dto.reference),
			printingKey = null,
			game = AlteredGame.id,
			setId = set.id,
			setCode = set.code,
			setName = set.name,
			collectorNumber = collectorNumberOf(dto),
			providerRawCollectorNumber = dto.collectorNumber ?: dto.reference,
			identity = null,
			text = LocalizedText(
				language = language,
				name = dto.name.ifBlank { dto.reference },
				rules = dto.elements[ELEMENT_MAIN_EFFECT]?.ifBlank { null },
				// Altered's data carries no flavour text.
				flavour = null,
				isProviderStated = true,
			),
			artwork = Artwork(
				id = SourceId(provider, dto.reference),
				imageUrl = "$imageBaseUrl/${language.code}/${set.id.local}/${dto.reference}.jpg",
				// The mirror stores one full-size JPEG per card and offers no resized variant, so
				// there is genuinely no thumbnail to point at. Null rather than the same URL under
				// a different name, so `CardImage` knows it is loading a full-size file.
				thumbnailUrl = null,
				displayUrl = "$imageBaseUrl/${language.code}/${set.id.local}/${dto.reference}.jpg",
				// Altered credits artists in a separate `ARTISTS` file, not on the card record.
				artist = null,
				treatment = ArtworkTreatment.STANDARD,
				language = language,
				accessibilityText = "Altered card: ${dto.name}.",
			),
			attributes = CardAttributes(
				// Hand cost -- what you pay to play the card. `GameVocabulary` labels it.
				cost = dto.elements[ELEMENT_MAIN_COST]?.trim()?.toIntOrNull(),
				// Reserve cost. Not a "might" in Altered's terms, but it is the card's second
				// number and the model has two slots; the labels come from `GameVocabulary`.
				primary = dto.elements[ELEMENT_RECALL_COST]?.trim()?.toIntOrNull(),
				// The three region powers are separate values and cannot be collapsed into one, so
				// none of them is put in `power` rather than one being chosen arbitrarily.
			),
			classification = CardClassification(
				type = dto.cardType?.name?.ifBlank { null },
				supertype = dto.cardSubTypes.firstOrNull()?.name?.ifBlank { null },
				rarity = dto.rarity?.name?.ifBlank { null },
				// Faction is Altered's colour-like axis.
				domains = listOfNotNull(dto.mainFaction?.name?.ifBlank { null }),
			),
			tags = buildList {
				addAll(dto.cardSubTypes.mapNotNull { it.name.ifBlank { null } })
				// Play-legality states, which a browser should surface: a banned card is still a
				// card, and the user should be able to see that it is banned.
				if (dto.isBanned) add("Banned")
				if (dto.isSuspended) add("Suspended")
				if (dto.isErrated) add("Errata")
			},
			// The mirror carries de, en, es, fr and it. Only the one downloaded is confirmed; the
			// others are not marked absent, because the app's four are not the mirror's five and
			// silence about Japanese is not a statement about it.
			languages = LanguageCoverage(confirmed = setOf(language)),
			finishes = FinishCoverage(),
			externalIds = buildMap {
				dto.id?.ifBlank { null }?.let { put(ExternalIdKey.PROVIDER_RECORD, listOf(it)) }
			},
		)
	}

	/**
	 * The number a card is printed with.
	 *
	 * `collectorNumber` arrives as `TBF-005-C-FR`: set code, number, rarity letter, language. Only
	 * the number sorts, so it is pulled out; the full string is kept as the raw value.
	 */
	private fun collectorNumberOf(dto: AlteredCardDto): String {
		val vParts = dto.collectorNumber?.split('-').orEmpty()
		val vNumber = vParts.getOrNull(1)?.trim()
		if (!vNumber.isNullOrBlank()) return vNumber
		// No collector number: fall back to the numeric segment of the reference,
		// `ALT_ALIZE_A_AX_35_C` -> `35`. Better than sorting the whole set under one key.
		return dto.reference.split('_').lastOrNull { it.toIntOrNull() != null } ?: dto.reference
	}

	private const val ELEMENT_MAIN_COST = "MAIN_COST"

	private const val ELEMENT_RECALL_COST = "RECALL_COST"

	private const val ELEMENT_MAIN_EFFECT = "MAIN_EFFECT"
}
