package com.bitsycore.cardbrowser.providers.wuwa

import com.bitsycore.cardbrowser.core.model.Artwork
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardAttributes
import com.bitsycore.cardbrowser.core.model.CardClassification
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.FinishCoverage
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.model.LanguageCoverage
import com.bitsycore.cardbrowser.core.model.LocalizedText
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId

/** Turns the UCP card API's wire format into core's model. */
internal object WuwaMapper {

	/**
	 * The set a card belongs to, taken from the part of its code before the hyphen.
	 *
	 * `SD01-001` is card 001 of `SD01`. This is derivation from the data rather than a guess: the
	 * API has no set entity at all and no endpoint that lists one, and the prefix is the only thing
	 * that partitions the 123 cards into the three groups the game actually ships.
	 */
	fun setCodeOf(code: String): String? =
		code.substringBefore('-', missingDelimiterValue = "").trim().ifBlank { null }

	/** The number within the set. `SD01-001` is `001`. */
	fun collectorNumberOf(code: String): String =
		code.substringAfter('-', missingDelimiterValue = code).trim().ifBlank { code }

	/**
	 * A set built from a code and how many cards carry it.
	 *
	 * The name is the code, deliberately. `/web/goods/list` does publish product names
	 * ("スターターデッキ01", "ブースターパック01") that look like they line up with `SD01` and
	 * `BP01`, and the API has a `goods_id` filter that would confirm it -- but that filter returns
	 * zero cards for every product id, so the link cannot be verified. Rather than assume `SD` is
	 * スターターデッキ, the code is shown as itself.
	 */
	fun toSet(code: String, cardCount: Int, provider: ProviderId): CardSet = CardSet(
		id = SourceId(provider, code),
		game = Game.WUTHERING_WAVES,
		code = code,
		name = code,
		cardCount = cardCount,
		// No date anywhere in the card API.
		releaseDate = null,
	)

	// ============
	//  Cards

	/**
	 * Maps a list entry.
	 *
	 * The list carries six fields, so rarity, attribute and cost are simply absent here rather than
	 * defaulted. The detail endpoint fills them in when a card is opened.
	 */
	fun toPrinting(
		dto: WuwaCardBriefDto,
		provider: ProviderId,
		set: CardSet,
		language: CardLanguage,
	): CardPrinting? {
		if (dto.code.isBlank()) return null
		return CardPrinting(
			// The numeric `id`, not the printed code.
			//
			// The code is *not* unique: 36 of the 87 codes in the catalogue are carried by two
			// records each, with different images -- `SD01-003` is two artworks of 秧秧. They are
			// two tiles in this app's model, and keying on the code collapsed them into one, which
			// LazyGrid rejects outright with "Key ... was already used".
			//
			// The trade-off is that this id is locale-scoped -- `SD01-001` is 651 under `ja-jp`
			// and 1357 under `zh-cn`. That costs nothing here because the adapter only ever asks
			// for `ja-jp`, and it is what `/web/card/info` takes, so a card can be fetched by id
			// without scanning the catalogue for it.
			id = SourceId(provider, dto.id.toString()),
			printingKey = null,
			game = Game.WUTHERING_WAVES,
			setId = set.id,
			setCode = set.code,
			setName = set.name,
			collectorNumber = collectorNumberOf(dto.code),
			providerRawCollectorNumber = dto.code,
			identity = null,
			text = LocalizedText(language = language, name = dto.name, isProviderStated = true),
			artwork = artworkOf(dto.id.toString(), dto.img, provider, language, dto.name),
			classification = CardClassification(type = dto.cardType?.ifBlank { null }),
			languages = LanguageCoverage(confirmed = setOf(language)),
			finishes = FinishCoverage(),
		)
	}

	/** Maps a detail record, which carries everything the list does not. */
	fun toPrinting(
		dto: WuwaCardDetailDto,
		provider: ProviderId,
		set: CardSet?,
		language: CardLanguage,
	): CardPrinting? {
		if (dto.code.isBlank()) return null
		val vSetCode = set?.code ?: dto.obtain?.ifBlank { null } ?: setCodeOf(dto.code) ?: return null
		val vSetId = set?.id ?: SourceId(provider, vSetCode)
		return CardPrinting(
			id = SourceId(provider, dto.id.toString()),
			printingKey = null,
			game = Game.WUTHERING_WAVES,
			setId = vSetId,
			setCode = vSetCode,
			setName = set?.name ?: vSetCode,
			collectorNumber = collectorNumberOf(dto.code),
			providerRawCollectorNumber = dto.code,
			identity = null,
			text = LocalizedText(
				language = language,
				name = dto.name,
				rules = dto.info?.ifBlank { null },
				flavour = null,
				isProviderStated = true,
			),
			artwork = artworkOf(dto.id.toString(), dto.img, provider, language, dto.name),
			attributes = CardAttributes(
				// Cost. Arrives as a string and is empty on character cards, which have none.
				energy = dto.fee?.trim()?.toIntOrNull(),
				might = dto.damage?.trim()?.toIntOrNull(),
				power = dto.speed?.trim()?.toIntOrNull(),
			),
			classification = CardClassification(
				type = dto.typeName?.ifBlank { null } ?: dto.cardType?.ifBlank { null },
				supertype = dto.weaponTypeName?.dashToNull(),
				rarity = dto.rarityName?.dashToNull(),
				// Attribute is the game's element axis: 気動, 焦熱, 電導 and so on.
				domains = listOfNotNull(dto.attrName?.dashToNull()),
			),
			tags = buildList {
				dto.forceName?.dashToNull()?.let(::add)
				dto.featureName?.dashToNull()?.let(::add)
				dto.characterName?.dashToNull()?.let(::add)
				dto.colorName?.dashToNull()?.let(::add)
				// Which products actually contain this card, which is not the same as the product
				// its number was assigned under -- `SD02-003` states `収録：BP01`. The field is a
				// list: a card reprinted across products arrives as "SD01、BP01", separated by an
				// ideographic comma. Both are shown, because both are true.
				addAll(productsOf(dto.obtain))
			},
			languages = LanguageCoverage(confirmed = setOf(language)),
			finishes = FinishCoverage(),
		)
	}

	/**
	 * The art for one record.
	 *
	 * Both records sharing a code are left [ArtworkTreatment.STANDARD]. The API states no variant
	 * field at all, so while two differing images under one code are evidence that a variant
	 * relationship exists, nothing says *which* of the two is the variant -- and marking both as
	 * "other treatment" would be wrong for at least one of them.
	 */
	private fun artworkOf(
		recordId: String,
		img: String?,
		provider: ProviderId,
		language: CardLanguage,
		name: String,
	): Artwork = Artwork(
		id = SourceId(provider, recordId),
		imageUrl = img.orEmpty(),
		// Already WebP and already compressed -- the CDN path says `compressed` -- and there is no
		// second size, so the grid and the detail screen load the same file.
		thumbnailUrl = null,
		displayUrl = img,
		artist = null,
		treatment = ArtworkTreatment.STANDARD,
		language = language,
		accessibilityText = "Wuthering Waves TCG card: $name.",
	)

	/**
	 * The products a card is found in, from `obtain`.
	 *
	 * Separated by an ideographic comma, not an ASCII one. Nine of the 123 cards carry a value that
	 * disagrees with their code prefix, so this is genuinely extra information rather than a
	 * restatement of the number: six BP01-numbered cards also ship in a starter deck, and three
	 * cards numbered under `SD01`/`SD02` are only found in `BP01`.
	 */
	private fun productsOf(obtain: String?): List<String> =
		obtain?.split('、', ',')
			.orEmpty()
			.mapNotNull { it.trim().ifBlank { null } }

	/**
	 * Treats the API's `"-"` placeholder as absent.
	 *
	 * The endpoint writes a literal hyphen where a field does not apply -- `force_name: "-"` on a
	 * card with no faction -- and showing that as a value would put a chip reading "-" in the
	 * filter sheet.
	 */
	private fun String.dashToNull(): String? = trim().takeIf { it.isNotEmpty() && it != "-" }
}
