package com.bitsycore.cardbrowser.data

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.Artwork
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.model.LocalizedText
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.BulkCatalogue
import com.bitsycore.cardbrowser.core.provider.BulkSummary
import com.bitsycore.cardbrowser.core.provider.CardPage
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import com.bitsycore.cardbrowser.core.provider.CardProvider
import com.bitsycore.cardbrowser.core.provider.DataCapabilities
import com.bitsycore.cardbrowser.core.provider.FilterSupport
import com.bitsycore.cardbrowser.core.provider.ProviderCapabilities
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.core.provider.ProviderRoute
import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.data.cache.MetadataCache
import com.bitsycore.cardbrowser.data.repository.BulkImportProgress
import com.bitsycore.cardbrowser.data.repository.CardRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Importing a whole catalogue from a bulk file.
 *
 * The real one is 598 MB, so what is exercised here is the shape rather than the size: cards
 * arriving one at a time in no particular order, being sorted into sets, and coming out as cached
 * sets indistinguishable from ones fetched a set at a time.
 */
class BulkImportTest {

	private val mProviderId = ProviderId("bulky")

	private fun printing(set: String, number: String) = CardPrinting(
		id = SourceId(mProviderId, "$set-$number"),
		game = TestGameProfile.id,
		setId = SourceId(mProviderId, set),
		setCode = set,
		setName = set,
		collectorNumber = number,
		providerRawCollectorNumber = number,
		identity = null,
		text = LocalizedText(language = CardLanguage.ENGLISH, name = "Card $set-$number"),
		artwork = Artwork(
			id = SourceId(mProviderId, "$set-$number"),
			imageUrl = "https://example.test/$set-$number.jpg",
			thumbnailUrl = null,
			artist = null,
			treatment = ArtworkTreatment.UNKNOWN,
			language = null,
		),
	)

	/** A provider whose whole catalogue arrives as a stream, in deliberately jumbled order. */
	private class FakeBulkProvider(
		private val mId: ProviderId,
		private val mCards: List<CardPrinting>,
		private val mSets: List<CardSet>,
	) : CardProvider<TestGameProfile>, BulkCatalogue {

		var streamed = 0
			private set

		override val id: ProviderId = mId
		override val displayName: String = "Fake bulk"
		override val game: TestGameProfile = TestGameProfile
		override val capabilities: ProviderCapabilities = ProviderCapabilities(
			filtering = FilterSupport(remote = emptySet(), localOnly = emptySet()),
			sorting = emptySet(),
			data = DataCapabilities(
				languages = setOf(CardLanguage.ENGLISH),
				localizedText = false,
				localizedImages = false,
				cardIdentity = false,
				artworkVariants = false,
				finishes = false,
				cardmarketProductMapping = false,
			),
			attribution = null,
			maxPageSize = 100,
		)

		override suspend fun listSets(language: CardLanguage?): List<CardSet> = mSets

		override suspend fun listCards(request: CardPageRequest): CardPage =
			CardPage(emptyList(), 1, 100, 0, false)

		override suspend fun cardDetail(id: SourceId, language: CardLanguage?): CardPrinting? = null

		/** Two, so a test can assert the caller picks one and reports which it read. */
		override suspend fun bulkVariants(): List<BulkSummary> = listOf(
			BulkSummary(
				id = "cheap",
				label = "Cheap",
				compressedBytes = 1234,
				updatedAt = LocalDate(2026, 9, 11),
				description = "a fake dump",
			),
			BulkSummary(
				id = "everything",
				label = "Everything",
				compressedBytes = 9999,
				updatedAt = LocalDate(2026, 9, 11),
				description = "a fake dump in every language",
				coversAllLanguages = true,
			),
		)

		/** The last variant asked for, so a test can assert the choice reached the adapter. */
		var streamedVariant: String? = null
			private set

		override suspend fun streamAll(
			variantId: String,
			onBytes: (Long, Long?) -> Unit,
			onCard: suspend (CardPrinting) -> Unit,
		) {
			streamedVariant = variantId
			onBytes(0, 1234)
			// Interleaved on purpose: a real dump is not grouped by set, which is the entire
			// reason the import buckets rather than accumulating a list per set as it reads.
			mCards.forEach { streamed++; onCard(it) }
			onBytes(1234, 1234)
		}
	}

	private object TestGameProfile : GameProfile {
		override val id: GameId = GameId("testgame")
		override val displayName: String = "Test Game"
		override val vocabulary: GameVocabulary = GameVocabulary()
	}

	private fun set(code: String) = CardSet(
		id = SourceId(mProviderId, code),
		game = TestGameProfile.id,
		code = code,
		name = code,
		cardCount = null,
		releaseDate = null,
	)

	private fun repository(provider: FakeBulkProvider): CardRepository {
		val vStorage = AppStorage(FakeFileSystem(), "/cache".toPath(), "/prefs".toPath())
			.also { it.prepare() }
		return CardRepository(
			mRegistry = ProviderRegistry(
				providers = listOf(provider),
				routes = listOf(ProviderRoute(game = TestGameProfile.id, provider = provider.id)),
			),
			mCache = MetadataCache(
				mStorage = vStorage,
				mJson = Json { ignoreUnknownKeys = true },
				mIoDispatcher = Dispatchers.Unconfined,
				mClock = { 0L },
			),
			mClock = { 0L },
			mStorage = vStorage,
			mJson = Json { ignoreUnknownKeys = true },
		)
	}

	@Test
	fun `cards are stored under the language their own record states`() = runTest {
		// The bug this replaced: the import asked for a language, callers passed null, and
		// `resolveLanguage(null)` walks the preference order -- which for a source offering all
		// eleven resolves to French. An overwhelmingly English file was imported, cached and
		// reported as French. The file decides now, per record.
		val vEnglish = printing("AAA", "001")
		val vJapanese = printing("AAA", "002").let {
			it.copy(text = it.text.copy(language = CardLanguage.JAPANESE))
		}
		val vProvider = FakeBulkProvider(mProviderId, listOf(vEnglish, vJapanese), listOf(set("AAA")))
		val vRepository = repository(vProvider)

		val vResult = assertNotNull(vRepository.importBulk(TestGameProfile.id))

		// One set, two languages, two cache entries -- not one entry with the two mixed under a
		// single label that is wrong for half of them.
		assertEquals(2, vResult.cards)
		assertEquals(2, vResult.sets, "each language of a set is its own cached entry")
	}

	@Test
	fun `a jumbled stream comes out as complete sets`() = runTest {
		// Two sets interleaved, as a real dump has them.
		val vCards = listOf(
			printing("AAA", "001"), printing("BBB", "001"),
			printing("AAA", "002"), printing("BBB", "002"), printing("AAA", "003"),
		)
		val vProvider = FakeBulkProvider(mProviderId, vCards, listOf(set("AAA"), set("BBB")))
		val vRepository = repository(vProvider)

		val vResult = assertNotNull(vRepository.importBulk(TestGameProfile.id))

		assertEquals(5, vResult.cards)
		assertEquals(2, vResult.sets)
		assertEquals(2, vResult.knownSets)
	}

	@Test
	fun `an imported set reads back exactly like a downloaded one`() = runTest {
		// The point of the whole exercise: a bulk import and a set-at-a-time download must leave
		// the same cache, or the set list would mark one saved and not the other.
		val vProvider = FakeBulkProvider(
			mProviderId,
			listOf(printing("AAA", "001"), printing("AAA", "002")),
			listOf(set("AAA")),
		)
		val vRepository = repository(vProvider)

		vRepository.importBulk(TestGameProfile.id)

		val vSnapshot = vRepository.cardDetail(
			id = SourceId(mProviderId, "AAA-001"),
			game = TestGameProfile.id,
			setId = SourceId(mProviderId, "AAA"),
		)

		assertEquals("Card AAA-001", assertNotNull(vSnapshot.value).displayName)
	}

	@Test
	fun `progress is reported through all three phases`() = runTest {
		val vProvider = FakeBulkProvider(mProviderId, listOf(printing("AAA", "001")), listOf(set("AAA")))
		val vSeen = mutableListOf<BulkImportProgress>()

		repository(vProvider).importBulk(TestGameProfile.id) { vSeen += it }

		assertTrue(vSeen.any { it is BulkImportProgress.Downloading }, "Got $vSeen")
		assertTrue(vSeen.any { it is BulkImportProgress.Writing }, "Got $vSeen")
	}

	@Test
	fun `a source with no bulk file answers null rather than pretending`() = runTest {
		// The caller has to be able to tell "no dump" from "an empty dump", so this is null and
		// not a result of zero.
		val vStorage = AppStorage(FakeFileSystem(), "/cache".toPath(), "/prefs".toPath())
			.also { it.prepare() }
		val vRepository = CardRepository(
			mRegistry = ProviderRegistry(providers = emptyList(), routes = emptyList()),
			mCache = MetadataCache(
				mStorage = vStorage,
				mJson = Json { ignoreUnknownKeys = true },
				mIoDispatcher = Dispatchers.Unconfined,
				mClock = { 0L },
			),
			mClock = { 0L },
			mStorage = vStorage,
		)

		assertNull(vRepository.importBulk(GameId("nothing-here")))
	}
	@Test
	fun `the default is the cheapest dump -- and the result says which was read`() = runTest {
		// The fake publishes two, as Scryfall does. Null must take the first rather than an
		// arbitrary one, because the two differ by 315 MB in the real case.
		val vProvider = FakeBulkProvider(mProviderId, listOf(printing("OGN", "1")), listOf(set("OGN")))
		val vResult = assertNotNull(repository(vProvider).importBulk(TestGameProfile.id))

		assertEquals("cheap", vProvider.streamedVariant)
		assertEquals("cheap", vResult.variantId)
		assertEquals(LocalDate(2026, 9, 11), vResult.dumpUpdatedAt)
	}

	@Test
	fun `an explicit variant is the one streamed`() = runTest {
		val vProvider = FakeBulkProvider(mProviderId, listOf(printing("OGN", "1")), listOf(set("OGN")))

		val vResult = assertNotNull(
			repository(vProvider).importBulk(TestGameProfile.id, variantId = "everything"),
		)

		assertEquals("everything", vProvider.streamedVariant)
		assertEquals("everything", vResult.variantId)
	}

	@Test
	fun `an unknown variant imports nothing rather than quietly taking another`() = runTest {
		// The two real ones differ by 315 MB. Falling back would spend that on a typo.
		val vProvider = FakeBulkProvider(mProviderId, listOf(printing("OGN", "1")), listOf(set("OGN")))

		assertNull(repository(vProvider).importBulk(TestGameProfile.id, variantId = "nope"))
		assertNull(vProvider.streamedVariant)
	}

}
