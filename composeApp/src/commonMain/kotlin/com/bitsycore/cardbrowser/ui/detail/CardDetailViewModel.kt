package com.bitsycore.cardbrowser.ui.detail

import androidx.lifecycle.viewModelScope
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardQuery
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.repository.CardRepository
import com.bitsycore.cardbrowser.platform.LinkOpener
import com.bitsycore.cardbrowser.ui.browse.BrowseSession
import com.bitsycore.lib.pulse.viewmodel.PulseViewModel
import kotlinx.coroutines.flow.first
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
	mSession: BrowseSession,
	private val mArgs: CardDetailArgs,
) : PulseViewModel<CardDetailContract.UiState, CardDetailContract.Intent, CardDetailContract.Effect>(
	// Seeded before the first frame, not loaded into afterwards.
	//
	// The card being opened is nearly always already in hand: the grid just drew it, and it
	// published its list to the session. Starting empty and filling in a moment later meant the
	// screen opened on a spinner and then snapped to content -- one frame of placeholder that reads
	// as a stutter precisely because everything else about the transition is smooth.
	initialState = seedFrom(mSession, mArgs),
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
			else -> Unit
		}
	}

	private suspend fun load(intent: CardDetailContract.Intent.Load) {
		val vCardId = SourceId.parse(intent.cardId) ?: return
		val vSetId = intent.setId?.let(SourceId::parse)

		val vCards = siblingsFor(intent.setId, vSetId, vCardId)
		val vIndex = vCards.indexOfFirst { it.id == vCardId }

		// The tapped card may be absent from the list -- a stale session, or a set that could not
		// be loaded. Fetching it on its own is better than an error screen, and it simply cannot be
		// swiped away from.
		val vResolved = if (vIndex >= 0) {
			vCards to vIndex
		} else {
			val vSingle = mRepository
				.cardDetail(id = vCardId, game = Game.RIFTBOUND, setId = vSetId)
				.value
			if (vSingle != null) listOf(vSingle) to 0 else emptyList<CardPrinting>() to 0
		}

		val vSet = if (vSetId != null) {
			mRepository.setList(Game.RIFTBOUND).first().value?.firstOrNull { it.id == vSetId }
		} else {
			null
		}

		val vProvider = mRegistry.resolve(Game.RIFTBOUND)

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
	): List<CardPrinting> {
		val vFromSession = mSession.cardsFor(rawSetId)
		if (vFromSession.any { it.id == cardId }) return vFromSession
		if (setId == null) return emptyList()
		return mRepository
			.cards(setId = setId, game = Game.RIFTBOUND, query = CardQuery())
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
		val vLink = vState.cardmarketLinkFor(vCard) ?: return
		viewModelScope.launch {
			if (!mLinkOpener.open(vLink.url)) {
				emitEffect(CardDetailContract.Effect.LinkFailed(vLink.url))
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
private fun seedFrom(session: BrowseSession, args: CardDetailArgs): CardDetailContract.UiState {
	val vCards = session.cardsFor(args.setId)
	val vIndex = vCards.indexOfFirst { it.id.qualified == args.cardId }
	return if (vIndex >= 0) {
		CardDetailContract.UiState(cards = vCards, currentIndex = vIndex, isLoading = false)
	} else {
		CardDetailContract.UiState()
	}
}
