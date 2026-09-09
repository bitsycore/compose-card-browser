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
 * What the user chose while browsing, remembered across launches.
 *
 * Browsing preferences only. Buying preferences -- seller country, minimum condition -- are a
 * separate concern and are deliberately not modelled here: none has been chosen, and a default
 * would be a decision nobody made.
 *
 * @property lastSetId the set to reopen, source-qualified so it survives a provider change
 * @property preferredLanguages the card-language preference order. A preference, not a claim that
 *   any provider serves all of them
 * @property gridColumnPreference `null` lets the layout choose from the window width
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
	val gridColumnPreference: Int? = null,
	/** Ceiling for downloaded card art. Applied when the image loader is built, so on next launch. */
	val imageCacheLimitBytes: Long = DEFAULT_IMAGE_CACHE_LIMIT_BYTES,
	/** Ceiling for cached card and set records. Applied on the next write. */
	val metadataCacheLimitBytes: Long = DEFAULT_METADATA_CACHE_LIMIT_BYTES,
	/** How many cards either side of the open one have their art fetched in advance. */
	val prefetchRadius: Int = DEFAULT_PREFETCH_RADIUS,
	/** Whether the set list is checked for new sets in the background on launch. */
	val revalidateSetsOnLaunch: Boolean = true,
) {

	/** The highest-priority language, used as the default request language. */
	val primaryLanguage: CardLanguage
		get() = preferredLanguages.firstOrNull() ?: CardLanguage.ENGLISH

	companion object {

		/** Matches `CacheManager.DEFAULT_IMAGE_CACHE_MAX_BYTES`, restated to avoid a cycle. */
		const val DEFAULT_IMAGE_CACHE_LIMIT_BYTES: Long = 1024L * 1024 * 1024

		/** Matches `MetadataCache.DEFAULT_MAX_BYTES`, restated to avoid a cycle. */
		const val DEFAULT_METADATA_CACHE_LIMIT_BYTES: Long = 256L * 1024 * 1024

		const val DEFAULT_PREFETCH_RADIUS: Int = 3

		/** What the settings screen offers for the image cache. */
		val IMAGE_CACHE_CHOICES: List<Long> = listOf(
			128L * 1024 * 1024,
			256L * 1024 * 1024,
			512L * 1024 * 1024,
			1024L * 1024 * 1024,
			4096L * 1024 * 1024,
		)

		/** What the settings screen offers for card data. */
		val METADATA_CACHE_CHOICES: List<Long> = listOf(
			32L * 1024 * 1024,
			64L * 1024 * 1024,
			128L * 1024 * 1024,
			256L * 1024 * 1024,
			1024L * 1024 * 1024,
		)

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
	suspend fun load() {
		val vLoaded = withContext(mIoDispatcher) {
			try {
				if (!mStorage.fileSystem.exists(mFile)) return@withContext BrowsingPreferences()
				val vText = mStorage.fileSystem.source(mFile).buffer().use { it.readUtf8() }
				mJson.decodeFromString(BrowsingPreferences.serializer(), vText)
			} catch (vSerialization: SerializationException) {
				BrowsingPreferences()
			} catch (vIo: IOException) {
				BrowsingPreferences()
			}
		}
		mState.value = vLoaded
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
