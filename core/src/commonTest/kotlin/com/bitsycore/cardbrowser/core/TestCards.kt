package com.bitsycore.cardbrowser.core

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
import kotlinx.datetime.LocalDate

/**
 * Printings and sets for tests, shaped like the real Riftcodex data.
 *
 * Values are taken from actual API responses -- Origins is `OGN`, 352 cards, published 2025-10-31,
 * Cardmarket expansion 6286 -- so a test that passes here is testing against something the provider
 * really returns rather than against a convenient invention.
 */
object TestCards {

	val PROVIDER = ProviderId("riftcodex")

	/** Origins, exactly as `/sets` reports it. */
	val ORIGINS = CardSet(
		id = SourceId(PROVIDER, "69bc5bf6e195be3e561d1eb1"),
		game = TestGame.id,
		code = "OGN",
		name = "Origins",
		cardCount = 352,
		releaseDate = LocalDate(2025, 10, 31),
		externalIds = mapOf(
			ExternalIdKey.CARDMARKET_EXPANSION to listOf("6286"),
			ExternalIdKey.TCGPLAYER to listOf("24344"),
		),
	)

	/** A set whose name is not one word, so no Cardmarket slug can be derived without guessing. */
	val PROVING_GROUNDS = CardSet(
		id = SourceId(PROVIDER, "69bc5bf6e195be3e561d1eb2"),
		game = TestGame.id,
		code = "OGS",
		name = "Origins: Proving Grounds",
		cardCount = 24,
		releaseDate = LocalDate(2025, 10, 31),
		externalIds = mapOf(ExternalIdKey.CARDMARKET_EXPANSION to listOf("6289")),
	)

	/**
	 * A printing, with everything defaulted to the common Riftbound case.
	 *
	 * Defaults match what Riftcodex really supplies: English confirmed, finishes unstated, no card
	 * identity.
	 */
	fun printing(
		id: String = "card-1",
		name: String = "Annie - Fiery",
		collectorNumber: String = "1",
		rarity: String? = "Epic",
		type: String? = "Unit",
		domains: List<String> = listOf("Fury"),
		energy: Int? = 5,
		might: Int? = 4,
		power: Int? = 1,
		treatment: ArtworkTreatment = ArtworkTreatment.STANDARD,
		languages: LanguageCoverage = LanguageCoverage.ENGLISH_ONLY,
		finishes: FinishCoverage = FinishCoverage(),
		externalIds: Map<String, List<String>> = emptyMap(),
		imageUrl: String = "https://cmsassets.rgpub.io/sanity/images/dsfx7636/game_data_live/abc-744x1039.png?accountingTag=RB",
	): CardPrinting = CardPrinting(
		id = SourceId(PROVIDER, id),
		game = TestGame.id,
		setId = SourceId(PROVIDER, "OGN"),
		setCode = "OGN",
		setName = "Origins",
		collectorNumber = collectorNumber,
		providerRawCollectorNumber = collectorNumber.filter { it.isDigit() },
		identity = null,
		text = LocalizedText(
			language = CardLanguage.ENGLISH,
			name = name,
			rules = "Your spells and abilities deal 1 Bonus Damage.",
			flavour = "I never play with matches.",
			isProviderStated = false,
		),
		artwork = Artwork(
			id = SourceId(PROVIDER, id),
			imageUrl = imageUrl,
			thumbnailUrl = "$imageUrl&w=320&fm=webp",
			displayUrl = "$imageUrl&w=744&fm=webp&q=90",
			artist = "Polar Engine Studio",
			treatment = treatment,
			language = CardLanguage.ENGLISH,
			accessibilityText = "Riftbound Unit: $name.",
		),
		attributes = CardAttributes(cost = energy, primary = might, secondary = power),
		classification = CardClassification(
			type = type,
			supertype = "Champion",
			rarity = rarity,
			domains = domains,
		),
		tags = listOf("Annie", "Noxus"),
		languages = languages,
		finishes = finishes,
		externalIds = externalIds,
	)
}
