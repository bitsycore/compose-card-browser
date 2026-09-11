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
import com.bitsycore.cardbrowser.core.provider.BulkSummary
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

		// The other half of the same report: the set opens in the language that *was* downloaded,
		// and the banner says so with the way out.
		renderPhone(vOut, "grid-language-substituted") {
			com.bitsycore.cardbrowser.ui.cards.CardGridContent(
				state = com.bitsycore.cardbrowser.ui.cards.CardGridContract.UiState(
					setId = "scryfall:blb",
					setName = "Bloomburrow",
					setCode = "BLB",
					cards = com.bitsycore.cardbrowser.ui.preview.PreviewData.CARDS,
					isLoading = false,
					isCompleteSet = true,
					cachedCardCount = com.bitsycore.cardbrowser.ui.preview.PreviewData.CARDS.size,
					knownSetSize = com.bitsycore.cardbrowser.ui.preview.PreviewData.CARDS.size,
					language = CardLanguage.ENGLISH,
					languageSubstitutedFor = CardLanguage.FRENCH,
					availableLanguages = setOf(CardLanguage.ENGLISH, CardLanguage.FRENCH),
				),
				dispatch = {},
			)
		}

		// The reported case: Scryfall's English dump already imported, browsing in French.
		// The chooser has to be reachable -- it was not -- and has to say what the cheap file
		// costs someone who does not read English.
		renderToPng(vOut, "downloads-kind-imported-english", width = 700, height = 1000, density = 1.65f) {
			DownloadKindDialog(
				setName = "",
				setCount = 988,
				cardCount = null,
				onDismiss = {},
				onConfirm = { _, _, _ -> },
				languages = listOf(CardLanguage.ENGLISH, CardLanguage.FRENCH, CardLanguage.JAPANESE),
				defaultLanguage = CardLanguage.FRENCH,
				bulkVariants = listOf(
					BulkSummary(
						id = "default_cards",
						label = "Default cards",
						compressedBytes = 78_000_000,
						updatedAt = null,
						description = "A card in each language it was printed in.",
					),
					BulkSummary(
						id = "all_cards",
						label = "All languages",
						compressedBytes = 393_000_000,
						updatedAt = null,
						description = "Every printing in every language.",
						coversAllLanguages = true,
					),
				),
				infoLanguages = setOf(CardLanguage.ENGLISH),
				importedVariantIds = setOf("default_cards"),
				onCheckForUpdate = {},
			)
		}

		// The worst realistic case: a source that serves eleven languages, on a phone.
		// The reported alignment: a done row and a checkbox row side by side.
		renderPhone(vOut, "downloads-kind-alignment") {
			DownloadKindDialog(
				setName = "Origins",
				cardCount = 298,
				onDismiss = {},
				onConfirm = { _, _, _ -> },
				alreadyHave = setOf(DownloadKind.CARD_INFO),
				infoLanguages = setOf(CardLanguage.ENGLISH),
				languages = listOf(CardLanguage.ENGLISH),
			)
		}
		// A source whose records ship with the app and which publishes one image size.
		renderPhone(vOut, "downloads-kind-bundled") {
			DownloadKindDialog(
				setName = "Beginning of Ripples",
				cardCount = 123,
				onDismiss = {},
				onConfirm = { _, _, _ -> },
				isCardDataBundled = true,
				hasThumbnails = false,
				languages = listOf(
					CardLanguage.JAPANESE,
					CardLanguage.KOREAN,
					CardLanguage.SIMPLIFIED_CHINESE,
				),
				defaultLanguage = CardLanguage.JAPANESE,
			)
		}
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

		renderPhone(vOut, "storage-screen") {
			com.bitsycore.cardbrowser.ui.storage.StorageContent(
				state = com.bitsycore.cardbrowser.ui.storage.StorageContract.UiState(
					usage = com.bitsycore.cardbrowser.data.cache.CacheUsage(
						metadataBytes = 486_000_000,
						metadataEntries = 1_240,
						metadataLimitBytes = 1_000_000_000,
						metadataKeptBytes = 462_000_000,
						imageBytes = 184_000_000,
						imageLimitBytes = 1_000_000_000,
					),
					kept = listOf(
						com.bitsycore.cardbrowser.ui.storage.StorageContract.KeptGame(
							game = GameId("magic"),
							displayName = "Magic: The Gathering",
							sets = 988,
							bytes = 441_000_000,
							knownSets = 988,
							// The reported case: an English-only import, whose file none the less
							// carries a few cards printed in nothing else.
							extraSets = 56,
							infoLanguages = mapOf(
								CardLanguage.ENGLISH to 988,
								CardLanguage.SPANISH to 3,
								CardLanguage.JAPANESE to 2,
								CardLanguage.FRENCH to 1,
							),
							importedVariant = com.bitsycore.cardbrowser.data.settings.BulkImportRecord(
								"default_cards",
								"2026-09-10T09:14:00Z",
							),
						),
						com.bitsycore.cardbrowser.ui.storage.StorageContract.KeptGame(
							game = GameId("pokemon"),
							displayName = "Pokémon",
							sets = 486,
							bytes = 81_100_000,
							knownSets = 486,
							infoLanguages = mapOf(
								CardLanguage.ENGLISH to 486,
								CardLanguage.FRENCH to 412,
								CardLanguage.JAPANESE to 301,
								CardLanguage.GERMAN to 120,
							),
						),
						com.bitsycore.cardbrowser.ui.storage.StorageContract.KeptGame(
							game = GameId("riftbound"),
							displayName = "Riftbound",
							sets = 2,
							bytes = 21_000_000,
							knownSets = 8,
							thumbnailSets = 2,
							infoLanguages = mapOf(CardLanguage.ENGLISH to 2),
						),
					),
					isLoading = false,
				),
				dispatch = {},
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

