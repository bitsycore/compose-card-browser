package com.bitsycore.cardbrowser.render

import com.bitsycore.cardbrowser.data.cache.CardSearchFilter
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import com.bitsycore.cardbrowser.sqlstore.StoredFacets
import com.bitsycore.cardbrowser.ui.search.SearchContent
import com.bitsycore.cardbrowser.ui.search.SearchContract
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Draws the advanced filter, open and shut.
 *
 * The panel decides what to show from what a game's stored cards actually contain, so what is worth
 * looking at is which controls appear -- and that a game with nothing stored shows none of them
 * rather than a column of empty menus.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests '*SearchFilterRenderer*'
 * ```
 */
class SearchFilterRenderer {

	@Test
	@Ignore("A rendering tool, not a check. Remove the annotation to write the PNGs.")
	fun `writes the search filters to build slash render`() {
		val vOut = File("build/render")
		vOut.mkdirs()
		val vFacets = StoredFacets(
			cardTypes = listOf("Battlefield", "Champion", "Rune", "Spell", "Unit"),
			rarities = listOf("Common", "Epic", "Overnumbered", "Rare", "Uncommon"),
			domains = listOf("body", "calm", "chaos", "fury", "mind", "order"),
			costRange = 0..12,
		)

		renderToPng(vOut, "search-filters-open", 620, 900, density = 1.65f) {
			SearchContent(
				state = SearchContract.UiState(
					game = RiftboundGame,
					query = "annie",
					submitted = "annie",
					facets = vFacets,
					isAdvancedOpen = true,
					filter = CardSearchFilter(excludeText = "fiery", rarity = "Epic", maxCost = 4),
					isProviderSearchable = false,
				),
				dispatch = {},
			)
		}

		renderToPng(vOut, "search-filters-none-stored", 620, 500, density = 1.65f) {
			SearchContent(
				state = SearchContract.UiState(
					game = RiftboundGame,
					// Nothing downloaded, so nothing to offer: the panel should be bare rather
					// than a column of empty menus.
					facets = StoredFacets(emptyList(), emptyList(), emptyList(), null),
					isAdvancedOpen = true,
				),
				dispatch = {},
			)
		}

		assertTrue(vOut.listFiles().orEmpty().any { it.length() > 0 }, "nothing was rendered")
	}
}
