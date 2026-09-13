package com.bitsycore.cardbrowser.ui.screen.downloads

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.data.download.DownloadJob
import com.bitsycore.cardbrowser.data.download.DownloadKind
import com.bitsycore.cardbrowser.data.download.DownloadRequest
import com.bitsycore.cardbrowser.data.download.DownloadStatus
import com.bitsycore.cardbrowser.ui.screen.downloads.describe
import com.bitsycore.cardbrowser.ui.screen.downloads.cardInfoIsElsewhere
import com.bitsycore.cardbrowser.ui.screen.downloads.cardInfoLanguageIsChosen
import com.bitsycore.cardbrowser.ui.screen.downloads.defaultInfoLanguages
import com.bitsycore.cardbrowser.ui.screen.downloads.infoIsComplete
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the download surfaces say, for the case that has several jobs per set.
 *
 * A download splits into one job per language -- card info in every language a set states, art in
 * the ones picked -- so one tap can put six jobs in the queue with the same set name. Both things
 * tested here were reported as faults: the queue could not be read, and the dialog claimed a set
 * was already held when one language of six had finished.
 */
class DownloadsUiTest {

	// ==================
	// MARK: Where a source's records come from
	// ==================

	@Test
	fun `a bulk-only source offers no card info for one set and offers it for the game`() {
		// Scryfall's case. 988 sets fetched one at a time rebuilds a file the source publishes as
		// one, which is the traffic the file exists to prevent.
		assertTrue(
			cardInfoIsElsewhere(
				isCardDataBundled = false,
				isCardInfoBulkOnly = true,
				setCount = 1,
			),
		)
		// And the other half, which is the reason this is not a flat refusal: the whole-game
		// dialog is where the import lives, so it must still offer the row.
		assertFalse(
			cardInfoIsElsewhere(
				isCardDataBundled = false,
				isCardInfoBulkOnly = true,
				setCount = 988,
			),
		)
	}

	@Test
	fun `bundled records are never on offer at any count`() {
		for (vCount in listOf(1, 42)) {
			assertTrue(
				cardInfoIsElsewhere(
					isCardDataBundled = true,
					isCardInfoBulkOnly = false,
					setCount = vCount,
				),
				"a set count of $vCount cannot make a bundled catalogue downloadable",
			)
		}
	}

	@Test
	fun `an ordinary source offers card info per set`() {
		assertFalse(
			cardInfoIsElsewhere(
				isCardDataBundled = false,
				isCardInfoBulkOnly = false,
				setCount = 1,
			),
		)
	}

	@Test
	fun `a dump decides its own languages and the chips do not`() {
		// Magic's whole-game dialog offered the file *and* a row of card-info language chips, which
		// is two controls for one fact and only one of them is obeyed: Scryfall's cheap dump is
		// 97% English whatever the chips say, and taking the every-language one is a different
		// file rather than a different tick. The variant selector is the language control there.
		assertFalse(
			cardInfoLanguageIsChosen(
				isCardDataBundled = false,
				isCardInfoBulkOnly = true,
				setCount = 988,
				hasBulkVariants = true,
			),
		)
		// A source with no dump fetches per set, and then the chips are the only control there is.
		assertTrue(
			cardInfoLanguageIsChosen(
				isCardDataBundled = false,
				isCardInfoBulkOnly = false,
				setCount = 42,
				hasBulkVariants = false,
			),
		)
	}

	// ==================
	// MARK: Which languages a download starts with
	// ==================

	@Test
	fun `one language is downloaded by default whether the editions are stated or not`() {
		val vStated = listOf(CardLanguage.ENGLISH, CardLanguage.FRENCH, CardLanguage.GERMAN)

		// It used to take all three where the source stated them. That is a fine trade for one
		// user's one set and a poor one across a catalogue -- three times the jobs and three times
		// the traffic for a source, mostly for languages nobody reads.
		assertEquals(
			setOf(CardLanguage.FRENCH),
			defaultInfoLanguages(vStated, CardLanguage.FRENCH, languagesAreClaimed = false),
		)
		// And the claimed case answers the same. The flag still changes the note beside the chips;
		// it no longer changes what is ticked.
		assertEquals(
			setOf(CardLanguage.GERMAN),
			defaultInfoLanguages(
				CardLanguage.PREFERENCE_ORDER,
				CardLanguage.GERMAN,
				languagesAreClaimed = true,
			),
		)
	}

	@Test
	fun `a preference the offer does not contain falls back to English`() {
		// A French-preferring user and a source that serves no French. English rather than the
		// first of the list, because it is the language a source is most likely to actually hold:
		// a poor guess that returns cards beats a good one that returns none.
		assertEquals(
			setOf(CardLanguage.ENGLISH),
			defaultInfoLanguages(
				listOf(CardLanguage.JAPANESE, CardLanguage.ENGLISH),
				CardLanguage.FRENCH,
				languagesAreClaimed = true,
			),
		)
	}

	@Test
	fun `an offer with neither the preference nor English still ticks something`() {
		// Nothing ticked leaves the Download button refusing with no indication why, so the first
		// of whatever there is wins.
		assertEquals(
			setOf(CardLanguage.JAPANESE),
			defaultInfoLanguages(
				listOf(CardLanguage.JAPANESE, CardLanguage.KOREAN),
				CardLanguage.FRENCH,
				languagesAreClaimed = false,
			),
		)
	}

	private fun job(
		language: CardLanguage?,
		kinds: Set<DownloadKind> = setOf(DownloadKind.CARD_INFO),
		status: DownloadStatus = DownloadStatus.Queued,
	) = DownloadJob(
		id = "job",
		request = DownloadRequest(
			setId = SourceId(ProviderId("tcgdex"), "base1"),
			game = GameId("pokemon"),
			setName = "Base Set",
			kinds = kinds,
			language = language,
		),
		status = status,
	)

	// ============
	//  The queue row

	@Test
	fun `a queued job names the language it is waiting to fetch`() {
		// The reported fault: four info jobs for one set all read "Waiting · info", so there was no
		// telling German from Italian.
		assertEquals("Waiting · info · German", describe(job(CardLanguage.GERMAN)))
		assertEquals("Waiting · info · Italian", describe(job(CardLanguage.ITALIAN)))
	}

	@Test
	fun `every status carries the language`() {
		val vLanguage = CardLanguage.JAPANESE
		val vDescriptions = listOf(
			DownloadStatus.Queued,
			DownloadStatus.Running(0, 0),
			DownloadStatus.Running(3, 10),
			DownloadStatus.Completed(cards = 102, imagesFetched = 102, imagesFailed = 0),
			DownloadStatus.Failed("Offline"),
			DownloadStatus.Cancelled,
		).map { describe(job(vLanguage, status = it)) }

		// Including the two that say least. A failure needs it most: this is where the user finds
		// out that the Italian art did not come down while the French did.
		for (vText in vDescriptions) {
			assertTrue(vText.contains("Japanese"), "no language in \"$vText\"")
		}
		assertEquals("Offline · Japanese", describe(job(vLanguage, status = DownloadStatus.Failed("Offline"))))
		assertEquals("Stopped · Japanese", describe(job(vLanguage, status = DownloadStatus.Cancelled)))
	}

	@Test
	fun `a source that states no language is not given one`() {
		// Most sources say nothing about language. Naming one here would be inventing it.
		assertEquals("Waiting · info", describe(job(null)))
		assertEquals(
			"102 cards",
			describe(job(null, status = DownloadStatus.Completed(102, 0, 0))),
		)
	}

	@Test
	fun `a part-finished job still reports what failed`() {
		// Never rounded up to "done": a set four images short is not complete.
		assertEquals(
			"102 cards, 98 images · 4 failed · French",
			describe(
				job(
					CardLanguage.FRENCH,
					status = DownloadStatus.Completed(cards = 102, imagesFetched = 98, imagesFailed = 4),
				),
			),
		)
	}

	// ============
	//  "Do I already have this?"

	private val mSix = listOf(
		CardLanguage.ENGLISH,
		CardLanguage.FRENCH,
		CardLanguage.GERMAN,
		CardLanguage.SPANISH,
		CardLanguage.ITALIAN,
		CardLanguage.PORTUGUESE,
	)

	@Test
	fun `one language of six is not the whole set`() {
		// The reported fault. Card info is fetched in every language a set states, so this used to
		// tick and lock the checkbox after French finished and hid the other five behind
		// "Download again".
		assertFalse(
			infoIsComplete(
				alreadyHave = setOf(DownloadKind.CARD_INFO),
				languages = mSix,
				infoLanguages = setOf(CardLanguage.FRENCH),
			),
		)
	}

	@Test
	fun `every stated language present is complete`() {
		assertTrue(
			infoIsComplete(
				alreadyHave = setOf(DownloadKind.CARD_INFO),
				languages = mSix,
				infoLanguages = mSix.toSet(),
			),
		)
	}

	@Test
	fun `a source stating no languages falls back to plain presence`() {
		// Four of the seven sources say nothing about language, and for those the old rule was
		// right -- there are no editions to be missing.
		assertTrue(
			infoIsComplete(
				alreadyHave = setOf(DownloadKind.CARD_INFO),
				languages = emptyList(),
				infoLanguages = emptySet(),
			),
		)
	}

	@Test
	fun `nothing on disk is never complete`() {
		assertFalse(
			infoIsComplete(
				alreadyHave = emptySet(),
				languages = mSix,
				infoLanguages = mSix.toSet(),
			),
		)
		assertFalse(infoIsComplete(emptySet(), emptyList(), emptySet()))
	}

	@Test
	fun `a language held that the set does not state does not make it complete`() {
		// Guards against `containsAll` being written the wrong way round.
		assertFalse(
			infoIsComplete(
				alreadyHave = setOf(DownloadKind.CARD_INFO),
				languages = mSix,
				infoLanguages = setOf(CardLanguage.JAPANESE),
			),
		)
	}
}
