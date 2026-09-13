package com.bitsycore.cardbrowser.render

import com.bitsycore.cardbrowser.core.filter.CardFacets
import com.bitsycore.cardbrowser.core.provider.CardFilterField
import com.bitsycore.cardbrowser.games.pokemon.PokemonGame
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import com.bitsycore.cardbrowser.ui.screen.cards.CardGridContract
import com.bitsycore.cardbrowser.ui.screen.cards.FilterSheet
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * The filter sheet either side of the chips-or-menu threshold.
 *
 * An axis is chips while it fits in a glance and a menu once it does not, and the only way to judge
 * where "does not" falls is to look at both. Riftbound's five rarities are the first; Pokemon's
 * nineteen -- measured across thirteen sets from Base Set to Surging Sparks -- are the second.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests '*FilterSheetRenderer*'
 * ```
 */
class FilterSheetRenderer {

	@Test
	@Ignore("A rendering tool, not a check. Remove the annotation to write the PNGs.")
	fun `writes the filter sheet either side of the threshold`() {
		val vOut = File("build/render")
		vOut.mkdirs()

		renderToPng(vOut, "filters-chips", width = 660, height = 900, density = 1.65f) {
			FilterSheet(
				state = CardGridContract.UiState(
					game = RiftboundGame,
					supportedFilters = SUPPORTED,
					facets = CardFacets(
						domains = listOf("fury", "calm", "mind", "body", "chaos", "order"),
						cardTypes = listOf("Unit", "Spell", "Gear", "Rune", "Legend"),
						rarities = listOf("Common", "Uncommon", "Rare", "Epic", "Showcase"),
					),
				),
				onQueryChanged = {},
				onSetsChanged = {},
				onClearAll = {},
			)
		}

		renderToPng(vOut, "filters-menus", width = 660, height = 900, density = 1.65f) {
			FilterSheet(
				state = CardGridContract.UiState(
					game = PokemonGame,
					supportedFilters = SUPPORTED,
					facets = CardFacets(
						domains = POKEMON_TYPES,
						cardTypes = listOf("Pokemon", "Trainer", "Energy"),
						rarities = POKEMON_RARITIES,
					),
				),
				onQueryChanged = {},
				onSetsChanged = {},
				onClearAll = {},
			)
		}
	}

	private companion object {

		val SUPPORTED = setOf(
			CardFilterField.DOMAIN,
			CardFilterField.CARD_TYPE,
			CardFilterField.RARITY,
		)

		/** Ten, so this one stays chips: the threshold is the point of the pair. */
		val POKEMON_TYPES = listOf(
			"grass", "fire", "water", "lightning", "psychic",
			"fighting", "darkness", "metal", "dragon", "colorless",
		)

		/** Nineteen, measured from TCGdex across thirteen sets on 2026-09-13. */
		val POKEMON_RARITIES = listOf(
			"Common", "Uncommon", "Rare", "Holo Rare", "Rare Holo", "Rare Holo LV.X",
			"Rare PRIME", "LEGEND", "Radiant Rare", "Double rare", "Holo Rare V",
			"Holo Rare VSTAR", "Holo Rare VMAX", "ACE SPEC Rare", "Ultra Rare",
			"Illustration rare", "Special illustration rare", "Hyper rare", "Secret Rare",
		)
	}
}
