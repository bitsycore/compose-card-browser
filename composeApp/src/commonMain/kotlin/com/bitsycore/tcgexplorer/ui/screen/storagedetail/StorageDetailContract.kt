package com.bitsycore.tcgexplorer.ui.screen.storagedetail

import com.bitsycore.tcgexplorer.core.model.CardLanguage
import com.bitsycore.tcgexplorer.core.model.GameId
import com.bitsycore.tcgexplorer.data.repository.KeptSet
import com.bitsycore.lib.pulse.container.ContainerContract

/**
 * What one game has downloaded, in enough detail to remove part of it.
 *
 * The storage screen answers "how much of this game is on the device". This answers "which parts",
 * because those are different questions and only the second one can be acted on. A reader who
 * downloaded a set in two languages and wants one back has no way to say so from a row that offers
 * a single trash icon for the whole game.
 *
 * Grouped by language rather than by set. That is the axis a person actually wants to drop -- a
 * whole second edition of everything -- and a set appears under each language it is held in, which
 * is what is on disk.
 */
object StorageDetailContract : ContainerContract<
	StorageDetailContract.UiState,
	StorageDetailContract.Intent,
	StorageDetailContract.Effect,
	>() {

	data class UiState(
		val game: GameId? = null,
		val displayName: String = "",
		val sets: List<KeptSet> = emptyList(),
		val isLoading: Boolean = true,
		/** What a confirmation is open for, or null. */
		val pendingDelete: Target? = null,
		val isDeleting: Boolean = false,
	) {

		val totalBytes: Long get() = sets.sumOf { it.bytes }

		val totalCards: Int get() = sets.sumOf { it.cardCount }

		/** Distinct sets, not editions -- a set held in two languages is one set. */
		val setCount: Int get() = sets.map { it.setId }.distinct().size

		/**
		 * The headline, worded so that every number counts the population its noun names.
		 *
		 * "3 sets - 986 cards" was wrong and looked fine: the sets were counted distinct and the
		 * cards per edition, so those three sets held 734 cards and not 986. The extra 252 are the
		 * French printing of one of them. Where there is more than one language the sentence
		 * therefore leads with editions, which is what the card total actually belongs to.
		 */
		val summary: String
			get() = buildString {
				if (hasSeveralLanguages) {
					append(plural(sets.size, "edition"))
					append(" of ")
					append(plural(setCount, "set"))
				} else {
					append(plural(setCount, "set"))
				}
				append(" · ")
				append(plural(totalCards, "card"))
			}

		/**
		 * The editions, grouped by the language they are stored under, heaviest group first.
		 *
		 * The key is the stored code rather than a [CardLanguage], because `"-"` -- the source
		 * stated none -- is a real group and has no enum value. Rendering it as English would be
		 * the claim this app does not make.
		 */
		val byLanguage: List<LanguageGroup>
			get() = sets
				.groupBy { it.languageCode }
				.map { (vCode, vSets) ->
					LanguageGroup(
						code = vCode,
						language = CardLanguage.fromCode(vCode),
						// By code where there is one, because the code is now the first thing
						// on the row and "OP-02, OP-01" reads as a mistake. Sets without a code
						// fall back to the name and sort after, which keeps the order stable.
						sets = vSets.sortedWith(
							compareBy({ it.code == null }, { it.code ?: it.label }),
						),
					)
				}
				.sortedByDescending { it.bytes }

		/** True when there is more than one language, so dropping one is a meaningful offer. */
		val hasSeveralLanguages: Boolean get() = byLanguage.size > 1
	}

	/** One language's downloaded sets. */
	data class LanguageGroup(
		val code: String,
		val language: CardLanguage?,
		val sets: List<KeptSet>,
	) {

		/** "English", or "No language stated" where the source names none. */
		val displayName: String get() = language?.displayName ?: "No language stated"

		val bytes: Long get() = sets.sumOf { it.bytes }

		val cardCount: Int get() = sets.sumOf { it.cardCount }

		/** Every set here is one edition, so the two nouns count the same rows. */
		val summary: String get() = "${plural(sets.size, "set")} · ${plural(cardCount, "card")}"
	}

	/** "1 set", "3 sets". An "s" on a one is the kind of thing that reads as unfinished. */
	private fun plural(count: Int, noun: String): String =
		if (count == 1) "1 $noun" else "$count ${noun}s"

	/** What a delete is aimed at. Carried on the intent -- see [Intent.DeleteConfirmed]. */
	sealed interface Target {

		/** The label a confirmation names, so the dialog does not have to branch. */
		val label: String

		/** How much goes. */
		val bytes: Long

		data class OneSet(val set: KeptSet) : Target {

			override val label: String get() = set.label

			override val bytes: Long get() = set.bytes
		}

		data class WholeLanguage(val group: LanguageGroup) : Target {

			override val label: String get() = "every ${group.displayName} set"

			override val bytes: Long get() = group.bytes
		}
	}

	sealed interface Intent {

		/** Read, or read again after a deletion. */
		data object Refresh : Intent

		data class Loaded(val sets: List<KeptSet>, val displayName: String) : Intent

		/** Ask first. A set is a download, not a cache entry. */
		data class DeleteRequested(val target: Target?) : Intent

		/**
		 * Carries its target, because the reducer has already closed the dialog by the time the
		 * view model runs -- see `PulseDispatchOrderTest`.
		 */
		data class DeleteConfirmed(val target: Target) : Intent

		data object DeleteFinished : Intent

		data object BackPressed : Intent
	}

	sealed interface Effect {

		/** Said after a deletion, because the number is why it was worth confirming. */
		data class Deleted(val label: String, val sets: Int) : Effect

		data object NavigateBack : Effect

		/** Nothing is left, so there is nothing to stay for. */
		data object NavigateBackEmpty : Effect
	}

	override fun reduce(state: UiState, intent: Intent): UiState = when (intent) {

		Intent.Refresh -> state.copy(isLoading = true)

		is Intent.Loaded -> state.copy(
			sets = intent.sets,
			displayName = intent.displayName,
			isLoading = false,
		)

		is Intent.DeleteRequested -> state.copy(pendingDelete = intent.target)

		// Closes on confirm rather than when the work finishes: a dialog left up over a progress
		// state invites a second tap on a button that has already been pressed.
		is Intent.DeleteConfirmed -> state.copy(pendingDelete = null, isDeleting = true)

		Intent.DeleteFinished -> state.copy(isDeleting = false, isLoading = true)

		Intent.BackPressed -> state
	}
}
