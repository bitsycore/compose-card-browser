package com.bitsycore.cardbrowser.providers.optcg

import com.bitsycore.cardbrowser.core.model.Artwork
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardAttributes
import com.bitsycore.cardbrowser.core.model.CardClassification
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.FinishCoverage
import com.bitsycore.cardbrowser.core.model.LanguageCoverage
import com.bitsycore.cardbrowser.core.model.LocalizedText
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.games.onepiece.OnePieceGame

/** Turns the OPTCG API's wire format into core's model. */
internal object OptcgMapper {

	// ============
	//  Sets

	fun toSet(dto: OptcgSetDto, provider: ProviderId): CardSet? {
		if (dto.setId.isBlank()) return null
		return CardSet(
			id = SourceId(provider, dto.setId),
			game = OnePieceGame.id,
			code = dto.setId.uppercase(),
			name = dto.setName.ifBlank { dto.setId },
			// The API states neither, and neither is guessed. A set list ordered by code is the
			// honest consequence -- see `CardRepository.SET_ORDER`.
			cardCount = null,
			releaseDate = null,
		)
	}

	// ============
	//  Cards

	fun toPrinting(dto: OptcgCardDto, provider: ProviderId, set: CardSet?): CardPrinting? {
		if (dto.cardSetId.isBlank()) return null
		val vSetLocal = dto.setId.ifBlank { set?.id?.local ?: dto.cardSetId.substringBefore('-') }
		val vSetId = set?.id ?: SourceId(provider, vSetLocal)

		return CardPrinting(
			// `card_set_id` -- "OP01-077" -- is the printed identifier and the only stable key the
			// API exposes. There is no separate record id.
			id = SourceId(provider, dto.cardSetId),
			printingKey = null,
			game = OnePieceGame.id,
			setId = vSetId,
			setCode = set?.code ?: vSetLocal.uppercase(),
			setName = set?.name ?: dto.setName.ifBlank { vSetLocal },
			// The number after the hyphen. "OP01-077" is card 077 of OP-01.
			collectorNumber = dto.cardSetId.substringAfterLast('-', missingDelimiterValue = dto.cardSetId),
			providerRawCollectorNumber = dto.cardSetId,
			identity = null,
			text = LocalizedText(
				language = CardLanguage.ENGLISH,
				name = dto.cardName.ifBlank { dto.cardSetId },
				rules = dto.cardText?.ifBlank { null },
				// The API has no flavour-text field, and the rules text is not it.
				flavour = null,
				// Inferred, not stated. The API has no language field at all; the data is plainly
				// English, and saying so with `isProviderStated = false` is the difference between
				// an observation and a claim the source made.
				isProviderStated = false,
			),
			artwork = Artwork(
				id = SourceId(provider, dto.cardSetId),
				imageUrl = dto.cardImage.orEmpty(),
				// No resizing and no small variant exists on this CDN, so the grid loads the same
				// file the detail screen does. Stated rather than worked around.
				thumbnailUrl = null,
				displayUrl = dto.cardImage,
				// The API does not credit illustrators.
				artist = null,
				treatment = ArtworkTreatment.STANDARD,
				language = null,
				accessibilityText = "One Piece card: ${dto.cardName}.",
			),
			attributes = CardAttributes(
				// Cost arrives as a string and is blank on Leaders, which have no cost.
				cost = dto.cardCost?.trim()?.toIntOrNull(),
				primary = dto.cardPower?.trim()?.toIntOrNull(),
				secondary = dto.life,
			),
			classification = CardClassification(
				type = dto.cardType?.ifBlank { null },
				supertype = dto.attribute?.ifBlank { null },
				rarity = expandRarity(dto.rarity),
				// Colour is One Piece's colour-like axis. Dual-colour cards arrive as "Red/Green".
				// Split, because a dual-colour card is both of its colours rather than a seventh
				// thing. The API states them as one string -- `Blue Purple`, `Green Red` -- and
				// leaving it whole meant a Blue/Purple leader did not match the Blue filter and
				// added a "Blue Purple" chip of its own to the sheet.
				domains = colourKeysOf(dto.cardColor),
			),
			tags = dto.subTypes
				?.split('/')
				.orEmpty()
				.map { it.trim() }
				.filter { it.isNotEmpty() },
			// Not `ENGLISH_ONLY`. The API states no language, so English is not *confirmed* and
			// Japanese is certainly not absent -- One Piece is a Japanese game with Japanese
			// printings this source simply does not carry.
			languages = LanguageCoverage(),
			finishes = FinishCoverage(),
		)
	}

	/**
	 * Expands the API's abbreviated rarity codes.
	 *
	 * Expanded here rather than left as "SEC" because the rarity ladder, the filter chips and the
	 * detail screen all show this string to a reader. An unrecognised code is passed through
	 * untouched rather than being mapped to the nearest guess.
	 */
	fun expandRarity(code: String?): String? = when (code?.trim()?.uppercase()) {
		null, "" -> null
		"C" -> "Common"
		"UC" -> "Uncommon"
		"R" -> "Rare"
		"SR" -> "Super Rare"
		"SEC" -> "Secret Rare"
		"L" -> "Leader"
		"P" -> "Promo"
		"SP" -> "Special"
		else -> code.trim()
	}

	/**
	 * One Piece colours, split apart and keyed as `OnePieceGame` declares them.
	 *
	 * `card_color` carries a dual-colour card as a single space-separated string, and older records
	 * use a slash. Both separators are handled, and a token the game does not declare passes
	 * through lower-cased rather than being dropped -- a colour this app has not heard of is still
	 * a colour the card has.
	 */
	internal fun colourKeysOf(raw: String?): List<String> =
		raw.orEmpty()
			.split('/', ' ', '\u3001', ',')
			.mapNotNull { it.trim().lowercase().ifBlank { null } }
			.distinct()
}
