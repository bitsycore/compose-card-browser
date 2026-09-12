package com.bitsycore.cardbrowser.ui.search

import androidx.lifecycle.viewModelScope
import com.bitsycore.cardbrowser.core.model.CardSet
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.repository.CardRepository
import com.bitsycore.cardbrowser.data.repository.DataOrigin
import com.bitsycore.cardbrowser.data.repository.SearchScope
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.cardbrowser.ui.browse.BrowseSession
import com.bitsycore.lib.pulse.viewmodel.PulseViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** Which game to search. Passed at construction so the first frame already knows. */
data class SearchArgs(val game: GameId)

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
	private val mSession: BrowseSession,
	private val mArgs: SearchArgs,
) : PulseViewModel<SearchContract.UiState, SearchContract.Intent, SearchContract.Effect>(
	initialState = SearchContract.UiState(
		game = mRegistry.profileFor(mArgs.game),
		// Read synchronously from the registry, so the "only searching what you have already
		// downloaded" notice is right on the first frame rather than appearing a moment later.
		isProviderSearchable = mRegistry.resolve(mArgs.game)?.capabilities?.data?.crossSetSearch == true,
	),
	containerContract = SearchContract,
) {

	/** The game's sets, loaded once and reused for every search on this screen. */
	private var mSets: List<CardSet> = emptyList()

	private var mSearchJob: Job? = null

	/** Debounces live search. Cancelled and restarted on every keystroke. */
	private var mLiveSearchJob: Job? = null

	/**
	 * Whether this game's source can search its whole catalogue over the network.
	 *
	 * The one fact that decides whether typing searches. Read once from the registry rather than off
	 * the state, so it cannot be affected by a reducer.
	 */
	private val mIsProviderSearchable: Boolean =
		mRegistry.resolve(mArgs.game)?.capabilities?.data?.crossSetSearch == true

	init {
		viewModelScope.launch {
			mPreferences.load()
			loadSets()
			// What this game's stored cards actually contain, so the filter list offers nothing
			// that would match nothing.
			dispatch(SearchContract.Intent.FacetsLoaded(mRepository.searchFacets(mArgs.game)))
		}
	}

	override suspend fun handleIntent(intent: SearchContract.Intent) {
		when (intent) {
			SearchContract.Intent.BackPressed -> emitEffect(SearchContract.Effect.NavigateBack)

			is SearchContract.Intent.CardOpened -> {
				// The list the detail will swipe through: these results, in this order, rather
				// than the card's set. Opening a search result and finding yourself walking a set
				// you never asked for is the same surprise the grid's own filters avoid.
				mSession.publish(
					BrowseSession.searchKey(mArgs.game.value),
					stateFlow.value.results,
				)
				emitEffect(SearchContract.Effect.OpenCard(intent.card))
			}

			SearchContract.Intent.Submit -> {
				mLiveSearchJob?.cancel()
				startSearch()
			}

			is SearchContract.Intent.AdvancedToggled, is SearchContract.Intent.FacetsLoaded -> Unit

			// A filtered search runs as you type, always. It is a local query over indexed columns
			// and costs nobody a request -- which is the same rule `QueryChanged` follows, for the
			// same reason.
			is SearchContract.Intent.FilterChanged -> {
				mLiveSearchJob?.cancel()
				mLiveSearchJob = viewModelScope.launch {
					delay(LIVE_SEARCH_DEBOUNCE_MILLIS.milliseconds)
					dispatch(SearchContract.Intent.Submit)
				}
			}

			/**
			 * Typing searches, but only where searching is free.
			 *
			 * A cache-scoped search reads sets already on disk, so there is no one to be rude to and
			 * no reason to make the user press a key to see results. A *provider* search is a
			 * request per keystroke against someone else's server, which several of these sources
			 * explicitly ask callers not to do -- those keep waiting for the keyboard's action.
			 *
			 * So the same screen behaves differently per game, and it is the provider's declared
			 * `crossSetSearch` that decides, not a guess about how fast the source feels.
			 */
			is SearchContract.Intent.QueryChanged -> {
				mLiveSearchJob?.cancel()
				if (!mIsProviderSearchable && intent.text.isNotBlank()) {
					mLiveSearchJob = viewModelScope.launch {
						// Short, and not about rate limiting: it is there so a set of 350 cards is
						// not filtered five times while a five-letter word is typed.
						delay(LIVE_SEARCH_DEBOUNCE_MILLIS.milliseconds)
						dispatch(SearchContract.Intent.Submit)
					}
				}
			}

			SearchContract.Intent.Clear -> {
				mLiveSearchJob?.cancel()
				mSearchJob?.cancel()
			}

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

		// Two searches, and which one runs is decided by what the user filled in.
		//
		// Anything beyond a name -- an exclusion, a type, a rarity, a cost, a domain -- can only be
		// answered by the store, so it goes to the indexed query. A bare name still goes to the
		// provider where the provider can search, because that one can see sets this device has
		// never downloaded. Sending a name to the store instead would quietly shrink what a search
		// covers, which is the opposite of what this screen is for.
		if (vState.hasAdvancedFilters) {
			startStoredSearch(vState, vGeneration)
			return
		}
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

	private companion object {

		/** Long enough to swallow a fast typist's keystrokes, short enough to feel immediate. */
		const val LIVE_SEARCH_DEBOUNCE_MILLIS = 180L
	}


	/**
	 * The advanced search, against the store.
	 *
	 * Reports [SearchScope.LOCAL_CACHED_SETS] always, because that is what it is. The screen's
	 * coverage notice is doing real work here: this query is fast enough over a hundred thousand
	 * rows to feel like a search of everything, and an empty result means "not in what you have
	 * downloaded" rather than "does not exist".
	 */
	private fun startStoredSearch(state: SearchContract.UiState, generation: Int) {
		mSearchJob?.cancel()
		mSearchJob = viewModelScope.launch {
			if (mSets.isEmpty()) loadSets()
			val vResults = mRepository.searchStoredCards(
				game = mArgs.game,
				filter = state.filter.copy(
					text = state.submitted.takeIf { it.isNotBlank() },
					language = mPreferences.preferences.value.primaryLanguage,
				),
				knownSets = mSets,
			)
			dispatch(
				SearchContract.Intent.Loaded(
					generation = generation,
					results = vResults.cards,
					scope = vResults.scope,
					searchedSetCount = vResults.searchedSetCount,
					knownSetCount = vResults.knownSetCount,
					totalCount = vResults.totalCount,
					hasMore = vResults.hasMore,
					origin = DataOrigin.CACHE,
					error = null,
				),
			)
			dispatch(SearchContract.Intent.LoadFinished(generation))
		}
	}
}
