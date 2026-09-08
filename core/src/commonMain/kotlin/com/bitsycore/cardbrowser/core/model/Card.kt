package com.bitsycore.cardbrowser.core.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

// ==================
// MARK: Set
// ==================

/**
 * A regional set or release of a game.
 *
 * Regional releases stay separate. Two sets are the same set only when one provider says so with an
 * explicit id; nothing here infers a link from a shared name or a shared release week.
 *
 * @property id source-qualified, so a set from a second provider never collides with this one
 * @property code the provider's short code, e.g. `OGN`. Shown to the user and used for its filters
 * @property releaseDate `null` when the provider does not state one; the set list sorts those last
 * @property externalIds marketplace and third-party ids the provider supplied, keyed by [ExternalIdKey]
 */
@Serializable
data class CardSet(
	val id: SourceId,
	val game: Game,
	val code: String,
	val name: String,
	val cardCount: Int?,
	val releaseDate: LocalDate?,
	val externalIds: Map<String, List<String>> = emptyMap(),
) {

	/** The provider that supplied this record. Kept for provenance in the UI and in the cache. */
	val provider: ProviderId get() = id.provider
}

/** Keys used in [CardSet.externalIds] and [CardPrinting.externalIds]. */
object ExternalIdKey {

	/** A Cardmarket *expansion* id. Not a product id -- see `CardmarketLinkBuilder`. */
	const val CARDMARKET_EXPANSION = "cardmarket.expansion"

	/** A Cardmarket product id, which is what a direct product URL needs. */
	const val CARDMARKET_PRODUCT = "cardmarket.product"

	/** A TCGplayer group id (sets) or product id (cards). */
	const val TCGPLAYER = "tcgplayer"

	/** The game publisher's own card id, where the provider exposes one. */
	const val PUBLISHER_CARD = "publisher.card"

	/**
	 * The provider's own internal record id, when the app keys on something else.
	 *
	 * Riftbound sets are keyed by their game code rather than by Riftcodex's database id, so the
	 * database id is kept here for provenance and for debugging against the provider's own API.
	 */
	const val PROVIDER_RECORD = "provider.record"
}

// ==================
// MARK: Artwork and finish
// ==================

/**
 * How a printing's art differs from the plain version of the same card.
 *
 * A treatment is a property of the artwork, not of the finish: a card can be alternate-art and
 * non-foil at once. [UNKNOWN] is for providers that expose art variants without saying what kind.
 */
@Serializable
enum class ArtworkTreatment(val displayName: String) {
	STANDARD("Standard"),
	ALTERNATE_ART("Alternate art"),
	EXTENDED_ART("Extended art"),
	FULL_ART("Full art"),
	OVERNUMBERED("Overnumbered"),
	SIGNATURE("Signature"),
	PROMO("Promo"),
	UNKNOWN("Other treatment"),
}

/**
 * One distinct piece of art for a printing.
 *
 * The grid puts a tile per distinct artwork, so this is what a tile is keyed on. Finishes and
 * languages do not multiply tiles -- they are choices inside the detail screen.
 *
 * @property imageUrl the largest image the provider offers
 * @property thumbnailUrl a small variant for the grid and the preview strip, or `null` when the
 *   provider's CDN cannot resize, in which case the grid falls back to [imageUrl]
 * @property displayUrl the variant the detail screen and fullscreen viewer load: native resolution,
 *   compressed. `null` falls back to [imageUrl]
 * @property language the language of the *image*, which is not always the language of the text
 */
@Serializable
data class Artwork(
	val id: SourceId,
	val imageUrl: String,
	val thumbnailUrl: String?,
	val displayUrl: String? = null,
	val artist: String?,
	val treatment: ArtworkTreatment,
	val language: CardLanguage?,
	val accessibilityText: String? = null,
)

/** A physical finish. Which of these a printing actually exists in is [FinishCoverage]. */
@Serializable
enum class Finish(val displayName: String) {
	NON_FOIL("Non-foil"),
	FOIL("Foil"),
	ETCHED("Etched foil"),
	TEXTURED("Textured foil"),
}

/**
 * What a provider can say about the finishes of one printing.
 *
 * Same three-way split as [LanguageCoverage], and for the same reason: a provider with no finish
 * field is not asserting that no foil exists.
 */
@Serializable
data class FinishCoverage(
	val confirmed: Set<Finish> = emptySet(),
	val absent: Set<Finish> = emptySet(),
) {

	/** How [finish] stands for this printing. */
	fun availabilityOf(finish: Finish): Availability = when (finish) {
		in confirmed -> Availability.AVAILABLE
		in absent -> Availability.UNAVAILABLE
		else -> Availability.UNKNOWN
	}

	/** True when the provider stated nothing at all about finishes for this printing. */
	val isUnstated: Boolean get() = confirmed.isEmpty() && absent.isEmpty()
}

// ==================
// MARK: Card
// ==================

/** Game rules values a printing may carry. All nullable: a spell has no might, a token no rarity. */
@Serializable
data class CardAttributes(
	val energy: Int? = null,
	val might: Int? = null,
	val power: Int? = null,
)

/** How a printing is categorised by the game. */
@Serializable
data class CardClassification(
	val type: String? = null,
	val supertype: String? = null,
	val rarity: String? = null,
	val domains: List<String> = emptyList(),
)

/** Display text in one language. [isProviderStated] is false when the language was inferred. */
@Serializable
data class LocalizedText(
	val language: CardLanguage?,
	val name: String,
	val rules: String? = null,
	val flavour: String? = null,
	val isProviderStated: Boolean = true,
)

/**
 * The identity of a card across its printings, where a provider states one reliably.
 *
 * Null on [CardPrinting] whenever the provider gives no such relationship. Nothing in this app
 * invents one: two printings are the same card because a provider said so, never because they share
 * a name. There is no cross-provider identity at all, which is why this is a [SourceId].
 */
@Serializable
data class CardIdentity(
	val id: SourceId,
	val name: String,
)

/**
 * A specific printing of a card in a specific set: the unit the grid and the detail screen show.
 *
 * This is deliberately *not* "a card". A card identity, a set printing, an artwork, a finish and a
 * printing language are five separate things, and this type is the third of them with the others
 * hanging off it.
 *
 * @property collectorNumber a string, always. Collector numbers carry letters, leading zeroes and
 *   suffixes; a provider that happens to expose an integer is normalised into a string here and the
 *   original is kept in [providerRawCollectorNumber] rather than being the value the app reasons on
 * @property identity `null` when the provider states no cross-printing relationship
 * @property languages what is known about printing languages -- often [LanguageCoverage.isUnstated]
 * @property finishes what is known about finishes -- often [FinishCoverage.isUnstated]
 */
@Serializable
data class CardPrinting(
	val id: SourceId,
	val game: Game,
	val setId: SourceId,
	val setCode: String,
	val setName: String,
	val collectorNumber: String,
	val providerRawCollectorNumber: String,
	val identity: CardIdentity?,
	val text: LocalizedText,
	val artwork: Artwork,
	val attributes: CardAttributes = CardAttributes(),
	val classification: CardClassification = CardClassification(),
	val tags: List<String> = emptyList(),
	val languages: LanguageCoverage = LanguageCoverage(),
	val finishes: FinishCoverage = FinishCoverage(),
	val externalIds: Map<String, List<String>> = emptyMap(),
	val orientation: CardOrientation = CardOrientation.PORTRAIT,
) {

	/** The provider that supplied this record. */
	val provider: ProviderId get() = id.provider

	/** The name to show, which is the text's name rather than any identity's. */
	val displayName: String get() = text.name

	/**
	 * Collector numbers in natural order: `2` before `10`, and `10a` after `10`.
	 *
	 * A plain string sort puts `10` before `2`, which is the single most visible way a card grid
	 * can look broken. Numeric runs are compared as numbers and everything else as text.
	 */
	val collectorSortKey: List<CollectorSortPart> get() = naturalParts(collectorNumber)

	companion object {

		/** Splits a collector number into alternating numeric and textual runs. */
		internal fun naturalParts(value: String): List<CollectorSortPart> {
			val vParts = mutableListOf<CollectorSortPart>()
			var vIndex = 0
			while (vIndex < value.length) {
				val vDigit = value[vIndex].isDigit()
				var vEnd = vIndex
				while (vEnd < value.length && value[vEnd].isDigit() == vDigit) vEnd++
				val vChunk = value.substring(vIndex, vEnd)
				vParts += if (vDigit) {
					// A collector number long enough to overflow Long is not a collector number,
					// but it must not crash the grid either.
					CollectorSortPart(number = vChunk.toLongOrNull() ?: Long.MAX_VALUE, text = null)
				} else {
					CollectorSortPart(number = null, text = vChunk.lowercase())
				}
				vIndex = vEnd
			}
			return vParts
		}
	}
}

/** One run of a collector number: either a number or a piece of text, never both. */
@Serializable
data class CollectorSortPart(
	val number: Long?,
	val text: String?,
) : Comparable<CollectorSortPart> {

	override fun compareTo(other: CollectorSortPart): Int = when {
		number != null && other.number != null -> number.compareTo(other.number)
		text != null && other.text != null -> text.compareTo(other.text)
		// A numeric run sorts before a textual one, so `10` precedes `10a`.
		number != null -> -1
		else -> 1
	}
}

/** Which way a card is printed. Landscape cards need a different tile aspect in the grid. */
@Serializable
enum class CardOrientation {
	PORTRAIT,
	LANDSCAPE,
}

/** Orders printings by [CardPrinting.collectorSortKey], then by name to break ties. */
object CollectorNumberComparator : Comparator<CardPrinting> {

	override fun compare(a: CardPrinting, b: CardPrinting): Int {
		val vLeft = a.collectorSortKey
		val vRight = b.collectorSortKey
		for (vIndex in 0 until minOf(vLeft.size, vRight.size)) {
			val vResult = vLeft[vIndex].compareTo(vRight[vIndex])
			if (vResult != 0) return vResult
		}
		val vBySize = vLeft.size.compareTo(vRight.size)
		return if (vBySize != 0) vBySize else a.displayName.compareTo(b.displayName)
	}
}
