package com.bitsycore.cardbrowser.ui.cards

import androidx.lifecycle.viewModelScope
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.repository.CardRepository
import com.bitsycore.cardbrowser.data.repository.DataOrigin
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.cardbrowser.ui.browse.BrowseSession
import com.bitsycore.lib.pulse.viewmodel.PulseViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Loads one set's cards and keeps the grid in step with the filters.
 *
 * The behaviour worth naming is what happens when the user changes their mind quickly. Three
 * separate mechanisms cooperate, because none alone is enough:
 *
 * 1. **Debounce.** A text query waits [TEXT_DEBOUNCE_MILLIS] before hitting the repository, so
 *    typing "Annie" is one load rather than five.
 * 2. **Cancellation.** Starting a load cancels the previous job, so superseded work stops rather
 *    than finishing into the void. `collectLatest` does the same within a single flow.
 * 3. **Generation tagging.** A response that was already past the point of cancellation still
 *    arrives, and carries the generation it was started for; the reducer drops it if that is no
 *    longer the current one. This is the one that actually guarantees an older response cannot
 *    overwrite a newer selection.
 */
class CardGridViewModel(
	private val mRepository: CardRepository,
	private val mRegistry: ProviderRegistry,
	private val mPreferences: PreferencesStore,
	private val mSession: BrowseSession,
) : PulseViewModel<CardGridContract.UiState, CardGridContract.Intent, CardGridContract.Effect>(
	initialState = CardGridContract.UiState(),
	containerContract = CardGridContract,
) {

	private var mLoadJob: Job? = null

	init {
		// What the routed provider can filter on. Read once: the sheet must not offer a control
		// the provider cannot honour, and this is the only thing that decides which controls exist.
		val vProvider = mRegistry.resolve(Game.RIFTBOUND)
		if (vProvider != null) {
			dispatch(
				CardGridContract.Intent.CapabilitiesResolved(
					vProvider.capabilities.filtering.supported,
				),
			)
		}
	}

	override suspend fun handleIntent(intent: CardGridContract.Intent) {
		when (intent) {
			is CardGridContract.Intent.SetSelected -> {
				mPreferences.update { it.copy(lastSetId = intent.setId) }
				dispatch(CardGridContract.Intent.Load)
			}
			CardGridContract.Intent.Load -> startLoad(debounce = false)
			is CardGridContract.Intent.QueryChanged -> startLoad(debounce = intent.query.text != null)
			CardGridContract.Intent.ClearFilters -> startLoad(debounce = false)
			else -> Unit
		}
	}

	/**
	 * Starts a load for the generation the reducer just moved to.
	 *
	 * @param debounce true for text input, where a keystroke should not be a request
	 */
	private fun startLoad(debounce: Boolean) {
		val vSnapshot = stateFlow.value
		val vSetId = SourceId.parse(vSnapshot.setId) ?: return
		val vGeneration = vSnapshot.requestGeneration
		val vQuery = vSnapshot.query
		// The routed provider is asked in a language only when it can actually serve one. Riftcodex
		// describes English and nothing else, so scoping its requests -- and therefore its cache
		// entries -- by "French" would key four identical copies of a set under four languages and
		// throw the cache away every time the preference changed, for no difference in the data.
		val vPreferred = mPreferences.preferences.value.primaryLanguage
		val vProviderLanguages = mRegistry.resolve(Game.RIFTBOUND)?.capabilities?.data?.languages.orEmpty()
		val vLanguage = vPreferred.takeIf { it in vProviderLanguages && vProviderLanguages.size > 1 }

		mLoadJob?.cancel()
		mLoadJob = viewModelScope.launch {
			if (debounce) delay(TEXT_DEBOUNCE_MILLIS)

			mRepository.cards(
				setId = vSetId,
				game = Game.RIFTBOUND,
				query = vQuery,
				language = vLanguage,
				knownSetSize = vSnapshot.knownSetSize,
			).collectLatest { vResult ->
				val vCards = vResult.value
				dispatch(
					CardGridContract.Intent.Loaded(
						generation = vGeneration,
						cards = vCards?.cards.orEmpty(),
						isCompleteSet = vCards?.isCompleteSet ?: false,
						cachedCardCount = vCards?.cachedCardCount ?: 0,
						knownSetSize = vCards?.knownSetSize,
						origin = vResult.origin,
						isStale = vResult.isStale,
						error = vResult.error,
						isFinal = vResult.origin != DataOrigin.CACHE || vResult.error != null,
					),
				)

				// Published so card detail can swipe through exactly this list -- filtered and
				// sorted as the user left it -- rather than the raw set. Guarded on the generation
				// so a superseded response cannot hand the detail screen a list the grid rejected.
				if (stateFlow.value.requestGeneration == vGeneration) {
					mSession.publish(vSnapshot.setId, vCards?.cards.orEmpty())
				}

				// Facets come from the complete set only, so they are recomputed after a load that
				// completed one. Offering a rarity that only appears on an unfetched page would
				// produce an empty result the user cannot explain.
				if (vCards?.isCompleteSet == true) {
					dispatch(
						CardGridContract.Intent.FacetsComputed(
							mRepository.facetsFor(vSetId, Game.RIFTBOUND),
						),
					)
				}
			}
		}
	}

	companion object {

		/** Long enough to swallow typing, short enough not to feel laggy. */
		private const val TEXT_DEBOUNCE_MILLIS = 300L
	}
}
