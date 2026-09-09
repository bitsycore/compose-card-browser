package com.bitsycore.cardbrowser.providers.tcgdex

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.core.provider.ProviderRoute
import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.data.cache.MetadataCache
import com.bitsycore.cardbrowser.data.net.ApiCallStats
import com.bitsycore.cardbrowser.data.net.HttpClientFactory
import com.bitsycore.cardbrowser.data.repository.CardRepository
import com.bitsycore.cardbrowser.games.pokemon.PokemonGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The whole language chain against the live API: set list, cached record, confirmation, cache.
 *
 * The individual pieces are covered by [TcgdexLiveSmokeTest]; this covers the *wiring*, which is
 * where the equivalent bug lived last time. A set's languages pass through four layers -- the
 * adapter merges eleven catalogues, the repository caches the record, the provider probes each
 * candidate, the repository caches that too -- and every one of them can be individually right
 * while the composition offers Korean anyway.
 *
 * Live, and therefore not part of the ordinary run:
 *
 * ```
 * ./gradlew :providers:tcgdex:liveProviderTest
 * ```
 */
class TcgdexLanguageWiringTest {

	private fun repository(root: String): CardRepository {
		val vStorage = AppStorage(
			fileSystem = FileSystem.SYSTEM,
			cacheRoot = "build/live-cache/$root".toPath(),
			preferencesRoot = "build/live-cache/$root-prefs".toPath(),
		)
		vStorage.prepare()
		val vProvider = TcgdexProvider(HttpClientFactory.create())
		return CardRepository(
			mRegistry = ProviderRegistry(
				providers = listOf(vProvider),
				routes = listOf(ProviderRoute(PokemonGame.id, TcgdexProvider.PROVIDER_ID)),
			),
			mCache = MetadataCache(
				mStorage = vStorage,
				mJson = Json { ignoreUnknownKeys = true },
				mIoDispatcher = Dispatchers.IO,
				mClock = { 0L },
			),
			mClock = { 0L },
		)
	}

	@Test
	fun `a set's offered languages are the ones that really have cards`() = runBlocking {
		val vRepository = repository("wiring-${kotlin.random.Random.nextInt()}")

		// The set list has to be loaded first, because that is what the record comes from -- which
		// is also true in the app: a set is reached by tapping it in a list.
		val vSets = vRepository.setList(PokemonGame.id, CardLanguage.ENGLISH).toList()
			.last().value.orEmpty()
		assertTrue(vSets.size > 400, "the merged catalogue did not load: ${vSets.size}")

		val vBaseId = SourceId(TcgdexProvider.PROVIDER_ID, "base1")
		val vRecord = assertNotNull(
			vRepository.setRecord(vBaseId, PokemonGame.id),
			"the cached set list did not yield Base Set's record",
		)
		// Claimed: the six western locales that list it.
		assertEquals(6, vRecord.languages.size, "claimed languages: ${vRecord.languages}")

		// Offered: only those with cards behind them. Spanish and Portuguese list Base Set with a
		// card count of 102 and serve none of it.
		val vOffered = vRepository.languagesFor(vBaseId, PokemonGame.id)
		assertEquals(
			setOf(
				CardLanguage.ENGLISH,
				CardLanguage.FRENCH,
				CardLanguage.GERMAN,
				CardLanguage.ITALIAN,
			),
			vOffered,
		)

		// And the answer survives a restart, so the probes are paid for once.
		assertEquals(vOffered, vRepository.languagesFor(vBaseId, PokemonGame.id))
	}

	@Test
	fun `switching language does not refetch the catalogues`() = runBlocking {
		// The catalogues are 202 KB across 12 requests and TCGdex answers all of them `no-store`,
		// so the HTTP cache cannot help. None of it depends on the language asked for -- only the
		// merge does -- but the repository keys its set-list cache by language, so a language
		// switch used to pay the whole thing again to re-merge identical inputs.
		val vStats = ApiCallStats()
		val vProvider = TcgdexProvider(HttpClientFactory.create(stats = vStats))

		val vEnglish = vProvider.listSets(CardLanguage.ENGLISH)
		val vFirstCallRequests = vStats.total
		assertTrue(vFirstCallRequests > 1, "expected a request per catalogue, saw $vFirstCallRequests")

		val vFrench = vProvider.listSets(CardLanguage.FRENCH)

		assertEquals(
			vFirstCallRequests,
			vStats.total,
			"the second language cost ${vStats.total - vFirstCallRequests} more requests",
		)
		// And it is genuinely the same catalogue, re-merged rather than re-fetched: the same sets,
		// with French names where French has one.
		assertEquals(vEnglish.map { it.id }.toSet(), vFrench.map { it.id }.toSet())
		val vBaseEnglish = assertNotNull(vEnglish.firstOrNull { it.id.local == "base1" })
		val vBaseFrench = assertNotNull(vFrench.firstOrNull { it.id.local == "base1" })
		assertEquals("Base Set", vBaseEnglish.name)
		assertTrue(
			vBaseFrench.name != vBaseEnglish.name,
			"the merge did not use the requested language: ${vBaseFrench.name}",
		)
	}

	@Test
	fun `a Japanese set is found and never offered a western language`() = runBlocking {
		// The case that would show the wrong set's cards rather than nothing: TCGdex answers
		// `/en/sets/SM10` with international Unbroken Bonds, so English must never be offered for
		// the Japanese SM10 even though a request for it succeeds.
		val vRepository = repository("wiring-jp-${kotlin.random.Random.nextInt()}")
		vRepository.setList(PokemonGame.id, CardLanguage.ENGLISH).toList()

		val vJapaneseId = SourceId(TcgdexProvider.PROVIDER_ID, "SM10")
		val vRecord = assertNotNull(vRepository.setRecord(vJapaneseId, PokemonGame.id))
		assertEquals(PokemonGame.REGION_JAPAN, vRecord.region)

		val vOffered = vRepository.languagesFor(vJapaneseId, PokemonGame.id)
		assertTrue(CardLanguage.ENGLISH !in vOffered, "offered English for a Japan-line set")
		assertTrue(CardLanguage.JAPANESE in vOffered)

		// A user who prefers French gets the set in a language it exists in rather than an empty
		// grid -- the rule the set list's saved mark also uses.
		assertEquals(
			CardLanguage.JAPANESE,
			vRecord.copy(languages = vOffered).languageFor(CardLanguage.FRENCH),
		)
	}
}
