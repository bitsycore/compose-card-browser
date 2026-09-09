package com.bitsycore.cardbrowser.providers.riftcodex

import com.bitsycore.cardbrowser.core.model.Artwork
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardAttributes
import com.bitsycore.cardbrowser.core.model.CardClassification
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardOrientation
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.ExternalIdKey
import com.bitsycore.cardbrowser.core.model.FinishCoverage
import com.bitsycore.cardbrowser.core.model.LanguageCoverage
import com.bitsycore.cardbrowser.core.model.LocalizedText
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import kotlinx.datetime.LocalDate

/**
 * Turns Riftcodex's wire format into core's model.
 *
 * Kept as an object of pure functions so the mapping can be tested against captured JSON without a
 * client, a server or a coroutine anywhere near it.
 */
internal object RiftcodexMapper {

	// ============
	//  Sets

	/** Maps one set record. Returns `null` only when the record has no usable id. */
	fun toSet(dto: SetDto, provider: ProviderId): CardSet? {
		if (dto.id.isBlank() || dto.setId.isBlank()) return null
		val vCode = dto.setId.uppercase()
		return CardSet(
			// The *set code*, not Riftcodex's own record id, is the local part.
			//
			// Two reasons, one of them a bug this fixes. The `/cards` endpoint's `set_id` parameter
			// takes the code (`OGN`), not the record id (`69bc5bf6e195be3e561d1eb1`) -- passing the
			// record id returns 200 with an empty page, so a set browsed that way looked like a set
			// with no cards in it. And the code is the more stable key of the two: a record id can
			// change if the provider rebuilds its database, which would silently orphan every
			// cached set, whereas `OGN` is the game's own name for the set.
			//
			// The record id is kept below rather than thrown away.
			id = SourceId(provider, vCode),
			game = RiftboundGame.id,
			code = vCode,
			name = dto.name.ifBlank { vCode },
			cardCount = dto.cardCount,
			releaseDate = parseDate(dto.publishedOn),
			externalIds = buildMap {
				dto.cardmarketIds().takeIf { it.isNotEmpty() }
					?.let { put(ExternalIdKey.CARDMARKET_EXPANSION, it) }
				dto.tcgplayerId?.let { put(ExternalIdKey.TCGPLAYER, listOf(it)) }
				put(ExternalIdKey.PROVIDER_RECORD, listOf(dto.id))
			},
		)
	}

	/**
	 * Reads the date part of Riftcodex's `published_on`.
	 *
	 * The field is documented as ISO 8601 and arrives as `2025-10-31T00:00:00` -- a local date-time
	 * with no zone. Only the date is meaningful for "which set came out first", so the time is
	 * dropped rather than being given a timezone it does not have.
	 */
	fun parseDate(raw: String?): LocalDate? {
		if (raw.isNullOrBlank()) return null
		val vDatePart = raw.substringBefore('T')
		return try {
			LocalDate.parse(vDatePart)
		} catch (vError: IllegalArgumentException) {
			// A set whose date will not parse still belongs in the list; it just sorts last.
			null
		}
	}

	// ============
	//  Cards

	/** Maps one card record. Returns `null` when the record cannot identify a printing. */
	fun toPrinting(dto: CardDto, provider: ProviderId): CardPrinting? {
		if (dto.id.isBlank()) return null

		val vCollector = collectorNumberOf(dto)
		val vTreatment = treatmentOf(dto.metadata)
		val vImage = dto.media.imageUrl

		return CardPrinting(
			id = SourceId(provider, dto.id),
			game = RiftboundGame.id,
			setId = SourceId(provider, dto.set.setId.uppercase()),
			setCode = dto.set.setId.uppercase(),
			setName = dto.set.label,
			collectorNumber = vCollector,
			providerRawCollectorNumber = dto.collectorNumber?.toString() ?: vCollector,
			// Riftcodex's own per-printing id, and the only thing that identifies a *printing*
			// here: its record ids are per-record, and Vendetta really does ship the same printing
			// twice under two of them -- 358 records for 227 distinct `riftbound_id`s. Without this
			// the grid shows a third of that set twice over.
			printingKey = dto.riftboundId.takeIf { it.isNotBlank() },
			// Riftcodex states no relationship between printings of the same card. `riftbound_id`
			// looked like a candidate, but it is unique per printing -- `ogn-299-298` and
			// `ogn-299*-298` are two records for what a player would call one card, and the API's
			// own lookup by that id returns a single record. Inventing an identity by matching on
			// name would be exactly the guess the brief rules out, so this stays null.
			identity = null,
			text = LocalizedText(
				// Riftcodex has no language field at all. The text it serves is English, and that
				// is stated here as an observation about this provider rather than read off a
				// field that does not exist.
				language = CardLanguage.ENGLISH,
				name = dto.name,
				rules = dto.text.plain?.takeIf { it.isNotBlank() },
				flavour = dto.text.flavour?.takeIf { it.isNotBlank() },
				isProviderStated = false,
			),
			artwork = Artwork(
				// The artwork id is the card id: one Riftcodex record carries exactly one image, so
				// a distinct record is a distinct tile in the grid.
				id = SourceId(provider, dto.id),
				imageUrl = vImage.orEmpty(),
				thumbnailUrl = vImage?.let(::thumbnailUrl),
				displayUrl = vImage?.let(::displayUrl),
				artist = dto.media.artist?.takeIf { it.isNotBlank() },
				treatment = vTreatment,
				language = CardLanguage.ENGLISH,
				accessibilityText = dto.media.accessibilityText?.takeIf { it.isNotBlank() },
			),
			attributes = CardAttributes(
				// The slot names are the model's, deliberately game-neutral; the field names on the
				// right are Riftcodex's own. `RiftboundGame.vocabulary` is what labels them.
				cost = dto.attributes.energy,
				primary = dto.attributes.might,
				secondary = dto.attributes.power,
			),
			classification = CardClassification(
				type = dto.classification.type?.takeIf { it.isNotBlank() },
				supertype = dto.classification.supertype?.takeIf { it.isNotBlank() },
				rarity = dto.classification.rarity?.takeIf { it.isNotBlank() },
				domains = dto.classification.domain,
			),
			tags = dto.tags,
			// English confirmed; French, Japanese and Korean left UNKNOWN rather than UNAVAILABLE.
			// Riftcodex not carrying a translation is not evidence that no such printing exists.
			languages = LanguageCoverage.ENGLISH_ONLY,
			// Nothing at all is known about finishes: the schema has no finish field. An empty
			// coverage reports every finish as UNKNOWN, which is the honest answer and is what
			// stops the detail screen offering a foil toggle it cannot back up.
			finishes = FinishCoverage(),
			externalIds = buildMap {
				dto.tcgplayerId?.let { put(ExternalIdKey.TCGPLAYER, listOf(it)) }
				dto.riftboundId.takeIf { it.isNotBlank() }
					?.let { put(ExternalIdKey.PUBLISHER_CARD, listOf(it)) }
				// No Cardmarket *product* id is ever put here: Riftcodex maps Cardmarket only at
				// set level, so the detail screen gets a search link rather than a product link.
			},
			orientation = when (dto.orientation?.lowercase()) {
				"landscape" -> CardOrientation.LANDSCAPE
				else -> CardOrientation.PORTRAIT
			},
		)
	}

	/**
	 * The collector number as a string, preferring the one embedded in `riftbound_id`.
	 *
	 * `collector_number` is an integer and is *not unique within a set*: in Origins, 299 is both
	 * "Kai'Sa (Overnumbered)" and "Kai'Sa (Signature)". The `riftbound_id` middle segment keeps them
	 * apart as `299` and `299*`, so that is the value the app shows and sorts on. The bare integer
	 * is still kept on the printing as `providerRawCollectorNumber`.
	 */
	fun collectorNumberOf(dto: CardDto): String {
		val vFromId = dto.riftboundId
			.split('-')
			.getOrNull(1)
			?.takeIf { it.isNotBlank() }
		return vFromId ?: dto.collectorNumber?.toString() ?: dto.id
	}

	/**
	 * Which treatment the three boolean flags describe.
	 *
	 * More than one can be set in principle, so they are checked most-specific first. All three
	 * false is a plain printing; there is no fourth flag and no "unknown" case here, because the
	 * provider does state these.
	 */
	fun treatmentOf(metadata: MetadataDto): ArtworkTreatment = when {
		metadata.signature -> ArtworkTreatment.SIGNATURE
		metadata.overnumbered -> ArtworkTreatment.OVERNUMBERED
		metadata.alternateArt -> ArtworkTreatment.ALTERNATE_ART
		else -> ArtworkTreatment.STANDARD
	}

	/**
	 * A grid-sized variant of a card image.
	 *
	 * Riftbound art is served from Riot's Sanity CDN, which resizes from a `w` parameter and picks
	 * an output format from `fm`. Any URL not on that CDN is left alone and the grid falls back to
	 * the full image.
	 *
	 * ## Why the format is pinned rather than negotiated
	 *
	 * Left to itself the CDN chooses the format per asset, and for a minority of Riftbound cards it
	 * chooses **AVIF** -- regardless of the `Accept` header, which it ignores. Skia decodes no AVIF,
	 * so those thumbnails failed to decode on desktop and iOS while every other card worked, and
	 * because the response was a perfectly valid `200` it was cached and failed forever after. It
	 * would also break Android below API 31.
	 *
	 * `fm=webp` removes the negotiation entirely. WebP decodes on Skia and on Android from API 14,
	 * comfortably below this app's minimum of 24.
	 *
	 * It is also far smaller. Measured on real assets at `w=320`: PNG ~260 KB, WebP ~22 KB.
	 */
	fun thumbnailUrl(imageUrl: String): String? =
		sanityVariant(imageUrl, width = THUMBNAIL_WIDTH, quality = null)

	/**
	 * The variant the detail screen and the fullscreen viewer load.
	 *
	 * Native resolution and nothing more. The CDN will happily serve `w=1488`, but it is
	 * interpolating: measured against a real asset, its 1488 render is *less* sharp than a plain
	 * Lanczos upscale of the 744 one (high-pass variance 94 against 122) and differs from it by a
	 * mean of under 4/255. There is no extra detail to buy -- 744x1040 is the source -- so asking
	 * for more spends 5 MB of PNG on pixels that carry nothing.
	 *
	 * What it does change is the format. At native width, lossless PNG is ~1.17 MB and WebP at
	 * `q=90` is ~180 KB for an image nobody can tell apart at this size. That is the whole win.
	 *
	 * The width is read from the asset's own filename rather than assumed, because Riftbound cards
	 * are not all the same size -- 744x1039 and 744x1040 both occur, and a landscape card would be
	 * neither.
	 */
	fun displayUrl(imageUrl: String): String? =
		sanityVariant(imageUrl, width = nativeWidthOf(imageUrl), quality = DISPLAY_QUALITY)

	/**
	 * The asset's own pixel width, from the `-WIDTHxHEIGHT` Sanity puts in every filename.
	 *
	 * `null` when the name does not carry one, in which case the caller asks for no `w` at all and
	 * gets the original -- which is right, since the point is to avoid resampling.
	 */
	fun nativeWidthOf(imageUrl: String): Int? =
		NATIVE_SIZE.find(imageUrl.substringBefore('?'))?.groupValues?.get(1)?.toIntOrNull()

	/** Builds a CDN variant URL, or `null` for anything not on the CDN. */
	private fun sanityVariant(imageUrl: String, width: Int?, quality: Int?): String? {
		if (!imageUrl.contains(SANITY_CDN_HOST)) return null
		val vParameters = buildList {
			width?.let { add("w=$it") }
			add("fm=$IMAGE_FORMAT")
			quality?.let { add("q=$it") }
		}
		val vSeparator = if (imageUrl.contains('?')) '&' else '?'
		return imageUrl + vSeparator + vParameters.joinToString("&")
	}

	private val NATIVE_SIZE = Regex("""-(\d+)x(\d+)\.[A-Za-z0-9]+$""")

	private const val SANITY_CDN_HOST = "cmsassets.rgpub.io"

	/** Wide enough for a two-to-four column grid on a phone at 3x density. */
	private const val THUMBNAIL_WIDTH = 320

	/** Decodable everywhere this app runs, unlike the CDN's own default. See [thumbnailUrl]. */
	private const val IMAGE_FORMAT = "webp"

	/**
	 * WebP quality for the full-size image.
	 *
	 * 90 rather than the CDN's default of 75: this is the one image a user looks at closely and
	 * zooms into, and the step from 97 KB to 180 KB is worth not having to argue about artefacts.
	 */
	private const val DISPLAY_QUALITY = 90
}
