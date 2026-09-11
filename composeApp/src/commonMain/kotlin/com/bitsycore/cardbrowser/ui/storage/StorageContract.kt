package com.bitsycore.cardbrowser.ui.storage

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
	 * One game's kept records.
	 *
	 * @property sets how many set records are pinned, which for an imported catalogue is most of
	 *   the game and for a few downloads is a handful
	 * @property importedVariant the bulk dump this came from, when it came from one. Null for sets
	 *   downloaded individually
	 */
	data class KeptGame(
		val game: GameId,
		val displayName: String,
		val sets: Int,
		val bytes: Long,
		val importedVariant: BulkImportRecord? = null,
	)

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
	}

	sealed interface Effect {

		/** Said after a deletion, because the number is the point of having asked. */
		data class Deleted(val game: String, val sets: Int) : Effect
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
	}
}
