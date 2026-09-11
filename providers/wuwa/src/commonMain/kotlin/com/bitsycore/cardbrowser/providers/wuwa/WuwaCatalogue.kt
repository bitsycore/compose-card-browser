package com.bitsycore.cardbrowser.providers.wuwa

import com.bitsycore.cardbrowser.core.model.Artwork
import com.bitsycore.cardbrowser.core.model.ArtworkTreatment
import com.bitsycore.cardbrowser.core.model.CardAttributes
import com.bitsycore.cardbrowser.core.model.CardClassification
import com.bitsycore.cardbrowser.core.model.CardIdentity
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.CollectorNumberComparator
import com.bitsycore.cardbrowser.core.model.ExternalIdKey
import com.bitsycore.cardbrowser.core.model.FinishCoverage
import com.bitsycore.cardbrowser.core.model.LanguageCoverage
import com.bitsycore.cardbrowser.core.model.LocalizedText
import com.bitsycore.cardbrowser.core.model.ProviderId
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.games.wutheringwaves.WutheringWavesGame
import com.bitsycore.cardbrowser.providers.wuwa.resources.Res
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * The bundled Wuthering Waves catalogue: a shipped asset, parsed once and mapped into core's model.
 *
 * ## Why the whole game is one asset
 *
 * UCP publishes no API. What the adapter used to call is the undocumented backend of the game's own
 * card-list page, and it cost 123 requests *per language* to assemble the catalogue: the list
 * endpoint returns six fields per card, so rarity, cost, attribute and rules text had to be fetched
 * one card at a time. Three locales is 357 requests for a game that ships 128 printings in total.
 *
 * So the catalogue is scraped once, aligned across the three locales, and shipped as
 * `src/commonMain/composeResources/files/wuwa-cards.json` -- see `tools/scrape_wuwa.py`, which is
 * committed and is how the file is refreshed. The adapter makes no requests for card data at all
 * now; only the images are still fetched, from UCP's own CDN, as the game's page serves them.
 *
 * That trade is worth stating plainly. The game gets a new set perhaps twice a year, and this is a
 * snapshot, so a new set needs the script re-run and the app rebuilt. In exchange the adapter is
 * instant, works offline from a cold start, no longer hammers somebody else's private endpoint, and
 * cannot break when that endpoint changes shape.
 *
 * ## Why a Compose resource
 *
 * The asset has to be readable on JVM, Android and both iOS targets. Kotlin Multiplatform has no
 * standard resource API, and a klib carries no files, so an `expect`/`actual` split would work on
 * the JVM side and then need the JSON manually copied into the iOS app bundle -- a step that fails
 * silently at runtime when somebody forgets it. Compose Multiplatform's resources are the mechanism
 * that already solves this, and they package the file for every target including iOS. The cost is
 * that this module now depends on the Compose runtime despite drawing nothing, and that reading is
 * asynchronous, which is why everything below suspends.
 *
 * ## What the snapshot makes possible that the API could not
 *
 * - **Locale-independent ids.** UCP issues a different numeric id per locale for the same card, so
 *   an id was only meaningful next to the locale that produced it. The key here is the printed code
 *   and rarity tier, which is the same in every language.
 * - **Card identity.** Two printings sharing a code are the same card -- stated by UCP itself, which
 *   gives them one code, one name and one rules text -- so [CardIdentity] is populated and the app
 *   can list a card's other artwork instead of merely hinting that it exists.
 * - **Real language coverage.** The snapshot knows exactly which of the three catalogues carry each
 *   printing, so [LanguageCoverage.confirmed] is a fact rather than "whatever was asked for".
 */
internal object WuwaCatalogue {

	private val mJson = Json { ignoreUnknownKeys = true }

	// One lock over both caches. Reading the asset is I/O and parsing it is not free, so a set list
	// and a card grid arriving together must not do either twice.
	private val mLock = Mutex()
	private var mSnapshot: WuwaSnapshotDto? = null
	private val mByLanguage = mutableMapOf<CardLanguage, List<CardPrinting>>()

	/** The snapshot, read and parsed on first use. */
	suspend fun snapshot(): WuwaSnapshotDto = mLock.withLock { loaded() }

	/** Every language the snapshot actually holds text for. */
	suspend fun languages(): Set<CardLanguage> = mLock.withLock {
		loaded().cards
			.flatMap { it.printings.keys }
			.mapNotNull(CardLanguage::fromCode)
			.toSet()
	}

	/**
	 * Every printing, in one language, in collector order.
	 *
	 * A printing the requested locale does not carry is still returned, using another locale's text
	 * and saying so on its [LanguageCoverage]. Dropping it would make the Korean catalogue look 21
	 * cards shorter than the game is, which is a claim about the game rather than about UCP.
	 */
	suspend fun printings(language: CardLanguage, provider: ProviderId): List<CardPrinting> =
		mLock.withLock { printingsLocked(language, provider) }

	/**
	 * The sets, with the number of printings each holds and the languages each is published in.
	 *
	 * No `language` parameter, because there is nothing here for one to select. A set's name is its
	 * own code -- see [setsByCode] for why -- so every locale produces byte-identical records, and
	 * taking a language only to discard it invited the reader to believe otherwise.
	 */
	suspend fun sets(provider: ProviderId): List<CardSet> = mLock.withLock {
		setsByCode(loaded(), provider).values.sortedBy { it.code }
	}

	/** One printing by its [WuwaSnapshotCardDto.key], or null when the snapshot has no such card. */
	suspend fun printing(key: String, language: CardLanguage, provider: ProviderId): CardPrinting? =
		mLock.withLock { printingsLocked(language, provider).firstOrNull { it.id.local == key } }

	// ==================
	// MARK: Loading
	// ==================

	/**
	 * The parsed snapshot. **Call only while holding [mLock]** -- [Mutex] is not re-entrant, so a
	 * public function that has taken the lock must reach the snapshot through this and not through
	 * [snapshot].
	 */
	@OptIn(ExperimentalResourceApi::class)
	private suspend fun loaded(): WuwaSnapshotDto = mSnapshot ?: run {
		val vText = Res.readBytes(ASSET_PATH).decodeToString()
		val vSnapshot = mJson.decodeFromString<WuwaSnapshotDto>(vText)
		require(vSnapshot.schemaVersion == SCHEMA_VERSION) {
			"Bundled Wuthering Waves catalogue is schema ${vSnapshot.schemaVersion}, expected $SCHEMA_VERSION"
		}
		vSnapshot.also { mSnapshot = it }
	}

	private suspend fun printingsLocked(
		language: CardLanguage,
		provider: ProviderId,
	): List<CardPrinting> {
		mByLanguage[language]?.let { return it }
		val vSnapshot = loaded()
		val vSets = setsByCode(vSnapshot, provider)
		return vSnapshot.cards
			.mapNotNull { toPrinting(vSnapshot, it, language, provider, vSets) }
			.sortedWith(CollectorNumberComparator)
			.also { mByLanguage[language] = it }
	}

	private fun setsByCode(snapshot: WuwaSnapshotDto, provider: ProviderId): Map<String, CardSet> =
		snapshot.cards
			.groupBy { it.set }
			.mapValues { (vCode, vCards) ->
				CardSet(
					id = SourceId(provider, vCode),
					game = WutheringWavesGame.id,
					code = vCode,
					// Stated rather than left silent, so a language menu is built from what this
					// set has rather than from what the source serves overall. They agree today --
					// all three products carry all three locales -- and the point is that the app
					// no longer has to assume they do. Individual *cards* already vary: the
					// Japanese catalogue holds 123 collector numbers, Korean 127 and Simplified
					// Chinese 107.
					languages = vCards
						.flatMap { it.printings.keys }
						.mapNotNullTo(mutableSetOf()) { CardLanguage.fromCode(it) },
					// The code is the name, deliberately. `/web/goods/list` does publish product
					// names that look like they line up -- スターターデッキ01 with `SD01` -- and the
					// `goods_id` filter that would confirm it returns zero cards for every product
					// id. Rather than assume `SD` is スターターデッキ, the code is shown as itself.
					name = vCode,
					cardCount = vCards.size,
					// No date anywhere in UCP's card data.
					releaseDate = null,
				)
			}

	// ==================
	// MARK: Mapping
	// ==================

	private fun toPrinting(
		snapshot: WuwaSnapshotDto,
		card: WuwaSnapshotCardDto,
		language: CardLanguage,
		provider: ProviderId,
		sets: Map<String, CardSet>,
	): CardPrinting? {
		if (card.code.isBlank()) return null
		// The requested language where the snapshot has it, and the app's preference order where it
		// does not. Whichever is used, the text says which language it actually is.
		val vShown = textLanguageFor(card, language) ?: return null
		val vText = card.printings[vShown.code] ?: return null
		val vSet = sets[card.set] ?: return null

		return CardPrinting(
			id = SourceId(provider, card.key),
			printingKey = card.key,
			game = WutheringWavesGame.id,
			setId = vSet.id,
			setCode = vSet.code,
			setName = vSet.name,
			collectorNumber = card.code.substringAfter('-', card.code),
			providerRawCollectorNumber = card.code,
			// Stated by UCP, not inferred: the two rarity tiers of a code share its number, its name
			// and its rules text, which is UCP saying they are one card.
			identity = CardIdentity(
				id = SourceId(provider, card.code),
				name = vText.name.orEmpty().ifBlank { card.code },
			),
			text = LocalizedText(
				language = vShown,
				name = vText.name.orEmpty().ifBlank { card.code },
				rules = vText.rules,
				flavour = null,
				isProviderStated = true,
			),
			artwork = artworkOf(snapshot, card, vShown, vText, provider),
			attributes = CardAttributes(
				cost = card.cost,
				primary = card.damage,
				secondary = card.speed,
			),
			classification = CardClassification(
				type = term(snapshot, "cardType", card.cardTypeId, language),
				supertype = term(snapshot, "weaponType", card.weaponTypeId, language),
				rarity = rarityOf(card),
				// The element axis, as UCP's own numeric attribute id rather than its localised
				// label -- attribute 2 is 焦熱, 热熔 and 용융, one element with three names.
				// `WutheringWavesGame` keys on the id and supplies an English label, so the filter
				// means the same thing in all three locales.
				domains = listOfNotNull(card.attributeId?.toString()),
			),
			tags = buildList {
				term(snapshot, "faction", card.factionId, language)?.let(::add)
				term(snapshot, "character", card.characterId, language)?.let(::add)
				term(snapshot, "color", card.colorId, language)?.let(::add)
				// A list on UCP's side too, comma-joined, and split by the scraper.
				card.featureIds.forEach { vId -> term(snapshot, "feature", vId, language)?.let(::add) }
				// Where the card is found, which for six cards is not the product it is numbered
				// under. Both are true and both are shown.
				addAll(card.products)
			},
			languages = LanguageCoverage(
				// Exactly the catalogues that carry it. Every other language is left unknown rather
				// than absent: UCP not publishing a Korean record is a fact about UCP.
				confirmed = card.printings.keys.mapNotNull(CardLanguage::fromCode).toSet(),
				// The requested language resolved to a different one, so the app must say so.
				textOnlyFallback = if (vShown == language) emptySet() else setOf(language),
			),
			// UCP states no finish field at all. Absent, not "no foil exists".
			finishes = FinishCoverage(),
			externalIds = buildMap {
				// UCP's own per-locale ids, kept for provenance: this app keys on the printed code,
				// so these are the only way back to a record on UCP's site.
				val vIds = card.printings.entries
					.sortedBy { it.key }
					.map { "${it.key}:${it.value.providerId}" }
				if (vIds.isNotEmpty()) put(ExternalIdKey.PROVIDER_RECORD, vIds)
			},
		)
	}

	/**
	 * Which language's text to show for one printing.
	 *
	 * The requested one when the snapshot has it. Otherwise the app's preference order, restricted
	 * to what this card actually has -- 21 printings exist in Japanese or Chinese but not Korean.
	 */
	private fun textLanguageFor(card: WuwaSnapshotCardDto, requested: CardLanguage): CardLanguage? {
		if (card.printings.containsKey(requested.code)) return requested
		return CardLanguage.PREFERENCE_ORDER.firstOrNull { card.printings.containsKey(it.code) }
			?: card.printings.keys.firstNotNullOfOrNull(CardLanguage::fromCode)
	}

	/**
	 * The rarity, as the stars UCP prints on the card.
	 *
	 * A star string rather than a word, because that is what the source states and what is on the
	 * card, and it reads the same in all three languages. "Collection" is the one tier with no
	 * stars and is carried through as its label.
	 */
	private fun rarityOf(card: WuwaSnapshotCardDto): String? =
		card.stars?.let { "★".repeat(it) } ?: card.rarity

	/**
	 * The art for one printing, in one language.
	 *
	 * The treatment is the one inference here and it is a narrow one. Where a code ships at two
	 * rarities, the lower tier is the standard printing and the higher one is a different
	 * illustration of the same card -- confirmed by looking at both: `SD01-003` at ★★★ is a framed
	 * portrait and at ★★★★ a full-bleed alternate. It is not a foil of the first, which is why this
	 * is [ArtworkTreatment.ALTERNATE_ART] and not a `FinishCoverage` entry.
	 */
	private fun artworkOf(
		snapshot: WuwaSnapshotDto,
		card: WuwaSnapshotCardDto,
		language: CardLanguage,
		printing: WuwaSnapshotPrintingDto,
		provider: ProviderId,
	): Artwork {
		val vBase = printing.imageBase?.let { snapshot.imageBases.getOrNull(it) }
		val vUrl = if (vBase != null && printing.image != null) "$vBase/${printing.image}" else ""
		val vLowestTier = snapshot.cards
			.filter { it.code == card.code }
			.minOfOrNull { it.stars ?: Int.MAX_VALUE }
		return Artwork(
			// Per language: each locale has its own scan, with its own text burned into it.
			id = SourceId(provider, "${card.key}@${language.code}"),
			imageUrl = vUrl,
			// A real thumbnail, asked of the CDN rather than published as a second file.
			//
			// The bucket is Tencent COS and honours `imageMogr2`, which is the same arrangement
			// Riftcodex has with Sanity: there is one asset and the size is a query parameter.
			// Measured across five random cards on 2026-09-11, `thumbnail/320x` turns 168-188 KB
			// into 22-43 KB -- a genuine pixel resize, 1055x1473 down to 320x447, not a
			// re-compression. That is the difference between a grid tile costing 33 KB and 180 KB.
			//
			// Left as `null` until then, on the grounds that the path already said `compressed`
			// and there was no second file. There is no second file; there is a second size.
			thumbnailUrl = vUrl.ifBlank { null }?.plus(THUMBNAIL_PARAMS),
			displayUrl = vUrl.ifBlank { null },
			artist = null,
			treatment = if (card.stars != null && vLowestTier != null && card.stars > vLowestTier) {
				ArtworkTreatment.ALTERNATE_ART
			} else {
				ArtworkTreatment.STANDARD
			},
			language = language,
			accessibilityText = "Wuthering Waves TCG card: ${printing.name.orEmpty().ifBlank { card.code }}.",
		)
	}

	/** One facet term's label in [language], falling back to any locale that has one. */
	private fun term(
		snapshot: WuwaSnapshotDto,
		facet: String,
		id: Int?,
		language: CardLanguage,
	): String? {
		if (id == null) return null
		val vTerm = snapshot.vocabulary[facet]?.firstOrNull { it.id == id } ?: return null
		return (vTerm.labels[language.code] ?: vTerm.labels.values.firstOrNull())?.ifBlank { null }
	}

	/**
	 * What turns a full asset into a grid thumbnail, appended to the URL.
	 *
	 * Tencent COS image processing. 320 px wide to match what every other adapter here asks for,
	 * which is wide enough for a two-to-four column grid on a phone at 3x. The format is left
	 * alone: these are already WebP.
	 *
	 * If the bucket ever stops honouring it the request still succeeds and returns the full asset,
	 * so the failure is silent and expensive rather than visible -- which is why
	 * `WuwaLiveSmokeTest` checks the resize really happens.
	 */
	internal const val THUMBNAIL_PARAMS = "?imageMogr2/thumbnail/320x"

	/** Where the asset lives, relative to `composeResources`. */
	private const val ASSET_PATH = "files/wuwa-cards.json"

	/** Bumped with the shape of `wuwa-cards.json`, so a mismatch fails loudly rather than quietly. */
	const val SCHEMA_VERSION: Int = 1
}
