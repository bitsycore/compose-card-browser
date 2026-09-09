package com.bitsycore.cardbrowser.data.net

import com.bitsycore.cardbrowser.data.cache.AppStorage
import io.ktor.client.plugins.cache.storage.CacheStorage
import io.ktor.client.plugins.cache.storage.CachedResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import okio.IOException
import okio.Path

/**
 * An HTTP response cache on disk, so a revalidation can come back as a 304 with no body.
 *
 * ## What this buys, and what it does not
 *
 * It does not stop requests. The app's own metadata cache already does that: a set is held for 24
 * hours and a set list is not even revalidated more than once every five minutes. What this saves
 * is the *body* on the requests that do go out -- and those are the expensive ones. Reopening a
 * Riftbound set the day after re-downloads four pages at about 116 KB each; a Magic set is three
 * pages at roughly 200 KB. If nothing has changed, every one of those becomes an empty 304.
 *
 * Every origin the app talks to serves validators, which is what makes this worth having: Scryfall,
 * TCGdex, YGOPRODeck and jsDelivr all send `ETag` or `Last-Modified`. Ktor's `HttpCache` does the
 * protocol work; this only has to keep the bytes somewhere.
 *
 * ## Why it is not installed on every client
 *
 * The image loader shares the app's default HTTP client, and Coil keeps its own 1 GB disk cache of
 * decoded artwork. Caching image responses here as well would store every card twice and blow this
 * budget in one grid. So this is attached to the *provider* clients only -- see `AppModule`.
 *
 * ## Format
 *
 * One file per entry: a single-line JSON header, a newline, then the raw body. One file rather than
 * two so a write is atomic in one move, and raw rather than Base64 so a 200 KB body costs 200 KB.
 * The header cannot contain a newline because it is compact JSON, which is what makes the split
 * unambiguous.
 *
 * @param maxBytes the ceiling. Oldest-written entries go first when it is exceeded -- crude next to
 *   the metadata cache's LRU, and right for this: an entry here is a *copy* of something the
 *   metadata cache also holds, so losing one costs a full body once and nothing else
 */
class OkioHttpCacheStorage(
	private val mStorage: AppStorage,
	private val mMaxBytes: Long = DEFAULT_MAX_BYTES,
) : CacheStorage {

	private val mJson = Json { ignoreUnknownKeys = true }
	private val mLock = Mutex()

	private val mDirectory: Path get() = mStorage.cacheRoot / HTTP_DIR

	override suspend fun store(url: Url, data: CachedResponseData) {
		// A response with no body is not worth a file: the point of an entry is to have the bytes
		// to serve when the origin answers 304.
		if (data.body.isEmpty()) return
		mLock.withLock {
			try {
				mStorage.fileSystem.createDirectories(mDirectory)
				val vPath = pathFor(url, data.varyKeys)
				val vTemp = mDirectory / "${vPath.name}$TEMP_SUFFIX"
				val vHeader = mJson.encodeToString(
					StoredResponse(
						url = data.url.toString(),
						status = data.statusCode.value,
						statusDescription = data.statusCode.description,
						requestTimeMillis = data.requestTime.timestamp,
						responseTimeMillis = data.responseTime.timestamp,
						expiresMillis = data.expires.timestamp,
						version = data.version.toString(),
						headers = data.headers.entries().associate { it.key to it.value },
						varyKeys = data.varyKeys,
					),
				)
				mStorage.fileSystem.write(vTemp) {
					writeUtf8(vHeader)
					writeByte(NEWLINE)
					write(data.body)
				}
				mStorage.fileSystem.atomicMove(vTemp, vPath)
				trimLocked()
			} catch (vIo: IOException) {
				// A cache that cannot be written is a cache that is not used. Nothing here is the
				// only copy of anything -- the metadata cache holds the mapped model -- so a failed
				// write costs a full body next time and never correctness.
			}
		}
	}

	override suspend fun find(url: Url, varyKeys: Map<String, String>): CachedResponseData? =
		mLock.withLock { readLocked(pathFor(url, varyKeys)) }

	/**
	 * Every entry for [url], whatever its vary keys.
	 *
	 * The layout files an entry under a hash of the URL *and* its vary keys, so there is no way to
	 * enumerate the variants of one URL without an index. None of the origins here send `Vary` on a
	 * card endpoint, so this answers with the no-vary entry alone rather than carrying an index
	 * whose only job would be to stay in step.
	 */
	override suspend fun findAll(url: Url): Set<CachedResponseData> =
		mLock.withLock { setOfNotNull(readLocked(pathFor(url, emptyMap()))) }

	override suspend fun remove(url: Url, varyKeys: Map<String, String>) {
		mLock.withLock { deleteQuietly(pathFor(url, varyKeys)) }
	}

	override suspend fun removeAll(url: Url) {
		mLock.withLock { deleteQuietly(pathFor(url, emptyMap())) }
	}

	/** Deletes every stored response. Used by "clear cache". */
	suspend fun clear() {
		mLock.withLock {
			try {
				if (mStorage.fileSystem.exists(mDirectory)) {
					mStorage.fileSystem.deleteRecursively(mDirectory)
				}
			} catch (vIo: IOException) {
				// Nothing to do about it, and nothing depends on it having worked.
			}
		}
	}

	private fun readLocked(path: Path): CachedResponseData? = try {
		if (!mStorage.fileSystem.exists(path)) {
			null
		} else {
			val vBytes = mStorage.fileSystem.read(path) { readByteArray() }
			val vSplit = vBytes.indexOfFirst { it == NEWLINE.toByte() }
			if (vSplit <= 0) {
				// Truncated before the header ended. Unusable, so it goes.
				deleteQuietly(path)
				null
			} else {
				val vStored = mJson.decodeFromString<StoredResponse>(
					vBytes.decodeToString(0, vSplit),
				)
				CachedResponseData(
					url = Url(vStored.url),
					statusCode = HttpStatusCode(vStored.status, vStored.statusDescription),
					requestTime = GMTDate(vStored.requestTimeMillis),
					responseTime = GMTDate(vStored.responseTimeMillis),
					version = HttpProtocolVersion.parse(vStored.version),
					expires = GMTDate(vStored.expiresMillis),
					headers = Headers.build {
						vStored.headers.forEach { (vName, vValues) -> appendAll(vName, vValues) }
					},
					varyKeys = vStored.varyKeys,
					body = vBytes.copyOfRange(vSplit + 1, vBytes.size),
				)
			}
		}
	} catch (vIo: IOException) {
		// Not readable *now* is not the same as unusable. Unlike a malformed header, this leaves
		// the file alone -- the same distinction `MetadataCache` draws, and for the same reason.
		null
	} catch (vFailure: IllegalArgumentException) {
		// A header this build cannot make sense of: a changed format, a bad status line.
		deleteQuietly(path)
		null
	} catch (vSerialization: kotlinx.serialization.SerializationException) {
		deleteQuietly(path)
		null
	}

	/** Oldest-written entries first, until the total fits. Call while holding [mLock]. */
	private fun trimLocked() {
		try {
			val vFiles = mStorage.fileSystem.list(mDirectory)
				.filter { !it.name.endsWith(TEMP_SUFFIX) }
				.mapNotNull { vPath ->
					val vMeta = mStorage.fileSystem.metadataOrNull(vPath) ?: return@mapNotNull null
					if (!vMeta.isRegularFile) null else Triple(vPath, vMeta.size ?: 0L, vMeta.lastModifiedAtMillis ?: 0L)
				}
			var vTotal = vFiles.sumOf { it.second }
			if (vTotal <= mMaxBytes) return
			for ((vPath, vSize, _) in vFiles.sortedBy { it.third }) {
				if (vTotal <= mMaxBytes) break
				deleteQuietly(vPath)
				vTotal -= vSize
			}
		} catch (vIo: IOException) {
			// Over budget and unable to trim is better than throwing out of a response.
		}
	}

	private fun deleteQuietly(path: Path) {
		try {
			mStorage.fileSystem.delete(path)
		} catch (vIo: IOException) {
			// Already gone, or not ours to delete.
		}
	}

	/**
	 * The file for one entry.
	 *
	 * Hashed, because a URL is not a filename -- query strings, colons and lengths all break on one
	 * platform or another. The vary keys are part of the hash so two variants of one URL cannot
	 * overwrite each other.
	 */
	private fun pathFor(url: Url, varyKeys: Map<String, String>): Path {
		val vIdentity = buildString {
			append(url.toString())
			varyKeys.entries.sortedBy { it.key }.forEach { append('|').append(it.key).append('=').append(it.value) }
		}
		return mDirectory / vIdentity.encodeUtf8().sha256().hex()
	}

	@Serializable
	private data class StoredResponse(
		val url: String = "",
		val status: Int = 0,
		val statusDescription: String = "",
		val requestTimeMillis: Long = 0,
		val responseTimeMillis: Long = 0,
		val expiresMillis: Long = 0,
		val version: String = "HTTP/1.1",
		val headers: Map<String, List<String>> = emptyMap(),
		val varyKeys: Map<String, String> = emptyMap(),
	)

	companion object {

		const val HTTP_DIR: String = "http"

		/**
		 * 64 MB.
		 *
		 * Sized against what it actually holds: the raw JSON of the set lists and set pages a user
		 * revisits. Scryfall's whole set catalogue is 622 KB and its largest set three pages of
		 * about 200 KB, so this covers a great many of both while staying a fraction of the
		 * metadata cache's own budget -- which holds the mapped models this only duplicates.
		 */
		const val DEFAULT_MAX_BYTES: Long = 64L * 1024 * 1024

		private const val TEMP_SUFFIX = ".tmp"
		private const val NEWLINE = '\n'.code
	}
}
