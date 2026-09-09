package com.bitsycore.cardbrowser.core.cardmarket

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.ExternalIdKey

// ==================
// MARK: Link kinds
// ==================

/**
 * A link out to Cardmarket, and how precise it actually is.
 *
 * Opening one never places an order: it is outbound navigation to a public web page, nothing is
 * embedded, and no Cardmarket API is called anywhere in this app.
 *
 * The three cases exist so the UI can be honest about precision. A user who taps a button labelled
 * "View on Cardmarket" and lands on a set listing has been misled; a user who taps "Browse Origins
 * singles" and lands there has not.
 */
sealed interface CardmarketLink {

	val url: String

	/** Button text. Names what the link actually opens, never more. */
	val label: String

	/**
	 * One product page, from a product path a provider supplied.
	 *
	 * Not reachable with the providers integrated today -- see [CardmarketLinkBuilder.linkFor].
	 */
	data class Product(override val url: String, val productPath: String) : CardmarketLink {
		override val label: String get() = "View on Cardmarket"
	}

	/**
	 * A search for this card's name, scoped to its expansion.
	 *
	 * The most precise link this app can build without a product slug, and precise enough in
	 * practice: the result is the card's own printings rather than the whole set.
	 */
	data class CardSearch(
		override val url: String,
		val expansion: String,
		val terms: String,
	) : CardmarketLink {
		override val label: String get() = "Find on Cardmarket"
	}

	/** Every single from one expansion. The precise card is one click away, chosen by the user. */
	data class ExpansionSingles(override val url: String, val expansion: String) : CardmarketLink {
		override val label: String get() = "Browse $expansion singles"
	}

	/**
	 * The game's Cardmarket section, when even the expansion slug is not known.
	 *
	 * @property gameName the game this actually opens. Named rather than hardcoded: the label read
	 *   "Open Riftbound on Cardmarket" on every game's cards, including the six it was not
	 */
	data class GameHome(override val url: String, val gameName: String) : CardmarketLink {
		override val label: String get() = "Open $gameName on Cardmarket"
	}
}

// ==================
// MARK: Builder
// ==================

/**
 * Builds Cardmarket URLs, and nothing else.
 *
 * Pure string work, no I/O, deliberately separate from catalogue fetching and from the platform
 * browser launcher so it can be unit-tested and so neither of those has to know it exists.
 *
 * ## URL shapes
 *
 * Confirmed by inspection of live Cardmarket pages:
 *
 * ```
 * /{locale}/Riftbound/Expansions/Origins                        an expansion
 * /{locale}/Riftbound/Products/Singles/Origins                  that expansion's singles
 * /{locale}/Riftbound/Products/Singles/Origins/Charm            one card
 * /{locale}/Riftbound/Products/Singles/Origins/KaiSa-Survivor-V1-Epic
 * ```
 *
 * ## Why no card-level link is generated
 *
 * The last example is the reason. A card slug is not the card's name: `KaiSa-Survivor-V1-Epic`
 * folds in the apostrophe-stripped name, an abbreviated subtitle, a variant ordinal and the rarity.
 * Riftcodex publishes none of that in a reconstructible form -- it has no variant ordinal at all --
 * so synthesising a slug would produce a 404 for every card whose name is not a single plain word.
 * Guessing product slugs is exactly what the brief forbids, so this builder stops at the expansion
 * and lets the user pick the printing on a page that really lists them.
 *
 * The moment a provider supplies a real Cardmarket product path under
 * [ExternalIdKey.CARDMARKET_PRODUCT], [linkFor] uses it and the user gets the exact page.
 *
 * ## Search parameters
 *
 * One further shape is confirmed, a scoped search within an expansion:
 *
 * ```
 * /{locale}/Riftbound/Products/Singles/Origins
 *     ?searchMode=v2&idCategory=1655&idExpansion=6286
 *     &searchString=Kai%27sa+Survivor&idRarity=0&perSite=30
 * ```
 *
 * `idExpansion=6286` is the same value Riftcodex publishes as Origins' `cardmarket_id`, so the two
 * sources line up without a mapping table of our own. That is what makes [CardmarketLink.CardSearch]
 * possible, and it is used whenever the provider supplied an expansion id.
 *
 * ## What is deliberately absent
 *
 * No language preset, no minimum-condition preset, no finish preset, no seller-country preset. Only
 * the six parameters above have been seen on a real Cardmarket URL; anything else would be a guess,
 * and a guessed parameter that the site drops silently is worse than none. Buying preferences are a
 * separate concern from browsing preferences and are intentionally left unset.
 *
 * The `{locale}` segment is the *site's* UI language, not a card language. It is fixed to English
 * because this app's UI is English; it says nothing about the language of the cards on the page.
 */
object CardmarketLinkBuilder {

	private const val BASE_URL = "https://www.cardmarket.com"

	/** The site UI language segment. Not a card-language preset -- see the class doc. */
	private const val UI_LOCALE = "en"

	/**
	 * Cardmarket's path segment for a set, or `null` when it cannot be known without guessing.
	 *
	 * Two cases produce an answer:
	 *
	 * 1. The set name is a single word with no punctuation, in which case the segment is that same
	 *    word. `Origins` is confirmed against a live page, and `Spiritforged`, `Unleashed` and
	 *    `Vendetta` follow the identical rule -- this is not a guess about slug *format*, it is the
	 *    unchanged string.
	 * 2. Nothing else. A name like "Origins: Proving Grounds" or "Riftbound Judge Promotional Cards"
	 *    could be hyphenated, truncated or abbreviated on Cardmarket's side and there is no way to
	 *    tell which without looking, so those fall back to the game page.
	 */
	fun expansionSlug(set: CardSet): String? {
		val vName = set.name.trim()
		val vIsSingleWord = vName.isNotEmpty() && vName.all { it.isLetterOrDigit() }
		return if (vIsSingleWord) vName else null
	}

	/**
	 * The most precise honest link for [printing], given what is known about its [set].
	 *
	 * Falls back down the three [CardmarketLink] cases in order of precision. Returns `null` only
	 * when the game itself has no known Cardmarket section, because a button that lands nowhere is
	 * worse than no button.
	 *
	 * @param set the printing's set, needed for the expansion segment. Pass `null` when it is not
	 *   to hand and the link degrades to the game page rather than failing
	 * @param game the printing's game, which is where the Cardmarket path segment comes from. A
	 *   game whose segment has never been confirmed against a real page declares none, and this
	 *   returns `null` rather than shipping a button that lands on a 404
	 */
	fun linkFor(printing: CardPrinting, set: CardSet?, game: GameProfile): CardmarketLink? {
		val vGame = game.cardmarketSlug ?: return null

		// 1. An exact product path, if a provider ever supplies one.
		val vProductPath = printing.externalIds[ExternalIdKey.CARDMARKET_PRODUCT]?.firstOrNull()
		if (vProductPath != null) {
			return CardmarketLink.Product(
				url = "$BASE_URL/$UI_LOCALE/$vGame/Products/Singles/$vProductPath",
				productPath = vProductPath,
			)
		}

		val vExpansion = set?.let(::expansionSlug)
		val vSinglesPath = vExpansion?.let { "$BASE_URL/$UI_LOCALE/$vGame/Products/Singles/$it" }

		// 2. A name search scoped to the expansion.
		//
		//    `idExpansion` is included when the provider supplied one and simply left out when it
		//    did not -- Riftcodex has no Cardmarket id for Vendetta, and dropping the whole search
		//    over a missing filter sent people to an unfiltered listing of 358 cards to find the one
		//    they were already looking at. The URL path already names the expansion, so the scope
		//    survives without it; every parameter still comes from the observed URL and none is
		//    invented to replace it.
		val vExpansionId = set?.externalIds?.get(ExternalIdKey.CARDMARKET_EXPANSION)?.firstOrNull()
		val vTerms = searchTermsFor(printing)
		if (vSinglesPath != null && vTerms.isNotBlank()) {
			val vQuery = buildList {
				// Cardmarket's current search implementation. Sending v1 parameters to a v2 page
				// silently returns the unfiltered listing.
				add("searchMode=v2")
				// The "Cards" product category, as opposed to sealed product or accessories.
				add("idCategory=$CATEGORY_CARDS")
				vExpansionId?.let { add("idExpansion=$it") }
				add("searchString=${formEncode(vTerms)}")
				// 0 is "any rarity". Sent explicitly because the v2 form always submits it.
				add("idRarity=0")
				add("perSite=$RESULTS_PER_PAGE")
			}.joinToString("&")
			return CardmarketLink.CardSearch(
				url = "$vSinglesPath?$vQuery",
				expansion = vExpansion,
				terms = vTerms,
			)
		}

		// 3. The expansion's singles listing, unfiltered.
		if (vExpansion != null) {
			return CardmarketLink.ExpansionSingles(
				url = "$BASE_URL/$UI_LOCALE/$vGame/Products/Singles/$vExpansion",
				expansion = vExpansion,
			)
		}

		// 4. The game's section.
		return CardmarketLink.GameHome(
			url = "$BASE_URL/$UI_LOCALE/$vGame",
			gameName = game.displayName,
		)
	}

	/**
	 * What to type into Cardmarket's search box for [printing].
	 *
	 * Riftcodex writes champion names as `Kai'Sa - Survivor`; Cardmarket's search does not match the
	 * hyphen separator, so it is replaced by a space. A parenthesised suffix such as `(Signature)`
	 * is dropped too -- it is this provider's own annotation, not part of the printed name, and
	 * including it returns nothing. The apostrophe is kept and percent-encoded, which the verified
	 * URL shows Cardmarket accepting as `%27`.
	 */
	internal fun searchTermsFor(printing: CardPrinting): String =
		printing.displayName
			.substringBefore('(')
			.replace(" - ", " ")
			.trim()

	/**
	 * Percent-encodes [value] the way an HTML form does, with `+` for spaces.
	 *
	 * Hand-rolled because `java.net.URLEncoder` is not multiplatform. Unreserved characters per RFC
	 * 3986 pass through, a space becomes `+`, and everything else is encoded from its UTF-8 bytes --
	 * which is what makes an accented or apostrophised card name survive the trip.
	 */
	internal fun formEncode(value: String): String {
		val vBuilder = StringBuilder(value.length)
		for (vByte in value.encodeToByteArray()) {
			val vCode = vByte.toInt() and 0xFF
			val vChar = vCode.toChar()
			when {
				vCode < 0x80 && (vChar.isLetterOrDigit() || vChar in "-_.~") -> vBuilder.append(vChar)
				vChar == ' ' -> vBuilder.append('+')
				else -> {
					vBuilder.append('%')
					vBuilder.append(HEX[(vCode shr 4) and 0xF])
					vBuilder.append(HEX[vCode and 0xF])
				}
			}
		}
		return vBuilder.toString()
	}

	private const val HEX = "0123456789ABCDEF"

	/** Cardmarket's "Cards" category id, from a verified search URL. */
	private const val CATEGORY_CARDS = 1655

	/** Matches the `perSite` value the site's own search form submits. */
	private const val RESULTS_PER_PAGE = 30

	/** The expansion's own page, as distinct from its singles listing. `null` if not derivable. */
	fun expansionLink(set: CardSet, game: GameProfile): String? {
		val vGame = game.cardmarketSlug ?: return null
		val vExpansion = expansionSlug(set) ?: return null
		return "$BASE_URL/$UI_LOCALE/$vGame/Expansions/$vExpansion"
	}
}
