package com.bitsycore.cardbrowser.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.data.download.DownloadJob
import com.bitsycore.cardbrowser.data.download.DownloadKind
import com.bitsycore.cardbrowser.data.download.DownloadRequest
import com.bitsycore.cardbrowser.data.download.DownloadStatus
import com.bitsycore.cardbrowser.ui.downloads.DownloadKindDialog
import com.bitsycore.cardbrowser.ui.downloads.DownloadsScreen
import com.bitsycore.cardbrowser.ui.theme.CardBrowserTheme
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Renders the download dialogs off-screen, to read what they actually tell the user.
 *
 * A tool, like [DetailRenderer] and [SetListRenderer], and `@Ignore`d for the same reason: its
 * output is looked at rather than asserted. Kept because the queue is the one surface where several
 * jobs for one set have to be told apart, and that is exactly what a screenshot shows and a unit
 * test does not.
 */
class DownloadRenderer {

	@Test
	@Ignore("A rendering tool, not a check. Remove the annotation to write the PNGs.")
	fun `writes the download dialogs to build slash render`() {
		val vOut = File("build/render")
		vOut.mkdirs()

		renderToPng(vOut, "downloads-queue-multilingual", width = 700, height = 900, density = 1.65f) {
			DownloadsScreen(
				jobs = multilingualJobs(),
				onBack = {},
				onCancel = {},
				onCancelAll = {},
				onClearFinished = {},
			)
		}
		renderToPng(vOut, "downloads-kind-dialog", width = 700, height = 900, density = 1.65f) {
			DownloadKindDialog(
				setName = "Base Set",
				cardCount = 102,
				onDismiss = {},
				onConfirm = { _, _, _ -> },
				languages = listOf(
					CardLanguage.ENGLISH,
					CardLanguage.FRENCH,
					CardLanguage.GERMAN,
					CardLanguage.ITALIAN,
				),
				defaultLanguage = CardLanguage.FRENCH,
			)
		}
		renderToPng(vOut, "downloads-kind-partly-held", width = 700, height = 900, density = 1.65f, isDark = false) {
			DownloadKindDialog(
				setName = "Base Set",
				cardCount = 102,
				onDismiss = {},
				onConfirm = { _, _, _ -> },
				alreadyHave = setOf(DownloadKind.CARD_INFO),
				languages = listOf(CardLanguage.ENGLISH, CardLanguage.FRENCH, CardLanguage.GERMAN),
				defaultLanguage = CardLanguage.FRENCH,
				// Half held: the case that used to read as finished.
				infoLanguages = setOf(CardLanguage.FRENCH),
			)
		}

		// The worst realistic case: a source that serves eleven languages, on a phone.
		renderPhone(vOut, "downloads-kind-eleven-languages") {
			DownloadKindDialog(
				setName = "Base Set",
				cardCount = 102,
				onDismiss = {},
				onConfirm = { _, _, _ -> },
				languages = CardLanguage.entries.toList(),
				defaultLanguage = CardLanguage.FRENCH,
			)
		}

		println("Wrote ${vOut.absolutePath}")
	}
}

/**
 * The case under audit: one set, card info in four languages and art in one.
 *
 * Six jobs whose only difference is the language, which is what the queue has to make legible.
 */
private fun multilingualJobs(): List<DownloadJob> {
	val vSetId = SourceId(ProviderId("tcgdex"), "base1")
	fun job(index: Int, language: CardLanguage, kinds: Set<DownloadKind>, status: DownloadStatus) =
		DownloadJob(
			id = "job-$index",
			request = DownloadRequest(
				setId = vSetId,
				game = GameId("pokemon"),
				setName = "Base Set",
				kinds = kinds,
				language = language,
			),
			status = status,
		)
	return listOf(
		job(1, CardLanguage.FRENCH, setOf(DownloadKind.CARD_INFO), DownloadStatus.Completed(102, 0, 0)),
		job(2, CardLanguage.ENGLISH, setOf(DownloadKind.CARD_INFO), DownloadStatus.Running(0, 0)),
		job(3, CardLanguage.GERMAN, setOf(DownloadKind.CARD_INFO), DownloadStatus.Queued),
		job(4, CardLanguage.ITALIAN, setOf(DownloadKind.CARD_INFO), DownloadStatus.Queued),
		job(
			5,
			CardLanguage.FRENCH,
			setOf(DownloadKind.CARD_INFO, DownloadKind.GRID_THUMBNAILS),
			DownloadStatus.Running(64, 204),
		),
		job(6, CardLanguage.ENGLISH, setOf(DownloadKind.GRID_THUMBNAILS), DownloadStatus.Failed("Offline")),
	)
}

/** A phone-shaped viewport: 390 x 760 dp, roughly a mid-size handset. */
@OptIn(ExperimentalComposeUiApi::class)
private fun renderPhone(
	directory: File,
	name: String,
	content: @Composable () -> Unit,
) {
	val vScene = ImageComposeScene(width = 644, height = 1254, density = Density(1.65f)) {
		CardBrowserTheme(useDarkTheme = true) {
			Surface(modifier = Modifier.fillMaxSize()) {
				Box(Modifier.fillMaxSize()) { content() }
			}
		}
	}
	try {
		val vImage = vScene.render()
		File(directory, "$name.png").writeBytes(
			vImage.encodeToData()?.bytes ?: error("could not encode $name"),
		)
	} finally {
		vScene.close()
	}
}

