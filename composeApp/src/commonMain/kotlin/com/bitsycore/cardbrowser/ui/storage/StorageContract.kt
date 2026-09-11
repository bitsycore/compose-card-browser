package com.bitsycore.cardbrowser.ui.storage

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.data.cache.CacheUsage
import com.bitsycore.cardbrowser.data.settings.BulkImportRecord
import com.bitsycore.lib.pulse.container.ContainerContract

/**
 * What is on the device, and what can be removed.
 *
 * Split from settings because the two answer different questions. Settings sets a *limit*; this
 * shows what is actually there and offers to delete it. They used to be one section, which worked
 * while everything on disk was reclaimable -- and stopped working the moment a bulk import put
 * hundreds of megabytes on the device that no limit governs.
 *
 * The distinction this screen exists to draw:
 *
 * - **Browsing data** accumulates on its own, is bounded by the card-data limit, and the cache
 *   evicts the least recently used of it without being asked.
 * - **Kept data** is there because the user asked for it -- a downloaded set, an imported
 *   catalogue -- and nothing will ever evict it. It is outside the limit, which is why it needs a
 *   screen: the only thing that removes it is a decision.
 */
object StorageContract :
	ContainerContract<StorageContract.UiState, StorageContract.Intent, StorageContract.Effect>() {

	data class UiState(
		val usage: CacheUsage? = null,
		/** One row per game holding kept records, largest first. */
		val kept: List<KeptGame> = emptyList(),
		val isLoading: Boolean = true,
		/** The game a confirmation is open for, or `null`. */
		val pendingDelete: KeptGame? = null,
		val isDeleting: Boolean = false,
	) {

		/** True when nothing is kept, so the screen says so rather than showing an empty heading. */
		val hasKept: Boolean get() = kept.isNotEmpty()

		/** What every game is keeping. Stated rather than derived from the usage split, which
		 * counts records this screen may not be able to attribute to a game. */
		val keptBytes: Long get() = kept.sumOf { it.bytes }

		/**
		 * Kept records this screen cannot attribute to any game.
		 *
		 * Not hidden. A game whose set list has been evicted still has its pinned sets on disk, and
		 * they cannot be mapped back to a name without it -- so the bytes are real, unattributable,
		 * and saying "other" is the honest way to show them rather than letting the numbers
		 * disagree with the total.
		 */
		val unattributedKeptBytes: Long
			get() = ((usage?.metadataKeptBytes ?: 0L) - keptBytes).coerceAtLeast(0L)
	}

	/**
	 * One game's downloaded records.
	 *
	 * @property sets how many *sets* have card info downloaded, in any language -- comparable with
	 *   [knownSets], which is also sets. Never a record count; see `GameStorage.sets`
	 * @property knownSets how many the game has, or `null` when its catalogue is not cached and no
	 *   denominator can honestly be given
	 * @property extraSets sets held that this game's catalogue does not list -- see
	 *   `GameStorage.extraSets`. Shown only where there is room for the explanation
	 * @property infoLanguages how many sets each language covers. A count per language, not a bare
	 *   list: a bulk import files cards under the language they state, and a handful of records in
	 *   ten other languages is not ten editions of the game
	 * @property thumbnailSets how many sets have their grid pictures downloaded. A count, not
	 *   bytes: images live in the image cache, which is one pool for every game and cannot be
	 *   attributed to one. Full-size art has no entry because it is never bulk-fetched -- it
	 *   arrives as a card is read
	 * @property importedVariant the bulk dump this came from, when it came from one
	 */
	data class KeptGame(
		val game: GameId,
		val displayName: String,
		val sets: Int,
		val bytes: Long,
		val knownSets: Int? = null,
		val thumbnailSets: Int = 0,
		val extraSets: Int = 0,
		val infoLanguages: Map<CardLanguage, Int> = emptyMap(),
		val importedVariant: BulkImportRecord? = null,
	) {

		/** "Card info 988/988 · English · Thumbnails 2" -- only the parts that are there. */
		val summary: String
			get() = buildList {
				add(if (knownSets != null) "Card info $sets/$knownSets" else "Card info $sets")
				if (infoLanguages.isNotEmpty()) add(languageSummary)
				if (thumbnailSets > 0) add("Thumbnails $thumbnailSets")
			}.joinToString(" · ")

		/** Languages by how much of the game each covers, most first. */
		val languagesByCoverage: List<Pair<CardLanguage, Int>>
			get() = infoLanguages.entries
				.sortedWith(compareByDescending<Map.Entry<CardLanguage, Int>> { it.value }
					.thenBy { CardLanguage.PREFERENCE_ORDER.indexOf(it.key) })
				.map { it.key to it.value }

		/**
		 * The language the download is mostly in, and how many others there are.
		 *
		 * Weighted rather than listed, because a flat list answers the wrong question. An
		 * English-only import of Magic files records in eleven languages -- Scryfall's cheap dump
		 * carries the few cards that have no English printing at all -- so "11 languages" was true
		 * and read as though ten editions had been downloaded. Ten of them are one set apiece.
		 */
		private val languageSummary: String
			get() {
				val vByCoverage = languagesByCoverage
				val vMain = vByCoverage.firstOrNull() ?: return ""
				val vRest = vByCoverage.size - 1
				return if (vRest == 0) vMain.first.displayName else "${vMain.first.displayName} +$vRest"
			}
	}

	sealed interface Intent {

		/** Read, or read again after a deletion. */
		data object Refresh : Intent

		data class Loaded(val usage: CacheUsage, val kept: List<KeptGame>) : Intent

		/** Ask before deleting: this is hundreds of megabytes and a long re-download. */
		data class DeleteRequested(val game: KeptGame?) : Intent

		data object DeleteConfirmed : Intent

		data object DeleteFinished : Intent

		/** The two ordinary caches, which need no confirmation: they refill by themselves. */
		data object ClearBrowsingData : Intent

		data object ClearImages : Intent

		/** The back arrow. Navigation goes through the container like everything else. */
		data object BackPressed : Intent

		/** "Cache settings" -- the limits these bars are measured against live in settings. */
		data object CacheSettingsRequested : Intent
	}

	sealed interface Effect {

		/** Said after a deletion, because the number is the point of having asked. */
		data class Deleted(val game: String, val sets: Int) : Effect

		data object NavigateBack : Effect

		data object OpenCacheSettings : Effect
	}

	override fun reduce(state: UiState, intent: Intent): UiState = when (intent) {

		Intent.Refresh -> state.copy(isLoading = true)

		is Intent.Loaded -> state.copy(
			usage = intent.usage,
			kept = intent.kept.sortedByDescending { it.bytes },
			isLoading = false,
		)

		is Intent.DeleteRequested -> state.copy(pendingDelete = intent.game)

		// The dialog closes on confirm rather than when the work finishes: leaving it up over a
		// progress state invites a second tap on a button that has already been pressed.
		Intent.DeleteConfirmed -> state.copy(pendingDelete = null, isDeleting = true)

		Intent.DeleteFinished -> state.copy(isDeleting = false, isLoading = true)

		Intent.ClearBrowsingData, Intent.ClearImages -> state.copy(isLoading = true)

		// Navigation changes no state. The view model turns these into effects.
		Intent.BackPressed, Intent.CacheSettingsRequested -> state
	}
}
