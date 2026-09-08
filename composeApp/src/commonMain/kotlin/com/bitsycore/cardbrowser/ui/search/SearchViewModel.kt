package com.bitsycore.cardbrowser.ui.search

import androidx.lifecycle.viewModelScope
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.repository.CardRepository
import com.bitsycore.cardbrowser.data.repository.SearchScope
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.lib.pulse.viewmodel.PulseViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Which game to search. Passed at construction so the first frame already knows. */
data class SearchArgs(val game: Game)

/**
 * Runs cross-set searches and keeps the set list they need to hand.
 *
 * The set list is a dependency of *searching*, not of displaying. A local search needs to know
 * which sets to look in, and both kinds of search need the game's set count to report their
 * coverage honestly. It comes from the repository's cache-first path, so it usually costs nothing.
 */
class SearchViewModel(
	private val mRepository: CardRepository,
	private val mRegistry: ProviderRegistry,
	private val mPreferences: PreferencesStore,
	private val mArgs: SearchArgs,
) : PulseViewModel<SearchContract.UiState, SearchContract.Intent, SearchContract.Effect>(
	initialState = SearchContract.UiState(
		game = mArgs.game,
		// Read synchronously from the registry, so the "only searching what you have already
		// downloaded" notice is right on the first frame rather than appearing a moment later.
		isProviderSearchable = mRegistry.resolve(mArgs.game)?.capabilities?.data?.crossSetSearch == true,
	),
	containerContract = SearchContract,
) {

	/** The game's sets, loaded once and reused for every search on this screen. */
	private var mSets: List<CardSet> = emptyList()

	private var mSearchJob: Job? = null

	init {
		viewModelScope.launch {
			mPreferences.load()
			loadSets()
		}
	}

	override suspend fun handleIntent(intent: SearchContract.Intent) {
		when (intent) {
			SearchContract.Intent.Submit -> startSearch()
			SearchContract.Intent.Clear -> mSearchJob?.cancel()
			else -> Unit
		}
	}

	/**
	 * The game's sets, from cache where one exists.
	 *
	 * Collects to completion rather than taking the first emission: the repository emits cache and
	 * then network, and the second is the better answer. When the list is already on disk and
	 * recent the flow ends after one emission anyway, so this does not hold the screen up.
	 */
	private suspend fun loadSets() {
		val vLanguage = mPreferences.preferences.value.primaryLanguage
		var vLatest: List<CardSet>? = null
		mRepository.setList(mArgs.game, vLanguage).collectLatest { vSnapshot ->
			vSnapshot.value?.let { vLatest = it }
		}
		mSets = vLatest.orEmpty()
	}

	private fun startSearch() {
		// Read after the reducer ran, so these are the generation and term this search owns.
		val vState = stateFlow.value
		val vGeneration = vState.requestGeneration
		val vText = vState.submitted
		if (vText.isBlank()) return

		mSearchJob?.cancel()
		mSearchJob = viewModelScope.launch {
			// A search fired before the set list settled would report its coverage against zero
			// known sets, and a local search would have nothing to look in.
			if (mSets.isEmpty()) loadSets()

			mRepository
				.searchAllSets(
					game = mArgs.game,
					text = vText,
					knownSets = mSets,
					language = mPreferences.preferences.value.primaryLanguage,
				)
				.collectLatest { vSnapshot ->
					val vResults = vSnapshot.value
					dispatch(
						SearchContract.Intent.Loaded(
							generation = vGeneration,
							results = vResults?.cards.orEmpty(),
							// A failure before any results is reported as the narrowest scope, so
							// an empty screen never claims the whole game was searched.
							scope = vResults?.scope ?: SearchScope.LOCAL_CACHED_SETS,
							searchedSetCount = vResults?.searchedSetCount ?: 0,
							knownSetCount = vResults?.knownSetCount ?: mSets.size,
							totalCount = vResults?.totalCount,
							hasMore = vResults?.hasMore == true,
							origin = vSnapshot.origin,
							error = vSnapshot.error,
						),
					)
				}
			dispatch(SearchContract.Intent.LoadFinished(vGeneration))
		}
	}
}
