package com.bitsycore.cardbrowser.providers.wuwa

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.model.ProviderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The UCP mapping. Deterministic and offline. */
class WuwaMapperTest {

	private val mProvider = ProviderId("ucp-wuwa")

	private val mSet = WuwaMapper.toSet("SD01", 25, mProvider)

	@Test
	fun `sets are derived from the code prefix -- since the API has none`() {
		assertEquals("SD01", WuwaMapper.setCodeOf("SD01-003"))
		assertEquals("BP01", WuwaMapper.setCodeOf("BP01-024"))
		assertNull(WuwaMapper.setCodeOf(""))
		// The name is the code: the product-name mapping could not be verified, so it is not used.
		assertEquals("SD01", mSet.name)
		assertEquals(Game.WUTHERING_WAVES, mSet.game)
		assertNull(mSet.releaseDate)
	}

	@Test
	fun `the collector number is the part after the hyphen`() {
		assertEquals("003", WuwaMapper.collectorNumberOf("SD01-003"))
		// A code with no hyphen is used whole rather than becoming an empty number.
		assertEquals("ODD", WuwaMapper.collectorNumberOf("ODD"))
	}

	@Test
	fun `two artworks sharing a code stay two distinct cards`() {
		// The crash this guards. `code` is not unique -- 36 of the 87 codes in the catalogue are
		// carried by two records with different images. Keying on it collapsed them into one id,
		// and LazyVerticalGrid throws "Key ... was already used" rather than degrading.
		val vFirst = WuwaMapper.toPrinting(
			WuwaCardBriefDto(id = 654, code = "SD01-003", name = "秧秧", cardType = "character", img = "a.webp"),
			mProvider,
			mSet,
			CardLanguage.JAPANESE,
		)
		val vSecond = WuwaMapper.toPrinting(
			WuwaCardBriefDto(id = 1884, code = "SD01-003", name = "秧秧", cardType = "character", img = "b.webp"),
			mProvider,
			mSet,
			CardLanguage.JAPANESE,
		)

		assertNotNull(vFirst)
		assertNotNull(vSecond)
		assertTrue(vFirst.id != vSecond.id, "Two records must not share an id")
		assertTrue(vFirst.dedupeKey != vSecond.dedupeKey, "They are two cards, not one duplicated")
		// The artwork ids must differ too, or the shared-element transition targets the wrong tile.
		assertTrue(vFirst.artwork.id != vSecond.artwork.id)
		// Both still show the same printed number, which is the truth.
		assertEquals("003", vFirst.collectorNumber)
		assertEquals("003", vSecond.collectorNumber)
	}

	@Test
	fun `the id is the API's numeric key -- which is what the detail endpoint takes`() {
		val vCard = WuwaMapper.toPrinting(
			WuwaCardBriefDto(id = 651, code = "SD01-001", name = "漂泊者（女）"),
			mProvider,
			mSet,
			CardLanguage.JAPANESE,
		)

		assertNotNull(vCard)
		assertEquals("651", vCard.id.local)
		// The printed code is not lost -- it is what a reader sees on the card.
		assertEquals("SD01-001", vCard.providerRawCollectorNumber)
	}

	@Test
	fun `the dash placeholder never becomes a value`() {
		// The API writes a literal "-" where a field does not apply. Shown as-is it would produce a
		// filter chip reading "-".
		val vCard = WuwaMapper.toPrinting(
			WuwaCardDetailDto(
				id = 651,
				code = "SD01-001",
				name = "漂泊者（女）",
				typeName = "キャラカード",
				weaponTypeName = "迅刀",
				forceName = "-",
				attrName = "回折",
				featureName = "-",
				characterName = "",
				rarityName = "★★★",
				colorName = "-",
				level = "2",
				obtain = "SD01",
				info = "■【自分のターン開始時】カード1枚を引く。",
			),
			mProvider,
			set = null,
			language = CardLanguage.JAPANESE,
		)

		assertNotNull(vCard)
		assertEquals(listOf("回折"), vCard.classification.domains)
		assertEquals(listOf("迅刀"), listOfNotNull(vCard.classification.supertype))
		assertTrue("-" !in vCard.tags, "Got ${vCard.tags}")
		assertTrue("" !in vCard.tags, "An empty string is not a tag either")
		assertEquals("★★★", vCard.classification.rarity)
	}

	@Test
	fun `a character card with no cost gets null rather than zero`() {
		val vCard = WuwaMapper.toPrinting(
			WuwaCardDetailDto(id = 651, code = "SD01-001", name = "漂泊者（女）", fee = ""),
			mProvider,
			set = null,
			language = CardLanguage.JAPANESE,
		)

		assertNotNull(vCard)
		assertNull(vCard.attributes.energy)
	}

	@Test
	fun `Japanese is confirmed and nothing else is denied`() {
		val vCard = WuwaMapper.toPrinting(
			WuwaCardBriefDto(id = 651, code = "SD01-001", name = "漂泊者（女）"),
			mProvider,
			mSet,
			CardLanguage.JAPANESE,
		)

		assertNotNull(vCard)
		assertEquals(setOf(CardLanguage.JAPANESE), vCard.languages.confirmed)
		// Simplified Chinese is served and is not one of the app's four; nothing is marked absent.
		assertTrue(vCard.languages.absent.isEmpty())
	}
}
