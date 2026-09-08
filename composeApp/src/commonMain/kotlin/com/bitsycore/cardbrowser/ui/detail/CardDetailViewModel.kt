package com.bitsycore.cardbrowser.ui.detail

import androidx.lifecycle.viewModelScope
import com.bitsycore.cardbrowser.core.cardmarket.CardmarketLinkBuilder
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.repository.CardRepository
import com.bitsycore.cardbrowser.platform.LinkOpener
import com.bitsycore.lib.pulse.viewmodel.PulseViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Loads one printing and works out what may honestly be offered for it.
 *
 * The Cardmarket link is built here rather than in the screen so the screen has no opinion about
 * URLs, and opened through [LinkOpener] so building and launching stay separable.
 */
class CardDetailViewModel(
	private val mRepository: CardRepository,
	private val mRegistry: ProviderRegistry,
	private val mLinkOpener: LinkOpener,
) : PulseViewModel<CardDetailContract.UiState, CardDetailContract.Intent, CardDetailContract.Effect>(
	initialState = CardDetailContract.UiState(),
	containerContract = CardDetailContract,
) {

	override suspend fun handleIntent(intent: CardDetailContract.Intent) {
		when (intent) {
			is CardDetailContract.Intent.Load -> load(intent)
			CardDetailContract.Intent.OpenCardmarket -> openCardmarket()
			else -> Unit
		}
	}

	private suspend fun load(intent: CardDetailContract.Intent.Load) {
		val vCardId = SourceId.parse(intent.cardId) ?: return
		val vSetId = intent.setId?.let(SourceId::parse)

		val vSnapshot = mRepository.cardDetail(id = vCardId, game = Game.RIFTBOUND, setId = vSetId)
		val vCard = vSnapshot.value

		// The set is needed for the Cardmarket expansion id, and comes from the same cached set
		// list the previous screen drew from, so this is not an extra request in practice.
		val vSet = if (vCard != null && vSetId != null) {
			mRepository.setList(Game.RIFTBOUND).first().value
				?.firstOrNull { it.id == vSetId || it.code == vCard.setCode }
		} else {
			null
		}

		val vProvider = mRegistry.resolve(Game.RIFTBOUND)

		dispatch(
			CardDetailContract.Intent.Loaded(
				card = vCard,
				set = vSet,
				error = vSnapshot.error,
				cardmarketLink = vCard?.let { CardmarketLinkBuilder.linkFor(it, vSet) },
				attribution = vProvider?.capabilities?.attribution?.text,
				providerStatesIdentity = vProvider?.capabilities?.data?.cardIdentity ?: false,
				providerStatesFinishes = vProvider?.capabilities?.data?.finishes ?: false,
			),
		)
	}

	/**
	 * Opens the Cardmarket page.
	 *
	 * Outbound navigation only. Nothing here can place an order, and a refusal from the platform is
	 * surfaced as an effect rather than swallowed.
	 */
	private fun openCardmarket() {
		val vLink = stateFlow.value.cardmarketLink ?: return
		viewModelScope.launch {
			if (!mLinkOpener.open(vLink.url)) {
				emitEffect(CardDetailContract.Effect.LinkFailed(vLink.url))
			}
		}
	}
}
