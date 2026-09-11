package com.bitsycore.cardbrowser.core.tcgplayer

import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.ExternalIdKey

/**
 * A link out to TCGplayer.
 *
 * One case, deliberately. Opening it never places an order: it is outbound navigation to a public
 * web page, nothing is embedded, and no TCGplayer API is called anywhere in this app.
 *
 * @property productId the id the provider published, kept so a screen can show provenance
 */
data class TcgplayerLink(val url: String, val productId: String) {

	/** Button text. Names what the link actually opens, never more. */
	val label: String get() = "View on TCGplayer"
}

/**
 * Builds TCGplayer URLs, and nothing else.
 *
 * Pure string work, no I/O, and no game table -- which is the difference from
 * `CardmarketLinkBuilder` and the reason this is so much smaller. TCGplayer resolves a product
 * from its id alone, with no category segment and no slug, so there is nothing here that has to
 * be confirmed per game.
 *
 * ## Where the ids come from
 *
 * Four of the eight sources already publish one, and the app already stores them all under
 * [ExternalIdKey.TCGPLAYER] -- they were mapped for provenance and nothing read them:
 *
 * - **Scryfall** `tcgplayer_id`, for Magic
 * - **TCGdex** `thirdParty.tcgplayer`, for Pokemon
 * - **Riftcodex** `tcgplayer_id`, for Riftbound
 * - **TCGCSV** the `productId`, for Lorcana, Cyberpunk and the WoW TCG -- which is TCGplayer's
 *   own id by definition, since TCGCSV is a mirror of their catalogue
 *
 * ## What is deliberately absent
 *
 * **No search fallback.** Cardmarket gets one because its per-expansion search URL was confirmed
 * against a real page; TCGplayer's would need a per-game path segment (`/search/magic/product`)
 * invented for ten games and verified for none. A card with no id gets no button, which is the
 * same rule the rest of this codebase follows.
 *
 * **No affiliate wrapper.** Scryfall publishes its TCGplayer links through
 * `partner.tcgplayer.com` with its own partner id attached. Following that would attribute this
 * app's traffic to Scryfall, and adding *any* affiliate code is a decision for the project owner
 * rather than something to inherit by copying a URL. The plain product URL is used.
 *
 * ## Not verified
 *
 * `https://www.tcgplayer.com/product/{id}` **has not been confirmed against a rendered page.**
 * TCGplayer answers 200 with an identical 41,461-byte shell for a real id, a nonsense id and a
 * Pokemon id alike -- the page is built client-side, so a scripted check cannot tell a live
 * product from a dead one, and this project's rule is not to claim what it has not checked.
 *
 * What is known: Scryfall ships this exact URL shape, wrapped in its partner redirect, to its own
 * users. That is corroboration and not proof. Measured 2026-09-11.
 */
object TcgplayerLinkBuilder {

	private const val BASE_URL = "https://www.tcgplayer.com"

	/**
	 * The product page for [printing], or `null` when no source published an id for it.
	 *
	 * Null rather than a search or a home page: a button that lands somewhere vaguely related is
	 * the thing this codebase treats as a lie, and without an id there is nothing precise to open.
	 *
	 * Reads the *printing's* ids only. A set carries a TCGplayer id too under the same key -- from
	 * TCGCSV, where it is a **group** id rather than a product id -- and building a product URL
	 * from a group id would resolve to the wrong thing or to nothing at all.
	 */
	fun linkFor(printing: CardPrinting): TcgplayerLink? {
		val vId = printing.externalIds[ExternalIdKey.TCGPLAYER]
			?.firstOrNull()
			?.trim()
			?.takeIf { it.isNotEmpty() }
			?: return null
		// Digits only. Every source publishes a numeric id, and a non-numeric value would mean
		// something changed shape upstream -- in which case no link is the honest answer.
		if (!vId.all { it.isDigit() }) return null
		return TcgplayerLink(url = "$BASE_URL/product/$vId", productId = vId)
	}
}
