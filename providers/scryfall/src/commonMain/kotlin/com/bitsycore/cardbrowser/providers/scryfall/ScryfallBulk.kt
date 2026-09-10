package com.bitsycore.cardbrowser.providers.scryfall

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.provider.BulkSummary
import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.data.net.mapProviderErrors
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.appendPathSegments
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.datetime.LocalDate
import kotlinx.io.readByteArray
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Path
import okio.buffer
import okio.gzip
import okio.use

/**
 * Scryfall's bulk data files: the manifest, and streaming one of them.
 *
 * https://scryfall.com/docs/api/bulk-data. Scryfall publishes these specifically so clients stop
 * walking the API a page at a time, and asks that they be used for exactly the case this app has:
 * wanting the whole catalogue at once.
 *
 * ## Which file, and why not the biggest one
 *
 * `default_cards` -- every card in English, or in its printed language where there is no English
 * printing. Measured 2026-09-10: **74.6 MB gzipped, 598 MB of JSON**, one card per line.
 *
 * `all_cards` carries every language and is 374.6 MB compressed, five times the transfer for
 * something this app cannot use as it stands: the cache is keyed per language, so importing every
 * language would write eleven copies of the catalogue. If per-language bulk is ever wanted it
 * should be a deliberate choice with its own number on the screen, not the default.
 *
 * ## Why it lands on disk first
 *
 * Gzip has to be read as a stream, and Okio -- which is what this project decompresses and reads
 * files with -- takes an Okio `Source`, which a Ktor response channel is not on every platform.
 * Writing the compressed file down and then reading it back keeps the whole thing to two bounded
 * costs: 74.6 MB of scratch disk, and one line of JSON in memory at a time. The alternative,
 * holding the compressed bytes in memory, is a 75 MB array on a phone for no benefit.
 *
 * The scratch file is deleted on the way out, including when the import is cancelled.
 */
internal class ScryfallBulk(
	private val mClient: HttpClient,
	private val mStorage: AppStorage,
	private val mBaseUrl: String,
) {

	suspend fun variants(): List<BulkSummary> = mapProviderErrors("Scryfall.bulkVariants") {
		val vEntries = manifest()
		WANTED_TYPES.mapNotNull { (vType, vLabel) ->
			val vEntry = vEntries.firstOrNull { it.type == vType } ?: return@mapNotNull null
			BulkSummary(
				id = vType,
				label = vLabel,
				compressedBytes = vEntry.compressedSize,
				updatedAt = vEntry.updatedAt.substringBefore('T').let {
					runCatching { LocalDate.parse(it) }.getOrNull()
				},
				description = vEntry.description,
				coversAllLanguages = vType == ALL_LANGUAGES,
			)
		}
	}

	suspend fun stream(
		variantId: String,
		onBytes: (Long, Long?) -> Unit,
		onCard: suspend (CardPrinting) -> Unit,
	) {
		// By exact id, with no fallback. The two differ by 315 MB, and quietly fetching the other
		// one because a name did not match is not a mistake a user could diagnose.
		val vEntry = manifest().firstOrNull { it.type == variantId } ?: return
		val vScratch = mStorage.cacheRoot / SCRATCH_NAME
		try {
			download(vEntry.downloadUri, vScratch, vEntry.compressedSize, onBytes)
			currentCoroutineContext().ensureActive()
			readCards(vScratch, onCard)
		} finally {
			// Including on cancellation: 75 MB left behind because someone changed their mind is
			// not a cache, it is litter the app has no way of ever explaining.
			runCatching { mStorage.fileSystem.delete(vScratch) }
		}
	}

	// ============
	//  Steps

	private suspend fun manifest(): List<BulkEntryDto> = mClient
		.get(mBaseUrl) { url { appendPathSegments("bulk-data") } }
		.body<BulkListDto>()
		.data

	/** Streams the compressed file to disk, reporting progress as it goes. */
	private suspend fun download(uri: String, into: Path, total: Long, onBytes: (Long, Long?) -> Unit) {
		mStorage.fileSystem.createDirectories(mStorage.cacheRoot)
		mClient.prepareGet(uri).execute { vResponse ->
			val vChannel = vResponse.bodyAsChannel()
			var vSoFar = 0L
			mStorage.fileSystem.sink(into).buffer().use { vSink ->
				while (!vChannel.isClosedForRead) {
					currentCoroutineContext().ensureActive()
					val vPacket = vChannel.readRemaining(CHUNK_BYTES)
					val vBytes = vPacket.readByteArray()
					if (vBytes.isEmpty()) continue
					vSink.write(vBytes)
					vSoFar += vBytes.size
					onBytes(vSoFar, total.takeIf { it > 0 })
				}
			}
		}
	}

	/**
	 * Reads the file back a line at a time.
	 *
	 * JSONL is what makes this cheap: one card per line means the parser never needs more than a
	 * line, and a malformed one can be skipped instead of failing the whole import. A single bad
	 * record in a 100,000-line file is not a reason to abandon the other 99,999.
	 */
	private suspend fun readCards(from: Path, onCard: suspend (CardPrinting) -> Unit) {
		mStorage.fileSystem.source(from).gzip().buffer().use { vSource ->
			while (true) {
				currentCoroutineContext().ensureActive()
				val vLine = vSource.readUtf8Line() ?: break
				if (vLine.isBlank()) continue
				val vDto = runCatching { JSON.decodeFromString<ScryfallCardDto>(vLine) }.getOrNull()
					?: continue
				// The record's own language, not one chosen by the caller. `default_cards` is
				// mostly English and genuinely mixed -- Spanish, Japanese, French, Italian and a
				// few others -- so a single label across the file would be wrong for thousands of
				// cards. Scryfall states `lang` per record; this believes it.
				//
				// A language this app has no name for is skipped rather than guessed at. Scryfall
				// ships Phyrexian (`ph`), which is a real printing language and not one of the
				// eleven the app knows, and labelling it as something else would be a claim.
				val vLanguage = vDto.lang?.let(CardLanguage::fromCode) ?: continue
				val vCard = ScryfallMapper.toPrinting(
					dto = vDto,
					provider = ScryfallProvider.PROVIDER_ID,
					set = null,
					language = vLanguage,
				) ?: continue
				onCard(vCard)
			}
		}
	}

	private companion object {

		/** Every card in every language Scryfall has. Measured 2026-09-11: 392.8 MB compressed. */
		const val ALL_LANGUAGES = "all_cards"

		/**
		 * The two dumps this app can use, cheapest first, with a label a menu can show.
		 *
		 * `default_cards` is "every card in English, or its printed language where there is no
		 * English printing", which sounds multilingual and is not: sampled 2026-09-10, 8780
		 * English records against 92 Spanish, 47 Japanese, 27 French and 1 German. So it is the
		 * right default -- 78.2 MB, and the language nearly everything is printed in -- and the
		 * wrong thing to describe as covering languages.
		 *
		 * `all_cards` genuinely does, at 392.8 MB compressed and roughly eleven cache records per
		 * set instead of one, because the cache is keyed per language. That is a real purchase
		 * and it is now the user's to make rather than one made for them here. Both figures
		 * measured against the live manifest on 2026-09-11.
		 *
		 * Scryfall publishes five more -- `oracle_cards`, `unique_artwork`, `rulings`,
		 * `art_tags`, `oracle_tags` -- and none is a catalogue of printings, which is what this
		 * app caches. They are deliberately not offered.
		 */
		val WANTED_TYPES: List<Pair<String, String>> = listOf(
			"default_cards" to "English",
			ALL_LANGUAGES to "Every language",
		)

		const val SCRATCH_NAME = "scryfall-bulk.jsonl.gz"

		/** Read in megabyte gulps. Small enough to report progress, large enough not to thrash. */
		const val CHUNK_BYTES = 1L * 1024 * 1024

		/**
		 * Its own parser, not the shared one.
		 *
		 * The bulk file carries every field Scryfall has, most of which this app has no DTO for,
		 * and a strict parser would reject all 100,000 lines.
		 */
		val JSON = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
	}
}

// ==================
// MARK: Wire types
// ==================

@Serializable
private data class BulkListDto(val data: List<BulkEntryDto> = emptyList())

@Serializable
private data class BulkEntryDto(
	val type: String = "",
	@SerialName("updated_at") val updatedAt: String = "",
	val description: String = "",
	// The JSONL form, which is what makes a line-at-a-time read possible. The plain `.json` file
	// is one enormous array and would have to be parsed whole.
	@SerialName("jsonl_download_uri") val downloadUri: String = "",
	@SerialName("compressed_size") val compressedSize: Long = 0,
)
