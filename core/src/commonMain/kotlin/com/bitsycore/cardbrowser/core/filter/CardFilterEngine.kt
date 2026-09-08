package com.bitsycore.cardbrowser.core.filter

import com.bitsycore.cardbrowser.core.model.Availability
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CollectorNumberComparator
import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.provider.CardSortField
import com.bitsycore.cardbrowser.core.provider.SortDirection

/**
 * Applies a [CardQuery] to printings the app already holds.
 *
 * Pure and synchronous, so it can be unit-tested without a provider and reused by any caller that
 * has a complete set in hand. Whether running it is *legitimate* -- that is, whether the caller
 * really has the complete set rather than one page -- is the repository's business, not this
 * object's; see `CardRepository`.
 */
object CardFilterEngine {

	/** Filters then sorts [cards]. Order matters: sorting a filtered list is cheaper. */
	fun apply(cards: List<CardPrinting>, query: CardQuery): List<CardPrinting> =
		sort(cards.filter { matches(it, query) }, query)

	/**
	 * Whether one printing satisfies every active part of [query].
	 *
	 * AND across fields, OR within a field: "Fury or Chaos" and "Epic" means a Fury Epic card and a
	 * Chaos Epic card both match, and a Fury Common does not.
	 */
	fun matches(card: CardPrinting, query: CardQuery): Boolean {
		if (!matchesText(card, query.text)) return false

		if (query.domains.isNotEmpty() &&
			card.classification.domains.none { it in query.domains }
		) {
			return false
		}

		if (query.cardTypes.isNotEmpty() && card.classification.type !in query.cardTypes) return false
		if (query.rarities.isNotEmpty() && card.classification.rarity !in query.rarities) return false

		if (query.energyCosts.isNotEmpty() && card.attributes.energy !in query.energyCosts) return false

		if (query.treatments.isNotEmpty() && card.artwork.treatment !in query.treatments) return false

		// Finish and language are matched on *confirmed* availability only. A printing whose finish
		// coverage is unstated does not match a foil filter: unknown is not a yes. Offering the
		// filter at all is gated on provider capability, so this is a backstop rather than the main
		// defence, but it is the one that keeps an unknown from being silently counted as a match.
		if (query.finishes.isNotEmpty() &&
			query.finishes.none { card.finishes.availabilityOf(it) == Availability.AVAILABLE }
		) {
			return false
		}

		if (query.languages.isNotEmpty() &&
			query.languages.none { card.languages.availabilityOf(it) == Availability.AVAILABLE }
		) {
			return false
		}

		return true
	}

	/**
	 * Free-text matching over name and collector number.
	 *
	 * Case- and accent-insensitive on the name, because a French card name typed without accents
	 * should still find it. Collector number matching is a prefix test rather than a substring one:
	 * typing `1` should offer 1, 10 and 100, not every card with a 1 anywhere in its number.
	 */
	private fun matchesText(card: CardPrinting, text: String?): Boolean {
		val vNeedle = text?.trim()?.takeIf { it.isNotEmpty() } ?: return true
		val vFolded = fold(vNeedle)
		if (fold(card.displayName).contains(vFolded)) return true
		if (card.collectorNumber.lowercase().startsWith(vNeedle.lowercase())) return true
		return false
	}

	/**
	 * Lowercases and strips the accents this app's languages actually use.
	 *
	 * A table rather than Unicode normalisation because `java.text.Normalizer` is not multiplatform
	 * and pulling in an ICU dependency to fold a dozen French characters is not a trade worth
	 * making. Japanese and Korean text is left alone: neither has accents to fold, and both compare
	 * correctly as-is.
	 */
	internal fun fold(value: String): String {
		val vBuilder = StringBuilder(value.length)
		for (vChar in value.lowercase()) {
			vBuilder.append(ACCENT_FOLDING[vChar] ?: vChar)
		}
		return vBuilder.toString()
	}

	private val ACCENT_FOLDING: Map<Char, Char> = mapOf(
		'à' to 'a', 'â' to 'a', 'ä' to 'a', 'á' to 'a', 'ã' to 'a', 'å' to 'a',
		'ç' to 'c',
		'è' to 'e', 'é' to 'e', 'ê' to 'e', 'ë' to 'e',
		'ì' to 'i', 'í' to 'i', 'î' to 'i', 'ï' to 'i',
		'ñ' to 'n',
		'ò' to 'o', 'ó' to 'o', 'ô' to 'o', 'ö' to 'o', 'õ' to 'o',
		'ù' to 'u', 'ú' to 'u', 'û' to 'u', 'ü' to 'u',
		'ý' to 'y', 'ÿ' to 'y',
		'œ' to 'o', 'æ' to 'a',
	)

	/** Sorts by the query's field, falling back to collector order to keep ties stable. */
	fun sort(cards: List<CardPrinting>, query: CardQuery): List<CardPrinting> {
		val vComparator: Comparator<CardPrinting> = when (query.sortBy) {
			CardSortField.COLLECTOR_NUMBER -> CollectorNumberComparator
			CardSortField.NAME -> compareBy<CardPrinting> { fold(it.displayName) }
				.then(CollectorNumberComparator)
			// Rarity has no inherent order the provider states, so it is alphabetical and
			// deliberately so -- inventing a Common-to-Mythic ranking would be a guess about a game
			// whose rarity ladder the provider never describes.
			CardSortField.RARITY -> compareBy<CardPrinting> { it.classification.rarity ?: "" }
				.then(CollectorNumberComparator)
			// Cards with no energy cost sort last rather than as zero: a spell with no cost is not
			// a zero-cost card.
			CardSortField.ENERGY_COST -> compareBy<CardPrinting>(
				{ it.attributes.energy == null },
				{ it.attributes.energy ?: 0 },
			).then(CollectorNumberComparator)
		}
		return if (query.sortDirection == SortDirection.DESCENDING) {
			cards.sortedWith(vComparator.reversed())
		} else {
			cards.sortedWith(vComparator)
		}
	}

	/**
	 * The filter values actually present in a set, so the UI offers only what can match.
	 *
	 * Derived from the cards in hand rather than from a provider index: an index lists every value
	 * the game has, and offering "Mythic" as a filter for a set with no mythics produces an empty
	 * result the user cannot explain.
	 */
	fun facetsOf(cards: List<CardPrinting>): CardFacets = CardFacets(
		domains = cards.flatMap { it.classification.domains }.distinct().sorted(),
		cardTypes = cards.mapNotNull { it.classification.type }.distinct().sorted(),
		rarities = cards.mapNotNull { it.classification.rarity }.distinct().sorted(),
		energyCosts = cards.mapNotNull { it.attributes.energy }.distinct().sorted(),
		treatments = cards.map { it.artwork.treatment }.distinct().sortedBy { it.ordinal },
	)
}

/** The distinct filterable values found in a set of cards. */
data class CardFacets(
	val domains: List<String> = emptyList(),
	val cardTypes: List<String> = emptyList(),
	val rarities: List<String> = emptyList(),
	val energyCosts: List<Int> = emptyList(),
	val treatments: List<com.bitsycore.cardbrowser.core.model.ArtworkTreatment> = emptyList(),
) {

	/** True when there is nothing to offer, so the filter sheet stays closed. */
	val isEmpty: Boolean
		get() = domains.isEmpty() && cardTypes.isEmpty() && rarities.isEmpty() &&
			energyCosts.isEmpty() && treatments.isEmpty()
}
