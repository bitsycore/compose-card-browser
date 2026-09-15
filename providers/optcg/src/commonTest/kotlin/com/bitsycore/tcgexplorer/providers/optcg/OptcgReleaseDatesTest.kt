package com.bitsycore.tcgexplorer.providers.optcg

import com.bitsycore.tcgexplorer.core.model.ProviderId
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
The curated release dates, and the two ways a hand-written table goes wrong.

It can hold a typo -- a year or a month that puts a set in the wrong place -- and it can be keyed on
ids the source does not use. Both are silent: the app would simply sort a set oddly, which is what
having no dates did in the first place.
*/
class OptcgReleaseDatesTest {

	@Test
	fun `a set carries its curated date`() {
		assertEquals(LocalDate(2022, 12, 2), OptcgReleaseDates.of("OP-01"))
		assertEquals(LocalDate(2026, 6, 12), OptcgReleaseDates.of("OP-16"))
	}

	@Test
	fun `the combined EB-04 boxes are keyed the way the source names them`() {
		// Japan's EB-04 never got a standalone English box: Bandai folded it into the English OP-14
		// and OP-15 and this API uses the combined codes. A table keyed on "OP-14" would miss the
		// two sets a reader is most likely to look for -- they are the newest.
		assertEquals(LocalDate(2026, 1, 16), OptcgReleaseDates.of("OP14-EB04"))
		assertEquals(LocalDate(2026, 4, 3), OptcgReleaseDates.of("OP15-EB04"))
		assertNull(OptcgReleaseDates.of("OP-14"), "the source does not use this id")
	}

	@Test
	fun `a set the table does not know answers null rather than a guess`() {
		// Which sorts it after every dated set, exactly as before this table existed.
		assertNull(OptcgReleaseDates.of("OP-99"))
		assertNull(OptcgReleaseDates.of(""))
	}

	@Test
	fun `the main line runs forward in time`() {
		// The typo check. OP-01 through OP-13 are sequential in both name and release, so a wrong
		// year or a swapped month shows up here as an ordering that goes backwards.
		val vMain = (1..13).map { vIndex ->
			val vId = "OP-" + vIndex.toString().padStart(2, '0')
			vId to (OptcgReleaseDates.of(vId) ?: error("$vId is missing from the table"))
		}
		for ((vLeft, vRight) in vMain.zipWithNext()) {
			assertTrue(
				vLeft.second < vRight.second,
				"${vLeft.first} (${vLeft.second}) is not before ${vRight.first} (${vRight.second})",
			)
		}
	}

	@Test
	fun `every date is plausible for this game`() {
		// One Piece began in English in December 2022, so nothing can precede it -- and a date far
		// in the future is a typed year rather than an announcement.
		val vFirst = LocalDate(2022, 12, 1)
		val vCeiling = LocalDate(2030, 1, 1)
		for (vId in OptcgReleaseDates.KNOWN_IDS) {
			val vDate = OptcgReleaseDates.of(vId)!!
			assertTrue(vDate > vFirst, "$vId predates the game")
			assertTrue(vDate < vCeiling, "$vId is implausibly far ahead")
		}
	}

	@Test
	fun `the mapper puts the date on the set`() {
		val vSet = OptcgMapper.toSet(
			OptcgSetDto(setId = "OP-01", setName = "Romance Dawn"),
			ProviderId("optcg"),
		)

		assertEquals(LocalDate(2022, 12, 2), vSet?.releaseDate)
	}
}
