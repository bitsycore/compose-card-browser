package com.bitsycore.toploader.data

import com.bitsycore.toploader.data.repository.Completion
import com.bitsycore.toploader.data.repository.KeptSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
The rounding and the estimate marker, which are the two ways a percentage lies.

A percentage is a ratio, and every ratio in this project has to be asked what each side counts --
see the slash trap in CLAUDE.md. These are the answers written down.
*/
class CompletionTest {

	// ==================
	// MARK: Rounding
	// ==================

	@Test
	fun `a set one card short is not a hundred percent`() {
		// 999/1000 is 99.9%, and rounding it to 100 would have the screen claim a complete set
		// while a card is missing. That is the single most important thing here.
		val vAlmost = Completion(heldCards = 999, totalCards = 1000, isEstimate = false)

		assertEquals(99, vAlmost.percent)
		assertEquals("99%", vAlmost.label)
	}

	@Test
	fun `a hundred percent means nothing is missing`() {
		assertEquals(100, Completion(1000, 1000, isEstimate = false).percent)
	}

	@Test
	fun `holding more than the catalogue claims is still a hundred and not more`() {
		// A stale set list is the ordinary cause: the source added cards and the cached catalogue
		// has not caught up. "105%" is not a thing a storage screen may say.
		assertEquals(100, Completion(105, 100, isEstimate = false).percent)
		assertEquals(1f, Completion(105, 100, isEstimate = false).fraction)
	}

	@Test
	fun `nothing held is zero rather than a crash`() {
		assertEquals(0, Completion(0, 500, isEstimate = false).percent)
		// And a denominator of zero does not divide.
		assertEquals(0, Completion(0, 0, isEstimate = false).percent)
		assertEquals(0f, Completion(0, 0, isEstimate = false).fraction)
	}

	// ==================
	// MARK: Saying which it is
	// ==================

	@Test
	fun `an estimate is drawn differently from a count`() {
		// "62%" and "~62%" are different claims. A screen that renders them the same is the thing
		// this codebase exists not to do.
		assertEquals("62%", Completion(62, 100, isEstimate = false).label)
		assertEquals("~62%", Completion(62, 100, isEstimate = true).label)
	}

	// ==================
	// MARK: One set
	// ==================

	@Test
	fun `a complete set is a hundred percent whatever the set list claims`() {
		// The source served every page. A catalogue that says 300 is stale, and a stale number is
		// not evidence against a completed fetch.
		val vSet = keptSet(cardCount = 252, isComplete = true, knownCardCount = 300)

		assertEquals(100, vSet.completion?.percent)
	}

	@Test
	fun `a partial set is measured against what the set list states`() {
		val vSet = keptSet(cardCount = 120, isComplete = false, knownCardCount = 252)

		assertEquals(47, vSet.completion?.percent)
		assertEquals(false, vSet.completion?.isEstimate)
	}

	@Test
	fun `a partial set with no stated size gets no percentage at all`() {
		// OPTCG states no card counts. Dividing by a number nobody supplied is the invention this
		// project does not make -- the screen shows the card count and stops.
		assertNull(keptSet(cardCount = 120, isComplete = false, knownCardCount = null).completion)
	}

	@Test
	fun `a stated size of zero is treated as unstated`() {
		// Some sources carry 0 for "we do not know" rather than omitting the field, and dividing
		// by it is both wrong and a crash.
		assertNull(keptSet(cardCount = 10, isComplete = false, knownCardCount = 0).completion)
	}

	private fun keptSet(cardCount: Int, isComplete: Boolean, knownCardCount: Int?) = KeptSet(
		provider = "p",
		setId = "s",
		languageCode = "en",
		label = "A set",
		cardCount = cardCount,
		bytes = 0,
		isComplete = isComplete,
		knownCardCount = knownCardCount,
	)

	@Test
	fun `the label never reads as a count when it is a guess`() {
		// A property the screen depends on: anything estimated carries the tilde.
		for (vHeld in listOf(0, 1, 49, 99, 100)) {
			assertTrue(
				Completion(vHeld, 100, isEstimate = true).label.startsWith("~"),
				"an estimate of $vHeld/100 lost its marker",
			)
		}
	}
}
