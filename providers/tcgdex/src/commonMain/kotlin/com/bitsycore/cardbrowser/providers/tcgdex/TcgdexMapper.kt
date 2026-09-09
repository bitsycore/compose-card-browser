package com.bitsycore.cardbrowser.providers.tcgdex

import com.bitsycore.cardbrowser.core.model.Artwork
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardAttributes
import com.bitsycore.cardbrowser.core.model.CardClassification
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.ExternalIdKey
import com.bitsycore.cardbrowser.core.model.Finish
import com.bitsycore.cardbrowser.core.model.FinishCoverage
import com.bitsycore.cardbrowser.core.model.LanguageCoverage
import com.bitsycore.cardbrowser.core.model.LocalizedText
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SetSymbol
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.games.pokemon.PokemonGame
import kotlinx.datetime.LocalDate

/**
 * Turns TCGdex's wire format into core's model.
 *
 * Pure functions on an object, so the mapping is testable against captured JSON with no client and
 * no coroutine involved.
 */
internal object TcgdexMapper {

	// ============
	//  Images

	/**
	 * TCGdex image URLs are a *base*, not a file. The quality and extension go on the end.
	 *
	 * Measured on a real card: `low.webp` is 19 KB, `high.webp` 63 KB, and the PNG equivalents
	 * 55 KB and 257 KB. WebP for both variants, therefore, and the grid gets `low`.
	 */
	private const val THUMBNAIL_SUFFIX = "/low.webp"

	private const val DISPLAY_SUFFIX = "/high.webp"

	// ============
	//  Sets

	/**
	 * Maps one set from the catalogue.
	 *
	 * @param releaseDates id-to-date, from the GraphQL query. A set missing from it keeps a `null`
	 *   date and sorts last, which is the honest outcome for a locale-only set whose date the
	 *   English-only GraphQL schema never saw
	 */
	fun toSet(
		dto: TcgdexSetBriefDto,
		provider: ProviderId,
		releaseDates: Map<String, LocalDate>,
	): CardSet? {
		if (dto.id.isBlank()) return null
		return CardSet(
			id = SourceId(provider, dto.id),
			game = PokemonGame.id,
			// TCGdex's own set id doubles as the code -- `swsh3`, `base1`. The printed abbreviation
			// ("DAA") exists only on the full set object, which the catalogue endpoint does not
			// return, so using it here would cost 218 extra requests to save four characters.
			code = dto.id.uppercase(),
			name = dto.name.ifBlank { dto.id },
			// `total` rather than `official`: the grid shows secret rares, so the count beside it
			// has to include them or the set reads as over-full.
			cardCount = dto.cardCount?.total ?: dto.cardCount?.official,
			releaseDate = releaseDates[dto.id],
			externalIds = emptyMap(),
			symbol = symbolOf(dto.logo),
		)
	}

	/** Maps the set object returned with its cards. */
	fun toSet(dto: TcgdexSetDto, provider: ProviderId): CardSet? {
		if (dto.id.isBlank()) return null
		return CardSet(
			id = SourceId(provider, dto.id),
			game = PokemonGame.id,
			code = dto.abbreviation?.official?.ifBlank { null } ?: dto.id.uppercase(),
			name = dto.name.ifBlank { dto.id },
			cardCount = dto.cardCount?.total ?: dto.cardCount?.official,
			releaseDate = parseDate(dto.releaseDate),
			externalIds = emptyMap(),
			symbol = symbolOf(dto.logo),
		)
	}

	/**
	 * A set's logo, or `null` for the 61 of 218 sets that have none.
	 *
	 * The `logo` field, not `symbol`, even though a set symbol would suit a small tile better: 169
	 * sets advertise a `symbol` URL and **the CDN serves none of them** -- checked across several
	 * sets with `.png`, `.webp` and `.jpg`, all 404, while the bare URL returns an HTML error page.
	 * The logo is real and resolves, so that is what is used.
	 *
	 * Like card art, these URLs are a base that needs the extension appended.
	 */
	private fun symbolOf(logo: String?): SetSymbol? =
		logo?.ifBlank { null }?.let {
			// Full-colour wordmarks, so never recoloured.
			SetSymbol(url = "$it.webp", isMonochrome = false)
		}

	/** TCGdex dates are plain `YYYY-MM-DD`. A date that will not parse loses the set no data. */
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
	 * Maps a brief card, which is all a set response carries.
	 *
	 * A brief has no rarity, no type and no illustrator, so the classification is left empty rather
	 * than filled with defaults. The detail screen fetches the full card and replaces it.
	 *
	 * @param language the locale the request was made in. TCGdex serves a *different set catalogue*
	 *   per locale, so a card returned by the `fr` endpoint genuinely is a French printing -- this
	 *   is one of the few places in this app where a confirmed language is warranted
	 */
	fun toPrinting(
		dto: TcgdexCardBriefDto,
		provider: ProviderId,
		set: CardSet,
		language: CardLanguage,
	): CardPrinting? {
		if (dto.id.isBlank()) return null
		val vNumber = dto.localId?.ifBlank { null } ?: dto.id.substringAfterLast('-')
		return CardPrinting(
			id = SourceId(provider, dto.id),
			// TCGdex issues exactly one record per printing and its id is that printing's name, so
			// no separate key is needed and none is invented.
			printingKey = null,
			game = PokemonGame.id,
			setId = set.id,
			setCode = set.code,
			setName = set.name,
			collectorNumber = vNumber,
			providerRawCollectorNumber = dto.localId ?: vNumber,
			identity = null,
			text = LocalizedText(language = language, name = dto.name, isProviderStated = true),
			artwork = artworkOf(dto.id, dto.image, provider, language, artist = null, name = dto.name),
			languages = languageCoverageFor(language),
			finishes = FinishCoverage(),
		)
	}

	/** Maps a full card, which is what the detail screen and the cross-set search show. */
	fun toPrinting(
		dto: TcgdexCardDto,
		provider: ProviderId,
		set: CardSet?,
		language: CardLanguage,
	): CardPrinting? {
		if (dto.id.isBlank()) return null
		val vNumber = dto.localId?.ifBlank { null } ?: dto.id.substringAfterLast('-')
		val vSetId = set?.id ?: SourceId(provider, dto.set?.id?.ifBlank { null } ?: dto.id.substringBeforeLast('-'))
		return CardPrinting(
			id = SourceId(provider, dto.id),
			printingKey = null,
			game = PokemonGame.id,
			setId = vSetId,
			setCode = set?.code ?: vSetId.local.uppercase(),
			setName = set?.name ?: dto.set?.name ?: vSetId.local,
			collectorNumber = vNumber,
			providerRawCollectorNumber = dto.localId ?: vNumber,
			identity = null,
			text = LocalizedText(
				language = language,
				name = dto.name,
				// A Pokémon card's body text is an attack list rather than one rules paragraph, and
				// the app's model has one field. `effect` is a Trainer's text and `description` a
				// Basic's flavour-shaped blurb; whichever is present is the closest true answer.
				rules = dto.effect?.ifBlank { null },
				flavour = dto.description?.ifBlank { null },
				isProviderStated = true,
			),
			artwork = artworkOf(dto.id, dto.image, provider, language, dto.illustrator, dto.name),
			attributes = CardAttributes(
				// Pokémon has no single play cost -- cost is per attack -- so `energy` stays null
				// rather than being invented from the first attack's requirements. HP is the one
				// number that is genuinely a property of the card, and it goes in `might`.
				primary = dto.hp,
			),
			classification = CardClassification(
				type = dto.category?.ifBlank { null },
				supertype = dto.stage?.ifBlank { null } ?: dto.trainerType?.ifBlank { null },
				rarity = dto.rarity?.ifBlank { null },
				// The Pokémon "colour" axis. `GameVocabulary` labels this "Type" for Pokémon.
				domains = dto.types,
			),
			tags = listOfNotNull(dto.suffix?.ifBlank { null }, dto.energyType?.ifBlank { null }),
			languages = languageCoverageFor(language),
			finishes = finishCoverageOf(dto.variants),
			externalIds = buildMap {
				val vCardmarket = dto.variantsDetailed
					.mapNotNull { it.thirdParty?.cardmarket }
					.distinct()
					.map { it.toString() }
				if (vCardmarket.isNotEmpty()) put(ExternalIdKey.CARDMARKET_PRODUCT, vCardmarket)
				val vTcgplayer = dto.variantsDetailed
					.mapNotNull { it.thirdParty?.tcgplayer }
					.distinct()
					.map { it.toString() }
				if (vTcgplayer.isNotEmpty()) put(ExternalIdKey.TCGPLAYER, vTcgplayer)
			},
		)
	}

	// ============
	//  Pieces

	private fun artworkOf(
		cardId: String,
		imageBase: String?,
		provider: ProviderId,
		language: CardLanguage,
		artist: String?,
		name: String,
	): Artwork {
		// A card with no art at all is real -- TCGdex has entries for cards it has no scan of -- and
		// the empty string is what the UI already renders as a placeholder tile.
		val vBase = imageBase?.ifBlank { null }
		return Artwork(
			id = SourceId(provider, cardId),
			imageUrl = vBase?.plus(DISPLAY_SUFFIX).orEmpty(),
			thumbnailUrl = vBase?.plus(THUMBNAIL_SUFFIX),
			displayUrl = vBase?.plus(DISPLAY_SUFFIX),
			artist = artist?.ifBlank { null },
			// TCGdex marks variants as finishes, not as separate artworks, and issues one record
			// per card. There is no alternate-art relationship in the schema to report.
			treatment = ArtworkTreatment.STANDARD,
			// The image comes from the same locale endpoint as the text, so it is that language's
			// scan rather than an English one with translated text beside it.
			language = language,
			accessibilityText = "Pokémon card: $name.",
		)
	}

	/**
	 * What asking a locale endpoint tells us about languages.
	 *
	 * Only the requested one is confirmed. The others are UNKNOWN and never absent: TCGdex serving
	 * no Korean record for a card means its Korean catalogue does not list it, which is not the
	 * same as the card never having been printed in Korean.
	 */
	private fun languageCoverageFor(language: CardLanguage) =
		LanguageCoverage(confirmed = setOf(language))

	/**
	 * Turns the variant booleans into finish coverage.
	 *
	 * Both sides are populated, which is unusual among these providers and is earned: TCGdex states
	 * `holo: false` explicitly, so that is a real "not printed as holo" rather than a silence.
	 *
	 * The mapping is not one-to-one and does not pretend to be. `normal` is non-foil. `holo` and
	 * `reverse` are both foil treatments in Pokémon terms, so a card that is either is foil; only a
	 * card that is neither is recorded as foil-absent. `firstEdition` and `wPromo` are print runs
	 * rather than finishes and are not mapped at all.
	 */
	private fun finishCoverageOf(variants: TcgdexVariantsDto?): FinishCoverage {
		if (variants == null) return FinishCoverage()
		val vConfirmed = mutableSetOf<Finish>()
		val vAbsent = mutableSetOf<Finish>()

		when (variants.normal) {
			true -> vConfirmed += Finish.NON_FOIL
			false -> vAbsent += Finish.NON_FOIL
			null -> Unit
		}

		val vFoilish = listOfNotNull(variants.holo, variants.reverse)
		when {
			vFoilish.any { it } -> vConfirmed += Finish.FOIL
			vFoilish.isNotEmpty() -> vAbsent += Finish.FOIL
		}

		return FinishCoverage(confirmed = vConfirmed, absent = vAbsent)
	}
}
