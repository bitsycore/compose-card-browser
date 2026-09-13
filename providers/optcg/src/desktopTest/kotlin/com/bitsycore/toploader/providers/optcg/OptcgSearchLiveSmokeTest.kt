package com.bitsycore.toploader.providers.optcg

import com.bitsycore.toploader.data.cache.CardSearchFilter
import com.bitsycore.toploader.core.model.CardLanguage
import com.bitsycore.toploader.core.provider.CardQuery
import com.bitsycore.toploader.core.provider.ProviderRegistry
import com.bitsycore.toploader.core.provider.ProviderRoute
import com.bitsycore.toploader.data.cache.InMemoryMetadataStore
import com.bitsycore.toploader.data.cache.SqlSetRecordStore
import com.bitsycore.toploader.data.net.HttpClientFactory
import com.bitsycore.toploader.data.repository.CardRepository
import com.bitsycore.toploader.games.onepiece.OnePieceGame
import com.bitsycore.toploader.sqlstore.DesktopDriverFactory
import com.bitsycore.toploader.sqlstore.SqlCardStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/*
Downloads a real One Piece set and searches it, because searching One Piece was reported broken and
every layer looked correct in isolation.

The provider serves the card, so the fault is between the download and the query. Both halves run
here against real data and a real SQLite store -- that is the only arrangement that can tell a
mapping bug from a language-key mismatch from a folding bug, and none of the three is visible from
a unit test of either end.

Live, so it is not in the ordinary run: `./gradlew :providers:optcg:liveProviderTest`.
*/
class OptcgSearchLiveSmokeTest {

	private val mStore = SqlCardStore(DesktopDriverFactory().create(null))

	private val mSetStore = SqlSetRecordStore(mStore, Dispatchers.IO)

	private fun repository(): CardRepository {
		val vProvider = OptcgProvider(HttpClientFactory.create())
		return CardRepository(
			mRegistry = ProviderRegistry(
				providers = listOf(vProvider),
				routes = listOf(ProviderRoute(OnePieceGame.id, vProvider.id)),
			),
			mCache = InMemoryMetadataStore(),
			mSetStore = mSetStore,
			mClock = { 0L },
		)
	}

	@Test
	fun `a downloaded One Piece set is searchable by name`() = runBlocking {
		val vRepository = repository()
		val vSets = vRepository.setList(OnePieceGame.id).last().value.orEmpty()
		assertTrue(vSets.isNotEmpty(), "OPTCG served no sets")

		val vRomanceDawn = vSets.first { it.code == "OP-01" }
		val vDownloaded = vRepository
			.cards(vRomanceDawn.id, OnePieceGame.id, CardQuery())
			.last()
			.value
			?.cards
			.orEmpty()
		assertTrue(vDownloaded.isNotEmpty(), "OP-01 downloaded no cards at all")

		// The anchor. Yamato is in OP-01 -- confirmed straight off the API on 2026-09-13 -- so a
		// search that cannot find it is this app's bug and not a gap in the source.
		val vInPayload = vDownloaded.count { it.displayName.contains("Yamato", ignoreCase = true) }
		assertTrue(vInPayload > 0, "OP-01 arrived without Yamato, so the mapping dropped it")

		val vFound = vRepository.searchStoredCards(
			game = OnePieceGame.id,
			filter = CardSearchFilter(text = "yamato"),
			knownSets = vSets,
		)
		assertTrue(
			vFound.cards.isNotEmpty(),
			"OP-01 is stored and holds $vInPayload Yamato printings, but the stored search " +
				"found none -- it searched ${vFound.searchedSetCount} of ${vFound.knownSetCount} sets",
		)
	}

	@Test
	fun `a downloaded One Piece set is searchable when the screen names a language`() = runBlocking {
		// What the card grid actually sends. The screen carries the user's preferred language on
		// every search, and OPTCG states no languages at all -- so this is the ordinary case, not
		// an edge one, and it is the case that was reported broken.
		val vRepository = repository()
		val vSets = vRepository.setList(OnePieceGame.id).last().value.orEmpty()
		vRepository.cards(vSets.first { it.code == "OP-01" }.id, OnePieceGame.id, CardQuery()).last()

		for (vLanguage in listOf(CardLanguage.ENGLISH, CardLanguage.FRENCH)) {
			val vFound = vRepository.searchStoredCards(
				game = OnePieceGame.id,
				filter = CardSearchFilter(text = "yamato", language = vLanguage),
				knownSets = vSets,
			)
			assertTrue(
				vFound.cards.isNotEmpty(),
				"searching OP-01 for Yamato in ${vLanguage.code} found nothing, though the set " +
					"is stored -- the rows were written under the language the source answered in",
			)
		}
	}

	@Test
	fun `every printing in a real set has a distinct id`() = runBlocking {
		// A duplicate id is a crash rather than a cosmetic bug: `LazyVerticalGrid` throws on a
		// repeated key. One Piece prints the same card number with several arts, so this is the
		// game most likely to carry one.
		val vRepository = repository()
		val vSets = vRepository.setList(OnePieceGame.id).last().value.orEmpty()
		val vCards = vRepository
			.cards(vSets.first { it.code == "OP-01" }.id, OnePieceGame.id, CardQuery())
			.last()
			.value
			?.cards
			.orEmpty()

		val vDuplicates = vCards.groupBy { it.id.qualified }.filterValues { it.size > 1 }
		assertTrue(
			vDuplicates.isEmpty(),
			"OP-01 served ${vDuplicates.size} repeated ids, e.g. ${vDuplicates.keys.take(3)}",
		)
	}
}
