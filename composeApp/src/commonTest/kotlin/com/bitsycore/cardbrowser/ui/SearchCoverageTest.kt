package com.bitsycore.cardbrowser.ui

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.data.cache.CardSearchFilter
import com.bitsycore.cardbrowser.data.repository.SearchScope
import com.bitsycore.cardbrowser.ui.search.SearchContract.UiState
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sentence that says what a search actually looked at.
 *
 * It exists because two empty results mean opposite things -- a whole-catalogue search that found
 * nothing, and a cache-scoped one that had almost nothing to look in. It is tested because it got
 * the second one wrong in the worst possible direction: it told the user how many sets they had
 * downloaded, using a number that counts the sets the *game* has.
 */
class SearchCoverageTest {

	private object TestGame : GameProfile {
		override val id: GameId = GameId("pokemon")
		override val displayName: String = "Pokémon"
		override val shortName: String = "Pokémon"
		override val vocabulary: GameVocabulary = GameVocabulary()
	}

	private fun local(searched: Int, known: Int) = UiState(
		game = TestGame,
		submitted = "pikachu",
		scope = SearchScope.LOCAL_CACHED_SETS,
		searchedSetCount = searched,
		knownSetCount = known,
	)

	@Test
	fun `a search confined to chosen sets has no coverage to report`() {
		// The notice exists to say how much of a game the search could reach. Once the user has
		// named the sets, "3 of 120" is not a shortfall -- it is the filter doing as it was told,
		// and the chips already say which sets those are.
		val vScoped = UiState(
			results = listOf(),
			searchedSetCount = 1,
			knownSetCount = 120,
			scope = SearchScope.LOCAL_CACHED_SETS,
			filter = CardSearchFilter(setIds = setOf("p:origins")),
		)

		assertNull(vScoped.coverageNotice)
		assertNotNull(
			vScoped.copy(filter = CardSearchFilter()).coverageNotice,
			"across a game, the same numbers do have something to report",
		)
	}

	@Test
	fun `the catalogue size is not presented as what the user has downloaded`() {
		// The fault: "the 2 of 486 sets you have downloaded were searched" told a user who had
		// downloaded two sets that they had downloaded 486.
		val vNotice = local(searched = 2, known = 486).coverageNotice

		assertTrue(vNotice != null)
		assertTrue(
			"2 sets you have downloaded" in vNotice,
			"the count of downloaded sets should be the small number: $vNotice",
		)
		assertTrue("of 486" in vNotice, "the catalogue size should still be stated: $vNotice")
		assertTrue(
			"486 sets you have downloaded" !in vNotice,
			"still claiming the user downloaded the whole catalogue: $vNotice",
		)
	}

	@Test
	fun `one set is not called sets`() {
		val vNotice = local(searched = 1, known = 486).coverageNotice
		assertTrue(vNotice != null && "1 set you have downloaded" in vNotice, "$vNotice")
	}

	@Test
	fun `a search that saw the whole catalogue says nothing`() {
		// A remote search needs no caveat, and a strip that appeared anyway would be noise on every
		// search for the four games whose sources can search across sets.
		assertNull(
			UiState(
				game = TestGame,
				submitted = "pikachu",
				scope = SearchScope.REMOTE_ALL_SETS,
				searchedSetCount = 12,
				knownSetCount = 486,
			).coverageNotice,
		)
	}

	@Test
	fun `a local search that saw everything says nothing either`() {
		assertNull(local(searched = 486, known = 486).coverageNotice)
	}

	@Test
	fun `an unknown catalogue size cannot arise`() {
		// Not a wording case -- an unreachable one. `isLimitedByCache` requires
		// `searchedSetCount < knownSetCount`, so a zero catalogue is never "limited" and the strip
		// is simply absent. Writing this test is what showed the old sentence carried a dead
		// branch for it; the empty-results message is what actually speaks in this situation.
		assertNull(local(searched = 3, known = 0).coverageNotice)
		assertNull(local(searched = 0, known = 0).coverageNotice)
	}
}
