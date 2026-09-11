package com.bitsycore.cardbrowser.ui.detail

import com.bitsycore.cardbrowser.core.cardmarket.CardmarketLink
import com.bitsycore.cardbrowser.core.cardmarket.CardmarketLinkBuilder
import com.bitsycore.cardbrowser.core.tcgplayer.TcgplayerLink
import com.bitsycore.cardbrowser.core.tcgplayer.TcgplayerLinkBuilder
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.game.GameVocabulary
import com.bitsycore.cardbrowser.core.model.Availability
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardOrientation
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.ExternalIdKey
import com.bitsycore.cardbrowser.core.model.Finish
import com.bitsycore.cardbrowser.core.model.LanguageResolution
import com.bitsycore.cardbrowser.core.provider.ProviderError
import com.bitsycore.lib.pulse.container.ContainerContract

/**
 * The card detail state machine.
 *
 * The screen shows one card at a time but holds the whole list it can swipe through, so
 * [UiState.cards] is the list and [UiState.currentIndex] is where in it we are. Everything a page
 * needs is derived from *its own* card rather than read from state, which is what lets a pager
 * render the page either side of the current one correctly.
 *
 * Most of the interesting logic is about what *not* to claim -- see [languageOptionsFor],
 * [finishesFor] and [artworkNote].
 */
object CardDetailContract :
	ContainerContract<CardDetailContract.UiState, CardDetailContract.Intent, CardDetailContract.Effect>() {

	/**
	 * @property cards every card the user can swipe to, in the order the grid showed them
	 * @property currentIndex where in [cards] we are. Always in bounds when [cards] is non-empty
	 * @property requestedLanguage what the preference order asks for, which may not be on offer
	 */
	data class UiState(
		val cards: List<CardPrinting> = emptyList(),
		val currentIndex: Int = 0,
		val set: CardSet? = null,
		val isLoading: Boolean = true,
		val error: ProviderError? = null,
		val requestedLanguage: CardLanguage = CardLanguage.ENGLISH,
		val isZoomed: Boolean = false,
		val isFullscreen: Boolean = false,
		val providerStatesIdentity: Boolean = false,
		val providerStatesFinishes: Boolean = false,
		/** Every language the source behind these cards can be asked for. Empty when it states none. */
		val providerLanguages: Set<CardLanguage> = emptySet(),
		/** The source's own name, for the details table. `null` before the load completes. */
		val providerDisplayName: String? = null,
		/**
		 * The game these cards belong to, and everything the app knows about it.
		 *
		 * Its vocabulary is what labels the stat rows, and its Cardmarket segment is what decides
		 * whether there is a marketplace link at all. `null` until the load resolves a provider.
		 */
		val game: GameProfile? = null,
		/** True while a language change is being fetched, so the chips can say so. */
		val isChangingLanguage: Boolean = false,
		val attribution: String? = null,
	) {

		/** The card on screen, or `null` while loading or after a failure. */
		val card: CardPrinting? get() = cards.getOrNull(currentIndex)

		/** True when there is more than one card to move between, so the strip is worth showing. */
		val canSwipe: Boolean get() = cards.size > 1

		/** "12 of 352", for the top bar. */
		val positionLabel: String?
			get() = if (canSwipe) "${currentIndex + 1} of ${cards.size}" else null

		/**
		 * Which language is actually shown for [card] versus which was asked for.
		 *
		 * This is what stops the app labelling an English-only record as a French printing.
		 */
		fun languageResolutionFor(card: CardPrinting): LanguageResolution = LanguageResolution(
			requested = requestedLanguage,
			shown = card.languages.resolve(listOf(requestedLanguage) + CardLanguage.PREFERENCE_ORDER),
		)

		/**
		 * The language on screen, plus every other one this source can be asked for.
		 *
		 * Deliberately *not* every language the app knows. A chip reading "Russian — unknown" on a
		 * Riftbound card is noise: nothing here can fetch Russian Riftbound, so offering it and then
		 * explaining why it is greyed out costs a row of screen to say nothing. What is worth
		 * showing is what can be switched to, which is [providerLanguages], and what is being shown
		 * now, which stays in the list even when the source no longer claims it.
		 *
		 * Silence is still not a no: a language the provider serves but has not confirmed for *this*
		 * printing is offered as [Availability.UNKNOWN] and remains selectable, because asking the
		 * source is the only way to find out and refusing to ask would hide a printing that exists.
		 */
		fun languageOptionsFor(card: CardPrinting): List<LanguageOption> {
			val vShown = languageResolutionFor(card).shown
			val vOffered = offerableLanguages + card.languages.confirmed + setOfNotNull(vShown)
			return CardLanguage.PREFERENCE_ORDER
				.filter { it in vOffered }
				.map { vLanguage ->
					LanguageOption(
						language = vLanguage,
						availability = card.languages.availabilityOf(vLanguage),
						isSelected = vLanguage == vShown,
						isOfferedBySource = vLanguage in offerableLanguages,
					)
				}
		}

		/**
		 * The languages that can actually be asked for *here*.
		 *
		 * The set's own languages when it states any, and only then the source's. A source-wide list
		 * is a claim about the source: TCGdex serves eleven locales, and Pokemon's Base Set exists
		 * in six of them, so offering all eleven put seven dead entries in the menu -- which is what
		 * made every language have to be checked by hand.
		 *
		 * The distinction is not cosmetic. TCGdex folds the case of a set id, so
		 * `/en/sets/SM10` answers with international Unbroken Bonds rather than 404ing on the
		 * Japanese `SM10`; picking English for that set would have shown a different set's cards
		 * under its name.
		 *
		 * A set that states nothing falls back to the source's list unchanged, because a provider
		 * that does not describe its sets per language has not thereby said its sets are English.
		 */
		val offerableLanguages: Set<CardLanguage>
			get() = set?.languages?.takeIf { it.isNotEmpty() } ?: providerLanguages

		/**
		 * The finishes this printing is known to exist in, or nothing at all.
		 *
		 * Only the confirmed ones, for the same reason [languageOptionsFor] lists only what can be
		 * chosen: a greyed-out "Etched foil" chip says either "this card was never etched" or "our
		 * source has never mentioned etching", and no chip can tell those apart. `null` when the
		 * source states nothing, so the row is absent rather than explaining the app's limitations.
		 *
		 * A string rather than a row of chips, because a chip implies a choice and choosing a
		 * finish changed nothing: no source here publishes a separate scan per finish, so every
		 * finish of a card is the same picture. The chips were a control whose only effect was on
		 * themselves. Which finishes exist is a fact about the printing, so it sits with the other
		 * facts -- and it is still a filter in the grid, where it does narrow something.
		 */
		fun finishesFor(card: CardPrinting): String? {
			if (card.finishes.isUnstated) return null
			return Finish.entries
				.filter { card.finishes.availabilityOf(it) == Availability.AVAILABLE }
				.joinToString(", ") { it.displayName }
				.ifBlank { null }
		}

		/**
		 * Other printings of the same card that are already in hand, newest artwork last.
		 *
		 * Only ever a real list. It is built from [cards] -- the set the user is swiping through --
		 * matched on the identity the *provider* stated, never on a shared name. A source that links
		 * no printings therefore returns nothing here and the section is not drawn at all, which is
		 * the honest outcome: the app has no other artwork to show, so it shows none rather than a
		 * paragraph about why.
		 */
		fun otherArtworksFor(card: CardPrinting): List<CardPrinting> {
			val vIdentity = card.identity?.id ?: return emptyList()
			return cards.filter { it.identity?.id == vIdentity && it.artwork.id != card.artwork.id }
		}

		/**
		 * The Cardmarket link for one card.
		 *
		 * Derived per card rather than stored, because the screen renders several cards at once
		 * while a swipe is in flight and each needs its own.
		 */
		fun cardmarketLinkFor(card: CardPrinting): CardmarketLink? =
			game?.let { CardmarketLinkBuilder.linkFor(card, set, it) }

		/**
		 * The TCGplayer link for one card, or `null` when no source published an id for it.
		 *
		 * No game argument, unlike the Cardmarket one: TCGplayer resolves a product from its id
		 * alone, with no category segment to confirm per game. See `TcgplayerLinkBuilder`.
		 */
		fun tcgplayerLinkFor(card: CardPrinting): TcgplayerLink? =
			TcgplayerLinkBuilder.linkFor(card)

		/**
		 * Everything the provider stated about one printing, as labelled rows.
		 *
		 * The chips above the rules text are the glance -- rarity, type, cost. This is the rest of
		 * the record, and it exists because a field the app has parsed and then never shows may as
		 * well not have been parsed. A row is present only when the provider stated the value, so
		 * the table's length is itself a fair report of how much a given source knows.
		 *
		 * The labels come from [GameVocabulary] rather than from this app's field names: a screen
		 * that says "Energy" over a Magic mana value is wrong in the way users notice first.
		 */
		fun factsFor(card: CardPrinting): List<CardFact> {
			val vWords = game?.vocabulary ?: GameVocabulary()
			return buildList {
				fact("Set", "${card.setName} (${card.setCode})")
				fact("Number", card.collectorNumber)
				// Shown only when the provider's own value is not the one the app displays, which is
				// the case that is worth being able to check against the source's website.
				if (card.providerRawCollectorNumber != card.collectorNumber) {
					fact("Number as published", card.providerRawCollectorNumber)
				}
				fact(vWords.cardType, card.classification.type)
				fact("Supertype", card.classification.supertype)
				fact("Rarity", card.classification.rarity)
				vWords.domain?.let { vLabel ->
					// Labels, not keys: this row reads "White, Blue" rather than "W, U".
					fact(
						vLabel,
						card.classification.domains
							.takeIf { it.isNotEmpty() }
							?.joinToString(", ") { vKey -> game?.domainFor(vKey)?.label ?: vKey },
					)
				}
				// Labelled with the game's own word, and with a neutral one where the game states
				// none. A value the provider supplied is not dropped for want of a name for it --
				// that would hide real data behind a gap in a game module.
				fact(vWords.cost ?: "Cost", card.attributes.cost?.toString())
				fact(vWords.primaryStat ?: "Stat", card.attributes.primary?.toString())
				fact(vWords.secondaryStat ?: "Second stat", card.attributes.secondary?.toString())
				fact("Tags", card.tags.takeIf { it.isNotEmpty() }?.joinToString(", "))
				fact("Artist", card.artwork.artist)
				fact("Treatment", card.artwork.treatment.displayName)
				fact("Finish", finishesFor(card))
				if (card.orientation == CardOrientation.LANDSCAPE) fact("Orientation", "Landscape")
				fact("Text language", card.text.language?.displayName)
				// Worth its own row only when it disagrees with the text, which is the case a reader
				// needs to know about: YGOPRODeck translates the text and keeps the English scan.
				card.artwork.language
					?.takeIf { it != card.text.language }
					?.let { fact("Image language", it.displayName) }
				fact("Same card as", card.identity?.name)
				fact("Data source", providerDisplayName)
				fact("Provider id", card.id.local)
				card.externalIds.forEach { (vKey, vValues) ->
					fact(externalIdLabel(vKey), vValues.joinToString(", ").ifBlank { null })
				}
			}
		}

		/** Adds a row, or nothing at all when the provider stated no value. */
		private fun MutableList<CardFact>.fact(label: String, value: String?) {
			value?.ifBlank { null }?.let { add(CardFact(label, it)) }
		}
	}

	/** A readable name for an [ExternalIdKey], falling back to the raw key for one not listed. */
	private fun externalIdLabel(key: String): String = when (key) {
		ExternalIdKey.CARDMARKET_EXPANSION -> "Cardmarket expansion id"
		ExternalIdKey.CARDMARKET_PRODUCT -> "Cardmarket product id"
		ExternalIdKey.TCGPLAYER -> "TCGplayer id"
		ExternalIdKey.PUBLISHER_CARD -> "Publisher card id"
		ExternalIdKey.PROVIDER_RECORD -> "Provider record id"
		else -> key
	}

	/** One labelled fact about a printing, for the details table. */
	data class CardFact(val label: String, val value: String)

	/**
	 * One language row: what it is, whether it is offered, and whether it is showing.
	 *
	 * @property isOfferedBySource true when the source can be asked for this language at all, which
	 *   is what makes the chip tappable. [availability] is the narrower question of whether *this*
	 *   printing is known to exist in it
	 */
	data class LanguageOption(
		val language: CardLanguage,
		val availability: Availability,
		val isSelected: Boolean,
		val isOfferedBySource: Boolean = false,
	) {

		/**
		 * Whether tapping this chip can do anything.
		 *
		 * Anything the source serves, unless this printing is known not to exist in it. Unknown is
		 * not a no, and the only way to turn it into a yes or a no is to ask.
		 */
		val isSelectable: Boolean
			get() = !isSelected &&
				availability != Availability.UNAVAILABLE &&
				(isOfferedBySource || availability == Availability.AVAILABLE)
	}

	sealed interface Intent {

		/** The back arrow. Navigation goes through the container like everything else. */
		data object BackPressed : Intent

		data class Load(val cardId: String, val setId: String?) : Intent

		data class Loaded(
			val cards: List<CardPrinting>,
			val currentIndex: Int,
			val set: CardSet?,
			val error: ProviderError?,
			val attribution: String?,
			val providerStatesIdentity: Boolean,
			val providerStatesFinishes: Boolean,
			val providerLanguages: Set<CardLanguage> = emptySet(),
			val providerDisplayName: String? = null,
			val game: GameProfile? = null,
		) : Intent

		/** The pager settled on another card, or the preview strip was tapped. */
		data class PageChanged(val index: Int) : Intent

		data class LanguageSelected(val language: CardLanguage) : Intent

		/**
		 * The set, refetched in another language.
		 *
		 * Separate from [Loaded] because the cards are replaced while everything else about the
		 * screen stays as it was, and because the index has to be re-found rather than reused: a
		 * source whose ids differ per locale gives back the same set with different ids, so the card
		 * on screen is identified by its collector number.
		 */
		data class LanguageChanged(
			val language: CardLanguage,
			val cards: List<CardPrinting>,
			val collectorNumber: String,
		) : Intent

		/** A language change that could not be fetched. The screen keeps what it had. */
		data class LanguageChangeFailed(val language: CardLanguage) : Intent

		data class ZoomToggled(val isZoomed: Boolean) : Intent

		/** The card image was tapped, or the fullscreen viewer was dismissed. */
		data class FullscreenToggled(val isFullscreen: Boolean) : Intent

		/** Opening a link never places an order. */
		data class OpenCardmarket(val cardId: String) : Intent

		/** The user asked for this card's TCGplayer page. */
		data class OpenTcgplayer(val cardId: String) : Intent
	}

	sealed interface Effect {

		/** Shown when the platform refused to open a browser, so the tap is not silently lost. */
		data class LinkFailed(val url: String) : Effect

		/**
		 * A language was chosen and could not be shown, with the reason.
		 *
		 * The chips used to do nothing at all in this case, which is how a Pokémon card behaves for
		 * Japanese, Korean and either Chinese: TCGdex keys those catalogues by their own set ids --
		 * the English `base1` is `PMCG1` in Japanese and does not exist in Korean -- so there is no
		 * Korean edition of the set to fetch. A tap that cannot work has to say so.
		 */
		data class LanguageUnavailable(val language: CardLanguage, val reason: String) : Effect

		/** The back arrow, so navigation leaves by the same door as everything else. */
		data object NavigateBack : Effect
	}

	override fun reduce(state: UiState, intent: Intent): UiState = when (intent) {

		// Navigation changes no state. The view model turns it into an effect.
		Intent.BackPressed -> state

		// Only a screen with nothing to show waits. A seeded one is already drawing the card and
		// must not be thrown back to a spinner while the rest is fetched.
		is Intent.Load -> state.copy(isLoading = state.cards.isEmpty(), error = null)

		is Intent.Loaded -> state.copy(
			cards = intent.cards,
			// Clamped rather than trusted: a card that is no longer in the list -- because the
			// filter changed underneath, or the session held a stale list -- must not leave the
			// screen pointing past the end of it.
			currentIndex = intent.currentIndex.coerceIn(0, (intent.cards.size - 1).coerceAtLeast(0)),
			set = intent.set,
			error = intent.error,
			attribution = intent.attribution,
			providerStatesIdentity = intent.providerStatesIdentity,
			providerStatesFinishes = intent.providerStatesFinishes,
			providerLanguages = intent.providerLanguages,
			providerDisplayName = intent.providerDisplayName,
			game = intent.game,
			isLoading = false,
		)

		is Intent.PageChanged -> if (state.cards.isEmpty()) {
			state
		} else {
			state.copy(
				currentIndex = intent.index.coerceIn(0, state.cards.size - 1),
				// A new card is not the card that was zoomed in on.
				isZoomed = false,
				isFullscreen = false,
			)
		}

		// The preference moves at once, so the chip responds to the tap; the cards follow when the
		// fetch lands. If it never does, `LanguageChangeFailed` puts the preference back.
		is Intent.LanguageSelected -> state.copy(
			requestedLanguage = intent.language,
			isChangingLanguage = true,
		)

		// Three outcomes, and only one of them is a language change.
		//
		// An empty answer means the source has nothing in that language. A non-empty answer that
		// does not contain *this* card means the set exists in that language but this printing does
		// not -- Bloomburrow is 398 cards in English and 355 in French. Both leave the screen on
		// what it was showing; the view model says why. Only the third case moves.
		//
		// The middle case used to fall through `coerceAtLeast(0)` and silently jump to the first
		// card of the set, which read as the screen losing your place at random.
		is Intent.LanguageChanged -> {
			val vIndex = intent.cards.indexOfFirst { it.collectorNumber == intent.collectorNumber }
			if (intent.cards.isEmpty() || vIndex < 0) {
				state.copy(isChangingLanguage = false)
			} else {
				state.copy(
					cards = intent.cards,
					// Re-found rather than reused: the same set in another locale can come back
					// with different ids and a different length. The collector number survives.
					currentIndex = vIndex,
					requestedLanguage = intent.language,
					isChangingLanguage = false,
					isZoomed = false,
				)
			}
		}

		is Intent.LanguageChangeFailed -> state.copy(isChangingLanguage = false)


		is Intent.ZoomToggled -> state.copy(isZoomed = intent.isZoomed)

		is Intent.FullscreenToggled -> state.copy(isFullscreen = intent.isFullscreen)

		is Intent.OpenCardmarket -> state

		is Intent.OpenTcgplayer -> state
	}
}
