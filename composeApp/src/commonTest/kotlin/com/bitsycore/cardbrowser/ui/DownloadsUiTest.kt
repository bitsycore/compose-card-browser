package com.bitsycore.cardbrowser.ui

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.data.download.DownloadJob
import com.bitsycore.cardbrowser.data.download.DownloadKind
import com.bitsycore.cardbrowser.data.download.DownloadRequest
import com.bitsycore.cardbrowser.data.download.DownloadStatus
import com.bitsycore.cardbrowser.ui.downloads.describe
import com.bitsycore.cardbrowser.ui.downloads.infoIsComplete
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
