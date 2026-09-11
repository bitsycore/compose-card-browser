package com.bitsycore.cardbrowser.data.settings

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.data.cache.AppStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.IOException
import okio.buffer
import okio.use

// ==================
// MARK: State
// ==================

/**
 * How much of one set's art a download actually brought down.
 *
 * @property fetched images that arrived
 * @property total images attempted. `fetched < total` is a real outcome, not an error state -- a
 *   CDN drops requests -- and the set list shows the percentage rather than rounding up to a tick
 */
/**
 * Which dump of a game has been imported, and when the source last rebuilt it.
 *
 * Both, because "already imported" has to mean "the same file". Scryfall publishes an English
 * dump and an every-language one; having taken the first is no reason to stop offering the
 * second, and a bare flag could not tell them apart. The date catches the other case -- Scryfall
 * rebuilds daily, so last week's import is worth offering again.
 *
 * @property updatedAt the source's own rebuild day as an ISO date, or `"-"` where it states none
 */
@Serializable
data class BulkImportRecord(
	val variantId: String,
	val updatedAt: String,
)

@Serializable
data class ImageDownloadRecord(
	val fetched: Int,
	val total: Int,
) {

	/** 0..100. Guards against a zero total rather than dividing by it. */
	val percent: Int get() = if (total <= 0) 0 else (fetched * 100) / total

	val isComplete: Boolean get() = total > 0 && fetched >= total
}

/**
 * The key [BrowsingPreferences.imageDownloads] is stored under.
 *
 * Language, because a set in French and the same set in Japanese are different files. Rendition,
 * because there was a second one -- full-size art -- and there may be again; keying by kind is what
 * lets a rendition be added or removed without the records already on disk meaning something else.
 * Records written for a kind that no longer exists simply never match.
 */
fun imageDownloadKey(setId: String, language: CardLanguage?, kind: String): String =
	setId + "|" + (language?.code ?: "-") + "|" + kind

/**
 * Which colour scheme the app uses.
 *
 * Three values rather than a `Boolean`, because "dark" and "not dark" cannot express the default
 * anyone actually wants: a boolean has to pick a side at install time and then stops following the
 * platform when the user changes it there. [SYSTEM] is a real answer -- "whatever the device says"
 * -- and is not the same as either of the other two.
 */
@Serializable
enum class ThemeMode(val label: String) {

	/** Follow the platform, including when it switches at sunset. */
	SYSTEM("System"),
	LIGHT("Light"),
	DARK("Dark");

	/** Whether to use the dark scheme, given what the platform currently reports. */
	fun isDark(isSystemDark: Boolean): Boolean = when (this) {
		SYSTEM -> isSystemDark
		LIGHT -> false
		DARK -> true
	}
}

/**
 * What the user chose while browsing, remembered across launches.
 *
 * Browsing preferences only. Buying preferences -- seller country, minimum condition -- are a
 * separate concern and are deliberately not modelled here: none has been chosen, and a default
 * would be a decision nobody made.
 *
 * @property lastSetId the set to reopen, source-qualified so it survives a provider change
 * @property preferredLanguages the card-language preference order. A preference, not a claim that
 *   any provider serves all of them
 */
@Serializable
data class BrowsingPreferences(
	val lastSetId: String? = null,
	/**
	 * Which game the set list opens on, as a [com.bitsycore.cardbrowser.core.model.GameId] value.
	 *
	 * A string rather than the enum so that a preferences file written by a build that offered a
	 * game this one does not -- or the reverse -- deserialises instead of throwing. An unrecognised
	 * value falls back to the first routed game.
	 */
	val lastGame: String? = null,
	val preferredLanguages: List<CardLanguage> = CardLanguage.PREFERENCE_ORDER,
	/** Which colour scheme to use. [ThemeMode.SYSTEM] follows the platform, and is the default. */
	val themeMode: ThemeMode = ThemeMode.SYSTEM,
	/**
	 * Sets pinned to the top of the set list, in the user's own order, by qualified id.
	 *
	 * One ordered list rather than a set plus an order, because they are the same fact -- a
	 * favourite has a position by virtue of being in the list, so the two cannot disagree.
	 *
	 * Every game's favourites share it. A `SourceId.qualified` is unique across the app, so a set
	 * list only ever matches its own game's ids and the rest sit there inertly, which is also what
	 * makes a favourite survive a build that drops the game and a later one that brings it back.
	 * See `SetFavourites`.
	 */
	val favouriteSets: List<String> = emptyList(),
	/**
	 * The user's own order for the game picker, as `GameId` values. Empty means the routing order.
	 *
	 * Ids rather than an index or an enum, for the same reason [lastGame] is: the list of games a
	 * build offers changes between releases, and a stored order that disagreed with it would have to
	 * either lose a game or throw. It is a *hint* applied over the real list -- see `GameOrder`,
	 * which is where the rules for a partial or stale order live.
	 */
	val gameOrder: List<String> = emptyList(),
	/**
	 * Games the user has hidden from the picker, as `GameId` values.
	 *
	 * Hidden is a display choice and nothing more: the adapter stays registered, the routing table
	 * is untouched, and anything already downloaded stays on disk. It does stop the background set
	 * catalogue sweep from fetching them, which is the one place where hiding saves anything real.
	 *
	 * An id naming no game this build offers is inert rather than an error, so hiding a game and
	 * later installing a build without it does not corrupt the setting.
	 */
	val hiddenGames: Set<String> = emptySet(),
	/** Ceiling for downloaded card art. Applied when the image loader is built, so on next launch. */
	val imageCacheLimitBytes: Long = DEFAULT_IMAGE_CACHE_LIMIT_BYTES,
	/** Ceiling for cached card and set records. Applied on the next write. */
	val metadataCacheLimitBytes: Long = DEFAULT_METADATA_CACHE_LIMIT_BYTES,
	/** How many cards either side of the open one have their art fetched in advance. */
	val prefetchRadius: Int = DEFAULT_PREFETCH_RADIUS,
	/** Whether the set list is checked for new sets in the background on launch. */
	val revalidateSetsOnLaunch: Boolean = true,
	/**
	 * Whether sets a source states have no cards are left out of the set list.
	 *
	 * On by default, because such a row leads to an empty grid and there is nothing to do with it.
	 * TCGdex lists them in quantity -- a locale that carries a set's *name* but none of its cards
	 * still appears in that locale's catalogue.
	 *
	 * Only a *stated* zero. A set whose count is unknown is never hidden: unknown is not zero, and
	 * hiding on a silence would make sets disappear for every source that publishes no count.
	 */
	val hideEmptySets: Boolean = true,
	/**
	 * What an image download actually fetched, per set and language.
	 *
	 * Keyed by [imageDownloadKey]. Recorded because there is no cheap way to ask the question
	 * directly: answering "are this set's images cached?" honestly would mean a disk lookup per
	 * image -- around 700 for a large set, times every row on screen -- so what is stored instead
	 * is the outcome of a download that really happened.
	 *
	 * That is a record of a *download*, not a guarantee of *presence*. The image cache is an LRU
	 * with a ceiling, so a set downloaded months ago may since have been partly evicted, and the
	 * OS may purge the whole directory on Android and iOS regardless. The set list therefore says
	 * "images downloaded" rather than "images available", and that wording is the point.
	 *
	 * Images that arrive by ordinary browsing are not recorded here at all, so this under-claims
	 * rather than over-claims -- the safe direction.
	 */
	val imageDownloads: Map<String, ImageDownloadRecord> = emptyMap(),
	/**
	 * Which edition of each game's bulk file has been imported, by game id.
	 *
	 * The value is the source's own `updated_at` day for the dump that was read, as an ISO date,
	 * or `"-"` when it published none. Kept because "have I already imported this game?" cannot
	 * be answered from the set records the import wrote: a dump holds no cards for some sets a
	 * catalogue lists -- token sheets, memorabilia, sets with nothing printed yet -- so asking
	 * "is every set on disk?" answers no forever, and the download-all dialog went on offering an
	 * import that had already run and would fetch nothing new.
	 *
	 * The date rather than a flag, so a source rebuilding its file makes the import worth
	 * offering again. Scryfall rebuilds daily.
	 */
	val bulkImports: Map<String, BulkImportRecord> = emptyMap(),
) {

	/** What [imageDownloads] recorded for one rendition, or `null` if it was never downloaded. */
	fun imageDownloadFor(setId: String, language: CardLanguage?, kind: String): ImageDownloadRecord? =
		imageDownloads[imageDownloadKey(setId, language, kind)]

	/** The highest-priority language, used as the default request language. */
	val primaryLanguage: CardLanguage
		get() = preferredLanguages.firstOrNull() ?: CardLanguage.ENGLISH

	companion object {

		/** Matches `CacheManager.DEFAULT_IMAGE_CACHE_MAX_BYTES`, restated to avoid a cycle. */
		const val DEFAULT_IMAGE_CACHE_LIMIT_BYTES: Long = 256L * 1024 * 1024

		/** Matches `MetadataCache.DEFAULT_MAX_BYTES`, restated to avoid a cycle. */
		const val DEFAULT_METADATA_CACHE_LIMIT_BYTES: Long = 256L * 1024 * 1024

		const val DEFAULT_PREFETCH_RADIUS: Int = 3

		/**
		 * What the settings screen offers for either cache, plus whatever the user types.
		 *
		 * One list for both, because both ceilings now bound the same thing: what *browsing* is
		 * allowed to accumulate. They had different ranges when a limit also had to be large enough
		 * to hold a bulk import -- card data went to 1 GB for exactly that reason -- and that is no
		 * longer true, because a download is pinned and pinned bytes are outside the budget. So
		 * these are scratch sizes: 256 MB is ~12,000 thumbnails or every set of several games'
		 * JSON, and anything above 1 GB was only ever there to stop the ceiling evicting a
		 * download.
		 *
		 * A value off this list is valid and reachable -- the screen offers "Custom".
		 */
		val CACHE_LIMIT_CHOICES: List<Long> = listOf(
			64L * 1024 * 1024,
			128L * 1024 * 1024,
			256L * 1024 * 1024,
			512L * 1024 * 1024,
			1024L * 1024 * 1024,
		)

		/** Bounds for a typed-in limit. Below the floor the cache thrashes; above it is a typo. */
		const val MIN_CACHE_LIMIT_BYTES: Long = 16L * 1024 * 1024
		const val MAX_CACHE_LIMIT_BYTES: Long = 64L * 1024 * 1024 * 1024

		/** How far ahead the detail screen may prefetch. Zero switches prefetching off. */
		val PREFETCH_CHOICES: List<Int> = listOf(0, 1, 3, 5, 10)
	}
}

// ==================
// MARK: Store
// ==================

/**
 * Reads and writes [BrowsingPreferences], on disk, off the UI thread.
 *
 * Stored under [AppStorage.preferencesRoot] rather than the cache root, so "clear cache" cannot
 * take the user's choices with it. Written atomically for the same reason the cache is: a process
 * killed mid-write must leave the previous preferences intact rather than an empty file.
 *
 * A corrupt file falls back to defaults instead of throwing. Preferences are not worth a crash.
 */
class PreferencesStore(
	private val mStorage: AppStorage,
	private val mJson: Json,
	private val mIoDispatcher: CoroutineDispatcher,
) {

	private val mWriteLock = Mutex()
	private val mState = MutableStateFlow(BrowsingPreferences())

	/** The current preferences. Seeded by [load] and updated by [update]. */
	val preferences: StateFlow<BrowsingPreferences> get() = mState.asStateFlow()

	private val mFile get() = mStorage.preferencesRoot / FILE_NAME

	/** Reads from disk into [preferences]. Call once at startup. */
	init {
		// Read here, synchronously, and not only from [load].
		//
		// Two things read `preferences.value` before any coroutine has had a chance to run: the
		// image loader, which is built in the first composition and takes its disk budget from it,
		// and the metadata cache's byte-limit lambda. Both were therefore always constructed
		// against the *defaults* -- so a user who set the image cache to 128 MB got a 1 GB one
		// anyway, and the settings screen drew a usage bar against a limit nothing was enforcing.
		//
		// It is one small JSON file. Blocking the caller for it at construction is cheaper than
		// every consumer having to wait for a flow that may already have missed its moment.
		mState.value = readFromDisk()
	}

	/** Re-reads from disk. Kept for callers that want to pick up an external change. */
	suspend fun load() {
		mState.value = withContext(mIoDispatcher) { readFromDisk() }
	}

	/**
	 * The stored preferences, or the defaults.
	 *
	 * A corrupt or unreadable file falls back rather than throwing: preferences are not worth a
	 * crash, and the defaults are all valid.
	 */
	private fun readFromDisk(): BrowsingPreferences = try {
		if (!mStorage.fileSystem.exists(mFile)) {
			BrowsingPreferences()
		} else {
			val vText = mStorage.fileSystem.source(mFile).buffer().use { it.readUtf8() }
			mJson.decodeFromString(BrowsingPreferences.serializer(), vText)
		}
	} catch (vSerialization: SerializationException) {
		BrowsingPreferences()
	} catch (vIo: IOException) {
		BrowsingPreferences()
	}

	/** Applies [transform] and persists the result. */
	suspend fun update(transform: (BrowsingPreferences) -> BrowsingPreferences) {
		val vNext = transform(mState.value)
		mState.value = vNext
		withContext(mIoDispatcher) {
			mWriteLock.withLock {
				val vTemp = mStorage.preferencesRoot / "$FILE_NAME.tmp"
				try {
					mStorage.fileSystem.createDirectories(mStorage.preferencesRoot)
					mStorage.fileSystem.sink(vTemp).buffer().use { vSink ->
						vSink.writeUtf8(mJson.encodeToString(BrowsingPreferences.serializer(), vNext))
					}
					mStorage.fileSystem.atomicMove(vTemp, mFile)
				} catch (vIo: IOException) {
					// In-memory state still reflects the choice; it just will not survive a restart.
					try {
						mStorage.fileSystem.delete(vTemp, mustExist = false)
					} catch (vCleanup: IOException) {
						// Nothing useful to do.
					}
				}
			}
		}
	}

	companion object {

		private const val FILE_NAME = "browsing-preferences.json"
	}
}
