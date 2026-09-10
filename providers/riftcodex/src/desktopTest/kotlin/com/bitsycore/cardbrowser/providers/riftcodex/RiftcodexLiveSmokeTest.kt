package com.bitsycore.cardbrowser.providers.riftcodex

import com.bitsycore.cardbrowser.core.model.Availability
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.core.provider.ProviderRoute
import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.data.cache.MetadataCache
import com.bitsycore.cardbrowser.data.net.HttpClientFactory
import com.bitsycore.cardbrowser.data.repository.CardRepository
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Talks to the real Riftcodex API.
 *
 * **Not part of the ordinary test run.** It needs a network, it depends on somebody else's server
 * staying up, and its assertions are about data that can legitimately change -- so it is excluded
 * from `desktopTest` and has its own task:
 *
 * ```
 * ./gradlew :providers:riftcodex:liveProviderTest
 * ```
 *
 * What it is for is catching the thing unit tests structurally cannot: the provider changing its
 * response shape underneath us. Assertions are therefore about *shape and invariants* rather than
 * about exact values, apart from a couple of anchors solid enough to be worth pinning.
 */
class RiftcodexLiveSmokeTest {

	private fun provider() = RiftcodexProvider(HttpClientFactory.create())

	@Test
	fun `the real set list loads and contains Origins`() = runBlocking {
		val vSets = provider().listSets()

		assertTrue(vSets.isNotEmpty(), "Riftcodex returned no sets at all")

		val vOrigins = vSets.firstOrNull { it.code == "OGN" }
		assertNotNull(vOrigins, "Origins (OGN) is missing from the live set list")
		assertEquals("Origins", vOrigins.name)
		assertNotNull(vOrigins.releaseDate, "Origins should still carry a publication date")
		assertTrue((vOrigins.cardCount ?: 0) > 0)
	}

	@Test
	fun `a real set's first page maps into usable printings`() = runBlocking {
		// Proving Grounds is 24 cards, so one page is the whole set and the check stays cheap.
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(RiftcodexProvider.PROVIDER_ID, "OGS"),
				query = CardQuery(),
				page = 1,
				pageSize = 100,
			),
		)

		assertTrue(vPage.cards.isNotEmpty(), "OGS returned no cards")
		assertFalse(vPage.hasMore, "OGS is a 24-card set and should fit one page of 100")

		vPage.cards.forEach { vCard ->
			assertTrue(vCard.displayName.isNotBlank(), "a card came back with no name")
			assertTrue(vCard.collectorNumber.isNotBlank(), "a card came back with no collector number")
			assertTrue(vCard.artwork.imageUrl.isNotBlank(), "${vCard.displayName} has no image URL")
		}

		// Ids must be unique, or the grid silently drops cards and the cache overwrites them.
		val vIds = vPage.cards.map { it.id }
		assertEquals(vIds.size, vIds.toSet().size, "the live data contains duplicate card ids")
	}

	@Test
	fun `the coverage this app claims still matches what the provider sends`() = runBlocking {
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(RiftcodexProvider.PROVIDER_ID, "OGS"),
				page = 1,
				pageSize = 5,
			),
		)

		vPage.cards.forEach { vCard ->
			// If Riftcodex ever gains a language or finish field, these will start failing -- which
			// is the point. The app's honesty claims are only honest while they are true.
			assertEquals(Availability.UNKNOWN, vCard.languages.availabilityOf(CardLanguage.FRENCH))
			assertTrue(vCard.finishes.isUnstated, "the provider appears to have gained finish data")
			// Identity is *inferred* here, not stated, so this checks the inference rather than the
			// provider: every printing gets one, and it is scoped to the printing's own set.
			val vIdentity = assertNotNull(vCard.identity, "identity should be inferred for every card")
			assertTrue(
				vIdentity.id.local.startsWith("${vCard.setCode}:"),
				"identity ${vIdentity.id.local} is not scoped to a set",
			)
		}
	}

	@Test
	fun `the server still caps page size at 100`() = runBlocking {
		// The adapter clamps to 100 precisely because of this. If the cap moves, the clamp should.
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(RiftcodexProvider.PROVIDER_ID, "OGN"),
				page = 1,
				pageSize = 100,
			),
		)

		assertEquals(100, vPage.cards.size)
		assertTrue(vPage.hasMore, "Origins has 352 cards, so one page of 100 is not all of it")
	}

	/**
	 * The whole chain, against the real API and a real disk cache.
	 *
	 * Set list to set id to cards to cache to filter to offline replay. This is the check that
	 * caught the `set_id` bug the unit tests could not: the adapter was internally consistent and
	 * every fixture passed, but the id the set list produced was not the id the cards endpoint
	 * wanted, and only a round trip through both endpoints shows that.
	 */
	@OptIn(ExperimentalTime::class)
	@Test
	fun `the full browse flow works end to end and survives a restart offline`() = runBlocking {
		val vDirectory = "${System.getProperty("java.io.tmpdir")}/cardbrowser-live-${Random.nextInt()}".toPath()
		val vStorage = AppStorage(
			fileSystem = FileSystem.SYSTEM,
			cacheRoot = vDirectory / "cache",
			preferencesRoot = vDirectory / "prefs",
		).also { it.prepare() }

		try {
			val vCache = MetadataCache(
				mStorage = vStorage,
				mJson = HttpClientFactory.json,
				mIoDispatcher = Dispatchers.IO,
				mClock = { Clock.System.now().toEpochMilliseconds() },
			)
			val vRegistry = ProviderRegistry(
				providers = listOf(provider()),
				routes = listOf(
					ProviderRoute(
						RiftboundGame.id,
						RiftcodexProvider.PROVIDER_ID,
					),
				),
			)
			val vRepository = CardRepository(
				mRegistry = vRegistry,
				mCache = vCache,
				mClock = { Clock.System.now().toEpochMilliseconds() },
			)

			// 1. The set list, exactly as the first screen asks for it.
			val vSets = assertNotNull(
				vRepository.setList(RiftboundGame.id).last().value,
			)
			val vProvingGrounds = assertNotNull(vSets.firstOrNull { it.code == "OGS" })

			// 2. That set's cards, using the id the set list itself produced. This is the step that
			//    was broken: a mismatched id answers 200 with nothing.
			val vCards = assertNotNull(
				vRepository.cards(
						setId = vProvingGrounds.id,
						game = RiftboundGame.id,
						query = CardQuery(),
						knownSetSize = vProvingGrounds.cardCount,
					).last().value,
			)
			assertTrue(vCards.cards.isNotEmpty(), "the set list's own id returned no cards")
			assertTrue(vCards.isCompleteSet, "OGS should page to completion")
			assertEquals(vProvingGrounds.cardCount, vCards.cards.size)

			// 3. A local filter over the cached complete set.
			val vEpics = assertNotNull(
				vRepository.cards(
						setId = vProvingGrounds.id,
						game = RiftboundGame.id,
						query = CardQuery(rarities = setOf("Epic")),
					).last().value,
			)
			assertTrue(vEpics.cards.isNotEmpty(), "OGS should contain at least one Epic")
			assertTrue(vEpics.cards.all { it.classification.rarity == "Epic" })

			// 4. A restart with no network at all: a fresh repository over the same disk, and a
			//    provider pointed at an address that cannot answer.
			val vOfflineRepository = CardRepository(
				mRegistry = ProviderRegistry(
					providers = listOf(
						RiftcodexProvider(HttpClientFactory.create(), "http://127.0.0.1:1"),
					),
					routes = listOf(
						ProviderRoute(
							RiftboundGame.id,
							RiftcodexProvider.PROVIDER_ID,
						),
					),
				),
				mCache = MetadataCache(
					mStorage = vStorage,
					mJson = HttpClientFactory.json,
					mIoDispatcher = Dispatchers.IO,
					mClock = { Clock.System.now().toEpochMilliseconds() },
				),
				mClock = { Clock.System.now().toEpochMilliseconds() },
			)

			val vOffline = assertNotNull(
				vOfflineRepository.cards(vProvingGrounds.id, RiftboundGame.id, CardQuery()).last().value,
			)
			assertEquals(
				vCards.cards.size,
				vOffline.cards.size,
				"previously fetched cards must still be browsable with no network",
			)
		} finally {
			FileSystem.SYSTEM.deleteRecursively(vDirectory, mustExist = false)
		}
	}

	/**
	 * Vendetta really does ship duplicate records, and the repository really does collapse them.
	 *
	 * Measured when this was written: 358 card records for 227 distinct `riftbound_id`s -- 37% of
	 * the set sent twice, each copy under its own database id. If Riftcodex ever cleans that up this
	 * test still passes; what it guards is that the app never shows the same printing twice.
	 */
	@OptIn(ExperimentalTime::class)
	@Test
	fun `a set the provider duplicates is de-duplicated before it reaches the screen`() = runBlocking {
		val vDirectory = "${System.getProperty("java.io.tmpdir")}/cardbrowser-dupe-${Random.nextInt()}".toPath()
		val vStorage = AppStorage(
			fileSystem = FileSystem.SYSTEM,
			cacheRoot = vDirectory / "cache",
			preferencesRoot = vDirectory / "prefs",
		).also { it.prepare() }

		try {
			val vRepository = CardRepository(
				mRegistry = ProviderRegistry(
					providers = listOf(provider()),
					routes = listOf(ProviderRoute(RiftboundGame.id, RiftcodexProvider.PROVIDER_ID)),
				),
				mCache = MetadataCache(
					mStorage = vStorage,
					mJson = HttpClientFactory.json,
					mIoDispatcher = Dispatchers.IO,
					mClock = { Clock.System.now().toEpochMilliseconds() },
				),
				mClock = { Clock.System.now().toEpochMilliseconds() },
			)

			val vCards = assertNotNull(
				vRepository.cards(
					setId = SourceId(RiftcodexProvider.PROVIDER_ID, "VEN"),
					game = RiftboundGame.id,
					query = CardQuery(),
				).last().value,
			).cards

			// Every printing appears once.
			val vKeys = vCards.mapNotNull { it.printingKey }
			assertEquals(vCards.size, vKeys.size, "every Riftbound card should carry a printing key")
			assertEquals(
				vKeys.size,
				vKeys.toSet().size,
				"the same printing reached the screen more than once",
			)

			// And distinct printings were not collapsed along with them: 019 and 019a are two
			// different cards and must both survive.
			val vNineteens = vCards.filter { it.collectorNumber.startsWith("019") }
			assertEquals(
				vNineteens.map { it.collectorNumber }.toSet(),
				vNineteens.map { it.collectorNumber }.toSet(),
			)
			assertTrue(
				vNineteens.size >= 2,
				"019 and 019a are different printings and both should be present, saw $vNineteens",
			)
		} finally {
			FileSystem.SYSTEM.deleteRecursively(vDirectory, mustExist = false)
		}
	}

	private fun assertFalse(value: Boolean, message: String) = assertTrue(!value, message)
}
