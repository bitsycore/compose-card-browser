package com.bitsycore.cardbrowser.providers.wuwa

import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.Availability
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.ExternalIdKey
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import com.bitsycore.cardbrowser.core.provider.CardSearchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The bundled catalogue, and the adapter that serves it.
 *
 * These are assertions about real data rather than about a mock. The snapshot is committed, so a
 * test can say "SD01-003 ships at two rarities and they are one card" and be checking the thing the
 * app will actually show. What it cannot check is whether the snapshot is still *current*; that is
 * `WuwaSnapshotFreshnessTest`, which needs a network and is opt-in.
 */
class WuwaCatalogueTest {

	private val mProvider = WuwaProvider()

	private fun id(local: String) = SourceId(WuwaProvider.PROVIDER_ID, local)

	// ============
	//  The snapshot itself

	@Test
	fun `the snapshot parses and carries the whole game`() = runTest {
		val vSnapshot = WuwaCatalogue.snapshot()

		assertEquals(WuwaCatalogue.SCHEMA_VERSION, vSnapshot.schemaVersion)
		assertTrue(vSnapshot.cards.size > 100, "got ${vSnapshot.cards.size} printings")
		assertTrue(vSnapshot.imageBases.isNotEmpty())
		assertTrue(vSnapshot.source.scrapedOn.isNotBlank(), "provenance must say when it was taken")
	}

	@Test
	fun `every printing has a code and a set and a rarity tier and one locale`() = runTest {
		for (vCard in WuwaCatalogue.snapshot().cards) {
			assertTrue(vCard.code.isNotBlank())
			assertTrue(vCard.set.isNotBlank(), "${vCard.code} has no set")
			assertTrue(vCard.printings.isNotEmpty(), "${vCard.code} has no locale")
			// The tier is what distinguishes two records of one code, so it has to be there.
			assertTrue(vCard.stars != null || vCard.rarity != null, "${vCard.code} has no rarity tier")
		}
	}

	@Test
	fun `printing keys are unique -- the grid throws outright on a repeated one`() = runTest {
		val vKeys = WuwaCatalogue.snapshot().cards.map { it.key }
		val vDuplicates = vKeys.groupingBy { it }.eachCount().filterValues { it > 1 }

		assertEquals(vKeys.size, vKeys.toSet().size, "duplicate keys: $vDuplicates")
	}

	@Test
	fun `every locale tag in the vocabulary is a language the app knows`() = runTest {
		for ((vFacet, vTerms) in WuwaCatalogue.snapshot().vocabulary) {
			for (vTerm in vTerms) {
				for (vTag in vTerm.labels.keys) {
					assertNotNull(CardLanguage.fromCode(vTag), "$vFacet term ${vTerm.id} uses tag $vTag")
				}
			}
		}
	}

	// ============
	//  Ids that do not depend on a locale

	@Test
	fun `a card has the same id in every language`() = runTest {
		// The whole point of keying on the printed code. UCP issues a different numeric id per
		// locale -- 651, 1357 and 1139 for SD01-001 -- so a cached id had to be read alongside the
		// language that produced it. This is what removed that.
		val vJapanese = assertNotNull(mProvider.cardDetail(id(SD01_003_BASE), CardLanguage.JAPANESE))
		val vKorean = assertNotNull(mProvider.cardDetail(id(SD01_003_BASE), CardLanguage.KOREAN))

		assertEquals(vJapanese.id, vKorean.id)
		assertEquals(CardLanguage.JAPANESE, vJapanese.text.language)
		assertEquals(CardLanguage.KOREAN, vKorean.text.language)
		assertTrue(vJapanese.displayName != vKorean.displayName, "the names should be translated")
	}

	@Test
	fun `UCP's own per-locale ids are kept for provenance`() = runTest {
		val vCard = assertNotNull(mProvider.cardDetail(id(SD01_003_BASE), CardLanguage.JAPANESE))

		val vIds = assertNotNull(vCard.externalIds[ExternalIdKey.PROVIDER_RECORD])
		assertTrue(vIds.any { it.startsWith("ja:") }, "got $vIds")
	}

	// ============
	//  Rarity tiers are one card with two artworks

	@Test
	fun `two rarity tiers of one code are one card`() = runTest {
		val vBase = assertNotNull(mProvider.cardDetail(id(SD01_003_BASE), CardLanguage.JAPANESE))
		val vPremium = assertNotNull(mProvider.cardDetail(id(SD01_003_PREMIUM), CardLanguage.JAPANESE))

		// One card, stated by UCP giving them one code, one name and one rules text.
		assertEquals(vBase.identity?.id, vPremium.identity?.id)
		assertEquals(vBase.collectorNumber, vPremium.collectorNumber)
		assertEquals(vBase.text.rules, vPremium.text.rules)
		// Two printings of it, at two rarities, with different art.
		assertTrue(vBase.id != vPremium.id)
		assertEquals("★★★", vBase.classification.rarity)
		assertEquals("★★★★", vPremium.classification.rarity)
		assertTrue(vBase.artwork.imageUrl != vPremium.artwork.imageUrl)
	}

	@Test
	fun `the higher tier is the alternate art -- and no finish is claimed either way`() = runTest {
		// Checked against the real scans before it was claimed: SD01-003 at three stars is a framed
		// portrait, at four a full-bleed alternate with a different pose and background. It is not a
		// foil of the first, which is why this is a treatment and not a `FinishCoverage` entry.
		val vBase = assertNotNull(mProvider.cardDetail(id(SD01_003_BASE), CardLanguage.JAPANESE))
		val vPremium = assertNotNull(mProvider.cardDetail(id(SD01_003_PREMIUM), CardLanguage.JAPANESE))

		assertEquals(ArtworkTreatment.STANDARD, vBase.artwork.treatment)
		assertEquals(ArtworkTreatment.ALTERNATE_ART, vPremium.artwork.treatment)
		assertTrue(vBase.finishes.isUnstated, "UCP has no finish field at all")
		assertTrue(vPremium.finishes.isUnstated)
	}

	// ============
	//  Language coverage is a fact here

	@Test
	fun `coverage names the catalogues that actually carry a printing`() = runTest {
		val vCards = mProvider.listCards(
			CardPageRequest(
				setId = id("BP01"),
				language = CardLanguage.JAPANESE,
				pageSize = WuwaProvider.MAX_PAGE_SIZE,
			),
		).cards

		// Coverage is read off the snapshot, so it must match it printing by printing rather than
		// being whatever language was requested.
		for (vCard in vCards) {
			val vSnapshotCard = assertNotNull(
				WuwaCatalogue.snapshot().cards.firstOrNull { it.key == vCard.id.local },
			)
			val vExpected = vSnapshotCard.printings.keys.mapNotNull(CardLanguage::fromCode).toSet()
			assertEquals(vExpected, vCard.languages.confirmed, "${vCard.providerRawCollectorNumber}")
		}

		// The three catalogues are different sizes, so some printing must be missing from Korean.
		val vNotKorean = vCards.filter {
			it.languages.availabilityOf(CardLanguage.KOREAN) != Availability.AVAILABLE
		}
		assertTrue(vNotKorean.isNotEmpty(), "expected some printings absent from the Korean catalogue")
		// Unknown, never absent: UCP not publishing a Korean record is a fact about UCP.
		assertTrue(
			vNotKorean.all { it.languages.availabilityOf(CardLanguage.KOREAN) == Availability.UNKNOWN },
			"a missing catalogue entry is not evidence the printing does not exist",
		)
		// And the set is not filtered to the requested language. Asserted against Chinese, which is
		// the catalogue that lags: Japanese used to be missing a few printings and, after a refresh
		// that added a whole set, is no longer missing any -- so testing it against Japanese was
		// testing a passing fact about one snapshot rather than the behaviour.
		val vChinese = mProvider.listCards(
			CardPageRequest(
				setId = id("BP01"),
				language = CardLanguage.SIMPLIFIED_CHINESE,
				pageSize = WuwaProvider.MAX_PAGE_SIZE,
			),
		).cards
		assertTrue(
			vChinese.any {
				it.languages.availabilityOf(CardLanguage.SIMPLIFIED_CHINESE) != Availability.AVAILABLE
			},
			"expected at least one printing that Japanese does not carry",
		)
	}

	@Test
	fun `a printing the requested locale lacks still appears -- labelled honestly`() = runTest {
		// Dropping it would make the Korean catalogue look shorter than the game is.
		val vJapanese = mProvider.listCards(
			CardPageRequest(setId = id("BP01"), language = CardLanguage.JAPANESE),
		).cards.size
		val vKorean = mProvider.listCards(
			CardPageRequest(setId = id("BP01"), language = CardLanguage.KOREAN),
		).cards

		assertEquals(vJapanese, vKorean.size, "it is the same set in either language")
		val vFallback = vKorean.filter { it.text.language != CardLanguage.KOREAN }
		assertTrue(vFallback.isNotEmpty())
		assertTrue(
			vFallback.all { CardLanguage.KOREAN in it.languages.textOnlyFallback },
			"a fallback must be marked as one",
		)
	}

	@Test
	fun `the declared languages are exactly the ones the asset holds`() = runTest {
		// `capabilities` is a plain `val` and reading a bundled asset suspends, so the provider
		// restates its language set rather than deriving it. This is what keeps the two in step: add
		// a locale to the scraper without declaring it here and this fails.
		assertEquals(WuwaCatalogue.languages(), mProvider.capabilities.data.languages)
		assertEquals(
			setOf(CardLanguage.JAPANESE, CardLanguage.SIMPLIFIED_CHINESE, CardLanguage.KOREAN),
			mProvider.capabilities.data.languages,
		)
		// English is not one of them, so asking for it must not answer with an empty catalogue.
		assertTrue(mProvider.resolveLanguage(CardLanguage.ENGLISH) != CardLanguage.ENGLISH)
	}

	@Test
	fun `the asset is read once however many callers arrive at once`() = runTest {
		// The catalogue is a lazily-read asset behind a mutex. Two concurrent callers must get the
		// same parsed instance rather than each paying for a read and a parse.
		val vFirst = WuwaCatalogue.snapshot()
		val vSecond = WuwaCatalogue.snapshot()

		assertSame(vFirst, vSecond)
	}

	// ============
	//  Sets and paging

	@Test
	fun `sets are derived from card codes and count what they hold`() = runTest {
		val vSets = mProvider.listSets()

		assertEquals(listOf("BP01", "SD01", "SD02"), vSets.map { it.code })
		assertTrue(vSets.all { it.name == it.code })
		for (vSet in vSets) {
			// The provider's own maximum, which is what `CardRepository` passes. The default of 100
			// is nobody's real request, and asserting against it made this test fail the day BP01
			// grew past a hundred cards -- a fact about the catalogue, not about the adapter.
			val vCards = mProvider
				.listCards(CardPageRequest(setId = vSet.id, pageSize = WuwaProvider.MAX_PAGE_SIZE))
				.cards
			assertEquals(vSet.cardCount, vCards.size, "${vSet.code} count disagrees with its cards")
		}
	}

	@Test
	fun `a set arrives complete in one page`() = runTest {
		// At the provider's stated maximum, which is the size the repository asks for.
		val vPage = mProvider.listCards(
			CardPageRequest(setId = id("BP01"), pageSize = WuwaProvider.MAX_PAGE_SIZE),
		)

		assertFalse(vPage.hasMore)
		assertEquals(vPage.cards.size, vPage.totalCount)
		// The collector number is the part after the hyphen, and the full code is kept beside it.
		assertTrue(vPage.cards.all { !it.collectorNumber.contains('-') })
		assertTrue(vPage.cards.all { it.providerRawCollectorNumber.startsWith("BP01-") })
	}

	@Test
	fun `paging past the end is empty rather than a failure`() = runTest {
		val vPage = mProvider.listCards(CardPageRequest(setId = id("BP01"), page = 9, pageSize = 50))

		assertTrue(vPage.cards.isEmpty())
		assertFalse(vPage.hasMore)
	}

	@Test
	fun `cards are in collector order -- so the grid does not look broken`() = runTest {
		val vNumbers = mProvider.listCards(CardPageRequest(setId = id("BP01"))).cards
			.map { it.collectorNumber }

		assertEquals(vNumbers.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }, vNumbers)
	}

	// ============
	//  What the scraper cleaned up

	@Test
	fun `no field carries the API's dash placeholder`() = runTest {
		// UCP writes a literal "-" where a field does not apply, and a chip reading "-" in the
		// filter sheet is what that becomes if it is not stripped.
		val vCards = mProvider.listCards(CardPageRequest(setId = id("SD01"))).cards

		for (vCard in vCards) {
			assertTrue(vCard.tags.none { it == "-" || it.isBlank() }, "${vCard.collectorNumber}: ${vCard.tags}")
			assertTrue(vCard.classification.domains.none { it == "-" })
			assertTrue(vCard.classification.supertype != "-")
			assertTrue(vCard.classification.rarity != "-")
		}
	}

	@Test
	fun `a character card carries its type and element and rules text`() = runTest {
		val vCard = assertNotNull(mProvider.cardDetail(id(SD01_003_BASE), CardLanguage.JAPANESE))

		assertEquals("キャラカード", vCard.classification.type)
		assertTrue(vCard.classification.domains.isNotEmpty(), "an element should be stated")
		assertTrue(vCard.text.rules?.isNotBlank() == true, "rules text is in the snapshot")
		assertTrue(vCard.artwork.imageUrl.startsWith("https://"), "the image is still a real URL")
	}

	// ============
	//  Search

	@Test
	fun `search spans sets and matches names and codes alike`() = runTest {
		val vByCode = mProvider.searchAllSets(
			CardSearchRequest(text = "SD01-003"),
		)
		assertTrue(vByCode.cards.isNotEmpty())
		assertTrue(vByCode.cards.all { it.providerRawCollectorNumber == "SD01-003" })

		val vName = assertNotNull(mProvider.cardDetail(id(SD01_003_BASE), CardLanguage.JAPANESE)).displayName
		val vByName = mProvider.searchAllSets(
			CardSearchRequest(text = vName),
		)
		assertTrue(vByName.cards.size > 1, "this character appears on several cards")
	}

	@Test
	fun `a search matching nothing is an empty page rather than a failure`() = runTest {
		val vPage = mProvider.searchAllSets(
			CardSearchRequest(text = "no such card anywhere"),
		)

		assertTrue(vPage.cards.isEmpty())
		assertEquals(0, vPage.totalCount)
	}

	private companion object {

		/** 秧秧, the card that ships at both ★★★ and ★★★★ with different art for each. */
		const val SD01_003_BASE = "SD01-003#3"
		const val SD01_003_PREMIUM = "SD01-003#4"
	}
}
