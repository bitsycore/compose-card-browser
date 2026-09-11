package com.bitsycore.cardbrowser.ui.detail

import androidx.lifecycle.viewModelScope
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.repository.CardRepository
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.cardbrowser.platform.LinkOpener
import com.bitsycore.cardbrowser.ui.browse.BrowseSession
import com.bitsycore.lib.pulse.viewmodel.PulseViewModel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch

/**
 * Loads the card the user opened, plus the ones either side of it.
 *
 * The neighbours come from [BrowseSession] -- the list the grid was showing, filters and sort
 * included -- so swiping moves through what the user was actually looking at. When the session is
 * empty, which happens on a cold start straight into a card, it falls back to the cached set in
 * collector order rather than leaving the swipe dead.
 */
class CardDetailViewModel(
	private val mRepository: CardRepository,
	private val mRegistry: ProviderRegistry,
	private val mLinkOpener: LinkOpener,
	private val mPreferences: PreferencesStore,
	mSession: BrowseSession,
	private val mArgs: CardDetailArgs,
) : PulseViewModel<CardDetailContract.UiState, CardDetailContract.Intent, CardDetailContract.Effect>(
	// Seeded before the first frame, not loaded into afterwards.
	//
	// The card being opened is nearly always already in hand: the grid just drew it, and it
	// published its list to the session. Starting empty and filling in a moment later meant the
	// screen opened on a spinner and then snapped to content -- one frame of placeholder that reads
	// as a stutter precisely because everything else about the transition is smooth.
	initialState = seedFrom(mSession, mArgs, mPreferences.preferences.value.primaryLanguage),
	containerContract = CardDetailContract,
) {

	private val mSession: BrowseSession = mSession

	init {
		// Fills in what the seed could not know synchronously: the set behind the Cardmarket link,
		// the provider's capabilities, and the card itself when the session had nothing.
		dispatch(CardDetailContract.Intent.Load(mArgs.cardId, mArgs.setId))
	}

	override suspend fun handleIntent(intent: CardDetailContract.Intent) {
		when (intent) {
			is CardDetailContract.Intent.Load -> load(intent)

			// Told to the session rather than kept here, because the screen that needs to know is the
			// grid behind this one, and it needs to know after this view model is gone.
			is CardDetailContract.Intent.PageChanged ->
				mSession.focus(stateFlow.value.card?.id?.qualified)
			is CardDetailContract.Intent.OpenCardmarket -> openCardmarket(intent.cardId)
			is CardDetailContract.Intent.OpenTcgplayer -> openTcgplayer(intent.cardId)
			is CardDetailContract.Intent.LanguageSelected -> changeLanguage(intent.language)
			else -> Unit
		}
	}

	private suspend fun load(intent: CardDetailContract.Intent.Load) {
		val vCardId = SourceId.parse(intent.cardId) ?: return
		val vSetId = intent.setId?.let(SourceId::parse)

		// The game comes from the card's own id. Every id here is source-qualified, so it names the
		// provider that issued it, and that provider names its game -- which is why no game argument
		// travels through navigation alongside the card id.
		//
		// A card id naming a provider this build does not register has no game, and so nothing to
		// load. Returning beats guessing at one, which is what the old `?: Game.RIFTBOUND` did.
		val vGame = mRegistry.gameFor(vCardId.provider) ?: return

		// Every repository call below passes the language, because it is part of every cache key.
		// Omitting it resolved to the first language the provider happened to carry -- French, for
		// most of them -- so this screen looked for its data under a key the grid had never
		// written: a set list refetched, a card refetched in the wrong language, and a second full
		// download of a set that was already on disk.
		val vLanguage = mPreferences.preferences.value.primaryLanguage

		val vCards = siblingsFor(intent.setId, vSetId, vCardId, vGame, vLanguage)
		val vIndex = vCards.indexOfFirst { it.id == vCardId }

		// The tapped card may be absent from the list -- a stale session, or a set that could not
		// be loaded. Fetching it on its own is better than an error screen, and it simply cannot be
		// swiped away from.
		val vResolved = if (vIndex >= 0) {
			vCards to vIndex
		} else {
			val vSingle = mRepository
				.cardDetail(id = vCardId, game = vGame.id, setId = vSetId, language = vLanguage)
				.value
			if (vSingle != null) listOf(vSingle) to 0 else emptyList<CardPrinting>() to 0
		}

		val vSet = if (vSetId != null) {
			mRepository.setList(vGame.id, vLanguage).first().value
				?.firstOrNull { it.id == vSetId }
				// The same list the grid offers, by construction -- see
				// `CardRepository.knownLanguagesFor`. It reads the confirmation if the grid's menu
				// has already paid for one and falls back to the claim otherwise, and it never
				// makes a request, which is what opening a card must not do.
				?.let { vRecord ->
					mRepository.knownLanguagesFor(vRecord.id, vGame.id)
						.takeIf { it.isNotEmpty() }
						?.let { vRecord.copy(languages = it) }
						?: vRecord
				}
		} else {
			null
		}

		val vProvider = mRegistry.resolve(vGame)

		dispatch(
			CardDetailContract.Intent.Loaded(
				cards = vResolved.first,
				currentIndex = vResolved.second,
				set = vSet,
				error = if (vResolved.first.isEmpty()) {
					com.bitsycore.cardbrowser.core.provider.ProviderError.BadRequest(404)
				} else {
					null
				},
				attribution = vProvider?.capabilities?.attribution?.text,
				providerStatesIdentity = vProvider?.capabilities?.data?.cardIdentity ?: false,
				providerStatesFinishes = vProvider?.capabilities?.data?.finishes ?: false,
				providerLanguages = vProvider?.capabilities?.data?.languages.orEmpty(),
				providerDisplayName = vProvider?.displayName,
				game = vGame,
			),
		)
		mSession.focus(stateFlow.value.card?.id?.qualified)
	}

	/**
	 * Refetches the set in another language and puts the same card back on screen.
	 *
	 * The whole set rather than the one card, for two reasons. The swipe list has to be in the new
	 * language too -- swiping off a Japanese card onto its French neighbours would be worse than not
	 * offering the switch -- and the repository caches per language, so this is one request the first
	 * time and free afterwards.
	 *
	 * The card is re-found by collector number rather than by id, because an id is only meaningful
	 * within the locale that issued it: the Wuthering Waves source numbers its Japanese and Chinese
	 * catalogues separately, and matching on id there would land on nothing.
	 */
	private suspend fun changeLanguage(language: CardLanguage) {
		val vCard = stateFlow.value.card
		val vSetId = vCard?.setId ?: mArgs.setId?.let(SourceId::parse)
		if (vCard == null || vSetId == null) {
			dispatch(CardDetailContract.Intent.LanguageChangeFailed(language))
			return
		}

		// Written through to preferences as well: the user has just said which language they want to
		// read in, and the grid they came from should not disagree with the card they are holding.
		mPreferences.update { vPreferences ->
			vPreferences.copy(
				preferredLanguages = listOf(language) +
					vPreferences.preferredLanguages.filter { it != language },
			)
		}

		val vSnapshot = mRepository
			.cards(setId = vSetId, game = vCard.game, query = CardQuery(), language = language)
			// The last emission, not the first. A repository call emits its cached set before the
			// fetch it then makes, and on a first switch there is no cache under the new language's
			// key -- so the first emission is a partial page and the last is the complete set.
			.filter { it.value != null }
			.lastOrNull()

		val vCards = vSnapshot?.value?.cards.orEmpty()
		val vHasThisCard = vCards.any { it.collectorNumber == vCard.collectorNumber }

		if (vCards.isEmpty() || !vHasThisCard) {
			// Said out loud rather than shrugged off. Which of the two it is matters to the user:
			// one means the source has no edition of this set in that language, the other that it
			// has the set but not this card in it.
			dispatch(CardDetailContract.Intent.LanguageChangeFailed(language))
			emitEffect(
				CardDetailContract.Effect.LanguageUnavailable(
					language = language,
					reason = if (vCards.isEmpty()) {
						"${stateFlow.value.providerDisplayName ?: "This source"} has no " +
							"${language.displayName} edition of ${vCard.setName}."
					} else {
						"${vCard.setName} has a ${language.displayName} edition, but no " +
							"${language.displayName} printing of ${vCard.collectorNumber}."
					},
				),
			)
			return
		}

		dispatch(
			CardDetailContract.Intent.LanguageChanged(
				language = language,
				cards = vCards,
				collectorNumber = vCard.collectorNumber,
			),
		)
		mSession.focus(stateFlow.value.card?.id?.qualified)
	}

	/**
	 * The list to swipe through: the grid's, or the cached set as a fallback.
	 *
	 * The fallback reads the repository with an empty query, which for a set already browsed is a
	 * cache hit and costs nothing.
	 */
	private suspend fun siblingsFor(
		rawSetId: String?,
		setId: SourceId?,
		cardId: SourceId,
		game: GameProfile,
		language: CardLanguage,
	): List<CardPrinting> {
		val vFromSession = mSession.cardsFor(rawSetId)
		if (vFromSession.any { it.id == cardId }) return vFromSession
		if (setId == null) return emptyList()
		return mRepository
			.cards(setId = setId, game = game.id, query = CardQuery(), language = language)
			.first()
			.value
			?.cards
			.orEmpty()
	}

	/**
	 * Opens a card's Cardmarket page.
	 *
	 * Takes the card id rather than reading the current one from state, because the tap belongs to
	 * a specific page and a swipe may have moved on by the time this runs.
	 */
	private fun openCardmarket(cardId: String) {
		val vState = stateFlow.value
		val vCard = vState.cards.firstOrNull { it.id.qualified == cardId } ?: return
		openLink(vState.cardmarketLinkFor(vCard)?.url)
	}

	private fun openTcgplayer(cardId: String) {
		val vState = stateFlow.value
		val vCard = vState.cards.firstOrNull { it.id.qualified == cardId } ?: return
		openLink(vState.tcgplayerLinkFor(vCard)?.url)
	}

	/** Hands a URL to the platform, and says so when the platform will not take it. */
	private fun openLink(url: String?) {
		if (url == null) return
		viewModelScope.launch {
			if (!mLinkOpener.open(url)) {
				emitEffect(CardDetailContract.Effect.LinkFailed(url))
			}
		}
	}
}

/** What the detail screen was opened for. Passed to the view model so it can seed itself. */
data class CardDetailArgs(val cardId: String, val setId: String?)

/**
 * The state to open with, from what the browse session already holds.
 *
 * Falls back to an empty loading state when the session has nothing -- a cold start straight into a
 * card, or a process death -- and the asynchronous load then does the work with a spinner, which is
 * the honest thing to show when there genuinely is nothing yet.
 */
private fun seedFrom(
	session: BrowseSession,
	args: CardDetailArgs,
	language: CardLanguage,
): CardDetailContract.UiState {
	val vCards = session.cardsFor(args.setId)
	val vIndex = vCards.indexOfFirst { it.id.qualified == args.cardId }
	return if (vIndex >= 0) {
		CardDetailContract.UiState(
			cards = vCards,
			currentIndex = vIndex,
			isLoading = false,
			requestedLanguage = language,
		)
	} else {
		CardDetailContract.UiState(requestedLanguage = language)
	}
}
