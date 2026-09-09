package com.bitsycore.cardbrowser.providers.tcgcsv

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.model.Artwork
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardAttributes
import com.bitsycore.cardbrowser.core.model.CardClassification
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.ExternalIdKey
import com.bitsycore.cardbrowser.core.model.LocalizedText
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import kotlinx.datetime.LocalDate

/**
 * Turns TCGCSV records into the shared model.
 *
 * Everything game-specific arrives as a [TcgCsvMapping], so this file names no game.
 */
internal object TcgCsvMapper {

	// ==================
	// MARK: Sets
	// ==================

	/**
	 * A group becomes a set.
	 *
	 * `cardCount` is left null rather than guessed. The group record does not carry one and the only
	 * way to learn it is to fetch the products, which is exactly the request the set list is trying
	 * to avoid -- and a wrong count is what makes the app mislabel a partial page as a whole set.
	 */
	fun toSet(dto: TcgCsvGroupDto, provider: ProviderId, game: GameProfile): CardSet = CardSet(
		id = SourceId(provider, dto.groupId.toString()),
		game = game.id,
		// Cyberpunk publishes no abbreviation for any group and several WoW ones have none either,
		// so the fallback is the group id -- TCGplayer's own identifier for the set, which is at
		// least true, rather than an abbreviation invented from the name.
		code = dto.abbreviation?.takeIf { it.isNotBlank() } ?: dto.groupId.toString(),
		name = dto.name,
		cardCount = null,
		releaseDate = parseDate(dto.publishedOn),
		externalIds = mapOf(ExternalIdKey.TCGPLAYER to listOf(dto.groupId.toString())),
	)

	/** `2011-10-11T00:00:00` to a date. Null for anything that does not parse, never today. */
	private fun parseDate(value: String?): LocalDate? {
		val vDate = value?.substringBefore('T')?.takeIf { it.isNotBlank() } ?: return null
		return runCatching { LocalDate.parse(vDate) }.getOrNull()
	}

	// ==================
	// MARK: Cards
	// ==================

	/**
	 * A product becomes a printing, or `null` when it is not a card.
	 *
	 * Sealed product shares the catalogue with singles and is filtered here rather than upstream,
	 * because what marks a card is per game -- see [TcgCsvMapping.cardFields].
	 */
	fun toPrinting(
		dto: TcgCsvProductDto,
		provider: ProviderId,
		game: GameProfile,
		mapping: TcgCsvMapping,
		set: CardSet?,
	): CardPrinting? {
		val vFields = dto.extendedData
			.mapNotNull { vEntry -> vEntry.value?.takeIf { it.isNotBlank() }?.let { vEntry.name to it } }
			.toMap()
		if (!mapping.isCard(vFields)) return null

		val vImage = dto.imageUrl?.takeIf { it.isNotBlank() } ?: return null
		val vId = cardId(provider, groupId = dto.groupId, productId = dto.productId)
		val vNumber = mapping.number?.let { vFields[it] }.orEmpty()

		return CardPrinting(
			id = vId,
			game = game.id,
			setId = SourceId(provider, dto.groupId.toString()),
			setCode = set?.code ?: dto.groupId.toString(),
			setName = set?.name.orEmpty(),
			// Empty when the source states none, which is the WoW catalogue's position on every one
			// of its cards. The grid then orders those by name, because `collectorSortKey` on an
			// empty string ties and `CollectorNumberComparator` breaks ties by name.
			collectorNumber = vNumber,
			providerRawCollectorNumber = vNumber,
			identity = null,
			text = LocalizedText(
				// TCGplayer's catalogue is the English-language storefront and never says so in a
				// field. `isProviderStated = false` records that this is a reading of the text
				// rather than a claim the source made -- so a Japanese printing existing is UNKNOWN
				// here and never *absent*.
				language = null,
				name = dto.name,
				rules = mapping.rules?.let { vFields[it] },
				flavour = mapping.flavour?.let { vFields[it] },
				isProviderStated = false,
			),
			artwork = artworkFor(vImage, vId, mapping),
			attributes = CardAttributes(
				cost = mapping.cost?.let { vFields[it]?.toIntOrNull() },
				primary = mapping.primary?.let { vFields[it]?.toIntOrNull() },
				secondary = mapping.secondary?.let { vFields[it]?.toIntOrNull() },
			),
			classification = CardClassification(
				type = mapping.cardType?.let { vFields[it] },
				rarity = mapping.rarity?.let { vRarity ->
					vFields[vRarity]?.let { mapping.rarityNames[it] ?: it }
				},
				domains = domainsOf(vFields, game, mapping),
			),
			tags = mapping.tags
				.mapNotNull { vFields[it] }
				.flatMap { it.split(mapping.domainSeparator) }
				.map { it.trim() }
				.filter { it.isNotEmpty() },
			externalIds = mapOf(ExternalIdKey.TCGPLAYER to listOf(dto.productId.toString())),
		)
	}

	/**
	 * The colour-like axis, split where the source joins several with a separator.
	 *
	 * A dual-ink Lorcana card says `Amethyst;Sapphire` and is both of those rather than a seventh
	 * ink, so each half is matched against the game's declared domains and carried separately --
	 * which is what makes filtering by Amethyst match it. A value the game has never heard of is
	 * kept as its own raw text rather than dropped: a source inventing a colour must not vanish
	 * from the filter.
	 */
	private fun domainsOf(
		fields: Map<String, String>,
		game: GameProfile,
		mapping: TcgCsvMapping,
	): List<String> {
		val vRaw = mapping.domain?.let { fields[it] } ?: return emptyList()
		return vRaw.split(mapping.domainSeparator)
			.map { it.trim() }
			.filter { it.isNotEmpty() }
			.map { vValue -> game.domainFor(vValue)?.key ?: vValue }
			.distinct()
	}

	/**
	 * The three image renditions, derived from the one URL the record carries.
	 *
	 * Every product states its `_200w.jpg` and nothing else, but the CDN serves the same product id
	 * at other sizes and the suffix is the whole difference. Measured on 2026-09-09 across three
	 * sampled products in each of the three catalogues, all nine returning 200:
	 *
	 * | Suffix | Lorcana | Cyberpunk | WoW |
	 * | --- | --- | --- | --- |
	 * | `_200w` | 200x280, 14 KB | 200x279, 11 KB | 200x280, 27 KB |
	 * | `_400w` | 400x559, 43 KB | 400x559, 37 KB | 200x280, 16 KB |
	 * | `_in_1000x1000` | 500x699, 61 KB | 716x1000, 91 KB | 200x280, 16 KB |
	 *
	 * `in_` inscribes rather than upscaling, so it yields the original and is genuinely the largest
	 * -- Lorcana's originals are 500 wide and it returns 500, not a blown-up 1000.
	 *
	 * The WoW column is why [TcgCsvMapping.hasLargerArt] exists. Its originals are 200x280 and all
	 * three renditions are those same pixels, `_200w` merely being a worse re-encode at 27 KB
	 * against 16. So that catalogue gets one URL and states no thumbnail, which is the documented
	 * meaning of a CDN that cannot resize -- rather than three names for one file, which would make
	 * the download screen offer a "full art" purchase that buys nothing.
	 */
	private fun artworkFor(imageUrl: String, id: SourceId, mapping: TcgCsvMapping): Artwork {
		val vBase = imageUrl.substringBeforeLast('_')
		return Artwork(
			id = id,
			// `in_1000x1000` for every game, including the one with no larger art: there it is the
			// same pixels as `_200w` at 60% of the bytes, so it is the better URL either way.
			imageUrl = "${vBase}_in_1000x1000.jpg",
			thumbnailUrl = if (mapping.hasLargerArt) "${vBase}_200w.jpg" else null,
			displayUrl = if (mapping.hasLargerArt) "${vBase}_400w.jpg" else null,
			// The catalogue states no artist for any product in any of the three categories.
			artist = null,
			// Not STANDARD: that would assert this is the plain printing. TCGplayer marks variants
			// in the product *name* -- "(Foil)", "(Rare)" -- and states nothing in a field, so what
			// treatment this is has genuinely not been stated.
			treatment = ArtworkTreatment.UNKNOWN,
			language = null,
		)
	}

	/**
	 * A card's id carries its group as well as its product.
	 *
	 * TCGCSV has no per-product endpoint -- products are only ever served a whole group at a time --
	 * so the group is part of a card's address rather than incidental to it. Without it, opening a
	 * card from a cold start (a restored back stack, a deep link) could not be served at all.
	 */
	fun cardId(provider: ProviderId, groupId: Long, productId: Long): SourceId =
		SourceId(provider, "$groupId-$productId")

	/** The group half of an id built by [cardId], or `null` for anything not in that shape. */
	fun groupOf(id: SourceId): Long? = id.local.substringBefore('-').toLongOrNull()
}
