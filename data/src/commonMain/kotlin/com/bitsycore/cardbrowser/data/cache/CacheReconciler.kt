package com.bitsycore.cardbrowser.data.cache

import com.bitsycore.cardbrowser.data.settings.BrowsingPreferences
import com.bitsycore.cardbrowser.data.settings.PreferencesStore

/**
 * Makes what the app *claims* is on disk agree with what is on disk, once, at startup.
 *
 * ## Why anything has to do this
 *
 * Two of the preferences are records about storage rather than choices the user made:
 * `bulkImports` says a catalogue has been imported, and `imageDownloads` says a set's pictures are
 * held. Both are there because neither question can be answered from the data itself -- see their
 * KDoc -- and both are therefore claims that can outlive the thing they describe.
 *
 * There are exactly two ways that happens, and they need the same response:
 *
 * - **The store was discarded.** `CardStoreFactory` deletes a database that will not open or fails
 *   its integrity check, because a half-salvaged one is a store nobody can characterise. Everything
 *   in it is re-fetchable, so that is the right call -- but it is not silent: the records describing
 *   it have to go too, or the download dialog reports an import that is gone.
 * - **The install predates the store.** A file-per-record cache held complete sets as hashed JSON
 *   files that nothing reads any more. They are not recoverable into the store -- they carry no
 *   game and no label, which the store needs as columns -- and leaving them means a metadata
 *   directory full of files that will never be read and never be evicted by anything that knows
 *   what they are.
 *
 * The response to both is to forget the claims. Nothing of the user's is lost: their games,
 * languages, filters, favourites and limits are separate fields and are not touched. What goes is
 * an offer to re-import, which is a few taps, against a screen that lies, which is the thing this
 * codebase will not ship.
 */
class CacheReconciler(
	private val mMetadataCache: MetadataCache,
	private val mPreferences: PreferencesStore,
) {

	/**
	 * @param wasStoreRecovered true when the card store had to be recreated -- see
	 *   `CardStoreFactory.open`
	 * @return true when something was actually cleared, so a caller can say so rather than leaving
	 *   the user to notice an empty library
	 */
	suspend fun reconcile(wasStoreRecovered: Boolean): Boolean {
		val vPreferences = mPreferences.preferences.value
		val vIsOldGeneration = vPreferences.storeGeneration < BrowsingPreferences.CURRENT_STORE_GENERATION
		if (!wasStoreRecovered && !vIsOldGeneration) return false

		// The whole metadata directory, not a selective sweep. An old install's complete-set
		// records are hashed filenames indistinguishable from a set list's, so there is nothing to
		// select on -- and everything in here is one request away, which is why it is a cache.
		if (vIsOldGeneration) mMetadataCache.clear()

		val vHadClaims = vPreferences.bulkImports.isNotEmpty() || vPreferences.imageDownloads.isNotEmpty()
		mPreferences.update { vCurrent ->
			vCurrent.copy(
				bulkImports = emptyMap(),
				imageDownloads = emptyMap(),
				storeGeneration = BrowsingPreferences.CURRENT_STORE_GENERATION,
			)
		}
		return vHadClaims
	}
}
