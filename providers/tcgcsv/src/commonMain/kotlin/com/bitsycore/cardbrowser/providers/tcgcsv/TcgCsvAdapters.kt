package com.bitsycore.cardbrowser.providers.tcgcsv

import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.provider.Attribution
import com.bitsycore.cardbrowser.core.provider.CardFilterField
import com.bitsycore.cardbrowser.core.provider.CardSortField
import com.bitsycore.cardbrowser.core.provider.DataCapabilities
import com.bitsycore.cardbrowser.core.provider.FilterSupport
import com.bitsycore.cardbrowser.core.provider.ProviderCapabilities
import com.bitsycore.cardbrowser.games.cyberpunk.CyberpunkGame
import com.bitsycore.cardbrowser.games.lorcana.LorcanaGame
import com.bitsycore.cardbrowser.games.wowtcg.WowTcgGame
import io.ktor.client.HttpClient

// ==================
// MARK: Shared capabilities
// ==================

/**
 * What every TCGCSV adapter can and cannot do, minus the fields a given game happens to carry.
 *
 * Factored out because the answers come from the *service*, not from the game: there is no search
 * endpoint whichever category you ask, and no category states a language. What differs per game is
 * which filters have any data behind them, which is why [filters] is a parameter.
 *
 * @param filters the filter fields this game's records actually populate. Everything is `localOnly`
 *   -- a set arrives whole in one request, so every filter is applied to data already in hand
 */
private fun tcgCsvCapabilities(
	filters: Set<CardFilterField>,
	sorting: Set<CardSortField>,
	attribution: String,
): ProviderCapabilities = ProviderCapabilities(
	filtering = FilterSupport(remote = emptySet(), localOnly = filters),
	sorting = sorting,
	data = DataCapabilities(
		// Empty, not `setOf(ENGLISH)`. TCGplayer's catalogue is plainly the English storefront and
		// nowhere states a language, and this field is what the provider can *describe* rather than
		// what a reader can infer. So a Japanese printing existing is UNKNOWN here, never absent.
		languages = emptySet(),
		localizedText = false,
		localizedImages = false,
		cardIdentity = false,
		artworkVariants = false,
		finishes = false,
		// The catalogue *is* TCGplayer's, not Cardmarket's. A TCGplayer product id is kept on every
		// printing for provenance and no Cardmarket link is built from it -- they are different
		// marketplaces with different product ids, and treating one as the other is exactly the
		// kind of inferred link this app does not make.
		cardmarketProductMapping = false,
		// No query endpoint of any kind exists. A cross-set search therefore covers only the sets
		// already downloaded, and the app labels it as such with a count.
		crossSetSearch = false,
	),
	attribution = Attribution(text = attribution, url = "https://tcgcsv.com/"),
	maxPageSize = TcgCsvProvider.MAX_PAGE_SIZE,
)

// ==================
// MARK: Lorcana
// ==================

/**
 * Disney Lorcana, from TCGplayer category 71.
 *
 * ## Coverage, verified against the live service on 2026-09-09
 *
 * - **Sets**: 20 groups, every one carrying a release date, and most an abbreviation that is the
 *   set number (`13`, `Q3`).
 * - **Cards**: 3654 products across those groups, of which 3309 are cards and the rest sealed.
 *   Product ids are unique across the whole catalogue -- checked, because a repeated key crashes
 *   the grid outright rather than degrading.
 * - **Data**: the richest of the three. Ink, cost, Strength, Willpower, type, classification, rules
 *   text and flavour text. `Lore Value` and `Move Cost` are real and unmapped -- see `LorcanaGame`.
 * - **Rarities**: 11 distinct, of which six form the ladder the game has always used. See
 *   `LorcanaGame.rarityLadder` for why Epic and Iconic are left unplaced.
 * - **Images**: three renditions, 200x280 to 500x699. See `TcgCsvMapper.artworkFor`.
 * - **Languages**: none stated. Lorcana is published in several and this catalogue is the English
 *   storefront, so the others are unknown here rather than absent.
 *
 * A better source exists and was rejected: Lorcast (https://api.lorcast.com) is a proper
 * Scryfall-shaped API with a real search endpoint, collector numbers and a stated `lang`. It serves
 * its images as **AVIF only**, with no JPEG or WebP fallback, which Coil decodes on Android 12+ but
 * not through Skia on desktop and iOS -- so three of the four targets would show a card grid of
 * blank tiles. It is worth revisiting if that changes, and would be a second adapter here rather
 * than a change to this one.
 */
class LorcanaTcgCsvProvider(
	mClient: HttpClient,
	mBaseUrl: String = DEFAULT_BASE_URL,
) : TcgCsvProvider<LorcanaGame>(mClient, mBaseUrl) {

	override val id: ProviderId = PROVIDER_ID

	override val game: LorcanaGame = LorcanaGame

	internal override val mCategoryId: Int = 71

	internal override val mMapping: TcgCsvMapping = TcgCsvMapping.LORCANA

	override val capabilities: ProviderCapabilities = tcgCsvCapabilities(
		filters = setOf(
			CardFilterField.TEXT,
			CardFilterField.DOMAIN,
			CardFilterField.CARD_TYPE,
			CardFilterField.RARITY,
			CardFilterField.COST,
		),
		sorting = setOf(
			CardSortField.COLLECTOR_NUMBER,
			CardSortField.NAME,
			CardSortField.RARITY,
			CardSortField.COST,
		),
		attribution = "Lorcana card data from TCGCSV, a public mirror of TCGplayer's catalogue. " +
			"Not affiliated with Ravensburger or Disney.",
	)

	companion object {

		/** Never changed: it is written into every id and every cache file this adapter produces. */
		val PROVIDER_ID: ProviderId = ProviderId("tcgcsv-lorcana")
	}
}

// ==================
// MARK: Cyberpunk
// ==================

/**
 * Cyberpunk TCG, from TCGplayer category 92.
 *
 * ## Coverage, verified against the live service on 2026-09-09
 *
 * - **Sets**: nine groups, all dated 2026-11-06 -- the game is unreleased and these are pre-order
 *   listings. Four are Beta printings of the same four products, so the same card appears twice
 *   across the catalogue with two product ids and one collector number. That is real and not a
 *   fault: 408 cards carry 314 distinct numbers. Ids stay unique because they are product ids.
 * - **Cards**: 422 products, 408 of them cards.
 * - **Data**: colour, cost, Power, type, tags and rules text. `RAM` and `Eddies` are unmapped --
 *   see `CyberpunkGame`. Rarity is on 267 of the 408; the rest state none, which is left null.
 * - **Images**: three renditions, up to 716x1000.
 *
 * This game was previously absent from the app on the stated grounds that no data source existed,
 * which was true when it was checked and stopped being true when TCGplayer opened the category. The
 * set list will grow as the release approaches, and a pre-order catalogue is a real thing a source
 * published rather than a placeholder -- nothing here is invented.
 */
class CyberpunkTcgCsvProvider(
	mClient: HttpClient,
	mBaseUrl: String = DEFAULT_BASE_URL,
) : TcgCsvProvider<CyberpunkGame>(mClient, mBaseUrl) {

	override val id: ProviderId = PROVIDER_ID

	override val game: CyberpunkGame = CyberpunkGame

	internal override val mCategoryId: Int = 92

	internal override val mMapping: TcgCsvMapping = TcgCsvMapping.CYBERPUNK

	override val capabilities: ProviderCapabilities = tcgCsvCapabilities(
		filters = setOf(
			CardFilterField.TEXT,
			CardFilterField.DOMAIN,
			CardFilterField.CARD_TYPE,
			CardFilterField.RARITY,
			CardFilterField.COST,
		),
		sorting = setOf(
			CardSortField.COLLECTOR_NUMBER,
			CardSortField.NAME,
			CardSortField.RARITY,
			CardSortField.COST,
		),
		attribution = "Cyberpunk TCG card data from TCGCSV, a public mirror of TCGplayer's " +
			"catalogue. Not affiliated with CD Projekt.",
	)

	companion object {

		/** Never changed: it is written into every id and every cache file this adapter produces. */
		val PROVIDER_ID: ProviderId = ProviderId("tcgcsv-cyberpunk")
	}
}

// ==================
// MARK: World of Warcraft TCG
// ==================

/**
 * World of Warcraft TCG, from TCGplayer category 13.
 *
 * ## Coverage, verified against the live service on 2026-09-09
 *
 * This is the thinnest source in the app, and the reason is worth stating plainly: the game was
 * discontinued in 2013 and no publisher database outlived it. A marketplace catalogue is what is
 * left, and a marketplace only records what it needs to sell a card.
 *
 * - **Sets**: 54 groups, each with a release date. Many carry no abbreviation, so their code falls
 *   back to the TCGplayer group id.
 * - **Cards**: measured over 1549 products in 12 sampled groups.
 * - **Data**: a name, a rarity and a picture. That is the whole of it. There is **no collector
 *   number, no card text, no cost, no type, no class and no faction** anywhere in the category --
 *   the only game field any WoW product carries is `Rarity`, as a single letter, which this adapter
 *   expands into the words the ladder uses.
 * - **Images**: one size, 200x280. The CDN's other renditions are the same pixels, so this adapter
 *   states no thumbnail and no display variant rather than pointing three names at one file. A
 *   card's art is therefore small on the detail screen, and that is the source's ceiling.
 *
 * The consequence for the UI is that a WoW card's detail screen is mostly empty, and its filters
 * are rarity and text only. That is an honest picture of what is knowable rather than a broken
 * screen -- but if a fuller source is ever wanted, wowcards.info and the `wowtcg-decktools` dataset
 * both hold real card text and would be a second adapter, not a change to this one.
 */
class WowTcgCsvProvider(
	mClient: HttpClient,
	mBaseUrl: String = DEFAULT_BASE_URL,
) : TcgCsvProvider<WowTcgGame>(mClient, mBaseUrl) {

	override val id: ProviderId = PROVIDER_ID

	override val game: WowTcgGame = WowTcgGame

	internal override val mCategoryId: Int = 13

	internal override val mMapping: TcgCsvMapping = TcgCsvMapping.WOW_TCG

	override val capabilities: ProviderCapabilities = tcgCsvCapabilities(
		// Only the two the data supports. Offering a cost or type filter over a catalogue that
		// states neither would be a menu of chips that match nothing.
		filters = setOf(
			CardFilterField.TEXT,
			CardFilterField.RARITY,
		),
		// No collector number to sort by, so name is the default order rather than a fallback.
		sorting = setOf(
			CardSortField.NAME,
			CardSortField.RARITY,
		),
		attribution = "World of Warcraft TCG card data from TCGCSV, a public mirror of " +
			"TCGplayer's catalogue. Not affiliated with Blizzard Entertainment.",
	)

	companion object {

		/** Never changed: it is written into every id and every cache file this adapter produces. */
		val PROVIDER_ID: ProviderId = ProviderId("tcgcsv-wowtcg")
	}
}
