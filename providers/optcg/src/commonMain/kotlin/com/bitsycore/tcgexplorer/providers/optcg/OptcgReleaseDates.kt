package com.bitsycore.tcgexplorer.providers.optcg

import kotlinx.datetime.LocalDate

/**
 * English release dates for One Piece sets, which the API does not publish.
 *
 * `optcgapi.com` states a set's name and its id and nothing else -- the only date any endpoint
 * carries is `date_scraped`, which is when the scraper ran. Without a date every One Piece set
 * sorts into the undated group at the bottom of the list, in code order, which is neither
 * chronological nor useful.
 *
 * ## This is curated, not stated
 *
 * These dates are **not** the source's. They were taken from a published release calendar on
 * 2026-09-14 and four of them -- OP-01, the OP14 box, OP-16 and OP-17 -- cross-checked against a
 * second listing. They are recorded here rather than guessed at a call site so that the one place
 * they live can say where they came from, which is the difference between a curated fact and an
 * invented one.
 *
 * The English schedule is the one mapped, because this source serves the English printing. Japanese
 * dates run three to four months earlier for the older sets and had nearly converged by OP-14, so
 * the two lists are not interchangeable and no arithmetic converts one into the other.
 *
 * ## Ids are the source's, quirks included
 *
 * `OP14-EB04` and `OP15-EB04` are not typos. Japan's EB-04 never got a standalone English box;
 * Bandai folded its content into the English OP-14 and OP-15 releases and gave them combined codes,
 * and this API uses those codes. A table keyed on `OP-14` would have missed exactly the sets a
 * reader is most likely to be looking for.
 *
 * ## A set that is not here
 *
 * Answers `null`, which is what the API already answered for every set, so nothing regresses: an
 * undated set sorts after every dated one -- see `CardRepository.SET_ORDER`. A new set therefore
 * appears at the bottom until it is added here, rather than being given a wrong date to make it
 * sort. `OptcgSetDatesLiveSmokeTest` fails when the API serves a set this table does not know.
 */
internal object OptcgReleaseDates {

	/** The English release date for [setId], or `null` for a set this table does not know. */
	fun of(setId: String): LocalDate? = DATES[setId.uppercase()]

	/** Every set id this table knows, for the live check that nothing new has appeared. */
	val KNOWN_IDS: Set<String> get() = DATES.keys

	private val DATES: Map<String, LocalDate> = mapOf(
		"OP-01" to LocalDate(2022, 12, 2),
		"OP-02" to LocalDate(2023, 3, 10),
		"OP-03" to LocalDate(2023, 6, 30),
		"OP-04" to LocalDate(2023, 9, 22),
		"OP-05" to LocalDate(2023, 12, 8),
		"OP-06" to LocalDate(2024, 3, 15),
		"EB-01" to LocalDate(2024, 5, 3),
		"OP-07" to LocalDate(2024, 6, 28),
		"OP-08" to LocalDate(2024, 9, 13),
		"PRB-01" to LocalDate(2024, 11, 8),
		"OP-09" to LocalDate(2024, 12, 13),
		"OP-10" to LocalDate(2025, 3, 21),
		"EB-02" to LocalDate(2025, 5, 9),
		"OP-11" to LocalDate(2025, 6, 6),
		"OP-12" to LocalDate(2025, 8, 22),
		"PRB-02" to LocalDate(2025, 10, 3),
		"OP-13" to LocalDate(2025, 11, 7),
		"OP14-EB04" to LocalDate(2026, 1, 16),
		"EB-03" to LocalDate(2026, 2, 20),
		"OP15-EB04" to LocalDate(2026, 4, 3),
		"OP-16" to LocalDate(2026, 6, 12),
		"OP-17" to LocalDate(2026, 8, 28),
	)
}
