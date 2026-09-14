package com.bitsycore.tcgexplorer.data.settings

import com.bitsycore.tcgexplorer.core.model.CardLanguage
import com.bitsycore.tcgexplorer.data.cache.AppStorage
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
 * Which dump of a game has been imported, and when the source last rebuilt it.
 *
 * Both, because "already imported" must mean "the same file". Scryfall publishes an English dump
 * and an every-language one, and rebuilds daily -- so a flag alone could neither tell the two files
 * apart nor notice a newer one.
 *
 * @property updatedAt the source's rebuild day as an ISO date, or `"-"` where it states none
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
 * Language, because a set in French and in Japanese are different files. Rendition, so a second
 * size can be added or dropped without changing what the existing records mean -- a record for a
 * rendition that no longer exists simply never matches.
 */
fun imageDownloadKey(setId: String, language: CardLanguage?, kind: String): String =
	setId + "|" + (language?.code ?: "-") + "|" + kind

/**
 * Whether a set's cards are shown as pictures or as a list.
 *
 * Two different jobs. The grid is for looking at art. The list is for finding a card by name or
 * reading a set in collector order, and fits four or five times as many rows on screen.
 */
@Serializable
enum class CardViewMode(val label: String) {
	GRID("Grid"),
	LIST("List"),
}

/**
 * How tall a row is in [CardViewMode.LIST].
 *
 * Three: dense enough to scan, comfortable to read, and large enough to recognise the art.
 */
@Serializable
enum class CardRowHeight(val label: String) {
	COMPACT("Compact"),
	REGULAR("Regular"),
	TALL("Tall"),
}

/**
 * How wide a tile is in [CardViewMode.GRID], and so how many fit across.
 *
 * A minimum width, not a column count: the columns are adaptive, so one setting gives three across
 * on a phone and seven on a desktop.
 *
 * Separate from [CardRowHeight] even though both have three steps. A tile is artwork and a row is
 * mostly text, so the labels that suit one read oddly for the other.
 */
@Serializable
enum class CardTileSize(val label: String) {
	SMALL("Small"),
	MEDIUM("Medium"),
	LARGE("Large"),
}

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
 * Browsing only. Buying preferences -- seller country, condition -- are deliberately not here: no
 * default has been chosen, and inventing one would be a decision nobody made.
 *
 * @property lastSetId the set last opened, source-qualified. Written by both list screens and read
 *   by nothing. Kept because it is what a "resume where you left off" would read
 * @property preferredLanguages the language preference order. A preference, not a claim that any
 *   source serves all of them
 */
@Serializable
data class BrowsingPreferences(
	val lastSetId: String? = null,
	/**
	 * Which game the set list opens on, as a [com.bitsycore.tcgexplorer.core.model.GameId] value.
	 *
	 * A string, not an enum, so a file written by a build with different games still deserialises.
	 * An unknown value falls back to the first routed game.
	 */
	val lastGame: String? = null,
	val preferredLanguages: List<CardLanguage> = CardLanguage.PREFERENCE_ORDER,
	/** Which colour scheme to use. [ThemeMode.SYSTEM] follows the platform, and is the default. */
	val themeMode: ThemeMode = ThemeMode.SYSTEM,
	/**
	 * Sets pinned to the top of the set list, in the user's own order, by qualified id.
	 *
	 * One ordered list rather than a set plus an order: membership and position are the same fact,
	 * so they cannot disagree.
	 *
	 * All games share it. Ids are source-qualified and unique, so each set list matches only its
	 * own and ignores the rest -- which is also how a favourite survives a build that drops the
	 * game. See `SetFavourites`.
	 */
	val favouriteSets: List<String> = emptyList(),
	/**
	 * The user's own order for the game picker, as `GameId` values. Empty means the routing order.
	 *
	 * Ids rather than indices, for the same reason as [lastGame]: the set of games changes between
	 * releases. A hint applied over the real list -- see `GameOrder` for partial and stale orders.
	 */
	val gameOrder: List<String> = emptyList(),
	/**
	 * Games the user has hidden from the picker, as `GameId` values.
	 *
	 * A display choice only: the adapter stays registered and downloads stay on disk. It does stop
	 * the background catalogue sweep fetching them, which is the one real saving.
	 *
	 * An id for a game this build does not offer is inert, not an error.
	 */
	val hiddenGames: Set<String> = emptySet(),
	/**
	 * Whether the first-launch setup has been completed or skipped.
	 *
	 * False only on a fresh install. Skipping sets it too -- declining to choose is choosing the
	 * defaults, and asking again next launch would be nagging. Settings can set it back.
	 */
	val hasCompletedSetup: Boolean = false,
	/** Ceiling for downloaded card art. Applied when the image loader is built, so on next launch. */
	val imageCacheLimitBytes: Long = DEFAULT_IMAGE_CACHE_LIMIT_BYTES,
	/** How many cards either side of the open one have their art fetched in advance. */
	val prefetchRadius: Int = DEFAULT_PREFETCH_RADIUS,
	/** Whether the set list is checked for new sets in the background on launch. */
	val revalidateSetsOnLaunch: Boolean = true,
	/**
	 * Whether sets a source states have no cards are left out of the set list.
	 *
	 * On by default: such a row opens an empty grid. TCGdex lists many of them -- a locale carrying
	 * a set's name but none of its cards still appears in that catalogue.
	 *
	 * A *stated* zero only. Unknown is not zero, and hiding on silence would empty the set list for
	 * every source that publishes no count.
	 */
	val hideEmptySets: Boolean = true,
	/**
	 * What an image download actually fetched, per set and language.
	 *
	 * Keyed by [imageDownloadKey]. Recorded rather than measured: asking "are this set's images
	 * cached?" honestly would be a disk lookup per image, around 700 for a large set, per row.
	 *
	 * A record of a *download*, not of *presence*. The image cache is an LRU and the OS may purge
	 * it, so the set list says "images downloaded", never "images available".
	 *
	 * Images that arrive by browsing are not recorded, so this under-claims. That is the safe way
	 * round.
	 */
	val imageDownloads: Map<String, ImageDownloadRecord> = emptyMap(),
	/**
	 * Which edition of each game's bulk file has been imported, by game id.
	 *
	 * The value is the dump's `updated_at` day as an ISO date, or `"-"` where the source states
	 * none.
	 *
	 * Kept because the set records cannot answer it. A dump holds no cards for some sets a
	 * catalogue lists -- token sheets, memorabilia, unreleased sets -- so "is every set on disk?"
	 * answers no forever, and the dialog keeps offering an import that would fetch nothing.
	 *
	 * A date rather than a flag, so a rebuilt file is worth importing again. Scryfall rebuilds
	 * daily.
	 */
	val bulkImports: Map<String, BulkImportRecord> = emptyMap(),
	/**
	 * Which generation of the card store these records describe.
	 *
	 * Below [CURRENT_STORE_GENERATION] means the records on disk describe a store that no longer
	 * exists, so [bulkImports] and [imageDownloads] describe nothing. `CacheReconciler` clears them
	 * and bumps this once at startup.
	 */
	val storeGeneration: Int = 0,
	/** Grid or list, for every set. One choice rather than one per game -- it is a reading habit. */
	val cardViewMode: CardViewMode = CardViewMode.GRID,
	/** How tall the list's rows are. Only read in [CardViewMode.LIST]. */
	val cardRowHeight: CardRowHeight = CardRowHeight.REGULAR,
	/** How big the grid's tiles are. Only read in [CardViewMode.GRID]. */
	val cardTileSize: CardTileSize = CardTileSize.MEDIUM,
) {

	/** What [imageDownloads] recorded for one rendition, or `null` if it was never downloaded. */
	fun imageDownloadFor(setId: String, language: CardLanguage?, kind: String): ImageDownloadRecord? =
		imageDownloads[imageDownloadKey(setId, language, kind)]

	/** The highest-priority language, used as the default request language. */
	val primaryLanguage: CardLanguage
		get() = preferredLanguages.firstOrNull() ?: CardLanguage.ENGLISH

	companion object {

		/** Matches `CacheManager.DEFAULT_IMAGE_CACHE_MAX_BYTES`, restated to avoid a cycle. */
		const val DEFAULT_IMAGE_CACHE_LIMIT_BYTES: Long = 1024L * 1024 * 1024

		/**
		 * Bumped whenever what is on disk stops meaning what an older install thought it meant.
		 *
		 * 1 is the move from a file-per-set cache to the SQLite store. Nothing can read the old
		 * records, so the first launch after it sweeps them and forgets which imports had run.
		 */
		const val CURRENT_STORE_GENERATION: Int = 1

		const val DEFAULT_PREFETCH_RADIUS: Int = 3

		/**
		 * What the settings screen offers for either cache, plus whatever the user types.
		 *
		 * One list for both: both ceilings bound the same thing, what browsing may accumulate. A
		 * download is pinned and pinned bytes sit outside the budget.
		 *
		 * Values off this list are valid -- the screen offers "Custom".
		 */
		val CACHE_LIMIT_CHOICES: List<Long> = listOf(
			128L * 1024 * 1024,
			256L * 1024 * 1024,
			512L * 1024 * 1024,
			1024L * 1024 * 1024,
			4096L * 1024 * 1024,
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
 * Kept outside the cache root, so "clear cache" cannot take the user's choices with it. Written
 * atomically, so a process killed mid-write leaves the previous file intact.
 *
 * A corrupt file falls back to defaults. Preferences are not worth a crash.
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
		// Read synchronously here, not only from `load`.
		//
		// The image loader and the cache's byte limit both read `preferences.value` before any
		// coroutine runs, so both used to be built against the defaults: a user who chose a 128 MB
		// image cache got a 1 GB one, and the settings screen drew usage against a limit nothing
		// enforced. One small JSON file is cheaper to block on than to wait for.
		mState.value = readFromDisk()
	}

	/** Re-reads from disk. Kept for callers that want to pick up an external change. */
	suspend fun load() {
		mState.value = withContext(mIoDispatcher) { readFromDisk() }
	}

	/**
	 * The stored preferences, or the defaults.
	 *
	 * A corrupt file falls back rather than throwing. The defaults are all valid.
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
