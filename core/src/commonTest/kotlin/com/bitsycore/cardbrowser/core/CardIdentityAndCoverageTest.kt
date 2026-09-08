package com.bitsycore.cardbrowser.core

import com.bitsycore.cardbrowser.core.model.Availability
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CollectorNumberComparator
import com.bitsycore.cardbrowser.core.model.Finish
import com.bitsycore.cardbrowser.core.model.FinishCoverage
import com.bitsycore.cardbrowser.core.model.LanguageCoverage
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The identity and coverage rules.
 *
 * These are the invariants that keep the app from claiming things the data does not support, so
 * they are tested directly rather than only through a screen.
 */
class CardIdentityAndCoverageTest {

	private val mRiftcodex = ProviderId("riftcodex")
	private val mOther = ProviderId("someotherdb")

	// ============
	//  Source-qualified ids

	@Test
	fun `same local id from two providers is two different ids`() {
		val vLeft = SourceId(mRiftcodex, "OGN")
		val vRight = SourceId(mOther, "OGN")

		assertNotEquals(vLeft, vRight)
		assertNotEquals(vLeft.qualified, vRight.qualified)
	}

	@Test
	fun `a qualified id round-trips`() {
		val vOriginal = SourceId(mRiftcodex, "69bc5bd8d308c64675ca8816")

		assertEquals(vOriginal, SourceId.parse(vOriginal.qualified))
	}

	@Test
	fun `a local part containing a colon survives parsing`() {
		// Mongo ids do not contain colons, but a provider's ids might, and splitting on the last
		// separator instead of the first would silently truncate them.
		val vOriginal = SourceId(mRiftcodex, "set:OGN:variant")

		assertEquals(vOriginal, SourceId.parse(vOriginal.qualified))
	}

	@Test
	fun `a malformed qualified id parses to null rather than throwing`() {
		assertNull(SourceId.parse("nocolon"))
		assertNull(SourceId.parse(":leadingcolon"))
		assertNull(SourceId.parse("trailingcolon:"))
	}

	@Test
	fun `a blank id is rejected at construction`() {
		assertFailsWith<IllegalArgumentException> { SourceId(mRiftcodex, "") }
		assertFailsWith<IllegalArgumentException> { ProviderId(" ") }
	}

	// ============
	//  Language coverage

	@Test
	fun `an unstated language is unknown -- not unavailable`() {
		// This is the central distinction. Riftcodex says nothing about Korean, and the app must
		// not turn that silence into "no Korean printing exists".
		val vCoverage = LanguageCoverage.ENGLISH_ONLY

		assertEquals(Availability.AVAILABLE, vCoverage.availabilityOf(CardLanguage.ENGLISH))
		assertEquals(Availability.UNKNOWN, vCoverage.availabilityOf(CardLanguage.KOREAN))
		assertEquals(Availability.UNKNOWN, vCoverage.availabilityOf(CardLanguage.FRENCH))
		assertEquals(Availability.UNKNOWN, vCoverage.availabilityOf(CardLanguage.JAPANESE))
	}

	@Test
	fun `a language stated absent is unavailable -- which is not the same as unknown`() {
		val vCoverage = LanguageCoverage(
			confirmed = setOf(CardLanguage.ENGLISH),
			absent = setOf(CardLanguage.KOREAN),
		)

		assertEquals(Availability.UNAVAILABLE, vCoverage.availabilityOf(CardLanguage.KOREAN))
		assertEquals(Availability.UNKNOWN, vCoverage.availabilityOf(CardLanguage.FRENCH))
	}

	@Test
	fun `preferring French over an English-only record resolves to English`() {
		// The exact scenario the brief calls out: choosing French must not relabel an English
		// record. Resolution answers English, and the caller can see it differs from the request.
		val vShown = LanguageCoverage.ENGLISH_ONLY.resolve(
			listOf(CardLanguage.FRENCH, CardLanguage.JAPANESE, CardLanguage.ENGLISH),
		)

		assertEquals(CardLanguage.ENGLISH, vShown)
	}

	@Test
	fun `preference order is honoured when more than one language is confirmed`() {
		val vCoverage = LanguageCoverage(
			confirmed = setOf(CardLanguage.ENGLISH, CardLanguage.FRENCH, CardLanguage.JAPANESE),
		)

		assertEquals(CardLanguage.FRENCH, vCoverage.resolve(CardLanguage.PREFERENCE_ORDER))
		assertEquals(
			CardLanguage.JAPANESE,
			vCoverage.resolve(listOf(CardLanguage.JAPANESE, CardLanguage.ENGLISH)),
		)
	}

	@Test
	fun `a record confirming no language resolves to null rather than defaulting to English`() {
		// Silently defaulting to English here would be the app inventing a fact.
		assertNull(LanguageCoverage().resolve())
		assertTrue(LanguageCoverage().isUnstated)
	}

	// ============
	//  Finish coverage

	@Test
	fun `a provider with no finish field reports every finish as unknown`() {
		val vCoverage = FinishCoverage()

		assertTrue(vCoverage.isUnstated)
		Finish.entries.forEach { vFinish ->
			assertEquals(Availability.UNKNOWN, vCoverage.availabilityOf(vFinish))
		}
	}

	@Test
	fun `a confirmed finish is available and an absent one is unavailable`() {
		val vCoverage = FinishCoverage(
			confirmed = setOf(Finish.NON_FOIL),
			absent = setOf(Finish.ETCHED),
		)

		assertEquals(Availability.AVAILABLE, vCoverage.availabilityOf(Finish.NON_FOIL))
		assertEquals(Availability.UNAVAILABLE, vCoverage.availabilityOf(Finish.ETCHED))
		assertEquals(Availability.UNKNOWN, vCoverage.availabilityOf(Finish.FOIL))
	}

	// ============
	//  Collector numbers

	@Test
	fun `collector numbers sort naturally rather than as text`() {
		val vSorted = listOf("10", "2", "1", "100", "20")
			.map { TestCards.printing(collectorNumber = it) }
			.sortedWith(CollectorNumberComparator)
			.map { it.collectorNumber }

		assertEquals(listOf("1", "2", "10", "20", "100"), vSorted)
	}

	@Test
	fun `a starred collector number sorts after its plain twin`() {
		// Real Riftbound data: Origins 299 exists as both `299` (Overnumbered) and `299*`
		// (Signature). They must be adjacent and in a stable order, not interleaved with 300.
		val vSorted = listOf("300", "299*", "299", "301")
			.map { TestCards.printing(collectorNumber = it) }
			.sortedWith(CollectorNumberComparator)
			.map { it.collectorNumber }

		assertEquals(listOf("299", "299*", "300", "301"), vSorted)
	}

	@Test
	fun `letter suffixes sort after the bare number`() {
		val vSorted = listOf("10b", "10", "10a", "9")
			.map { TestCards.printing(collectorNumber = it) }
			.sortedWith(CollectorNumberComparator)
			.map { it.collectorNumber }

		assertEquals(listOf("9", "10", "10a", "10b"), vSorted)
	}

	@Test
	fun `an absurdly long collector number does not crash the comparator`() {
		val vHuge = "9".repeat(40)
		val vSorted = listOf(vHuge, "1")
			.map { TestCards.printing(collectorNumber = it) }
			.sortedWith(CollectorNumberComparator)
			.map { it.collectorNumber }

		assertEquals(listOf("1", vHuge), vSorted)
	}
}
