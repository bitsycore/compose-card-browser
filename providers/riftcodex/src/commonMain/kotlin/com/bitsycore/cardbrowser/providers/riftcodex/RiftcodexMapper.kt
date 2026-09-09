package com.bitsycore.cardbrowser.providers.riftcodex

import com.bitsycore.cardbrowser.core.model.Artwork
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardAttributes
import com.bitsycore.cardbrowser.core.model.CardClassification
import com.bitsycore.cardbrowser.core.model.CardIdentity
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
			identity = identityOf(dto, vTreatment, provider),
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
	 * What makes two Riftcodex records the same card.
	 *
	 * ## This is an inference, and it is the only one in the project
	 *
	 * Riftcodex states no cross-printing relationship. `riftbound_id` looked like a candidate and
	 * is not: it is unique per printing -- `ogn-299-298` and `ogn-299*-298` are two records for one
	 * card -- and `collector_number` is worse, because it is *reused* across unrelated cards. In
	 * OGN, five different cards all report collector number 13.
	 *
	 * So this matches on the name, which the rest of the codebase refuses to do. It is here at the
	 * project owner's instruction, they being the one who knows the game: within a set, two records
	 * with the same name are the same card, and a variant says so in a parenthetical -- "Poppy -
	 * Paragon" and "Poppy - Paragon (Alternate Art)".
	 *
	 * Three things make it defensible rather than a guess:
	 *
	 * 1. **The provider licenses the strip.** The parenthetical is only removed when one of
	 *    `metadata`'s three treatment booleans is set, and those *are* stated. A name is never
	 *    truncated on the strength of how it happens to read.
	 * 2. **It was checked against the data.** All 400 OGN records were grouped this way: 340
	 *    distinct cards, 53 of them with more than one printing, and **zero** groups whose rules
	 *    text disagreed -- so it never merged two different cards. `RiftcodexMapperTest` pins the
	 *    cases.
	 * 3. **It is scoped to one set.** The id carries the set code, so nothing claims that a
	 *    reprint in a later set is the same card. That is a relationship Riftcodex genuinely does
	 *    not state.
	 *
	 * If a later set breaks the rule, the symptom is two cards merged into one "other artwork"
	 * row, and the fix is here.
	 */
	private fun identityOf(
		dto: CardDto,
		treatment: ArtworkTreatment,
		provider: ProviderId,
	): CardIdentity? {
		val vName = baseNameOf(dto.name, treatment).ifBlank { return null }
		val vSet = dto.set.setId.uppercase().ifBlank { return null }
		return CardIdentity(id = SourceId(provider, "$vSet:$vName"), name = vName)
	}

	/**
	 * A card's name with a variant's parenthetical removed.
	 *
	 * Removed only when the provider has flagged the record as a variant, and only from the end --
	 * so "Poppy - Paragon (Alternate Art)" becomes "Poppy - Paragon" while a plain printing's name
	 * is never touched. A flagged record whose name carries no parenthetical is left alone too;
	 * some variants simply reuse the base name.
	 */
	internal fun baseNameOf(name: String, treatment: ArtworkTreatment): String {
		val vName = name.trim()
		if (treatment == ArtworkTreatment.STANDARD) return vName
		if (!vName.endsWith(")")) return vName
		val vOpen = vName.lastIndexOf('(')
		return if (vOpen <= 0) vName else vName.substring(0, vOpen).trim()
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
