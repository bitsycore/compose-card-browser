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
import com.bitsycore.cardbrowser.core.provider.CardPage
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import com.bitsycore.cardbrowser.core.provider.CardProvider
import com.bitsycore.cardbrowser.core.provider.CardSortField
import com.bitsycore.cardbrowser.core.provider.DataCapabilities
import com.bitsycore.cardbrowser.core.provider.FilterSupport
import com.bitsycore.cardbrowser.core.provider.ProviderCapabilities
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.core.provider.ProviderRoute
import com.bitsycore.cardbrowser.data.cache.InMemoryMetadataStore
import com.bitsycore.cardbrowser.data.cache.SqlSetRecordStore
import com.bitsycore.cardbrowser.data.repository.CardRepository
import com.bitsycore.cardbrowser.sqlstore.DesktopDriverFactory
import com.bitsycore.cardbrowser.sqlstore.SqlCardStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * How long `localSetFacts` takes at Magic's scale, against a real database.
 *
 * It issues one query per set for three of its five answers, which reads badly and may cost
 * nothing: the store answers a count in microseconds and 988 of them may still be invisible next to
 * drawing the list. **The number decides whether it is worth changing, not the shape of the code.**
 *
 * **Measured on 2026-09-13: 80.7 ms** for 988 sets, half of them held in two languages, against a
 * real in-memory SQLite. That was judged not worth restructuring for -- see TODO.md. Re-run it
 * before deciding otherwise; a phone will be slower than this and the answer could change.
 *
 * `@Ignore`d like `SqlStoreBench`: it is a measurement, not a check, and its result is a sentence in
 * a commit rather than an assertion.
 *
 * ```
 * ./gradlew :data:desktopTest --tests '*LocalSetFactsBench*'
 * ```
 */
class LocalSetFactsBench {

	@Test
	@Ignore("A measurement, not a check. Remove the annotation to run it.")
	fun `time localSetFacts over a Magic-sized catalogue`() = runBlocking {
		val vDriver = DesktopDriverFactory().create(null)
		val vStore = SqlCardStore(vDriver)
		val vSetStore = SqlSetRecordStore(vStore, Dispatchers.IO)
		val vProvider = BenchProvider()
		val vRepository = CardRepository(
			mRegistry = ProviderRegistry(
				providers = listOf(vProvider),
				routes = listOf(ProviderRoute(BenchGame.id, vProvider.id)),
			),
			mCache = InMemoryMetadataStore(),
			mSetStore = vSetStore,
			mClock = { 0L },
		)

		// Magic's shape: 988 sets. Every other one is actually held, in two languages, so the
		// per-set queries have something to find rather than missing on an empty table.
		val vSets = (1..SETS).map { vIndex ->
			CardSet(
				id = SourceId(vProvider.id, "s$vIndex"),
				game = BenchGame.id,
				code = "S$vIndex",
				name = "Set $vIndex",
				cardCount = CARDS_PER_SET,
				releaseDate = null,
				languages = setOf(CardLanguage.ENGLISH, CardLanguage.FRENCH),
			)
		}
		for ((vIndex, vSet) in vSets.withIndex()) {
			if (vIndex % 2 != 0) continue
			for (vLanguage in listOf(CardLanguage.ENGLISH, CardLanguage.FRENCH)) {
				vSetStore.write(
					provider = vProvider.id,
					setId = vSet.id,
					language = vLanguage,
					game = BenchGame.id,
					label = vSet.name,
					cards = (1..CARDS_PER_SET).map { card(vProvider.id, vSet, it, vLanguage) },
					isComplete = true,
					fetchedAtEpochMillis = 0L,
				)
			}
		}

		// Warm, so the figure is the queries rather than the first page fault.
		vRepository.localSetFacts(BenchGame.id, vSets, CardLanguage.ENGLISH)

		val vMark = TimeSource.Monotonic.markNow()
		repeat(RUNS) { vRepository.localSetFacts(BenchGame.id, vSets, CardLanguage.ENGLISH) }
		val vEach = vMark.elapsedNow() / RUNS

		println("localSetFacts over $SETS sets (${SETS / 2} held, two languages each): $vEach")
		vDriver.close()
	}

	private fun card(
		provider: ProviderId,
		set: CardSet,
		number: Int,
		language: CardLanguage,
	) = CardPrinting(
		id = SourceId(provider, "${set.id.local}-$number-${language.code}"),
		game = BenchGame.id,
		setId = set.id,
		setCode = set.code,
		setName = set.name,
		collectorNumber = number.toString(),
		providerRawCollectorNumber = number.toString(),
		identity = null,
		text = LocalizedText(language, "Card $number"),
		artwork = Artwork(
			id = SourceId(provider, "a$number"),
			imageUrl = "https://example.test/$number.png",
			thumbnailUrl = null,
			artist = null,
			treatment = ArtworkTreatment.STANDARD,
			language = language,
		),
		attributes = CardAttributes(cost = number % 8),
		classification = CardClassification(type = "Unit", rarity = "Common"),
		languages = LanguageCoverage.ENGLISH_ONLY,
		finishes = FinishCoverage(),
	)

	private object BenchGame : GameProfile {

		override val id: GameId = GameId("bench")

		override val displayName: String = "Bench"

		override val vocabulary: GameVocabulary = GameVocabulary(domain = "Domain", cost = "Cost")

		override val rarityLadder: List<String> = listOf("Common")
	}

	/** Never asked for anything: every call under test reads the disk. */
	private class BenchProvider : CardProvider<GameProfile> {

		override val id: ProviderId = ProviderId("bench")

		override val displayName: String = "Bench"

		override val game: GameProfile = BenchGame

		override val capabilities = ProviderCapabilities(
			filtering = FilterSupport(remote = emptySet(), localOnly = emptySet()),
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
			maxPageSize = 1000,
		)

		override suspend fun listSets(language: CardLanguage?): List<CardSet> = emptyList()

		override suspend fun listCards(request: CardPageRequest): CardPage =
			CardPage(emptyList(), 1, 0, 0, false)

		override suspend fun cardDetail(id: SourceId, language: CardLanguage?): CardPrinting? = null
	}

	private companion object {

		/** Magic's catalogue, near enough: 988 sets on 2026-09-13. */
		const val SETS = 988

		/** Small on purpose. This measures the per-set queries, not the write. */
		const val CARDS_PER_SET = 4

		const val RUNS = 5
	}
}
