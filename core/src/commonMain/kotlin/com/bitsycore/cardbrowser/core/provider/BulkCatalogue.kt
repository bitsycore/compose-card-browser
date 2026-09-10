package com.bitsycore.cardbrowser.core.provider

import com.bitsycore.cardbrowser.core.model.CardPrinting
import kotlinx.datetime.LocalDate

/**
 * A source that can hand over its whole catalogue in one download.
 *
 * Optional, and implemented by a `CardProvider` in addition to the ordinary contract rather than
 * instead of it. Most sources have nothing like this; the ones that do publish a periodic dump
 * precisely so that clients stop walking their API a page at a time.
 *
 * ## What it is for, and what it is not for
 *
 * One thing only: **downloading a whole game.** Today that means one request per set, which for
 * Magic is 988 of them against a service run on donations. A bulk file replaces the lot with a
 * single transfer, and is the reason Scryfall publishes one.
 *
 * It is emphatically *not* a faster way to open one set. The file is the entire catalogue, so
 * fetching 75 MB to read a set of 350 cards is far worse than the request it would replace. The
 * per-set path stays exactly as it is and remains what browsing uses.
 *
 * It also does nothing at all for images. No source here bulk-ships artwork, and artwork is where
 * the bytes actually are -- a 350-card set is roughly 33 MB of pictures against 0.3 MB of records.
 * A bulk import makes the *records* cheap to obtain and leaves the images exactly as expensive.
 *
 * ## Why it streams
 *
 * Measured on 2026-09-10: Scryfall's `default_cards` is 74.6 MB gzipped and **598 MB** of JSON,
 * about 5.4 KB per record across 63 fields. Nothing may hold that in memory, so [streamAll] hands
 * over one card at a time and the caller is expected to write as it goes.
 */
interface BulkCatalogue {

	/**
	 * The dumps this source publishes that the app can use, cheapest first. Empty when none.
	 *
	 * A list rather than one, because Scryfall publishes two the app can use and they are a very
	 * different purchase: 78 MB of one printing per card in its printed language, or 393 MB of
	 * every card in every language. Measured 2026-09-11.
	 *
	 * Fetched separately from the data, so a size can be shown *before* anything is downloaded.
	 * A dialog that says "this is 78 MB" is the difference between an informed choice and a
	 * surprise on a phone bill -- and where there are two, saying which is which is the
	 * difference between a choice and a lottery.
	 *
	 * Cheapest first is the order a menu should offer, and the first entry is the default.
	 */
	suspend fun bulkVariants(): List<BulkSummary>

	/**
	 * Every card the source holds, one at a time, in whatever order the file supplies.
	 *
	 * Not a list and not a `Flow` of lists: the point is that no caller ever holds more than one
	 * record, and a suspending callback is the plainest way to say so.
	 *
	 * There is deliberately **no language parameter**. A dump is a file, not a query: it contains
	 * whatever languages it contains, and asking for one would either be ignored or answered with a
	 * filter the caller could apply itself. Each card carries the language its own record states,
	 * so the caller can store them apart -- which is what the cache needs, being keyed per language.
	 *
	 * That absence is the fix for a real bug. The parameter used to be here, callers passed null,
	 * and `resolveLanguage(null)` walks the app's preference order -- which for a source offering
	 * all eleven languages resolves to **French**. So an overwhelmingly English file was imported,
	 * cached and reported as French. Measured on Scryfall's `default_cards`: 8780 English records
	 * in the first sample against 92 Spanish, 47 Japanese, 27 French and a handful of others.
	 *
	 * @param variantId which dump, by [BulkSummary.id]. An unknown id fetches nothing rather
	 *   than silently falling back to another, because the two differ by 315 MB
	 * @param onCard called once per card. Suspending, so a caller can write to disk without
	 *   buffering, and back-pressure is simply the callback taking its time
	 * @param onBytes called as the transfer progresses, with bytes so far and the total when the
	 *   source states one. A 78 MB download with no progress is indistinguishable from a hang
	 */
	suspend fun streamAll(
		variantId: String,
		onBytes: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
		onCard: suspend (CardPrinting) -> Unit,
	)
}

/**
 * What a source says about its dump before it is fetched.
 *
 * @property compressedBytes the transfer size, which is the number a user cares about
 * @property updatedAt the day the source last rebuilt it, so a screen can say how fresh it is
 * @property description the source's own words, shown rather than paraphrased
 */
data class BulkSummary(
	/** The source's own name for this dump, passed back to [BulkCatalogue.streamAll]. */
	val id: String,
	/** Short enough for a radio button. The source's own words go in [description]. */
	val label: String,
	val compressedBytes: Long,
	val updatedAt: LocalDate?,
	val description: String,
	/**
	 * Whether this dump carries every language the source has, or one printing per card.
	 *
	 * The distinction a user is actually choosing between, and it is not derivable from the
	 * size: Scryfall's smaller file is "English, or the printed language where there is no
	 * English printing", which reads as multilingual and is 97% English in practice.
	 */
	val coversAllLanguages: Boolean = false,
)
