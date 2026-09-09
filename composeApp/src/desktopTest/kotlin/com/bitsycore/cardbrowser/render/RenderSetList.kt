package com.bitsycore.cardbrowser.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.games.pokemon.PokemonGame
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import com.bitsycore.cardbrowser.ui.sets.SetListContent
import com.bitsycore.cardbrowser.ui.sets.SetListContract
import com.bitsycore.cardbrowser.ui.theme.CardBrowserTheme
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
	@Ignore("A rendering tool, not a check. Remove the annotation to write the PNGs.")
	fun `writes the set list to build slash render`() {
		val vOut = File("build/render")
		vOut.mkdirs()

		render(vOut, "sets-all-lines") { SetListContent(pokemonState(), {}, onOpenSet = {}, onOpenSettings = {}) }
		render(vOut, "sets-one-line") {
			SetListContent(pokemonState().copy(region = "jp"), {}, onOpenSet = {}, onOpenSettings = {})
		}
		render(vOut, "sets-single-line-game", isDark = false) {
			SetListContent(riftboundState(), {}, onOpenSet = {}, onOpenSettings = {})
		}

		assertTrue(vOut.listFiles().orEmpty().any { it.length() > 0 }, "nothing was rendered")
		println("Wrote ${vOut.absolutePath}")
	}
}

@OptIn(ExperimentalComposeUiApi::class)
private fun render(
	directory: File,
	name: String,
	isDark: Boolean = true,
	content: @Composable () -> Unit,
) {
	val vScene = ImageComposeScene(width = 660, height = 1100, density = Density(1.65f)) {
		CardBrowserTheme(useDarkTheme = isDark) {
			Surface(modifier = Modifier.fillMaxSize()) {
				Box(Modifier.fillMaxSize()) { content() }
			}
		}
	}
	try {
		val vImage = vScene.render()
		File(directory, "$name.png").writeBytes(
			vImage.encodeToData()?.bytes ?: error("could not encode $name"),
		)
	} finally {
		vScene.close()
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
