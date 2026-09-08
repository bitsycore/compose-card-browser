package com.bitsycore.cardbrowser.providers.scryfall

import com.bitsycore.cardbrowser.core.model.Artwork
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardAttributes
import com.bitsycore.cardbrowser.core.model.CardClassification
import com.bitsycore.cardbrowser.core.model.CardIdentity
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardOrientation
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.ExternalIdKey
import com.bitsycore.cardbrowser.core.model.Finish
import com.bitsycore.cardbrowser.core.model.FinishCoverage
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.model.LanguageCoverage
import com.bitsycore.cardbrowser.core.model.LocalizedText
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import kotlinx.datetime.LocalDate

/** Turns Scryfall's wire format into core's model. Pure functions, testable without a client. */
internal object ScryfallMapper {

	// ============
	//  Sets

	fun toSet(dto: ScryfallSetDto, provider: ProviderId): CardSet? {
		if (dto.code.isBlank()) return null
		return CardSet(
			// The set *code*, not the UUID. It is what `q=set:blb` takes, it is what is printed on
			// the card, and it is stable across Scryfall database rebuilds in a way a UUID is not.
			id = SourceId(provider, dto.code),
			game = Game.MAGIC,
			code = dto.code.uppercase(),
			name = dto.name.ifBlank { dto.code },
			cardCount = dto.cardCount.takeIf { it > 0 },
			releaseDate = parseDate(dto.releasedAt),
			externalIds = buildMap {
				put(ExternalIdKey.PROVIDER_RECORD, listOf(dto.id))
				dto.tcgplayerId?.let { put(ExternalIdKey.TCGPLAYER, listOf(it.toString())) }
			},
		)
	}

	/** Scryfall dates are plain `YYYY-MM-DD`. */
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
	 * Maps one printing.
	 *
	 * @param language the language actually fetched, which is not always the one asked for -- see
	 *   [ScryfallProvider.listCards]. It is recorded as what it is
	 */
	fun toPrinting(
		dto: ScryfallCardDto,
		provider: ProviderId,
		set: CardSet?,
		language: CardLanguage,
	): CardPrinting? {
		if (dto.id.isBlank()) return null
		val vFront = dto.cardFaces.firstOrNull()
		val vSetLocal = dto.set.ifBlank { set?.id?.local } ?: return null
		val vSetId = set?.id ?: SourceId(provider, vSetLocal)

		return CardPrinting(
			id = SourceId(provider, dto.id),
			// Scryfall issues exactly one id per printing and never repeats it, so the record id
			// is the printing key and no second one is invented.
			printingKey = null,
			game = Game.MAGIC,
			setId = vSetId,
			setCode = set?.code ?: vSetLocal.uppercase(),
			setName = set?.name ?: dto.setName.ifBlank { vSetLocal },
			collectorNumber = dto.collectorNumber.ifBlank { "0" },
			providerRawCollectorNumber = dto.collectorNumber,
			// The one genuine cross-printing identity in this app. `oracle_id` is Scryfall's own
			// statement that two printings are the same card; nothing is inferred from names.
			identity = dto.oracleId?.ifBlank { null }?.let {
				CardIdentity(id = SourceId(provider, it), name = dto.name)
			},
			text = LocalizedText(
				language = language,
				// `printed_name` is the localised one and is absent on English cards, where `name`
				// already is what is printed.
				name = dto.printedName?.ifBlank { null }
					?: vFront?.printedName?.ifBlank { null }
					?: dto.name,
				rules = dto.printedText?.ifBlank { null }
					?: dto.oracleText?.ifBlank { null }
					?: vFront?.oracleText?.ifBlank { null },
				flavour = dto.flavorText?.ifBlank { null } ?: vFront?.flavorText?.ifBlank { null },
				isProviderStated = true,
			),
			artwork = artworkOf(dto, vFront, provider, language),
			attributes = CardAttributes(
				// Mana value. `GameVocabulary` labels this "Mana value" for Magic, which is what
				// the game calls it -- the shared field is a single play cost, not "energy".
				energy = dto.cmc?.toInt(),
				// Power and toughness are strings because of `*` and `1+*`. Only a plain number is
				// carried across; a variable power is not a number and is not pretended to be one.
				might = dto.power?.toIntOrNull(),
				power = dto.toughness?.toIntOrNull(),
			),
			classification = CardClassification(
				type = dto.printedTypeLine?.ifBlank { null }
					?: dto.typeLine?.ifBlank { null }
					?: vFront?.typeLine?.ifBlank { null },
				supertype = null,
				rarity = dto.rarity?.ifBlank { null },
				// Colours, which is Magic's colour-like axis and what `GameVocabulary` labels for
				// it. `colors` rather than `color_identity`: the former is what the card *is*, the
				// latter is a deck-building rule about what it may go in.
				domains = dto.colors.ifEmpty { vFront?.colors.orEmpty() }.map(::colourName),
			),
			tags = dto.keywords,
			// Scryfall states the printing language on the record itself, which makes this a fact
			// rather than an inference from which endpoint was called.
			languages = LanguageCoverage(confirmed = setOf(language)),
			finishes = finishCoverageOf(dto.finishes),
			externalIds = buildMap {
				dto.cardmarketId?.let { put(ExternalIdKey.CARDMARKET_PRODUCT, listOf(it.toString())) }
				dto.tcgplayerId?.let { put(ExternalIdKey.TCGPLAYER, listOf(it.toString())) }
				dto.oracleId?.ifBlank { null }?.let { put(ExternalIdKey.PUBLISHER_CARD, listOf(it)) }
			},
			orientation = orientationOf(dto.layout),
		)
	}

	// ============
	//  Pieces

	private fun artworkOf(
		dto: ScryfallCardDto,
		front: ScryfallCardFaceDto?,
		provider: ProviderId,
		language: CardLanguage,
	): Artwork {
		// A transforming card has no top-level images; each face carries its own. Only the front is
		// used, because one printing is one tile in this app's model.
		val vImages = dto.imageUris ?: front?.imageUris
		val vDisplay = vImages?.display ?: vImages?.normal ?: vImages?.large
		return Artwork(
			id = SourceId(provider, dto.id),
			imageUrl = vImages?.large ?: vImages?.normal ?: vDisplay.orEmpty(),
			// `grid` rather than `thumb`: both are WebP, and `grid` is the variant Scryfall renders
			// for exactly this use.
			thumbnailUrl = vImages?.grid ?: vImages?.thumb ?: vImages?.small,
			displayUrl = vDisplay,
			artist = dto.artist?.ifBlank { null } ?: front?.artist?.ifBlank { null },
			treatment = treatmentOf(dto),
			// Scryfall renders a separate image per language, so the art matches the text.
			language = language,
			accessibilityText = "Magic card: ${dto.printedName ?: dto.name}.",
		)
	}

	/**
	 * Which artwork treatment a printing carries.
	 *
	 * Ordered most specific first, because a card can satisfy several at once and the app's model
	 * holds one. Everything here is read from a field Scryfall states; nothing is guessed from a
	 * collector number or a name suffix.
	 */
	private fun treatmentOf(dto: ScryfallCardDto): ArtworkTreatment = when {
		"showcase" in dto.frameEffects || "extendedart" in dto.frameEffects ->
			if ("extendedart" in dto.frameEffects) ArtworkTreatment.EXTENDED_ART else ArtworkTreatment.ALTERNATE_ART
		dto.fullArt -> ArtworkTreatment.FULL_ART
		"signature" in dto.promoTypes -> ArtworkTreatment.SIGNATURE
		dto.promo -> ArtworkTreatment.PROMO
		// `variation` means Scryfall knows this is an alternate of another printing in the same set
		// but has not classified how.
		dto.variation -> ArtworkTreatment.UNKNOWN
		else -> ArtworkTreatment.STANDARD
	}

	/**
	 * Finishes, both sides.
	 *
	 * Scryfall's `finishes` array is exhaustive for a printing -- it lists every finish that
	 * printing exists in -- so anything not in it is genuinely absent rather than merely unstated.
	 * That is what makes populating `absent` here honest, where for most providers it would not be.
	 */
	private fun finishCoverageOf(finishes: List<String>): FinishCoverage {
		if (finishes.isEmpty()) return FinishCoverage()
		val vConfirmed = finishes.mapNotNull { vName ->
			when (vName.lowercase()) {
				"nonfoil" -> Finish.NON_FOIL
				"foil" -> Finish.FOIL
				"etched" -> Finish.ETCHED
				// An unrecognised finish is not mapped to a wrong one. It simply leaves that finish
				// unstated, which is the truth.
				else -> null
			}
		}.toSet()
		return FinishCoverage(
			confirmed = vConfirmed,
			// TEXTURED is deliberately never marked absent: Scryfall has no such value, so its
			// absence from the array says nothing about it.
			absent = setOf(Finish.NON_FOIL, Finish.FOIL, Finish.ETCHED) - vConfirmed,
		)
	}

	/** Scryfall's one-letter colours, spelled out for the filter chips. */
	private fun colourName(code: String): String = when (code.uppercase()) {
		"W" -> "White"
		"U" -> "Blue"
		"B" -> "Black"
		"R" -> "Red"
		"G" -> "Green"
		else -> code
	}

	/**
	 * Which way a card is printed, from its layout.
	 *
	 * Only the two layouts that are unambiguously wide are marked landscape. `split` is left
	 * portrait on purpose: Scryfall renders split cards in a portrait frame, so treating them as
	 * landscape would give the grid a wrongly-shaped tile for a correctly-shaped image.
	 */
	private fun orientationOf(layout: String?): CardOrientation = when (layout?.lowercase()) {
		"planar", "battle" -> CardOrientation.LANDSCAPE
		else -> CardOrientation.PORTRAIT
	}
}
