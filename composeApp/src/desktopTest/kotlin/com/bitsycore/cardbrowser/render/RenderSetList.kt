package com.bitsycore.cardbrowser.render

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.games.pokemon.PokemonGame
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import com.bitsycore.cardbrowser.data.settings.ImageDownloadRecord
import com.bitsycore.cardbrowser.ui.sets.SetImageStatus
import com.bitsycore.cardbrowser.ui.sets.SetListContent
import com.bitsycore.cardbrowser.ui.sets.SetListContract
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the set list off-screen, to look at the product-line chips and the region badges.
 *
 * Same idea and same caveats as [DetailRenderer]: headless, no network, so set logos do not load
 * and what the files show is the layout and the type. `@Ignore` for the same reason -- it is a tool
 * whose output is looked at, not a check that can fail meaningfully.
 *
 * Run it with:
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests '*SetListRenderer*'
 * ```
 */
class SetListRenderer {

	@Test
	fun `writes the set list to build slash render`() {
		val vOut = File("build/render")
		vOut.mkdirs()

		renderToPng(vOut, "sets-all-lines", width = 660, height = 1100, density = 1.65f) { SetListContent(pokemonState(), {}) }

		// Browsing and arranging, side by side. Browsing should show a star only on a favourite and
		// no handles; arranging should show a star on every row, a handle on each favourite, and no
		// download button.
		listOf(false to "sets-browsing", true to "sets-arranging").forEach { (vEditing, vName) ->
			renderToPng(vOut, vName, width = 660, height = 700, density = 1.65f) {
				SetListContent(
					pokemonState().copy(
						isEditing = vEditing,
						favouriteIds = listOf(
							SourceId(TCGDEX, "sv08").qualified,
							SourceId(TCGDEX, "base1").qualified,
						),
					),
					{},
				)
			}
		}

		// A row with nothing left to fetch, beside one that has been opened but not finished. The
		// first should have no download button and the second should still have one.
		renderToPng(vOut, "sets-download-offer", width = 660, height = 760, density = 1.65f) {
			SetListContent(
				pokemonState().copy(
					savedSetIds = setOf(
						SourceId(TCGDEX, "base1").qualified,
						SourceId(TCGDEX, "sv08").qualified,
					),
					completeSetIds = setOf(SourceId(TCGDEX, "base1").qualified),
					imageDownloads = mapOf(
						SourceId(TCGDEX, "base1").qualified to SetImageStatus(
							thumbnails = ImageDownloadRecord(fetched = 102, total = 102),
						),
					),
				),
				{},
			)
		}
		// The bar has to say which language every row below it will open in.
		renderToPng(vOut, "sets-browsing-language", width = 660, height = 420, density = 1.65f) {
			SetListContent(
				pokemonState().copy(
					browsingLanguage = CardLanguage.FRENCH,
					browsingLanguageOptions = setOf(
						CardLanguage.ENGLISH,
						CardLanguage.FRENCH,
						CardLanguage.JAPANESE,
					),
				),
				{},
			)
		}
		renderToPng(vOut, "sets-one-line", width = 660, height = 1100, density = 1.65f) {
			SetListContent(pokemonState().copy(region = "jp"), {})
		}
		renderToPng(vOut, "sets-single-line-game", width = 660, height = 1100, density = 1.65f, isDark = false) {
			SetListContent(riftboundState(), {})
		}

		// Short enough that the footer is on screen: the tally and the option that moves it.
		renderToPng(vOut, "sets-footer", width = 660, height = 760, density = 1.65f) {
			SetListContent(
				pokemonState().copy(sets = pokemonState().sets.filter { it.code in setOf("sv08", "base2") }),
				{},
			)
		}

		assertTrue(vOut.listFiles().orEmpty().any { it.length() > 0 }, "nothing was rendered")
		println("Wrote ${vOut.absolutePath}")
	}
}


// ==================
// MARK: States
// ==================

private val TCGDEX = ProviderId("tcgdex")

private fun set(
	local: String,
	name: String,
	region: String,
	cards: Int,
	date: String?,
	languages: Set<CardLanguage>,
) = CardSet(
	id = SourceId(TCGDEX, local),
	game = PokemonGame.id,
	code = local,
	name = name,
	cardCount = cards,
	releaseDate = date?.let(LocalDate::parse),
	region = region,
	languages = languages,
)

/** Real records, so the badges and the mixed scripts are shown as they will really appear. */
private fun pokemonState() = SetListContract.UiState(
	sets = listOf(
		set(
			"sv08", "Surging Sparks", PokemonGame.REGION_INTERNATIONAL, 252, "2024-11-08",
			setOf(CardLanguage.ENGLISH, CardLanguage.FRENCH, CardLanguage.GERMAN),
		),
		set(
			"SV1a", "トリプレットビート", PokemonGame.REGION_JAPAN, 103, null,
			setOf(CardLanguage.JAPANESE, CardLanguage.TRADITIONAL_CHINESE),
		),
		set(
			"base1", "Base Set", PokemonGame.REGION_INTERNATIONAL, 102, "1999-01-09",
			setOf(CardLanguage.ENGLISH, CardLanguage.FRENCH, CardLanguage.ITALIAN),
		),
		set("SC1D", "劍&盾", PokemonGame.REGION_TAIWAN, 164, null, setOf(CardLanguage.TRADITIONAL_CHINESE)),
		set("CBB2C", "宝石包Vol.2", PokemonGame.REGION_CHINA, 15, null, setOf(CardLanguage.SIMPLIFIED_CHINESE)),
		// Listed by its catalogue and carrying none of its cards, which is the case the option is
		// for. TCGdex has many.
		set("base2", "Jungle", PokemonGame.REGION_INTERNATIONAL, 0, null, setOf(CardLanguage.ENGLISH)),
		set(
			"SM10", "ダブルブレイズ", PokemonGame.REGION_JAPAN, 116, null,
			setOf(CardLanguage.JAPANESE),
		),
		set(
			"sm10", "Unbroken Bonds", PokemonGame.REGION_INTERNATIONAL, 234, "2019-05-03",
			setOf(CardLanguage.ENGLISH, CardLanguage.FRENCH),
		),
	),
	game = PokemonGame,
	availableGames = listOf(PokemonGame, RiftboundGame),
	isLoading = false,
	savedSetIds = setOf(SourceId(TCGDEX, "base1").qualified),
	lastOpenedSetId = SourceId(TCGDEX, "sv08").qualified,
)

/** A game with one product line, which must get no chips at all. */
private fun riftboundState() = SetListContract.UiState(
	sets = listOf(
		CardSet(
			id = SourceId(ProviderId("riftcodex"), "OGN"),
			game = RiftboundGame.id,
			code = "OGN",
			name = "Origins",
			cardCount = 298,
			releaseDate = LocalDate.parse("2025-10-03"),
		),
		CardSet(
			id = SourceId(ProviderId("riftcodex"), "VEN"),
			game = RiftboundGame.id,
			code = "VEN",
			name = "Vendetta",
			cardCount = 358,
			releaseDate = LocalDate.parse("2026-02-27"),
		),
	),
	game = RiftboundGame,
	availableGames = listOf(PokemonGame, RiftboundGame),
	isLoading = false,
)
