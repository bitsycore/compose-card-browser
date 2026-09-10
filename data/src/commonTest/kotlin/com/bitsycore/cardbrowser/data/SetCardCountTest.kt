package com.bitsycore.cardbrowser.data

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.Artwork
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardAttributes
import com.bitsycore.cardbrowser.core.model.CardClassification
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.FinishCoverage
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.model.LanguageCoverage
import com.bitsycore.cardbrowser.core.model.LocalizedText
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardFilterField
import com.bitsycore.cardbrowser.core.provider.CardPage
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import com.bitsycore.cardbrowser.core.provider.CardProvider
import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.provider.CardSortField
import com.bitsycore.cardbrowser.core.provider.DataCapabilities
import com.bitsycore.cardbrowser.core.provider.FilterSupport
import com.bitsycore.cardbrowser.core.provider.ProviderCapabilities
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.core.provider.ProviderRoute
import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.data.cache.MetadataCache
import com.bitsycore.cardbrowser.data.repository.CardRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A set's card count is the count for the language the row will open in.
 *
 * ## The fault this closes
 *
 * Reported against Yu-Gi-Oh! on 2026-09-10 and reproduced against the live API: the set list said
 * "Magnificent Maestros -- 24 cards" and opening it showed four, and "Beyond the Brave -- 8 cards"
 * opened onto nothing at all.
 *
 * Neither number was wrong on its own. `cardsets.php` states `num_of_cards`, one figure per set,
 * counting the English printing. `cardinfo.php?cardset=Magnificent+Maestros` serves 24 and
 * `&language=fr` serves 4 -- both sets are 2026 releases whose translations do not exist yet, and
 * Beyond the Brave answers `language=fr` with a 400 that the adapter correctly reads as "none".
 * The app browses in French unless told otherwise, because `resolveLanguage(null)` walks
 * `CardLanguage.PREFERENCE_ORDER`. So the row printed an English figure above a French grid.
 *
 * That is the app stating something it has not checked, which is the one rule this codebase is
 * built around. The fix is not to caveat the number -- it is to print the one that was measured,
 * once it has been. A set never fetched in that language has no measurement and keeps the source's
 * figure, because that remains the only number anybody has.
 */
class SetCardCountTest {

	private val mProviderId = ProviderId("fake")

	private val mSetId = SourceId(mProviderId, "MAMS")

	private var mNow = 10_000L

	// ==================
	// MARK: A source that translates only some of a set
	// ==================

	/**
	 * Serves [mCountsByLanguage] cards for whichever language is asked for.
	 *
	 * The whole point of the fake: one set, one stated size, and a different number of cards
	 * actually served per language. That is YGOPRODeck's behaviour and it is not unusual -- every
	 * source that translates does it, because translation lags printing.
	 */
	private class PartlyTranslatedProvider(
		override val id: ProviderId,
		private val mSets: List<CardSet>,
		private val mCountsByLanguage: Map<CardLanguage, Int>,
	) : CardProvider<CountTestGame> {

		override val displayName = "Partly translated"

		override val game: CountTestGame = CountTestGame

		override val capabilities = ProviderCapabilities(
			filtering = FilterSupport(remote = emptySet(), localOnly = setOf(CardFilterField.TEXT)),
			sorting = setOf(CardSortField.COLLECTOR_NUMBER),
			data = DataCapabilities(
				languages = setOf(CardLanguage.ENGLISH, CardLanguage.FRENCH),
				localizedText = true,
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

		override suspend fun listCards(request: CardPageRequest): CardPage {
			val vLanguage = request.language ?: CardLanguage.ENGLISH
			val vCards = (1..(mCountsByLanguage[vLanguage] ?: 0)).map { card(id, request.setId, it, vLanguage) }
			return CardPage(
				cards = vCards,
				page = request.page,
				pageSize = request.pageSize,
				totalCount = vCards.size,
				hasMore = false,
			)
		}

		override suspend fun cardDetail(id: SourceId, language: CardLanguage?): CardPrinting? = null

		/** What YGOPRODeck and TCGdex now do: ask, rather than assume the catalogue is complete. */
		override suspend fun confirmLanguages(
			setId: SourceId,
			candidates: Set<CardLanguage>,
		): Set<CardLanguage> = candidates.filterTo(mutableSetOf()) { (mCountsByLanguage[it] ?: 0) > 0 }
	}

	// ==================
	// MARK: Tests
	// ==================

	@Test
	fun `the count is what the language served -- not what the set states`() = runTest {
		// 24 in English, 4 in French, and the set record says 24 either way -- exactly the shape of
		// the reported fault.
		val vRepository = repository(english = 24, french = 4)

		// Nothing measured yet, so nothing to override the set's own figure with.
		assertEquals(
			emptyMap(),
			vRepository.confirmedCardCounts(CountTestGame.id, sets(), CardLanguage.FRENCH),
			"An unfetched set must not claim a count",
		)

		vRepository.cards(mSetId, CountTestGame.id, CardQuery(), CardLanguage.FRENCH).toList()

		assertEquals(
			mapOf(mSetId.qualified to 4),
			vRepository.confirmedCardCounts(CountTestGame.id, sets(), CardLanguage.FRENCH),
			"The row must say what French actually holds",
		)
	}

	@Test
	fun `each language is counted apart`() = runTest {
		val vRepository = repository(english = 24, french = 4)

		vRepository.cards(mSetId, CountTestGame.id, CardQuery(), CardLanguage.FRENCH).toList()
		vRepository.cards(mSetId, CountTestGame.id, CardQuery(), CardLanguage.ENGLISH).toList()

		assertEquals(
			4,
			vRepository.confirmedCardCounts(CountTestGame.id, sets(), CardLanguage.FRENCH)[mSetId.qualified],
		)
		assertEquals(
			24,
			vRepository.confirmedCardCounts(CountTestGame.id, sets(), CardLanguage.ENGLISH)[mSetId.qualified],
			"Fetching French must not overwrite what English measured",
		)
	}

	@Test
	fun `a language that serves nothing counts zero rather than falling back`() = runTest {
		// Beyond the Brave: eight cards, none of them translated. Zero is a measurement and absent
		// is not -- so this must be present in the map and equal to zero, or the row would go on
		// printing 8 above an empty grid.
		val vRepository = repository(english = 8, french = 0)

		vRepository.cards(mSetId, CountTestGame.id, CardQuery(), CardLanguage.FRENCH).toList()

		val vCounts = vRepository.confirmedCardCounts(CountTestGame.id, sets(), CardLanguage.FRENCH)
		assertTrue(mSetId.qualified in vCounts, "Zero must be recorded, not omitted")
		assertEquals(0, vCounts[mSetId.qualified])
	}

	@Test
	fun `a count survives the record it describes being evicted`() = runTest {
		// The count is a measurement of what a source served, not a property of the cached copy, so
		// trimming the cards away must not take the number with them. A ceiling of one byte evicts
		// everything evictable on the next write.
		val vFileSystem = FakeFileSystem()
		val vRepository = repository(english = 24, french = 4, fileSystem = vFileSystem, maxBytes = 1)

		vRepository.cards(mSetId, CountTestGame.id, CardQuery(), CardLanguage.FRENCH).toList()

		assertEquals(
			4,
			vRepository.confirmedCardCounts(CountTestGame.id, sets(), CardLanguage.FRENCH)[mSetId.qualified],
		)
	}

	// ==================
	// MARK: The language pin
	// ==================

	@Test
	fun `a set says nothing about its languages until something knows`() = runTest {
		// The pin's silence is deliberate. This source states no per-set languages, so before the
		// set has ever been opened there is nothing to show -- and filling the gap from the
		// provider's capability list would announce two languages for a set that may have one.
		assertEquals(
			emptyMap(),
			repository(english = 24, french = 4)
				.availableLanguages(CountTestGame.id, sets(), CardLanguage.FRENCH),
		)
	}

	@Test
	fun `a set that claims its own languages is pinned straight away`() = runTest {
		// What TCGdex and the Wuthering Waves snapshot do: the languages arrive with the catalogue,
		// so the row can say so on the first frame without a single request.
		val vRepository = repository(english = 24, french = 4)
		val vClaimed = sets().map { it.copy(languages = setOf(CardLanguage.ENGLISH, CardLanguage.FRENCH)) }

		assertEquals(
			mapOf(mSetId.qualified to setOf(CardLanguage.ENGLISH, CardLanguage.FRENCH)),
			vRepository.availableLanguages(CountTestGame.id, vClaimed, CardLanguage.FRENCH),
		)
	}

	@Test
	fun `a confirmed record outranks the claim`() = runTest {
		// A catalogue listing a set in a language is not evidence it has cards in it -- TCGdex's
		// Korean catalogue names 95 sets and serves none. Once the probe has run, its answer wins.
		val vRepository = repository(english = 24, french = 0)
		val vClaimed = sets().map { it.copy(languages = setOf(CardLanguage.ENGLISH, CardLanguage.FRENCH)) }

		vRepository.languagesFor(mSetId, CountTestGame.id)

		assertEquals(
			setOf(CardLanguage.ENGLISH),
			vRepository.availableLanguages(CountTestGame.id, vClaimed, CardLanguage.FRENCH)[mSetId.qualified],
			"The probe found no French cards, so the claim must not survive it",
		)
	}

	@Test
	fun `an unknown game has nothing to say`() = runTest {
		assertNull(
			repository(english = 24, french = 4)
				.confirmedCardCounts(GameId("not-routed"), sets(), CardLanguage.FRENCH)[mSetId.qualified],
		)
	}

	// ==================
	// MARK: Harness
	// ==================

	private fun sets(): List<CardSet> = listOf(
		CardSet(
			id = mSetId,
			game = CountTestGame.id,
			code = "MAMS",
			name = "Magnificent Maestros",
			// What the source states, and what the row printed before this existed.
			cardCount = 24,
			releaseDate = null,
		),
	)

	private fun repository(
		english: Int,
		french: Int,
		fileSystem: FakeFileSystem = FakeFileSystem(),
		maxBytes: Long = MetadataCache.DEFAULT_MAX_BYTES,
	): CardRepository {
		val vProvider = PartlyTranslatedProvider(
			id = mProviderId,
			mSets = sets(),
			mCountsByLanguage = mapOf(CardLanguage.ENGLISH to english, CardLanguage.FRENCH to french),
		)
		val vStorage = AppStorage(fileSystem, "/cache".toPath(), "/prefs".toPath()).also { it.prepare() }
		return CardRepository(
			mRegistry = ProviderRegistry(
				providers = listOf(vProvider),
				routes = listOf(ProviderRoute(CountTestGame.id, vProvider.id)),
			),
			mCache = MetadataCache(
				mStorage = vStorage,
				mJson = Json { ignoreUnknownKeys = true },
				mIoDispatcher = Dispatchers.Unconfined,
				mMaxBytes = { maxBytes },
				mClock = { mNow },
			),
			mClock = { mNow },
		)
	}
}

/** A game with no opinions, so the test is about counting and nothing else. */
private object CountTestGame : GameProfile {

	override val id: GameId = GameId("count-test-game")

	override val displayName: String = "Count Test Game"

	override val vocabulary: GameVocabulary = GameVocabulary()
}

/** One card, in one language, with only the fields the repository touches filled in. */
private fun card(
	provider: ProviderId,
	setId: SourceId,
	number: Int,
	language: CardLanguage,
): CardPrinting = CardPrinting(
	id = SourceId(provider, "${language.code}-card-$number"),
	printingKey = null,
	game = CountTestGame.id,
	setId = setId,
	setCode = "MAMS",
	setName = "Magnificent Maestros",
	collectorNumber = number.toString(),
	providerRawCollectorNumber = number.toString(),
	identity = null,
	text = LocalizedText(language, "Card $number"),
	artwork = Artwork(
		id = SourceId(provider, "${language.code}-art-$number"),
		imageUrl = "https://example.test/$number.png",
		thumbnailUrl = null,
		artist = null,
		treatment = ArtworkTreatment.STANDARD,
		language = language,
	),
	attributes = CardAttributes(cost = number),
	classification = CardClassification(type = "Monster", rarity = "Common", domains = emptyList()),
	languages = LanguageCoverage(confirmed = setOf(language)),
	finishes = FinishCoverage(),
)
