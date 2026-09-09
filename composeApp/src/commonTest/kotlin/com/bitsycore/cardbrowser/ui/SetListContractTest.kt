package com.bitsycore.cardbrowser.ui

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameRegion
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.ui.sets.SetListContract
import com.bitsycore.cardbrowser.ui.sets.SetListContract.Intent
import com.bitsycore.cardbrowser.ui.sets.SetListContract.UiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The set list's product-line filter.
 *
 * The behaviour under test exists because a game can ship several catalogues that are not
 * translations of each other. Pokémon's Japanese and international lines share 4 set ids out of
 * 486, and the app used to show whichever line the user's *language preference* happened to select
 * -- so two thirds of the game was unreachable without changing an unrelated setting.
 */
class SetListContractTest {

	private object TwoLineGame : GameProfile {
		override val id: GameId = GameId("two-line")
		override val displayName: String = "Two Line Game"
		override val vocabulary: GameVocabulary = GameVocabulary()
		override val regions: List<GameRegion> = listOf(
			GameRegion("intl", "International", "INTL"),
			GameRegion("jp", "Japan", "JP"),
			GameRegion("cn", "China", "CN"),
		)
	}

	private fun set(local: String, region: String?, name: String = local) = CardSet(
		id = SourceId(ProviderId("test"), local),
		game = TwoLineGame.id,
		code = local,
		name = name,
		cardCount = 10,
		releaseDate = null,
		region = region,
	)

	private fun loaded(vararg sets: CardSet): UiState {
		val vState = SetListContract.reduce(
			UiState(),
			Intent.GamesRestored(listOf(TwoLineGame), TwoLineGame),
		)
		return SetListContract.reduce(
			vState,
			Intent.Loaded(
				generation = vState.requestGeneration,
				sets = sets.toList(),
				origin = com.bitsycore.cardbrowser.data.repository.DataOrigin.NETWORK,
				isStale = false,
				error = null,
				isFinal = true,
			),
		)
	}

	// ============
	//  Chips

	@Test
	fun `every line is visible until one is chosen`() {
		val vState = loaded(
			set("base1", "intl"),
			set("sv1a", "jp"),
			set("SC1D", "cn"),
		)

		// The point of the whole change: the lines are in one list rather than one at a time.
		assertEquals(3, vState.visibleSets.size)
		assertEquals(listOf("International", "Japan", "China"), vState.regionOptions.map { it.label })
	}

	@Test
	fun `choosing a line narrows the list without reloading`() {
		val vLoaded = loaded(set("base1", "intl"), set("swsh1", "intl"), set("sv1a", "jp"))

		val vState = SetListContract.reduce(vLoaded, Intent.RegionSelected("jp"))

		assertEquals(listOf("sv1a"), vState.visibleSets.map { it.id.local })
		// Narrowing is a view of what is already loaded, so it must not bump the generation --
		// which would cancel nothing and re-request everything.
		assertEquals(vLoaded.requestGeneration, vState.requestGeneration)
		assertEquals(3, vState.sets.size, "the other lines stay loaded")
	}

	@Test
	fun `clearing the chip brings every line back`() {
		var vState = loaded(set("base1", "intl"), set("sv1a", "jp"))
		vState = SetListContract.reduce(vState, Intent.RegionSelected("jp"))

		vState = SetListContract.reduce(vState, Intent.RegionSelected(null))

		assertEquals(2, vState.visibleSets.size)
	}

	@Test
	fun `the region and the search narrow together`() {
		var vState = loaded(
			set("base1", "intl", name = "Base Set"),
			set("sv1a", "jp", name = "Triplet Beat"),
			set("sv2a", "jp", name = "Base Pack"),
		)
		vState = SetListContract.reduce(vState, Intent.RegionSelected("jp"))

		vState = SetListContract.reduce(vState, Intent.SearchChanged("base"))

		assertEquals(listOf("sv2a"), vState.visibleSets.map { it.id.local })
	}

	@Test
	fun `a game with one line offers no chips at all`() {
		// A single chip that is already selected and cannot be changed is furniture.
		val vState = loaded(set("base1", "intl"), set("swsh1", "intl"))

		assertTrue(vState.regionOptions.isEmpty())
		assertEquals(2, vState.visibleSets.size)
	}

	@Test
	fun `a line the provider does not serve gets no chip`() {
		// The game declares three lines; this provider turned out to serve two. A chip for the
		// third would filter to an empty list with nothing to say about why.
		val vState = loaded(set("base1", "intl"), set("sv1a", "jp"))

		assertEquals(listOf("International", "Japan"), vState.regionOptions.map { it.label })
	}

	@Test
	fun `switching game forgets the chosen line`() {
		var vState = loaded(set("base1", "intl"), set("sv1a", "jp"))
		vState = SetListContract.reduce(vState, Intent.RegionSelected("jp"))

		vState = SetListContract.reduce(
			vState,
			Intent.GameChanged(
				object : GameProfile {
					override val id: GameId = GameId("other")
					override val displayName: String = "Other"
					override val vocabulary: GameVocabulary = GameVocabulary()
				},
			),
		)

		// One game's lines mean nothing to another's, and a stale "jp" would filter the new game's
		// sets to none of them.
		assertEquals(null, vState.region)
	}

	// ============
	//  The language a set opens in

	@Test
	fun `a set opens in the preferred language when it has one`() {
		val vSet = set("base1", "intl").copy(
			languages = setOf(CardLanguage.ENGLISH, CardLanguage.FRENCH),
		)

		assertEquals(CardLanguage.FRENCH, vSet.languageFor(CardLanguage.FRENCH))
	}

	@Test
	fun `a set the preferred language does not cover opens in the best it has`() {
		// A Japan-line set for a user who prefers French. Opening it in French would show nothing
		// at all, so the preference order picks the best available instead.
		val vSet = set("sv1a", "jp").copy(
			languages = setOf(CardLanguage.JAPANESE, CardLanguage.KOREAN),
		)

		assertEquals(CardLanguage.JAPANESE, vSet.languageFor(CardLanguage.FRENCH))
	}

	@Test
	fun `a set that states no languages keeps the preference`() {
		// Silence from a provider is not a claim that its sets are English only.
		val vSet = set("OGN", null)

		assertEquals(CardLanguage.FRENCH, vSet.languageFor(CardLanguage.FRENCH))
	}
}
