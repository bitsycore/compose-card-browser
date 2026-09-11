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
	 * One product page, resolved by the product id a provider supplied.
	 *
	 * Reachable for Magic and Pokemon, whose sources publish the id: Scryfall's `cardmarket_id`
	 * and TCGdex's `thirdParty.cardmarket`.
	 */
	data class Product(override val url: String, val productId: String) : CardmarketLink {
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
 * ## Card-level links, and the two ways to ask for one
 *
 * There are two, and only one works:
 *
 * ```
 * /{locale}/Magic/Products?idProduct=778435           lands on the card
 * /{locale}/Magic/Products/Singles?idProduct=778435   does not
 * ```
 *
 * Both were put in front of a browser on 2026-09-11, because Cardmarket answers 403 to every
 * scripted request and blocks an automated browser too -- so this is a human's observation and
 * there is no cheaper way to get one.
 *
 * That distinction had been missed here. A note in this project recorded the second shape failing
 * and concluded product ids were useless, so this builder fell through to a search for cards whose
 * exact page was one parameter away -- and the dormant branch that did fire built the failing
 * shape, so the link was broken wherever an id existed.
 *
 * A *slug* path still cannot be synthesised, and that half of the old reasoning holds: a slug like
 * `KaiSa-Survivor-V1-Epic` folds in the apostrophe-stripped name, an abbreviated subtitle, a
 * variant ordinal and the rarity, and Riftcodex publishes no variant ordinal at all. Nothing here
 * guesses one. The id is published, not guessed, which is the difference.
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

		// 1. The exact product, by id.
		//
		//    `/{Game}/Products?idProduct={n}` -- and the missing `/Singles` segment is the whole
		//    point. This used to build `/{Game}/Products/Singles/{n}`, treating the id as though
		//    it were a path slug, which produced a broken link on every Magic and Pokemon card
		//    that carried one. Both shapes were put in front of a browser on 2026-09-11: the one
		//    here lands on the card, and `/Products/Singles?idProduct=` does not. An earlier note
		//    in this project generalised from the second to "ids do not work at all", which is
		//    what kept this branch dormant and wrong.
		val vProductId = printing.externalIds[ExternalIdKey.CARDMARKET_PRODUCT]?.firstOrNull()
		if (vProductId != null) {
			return CardmarketLink.Product(
				url = "$BASE_URL/$UI_LOCALE/$vGame/Products?idProduct=$vProductId",
				productId = vProductId,
			)
		}

		val vExpansion = set?.let(::expansionSlug)
		// Scoped to the expansion where its segment can be worked out, and to the whole game where
		// it cannot -- `/{Game}/Products/Singles` is a search page in its own right, which is what
		// makes a useful link possible for a set whose Cardmarket name we cannot derive.
		val vSinglesPath = vExpansion
			?.let { "$BASE_URL/$UI_LOCALE/$vGame/Products/Singles/$it" }
			?: "$BASE_URL/$UI_LOCALE/$vGame/Products/Singles"

		// 2. A name search scoped to the expansion.
		//
		//    `idExpansion` is included when the provider supplied one and simply left out when it
		//    did not -- Riftcodex has no Cardmarket id for Vendetta, and dropping the whole search
		//    over a missing filter sent people to an unfiltered listing of 358 cards to find the one
		//    they were already looking at. The URL path already names the expansion, so the scope
		//    survives without it; every parameter still comes from the observed URL and none is
		//    invented to replace it.
		val vExpansionId = set?.externalIds?.get(ExternalIdKey.CARDMARKET_EXPANSION)?.firstOrNull()
		val vTerms = searchTermsFor(printing, game)
		if (vTerms.isNotBlank()) {
			val vQuery = buildList {
				// Cardmarket's current search implementation. Sending v1 parameters to a v2 page
				// silently returns the unfiltered listing.
				add("searchMode=v2")
				// The "Cards" category, as opposed to sealed product or accessories -- narrowing an
				// already-Singles listing, so it is a refinement rather than a requirement. Omitted
				// where the id is unknown, which a real Magic search URL confirms is accepted:
				// sending a *wrong* id is what returns nothing, sending none is not.
				game.cardmarketCategoryId?.let { add("idCategory=$it") }
				// 0 is "every expansion", and is what the form submits when none is chosen. Sent
				// explicitly rather than omitted, which is what a real unscoped search URL does.
				add("idExpansion=${vExpansionId ?: 0}")
				add("searchString=${formEncode(vTerms)}")
				// 0 is "any rarity". Sent explicitly because the v2 form always submits it.
				add("idRarity=0")
				add("perSite=$RESULTS_PER_PAGE")
			}.joinToString("&")
			return CardmarketLink.CardSearch(
				url = "$vSinglesPath?$vQuery",
				// Empty when the search covers the whole game rather than one expansion, so the
				// UI can say which it is instead of implying a scope it does not have.
				expansion = vExpansion.orEmpty(),
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
	 *
	 * Games that declare [GameProfile.cardmarketSearchIncludesCode] get the printed code appended,
	 * which is the difference between a page of Yamatos and the Yamato in front of you.
	 */
	internal fun searchTermsFor(printing: CardPrinting, game: GameProfile): String {
		val vName = printing.displayName
			.substringBefore('(')
			.replace(" - ", " ")
			.trim()
		if (!game.cardmarketSearchIncludesCode) return vName
		// The raw code, `OP16-098`, not the bare `098` the model splits out of it -- the split half
		// is a number that matches everything and scopes nothing.
		val vCode = printing.providerRawCollectorNumber.trim()
		return if (vCode.isEmpty() || vName.isEmpty()) vName else "$vName $vCode"
	}

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

	/** Matches the `perSite` value the site's own search form submits. */
	private const val RESULTS_PER_PAGE = 30

	/** The expansion's own page, as distinct from its singles listing. `null` if not derivable. */
	fun expansionLink(set: CardSet, game: GameProfile): String? {
		val vGame = game.cardmarketSlug ?: return null
		val vExpansion = expansionSlug(set) ?: return null
		return "$BASE_URL/$UI_LOCALE/$vGame/Expansions/$vExpansion"
	}
}
