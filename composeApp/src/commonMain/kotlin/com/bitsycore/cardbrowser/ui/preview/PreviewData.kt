package com.bitsycore.cardbrowser.ui.preview

import androidx.compose.runtime.Composable
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
import com.bitsycore.cardbrowser.core.model.SetSymbol
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.games.magic.MagicGame
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import com.bitsycore.cardbrowser.ui.theme.CardBrowserTheme
import kotlinx.datetime.LocalDate

/**
 * Sample data for `@Preview`, shaped like the real thing.
 *
 * Values come from actual Riftcodex responses -- Origins really is `OGN`, 352 cards, published
 * 2025-10-31 -- so a preview shows what the screen looks like with real content rather than with
 * "Lorem ipsum" that happens to fit.
 *
 * Card images will not render in a preview, since previews have no network. That is fine and is
 * arguably the point: what previews are for here is layout, spacing, wrapping and both themes, and
 * the placeholder blocks are exactly what a user sees while the real art loads.
 *
 * In `commonMain` rather than a test source set because previews are compiled with the app.
 */
object PreviewData {

	private val PROVIDER = ProviderId("riftcodex")

	val ORIGINS = CardSet(
		id = SourceId(PROVIDER, "OGN"),
		game = RiftboundGame.id,
		code = "OGN",
		name = "Origins",
		cardCount = 352,
		releaseDate = LocalDate(2025, 10, 31),
		externalIds = mapOf(ExternalIdKey.CARDMARKET_EXPANSION to listOf("6286")),
	)

	val SETS: List<CardSet> = listOf(
		CardSet(SourceId(PROVIDER, "VEN"), RiftboundGame.id, "VEN", "Vendetta", 358, LocalDate(2026, 7, 31)),
		CardSet(SourceId(PROVIDER, "UNL"), RiftboundGame.id, "UNL", "Unleashed", 280, LocalDate(2026, 5, 8)),
		CardSet(SourceId(PROVIDER, "SFD"), RiftboundGame.id, "SFD", "Spiritforged", 288, LocalDate(2026, 2, 13)),
		ORIGINS,
		CardSet(
			id = SourceId(PROVIDER, "OGS"),
			game = RiftboundGame.id,
			code = "OGS",
			name = "Origins: Proving Grounds",
			cardCount = 24,
			releaseDate = LocalDate(2025, 10, 31),
		),
		CardSet(SourceId(PROVIDER, "PR"), RiftboundGame.id, "PR", "Riftbound Promotional Cards", 13, null),
	)

	/**
	 * A set that publishes a real symbol, which Riftbound's provider does not.
	 *
	 * Included so the set-list preview shows both branches of `SetMark` rather than only the
	 * monogram. The URL is unreachable in a preview, which is the point -- the fallback is what
	 * renders, and that is the path worth being able to see.
	 */
	val SET_WITH_SYMBOL: CardSet = CardSet(
		id = SourceId(ProviderId("scryfall"), "blb"),
		game = MagicGame.id,
		code = "BLB",
		name = "Bloomburrow",
		cardCount = 398,
		releaseDate = LocalDate(2024, 8, 2),
		symbol = SetSymbol(url = "https://example.invalid/blb.svg", isMonochrome = true),
	)

	/** One printing. Defaults match what Riftcodex really supplies for a Riftbound card. */
	fun card(
		number: String = "001",
		name: String = "Annie - Fiery",
		rarity: String = "Epic",
		type: String = "Unit",
		domains: List<String> = listOf("Fury"),
		energy: Int? = 5,
		treatment: ArtworkTreatment = ArtworkTreatment.STANDARD,
	): CardPrinting = CardPrinting(
		id = SourceId(PROVIDER, "card-$number"),
		printingKey = "ogn-$number-298",
		game = RiftboundGame.id,
		setId = SourceId(PROVIDER, "OGN"),
		setCode = "OGN",
		setName = "Origins",
		collectorNumber = number,
		providerRawCollectorNumber = number.trimStart('0'),
		identity = null,
		text = LocalizedText(
			language = CardLanguage.ENGLISH,
			name = name,
			rules = "Your spells and abilities deal 1 Bonus Damage. (Each instance of damage the " +
				"spell deals is increased by 1.)",
			flavour = "I never play with matches.",
			isProviderStated = false,
		),
		artwork = Artwork(
			id = SourceId(PROVIDER, "card-$number"),
			// Deliberately unreachable: a preview has no network, and the placeholder is what the
			// layout has to cope with anyway.
			imageUrl = "https://example.invalid/$number.png",
			thumbnailUrl = null,
			displayUrl = null,
			artist = "Polar Engine Studio",
			treatment = treatment,
			language = CardLanguage.ENGLISH,
			accessibilityText = "Riftbound Unit: $name.",
		),
		attributes = CardAttributes(cost = energy, primary = 4, secondary = 1),
		classification = CardClassification(
			type = type,
			supertype = "Champion",
			rarity = rarity,
			domains = domains,
		),
		tags = listOf("Annie", "Noxus"),
		languages = LanguageCoverage.ENGLISH_ONLY,
		finishes = FinishCoverage(),
	)

	/** A page of cards, numbered and varied enough to show the grid doing real work. */
	val CARDS: List<CardPrinting> = listOf(
		card("001", "Annie - Fiery", "Epic"),
		card("002", "Firestorm", "Common", type = "Spell", energy = 3),
		card("003", "Incinerate", "Uncommon", type = "Spell", energy = 2),
		card("004", "Master Yi - Meditative", "Rare", domains = listOf("Calm")),
		card("005", "Zephyr Sage", "Common", energy = 1),
		card("006", "Lux - Illuminated", "Showcase", domains = listOf("Order")),
		card("007", "Garen - Rugged", "Rare", domains = listOf("Order")),
		card("007a", "Garen - Rugged (Alternate Art)", "Rare", treatment = ArtworkTreatment.ALTERNATE_ART),
		card("008", "Gentlemen's Duel", "Common", type = "Spell", energy = 4),
		card("009", "Épée de Fureur", "Uncommon", type = "Gear", energy = null),
	)
}

/**
 * Wraps preview content in the app's theme.
 *
 * Every preview goes through this, so none of them can accidentally render against Material's
 * defaults and look fine while the real screen does not.
 */
@Composable
fun PreviewFrame(isDark: Boolean = true, content: @Composable () -> Unit) {
	CardBrowserTheme(useDarkTheme = isDark, content = content)
}
