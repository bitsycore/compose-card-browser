package com.bitsycore.tcgexplorer.ui.screen.cards

import com.bitsycore.tcgexplorer.core.model.CardPrinting
import com.bitsycore.tcgexplorer.core.model.GameId
import com.bitsycore.tcgexplorer.core.model.ArtworkTreatment
import com.bitsycore.tcgexplorer.core.game.RarityLadder
import com.bitsycore.tcgexplorer.core.filter.CardFacets
import androidx.lifecycle.viewModelScope
import com.bitsycore.tcgexplorer.core.game.GameProfile
import com.bitsycore.tcgexplorer.core.model.CardLanguage
import com.bitsycore.tcgexplorer.core.model.SourceId
import com.bitsycore.tcgexplorer.core.provider.ProviderRegistry
import com.bitsycore.tcgexplorer.data.cache.CardSearchFilter
import com.bitsycore.tcgexplorer.data.repository.CardRepository
import com.bitsycore.tcgexplorer.data.repository.DataOrigin
import com.bitsycore.tcgexplorer.data.settings.PreferencesStore
import com.bitsycore.tcgexplorer.controller.BrowseSession
import com.bitsycore.lib.pulse.viewmodel.PulseViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

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

	/** Which game's stored facets have been read. See [loadStoredFacets]. */
	private var mStoredFacetGame: GameId? = null

	init {
		// The view choice is a habit rather than a per-screen setting, so it arrives with the
		// screen instead of resetting to grid every time a set is opened.
		viewModelScope.launch {
			mPreferences.load()
			val vPreferences = mPreferences.preferences.value
			dispatch(
				CardGridContract.Intent.ViewPreferencesLoaded(
					mode = vPreferences.cardViewMode,
					height = vPreferences.cardRowHeight,
					tileSize = vPreferences.cardTileSize,
				),
			)
		}
	}

	override suspend fun handleIntent(intent: CardGridContract.Intent) {
		when (intent) {
			CardGridContract.Intent.BackPressed ->
				emitEffect(CardGridContract.Effect.NavigateBack)

			is CardGridContract.Intent.CardOpened ->
				emitEffect(
					CardGridContract.Effect.OpenCard(intent.card, stateFlow.value.browseKey),
				)

			is CardGridContract.Intent.SetFilterChanged -> startLoad(debounce = false)

			is CardGridContract.Intent.SearchCoverage, is CardGridContract.Intent.FacetsLoading -> Unit

			is CardGridContract.Intent.GameSelected -> {
				resolveGameCapabilities(intent.game)
				loadSetOptions(intent.game)
				startLoad(debounce = false)
			}

			CardGridContract.Intent.DownloadsRequested ->
				emitEffect(CardGridContract.Effect.OpenDownloads)

			is CardGridContract.Intent.ViewModeChanged ->
				mPreferences.update { it.copy(cardViewMode = intent.mode) }

			is CardGridContract.Intent.RowHeightChanged ->
				mPreferences.update { it.copy(cardRowHeight = intent.height) }

			is CardGridContract.Intent.TileSizeChanged ->
				mPreferences.update { it.copy(cardTileSize = intent.size) }

			is CardGridContract.Intent.SetSelected -> {
				mPreferences.update { it.copy(lastSetId = intent.setId) }
				// What the routed provider can filter on, and which game's vocabulary the sheet
				// should speak. Resolved here rather than in `init` because both depend on the set
				// -- and therefore on the provider -- which is not known until a set is selected.
				//
				// The provider comes from the set id itself: every id in this app is
				// source-qualified, so it already names its provider, and the routing table turns
				// that into a game. No game argument has to be threaded through navigation.
				val vGame = gameOf(intent.setId)
				val vProvider = vGame?.let { mRegistry.resolve(it) }
				if (vGame != null) loadSetOptions(vGame)
				if (vGame != null && vProvider != null) {
					// What *this set* is published in, not what the source can serve in general.
					// Those are different claims, and using the second one for a language menu is
					// what offered a Korean edition of Pokemon's Base Set: TCGdex serves eleven
					// locales, and Base Set exists in six of them. Worse than useless, because
					// TCGdex's detail endpoint folds the case of a set id -- `/en/sets/SM10`
					// answers with international Unbroken Bonds rather than 404ing on the Japanese
					// set -- so the wrong language could show a different set's cards entirely.
					//
					// Falls back to the provider's own languages when no set record is cached,
					// which is the only honest answer available: a menu of one language is not
					// evidence that only one exists.
					val vSetId = SourceId.parse(intent.setId)
					val vRecord = vSetId?.let { mRepository.setRecord(it, vGame.id) }
					val vPreferred = mPreferences.preferences.value.primaryLanguage
					// What the *menu* may offer, from the best answer already on hand and without
					// a request: the confirmation if this set's menu has ever been opened, else
					// the set's claim, else the source's list. The card detail screen reads the
					// identical helper, which is what keeps the two menus from disagreeing --
					// they did, and it was reported: detail listed the two languages whose cards
					// were on disk while the grid beside it offered eleven.
					val vClaimed = vSetId
						?.let { mRepository.knownLanguagesFor(it, vGame.id) }
						?.takeIf { it.isNotEmpty() }
						?: vProvider.capabilities.data.languages
					val vOpening = vSetId?.let {
						mRepository.openingLanguageFor(it, vGame.id, vPreferred)
					}
					// What is *established* about this set, as opposed to what `vClaimed` says: a
					// confirmation already on disk, plus every language whose cards are held. This
					// is what the menu may list -- see `UiState.confirmedLanguages`.
					val vConfirmed = vSetId
						?.let { mRepository.confirmedLanguagesFor(it, vGame.id) }
						.orEmpty()
					dispatch(
						CardGridContract.Intent.CapabilitiesResolved(
							supportedFilters = vProvider.capabilities.filtering.supported,
							game = vGame,
							languages = vClaimed,
							// Confirmed for *one* language rather than all of them.
							//
							// This used to call `languagesFor`, which probes the source once per
							// candidate -- eleven requests to api.scryfall.com for a Magic set,
							// run before the grid could draw anything, and run even when every
							// card was already on disk. It was both the API pressure and the
							// reason a downloaded set took seconds to open.
							//
							// `openingLanguageFor` answers from the cache where it can, one probe
							// where it cannot, and only pays for the full confirmation when the
							// preferred language really has nothing. The rule it protects is the
							// same: a set is never opened in a language with no cards.
							language = vOpening?.language ?: vProvider.resolveLanguage(vPreferred),
							confirmed = vConfirmed,
							// Non-null only when the set opened in a language other than the one
							// asked for -- see `OpeningLanguage`.
							substitutedFor = vOpening?.substitutedFor,
							substitution = vOpening?.reason,
						),
					)
				}
				dispatch(CardGridContract.Intent.Load)
			}
			CardGridContract.Intent.LanguageOptionsRequested -> {
				// The expensive confirmation, paid by someone looking at the menu rather than by
				// everyone who opens a set. Cached by the repository, so a second look is free,
				// and a set whose cards are on disk needs no request for the languages it holds.
				val vState = stateFlow.value
				val vGame = vState.game ?: return
				val vSetId = SourceId.parse(vState.setId) ?: return
				val vConfirmed = runCatching { mRepository.languagesFor(vSetId, vGame.id) }
					.getOrDefault(emptySet())
				dispatch(CardGridContract.Intent.LanguageOptionsResolved(vConfirmed))
			}
			CardGridContract.Intent.Load -> startLoad(debounce = false)
			is CardGridContract.Intent.QueryChanged -> startLoad(debounce = intent.query.text != null)
			CardGridContract.Intent.ClearFilters -> startLoad(debounce = false)
			is CardGridContract.Intent.LanguageSelected -> changeLanguage(intent.language)
			else -> Unit
		}
	}

	/**
	 * Loads this set in another language, and puts it back if that turns out to be impossible.
	 *
	 * The preference is written *after* the load succeeds, not before. A source that cannot answer
	 * for this set in the chosen language would otherwise leave the whole app switched to a
	 * language the user could not see anything in -- and that is a real case rather than a
	 * defensive one: TCGdex keys each locale by its own set ids, so the English `base1` is `PMCG1`
	 * in Japanese and simply absent from Korean. There is no Korean edition of an English Pokémon
	 * set to fetch.
	 */
	private fun changeLanguage(language: CardLanguage) {
		val vSetName = stateFlow.value.setName
		startLoad(debounce = false)

		viewModelScope.launch {
			// The load job is the one just started; waiting on it keeps the two decisions in order.
			mLoadJob?.join()
			val vState = stateFlow.value
			if (vState.language != language) return@launch

			if (vState.cards.isEmpty()) {
				dispatch(CardGridContract.Intent.LanguageUnavailable)
				emitEffect(
					CardGridContract.Effect.LanguageUnavailable(
						language = language,
						reason = "This source has no ${language.displayName} edition of $vSetName.",
					),
				)
			} else {
				// It worked, so it becomes the preference: the set list, search and card detail all
				// read the same one, and disagreeing with the grid is what made sets read as unsaved.
				mPreferences.update { vPreferences ->
					vPreferences.copy(
						preferredLanguages = listOf(language) +
							vPreferences.preferredLanguages.filter { it != language },
					)
				}
			}
		}
	}

	/**
	 * Starts a load for the generation the reducer just moved to.
	 *
	 * @param debounce true for text input, where a keystroke should not be a request
	 */
	private fun startLoad(debounce: Boolean) {
		val vSnapshot = stateFlow.value
		// One set selected reads that set; anything else reads the store.
		//
		// The two are not interchangeable and the difference is the point. A set is read through
		// the cache from its provider, which is what lets a set nobody has downloaded be browsed
		// at all. Across sets there is no such request to make -- no source here answers "every
		// Riftbound card matching this" -- so the only honest answer is what this device holds,
		// and the screen says so.
		if (vSnapshot.isStoredBrowse) {
			startStoredLoad(vSnapshot, debounce)
			return
		}
		// The set the *filter* names, which is the one the user is asking for. It is the routed set
		// on the ordinary path and any set at all when one was picked in the search's filter.
		val vSelectedSet = vSnapshot.setIds.single()
		val vSetId = SourceId.parse(vSelectedSet) ?: return
		val vGeneration = vSnapshot.requestGeneration
		val vQuery = vSnapshot.query
		// The state's language, which starts as the user's preference and can be changed from the
		// top bar. Never narrowed here: doing that was a bug -- it dropped the language to `null`
		// whenever the provider could not serve it while the set list passed the preference
		// unchanged, and since a cache key embeds the language the two wrote and read different
		// files, so no set ever showed as saved. `CardRepository` normalises it once, for every
		// caller, against what the provider will really answer in.
		val vLanguage = vSnapshot.language ?: mPreferences.preferences.value.primaryLanguage
		val vGame = gameOf(vSelectedSet) ?: return

		mLoadJob?.cancel()
		mLoadJob = viewModelScope.launch {
			if (debounce) delay(TEXT_DEBOUNCE_MILLIS.milliseconds)

			mRepository.cards(
				setId = vSetId,
				game = vGame.id,
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
					mSession.publish(vSnapshot.browseKey, vCards?.cards.orEmpty())
				}

				// Facets come from the complete set only, so they are recomputed after a load that
				// completed one. Offering a rarity that only appears on an unfetched page would
				// produce an empty result the user cannot explain.
				if (vCards?.isCompleteSet == true) {
					dispatch(
						CardGridContract.Intent.FacetsComputed(
							// The language matters: it is part of the cache key, and omitting it read
							// `…/set/…/fr` while the grid had written `…/set/…/en`. The miss was
							// silent -- an empty `CardFacets` -- so the filter sheet came up with no
							// chips at all for a set that was fully downloaded.
							mRepository.facetsFor(vSetId, vGame.id, vLanguage),
						),
					)
				}
			}
			// The flow is done. A fresh cached set emits once and returns, so this is the only
			// thing that clears the loading flag on that path.
			dispatch(CardGridContract.Intent.LoadFinished(vGeneration))
		}
	}

	/**
	 * The other source: the store, across whatever sets the filter names.
	 *
	 * This is what the separate search screen used to be, and it is the same query -- the grid's
	 * `CardQuery` translated into the store's filter. What it cannot do is see a card this device
	 * has never downloaded, which is why a single set still goes to the provider.
	 */
	private fun startStoredLoad(snapshot: CardGridContract.UiState, debounce: Boolean) {
		val vGame = snapshot.game?.id ?: gameOf(snapshot.setId)?.id ?: return
		loadStoredFacets(vGame, snapshot.game?.rarityLadder.orEmpty())
		val vGeneration = snapshot.requestGeneration
		val vQuery = snapshot.query
		val vLanguage = snapshot.language ?: mPreferences.preferences.value.primaryLanguage

		mLoadJob?.cancel()
		mLoadJob = viewModelScope.launch {
			if (debounce) delay(TEXT_DEBOUNCE_MILLIS.milliseconds)

			val vResults = mRepository.searchStoredCards(
				game = vGame,
				filter = CardSearchFilter(
					text = vQuery.text?.takeIf { it.isNotBlank() },
					cardTypes = vQuery.cardTypes,
					rarities = vQuery.rarities,
					domains = vQuery.domains,
					treatments = vQuery.treatments,
					// The chosen values, not the span between them. See `CardSearchFilter.costs`.
					costs = vQuery.costs.toSet(),
					setIds = snapshot.setIds,
					language = vLanguage,
				),
				// The catalogue, so the result can say how much of the game it could see. Read from
				// the cache: this is the screen that only searches what is downloaded, and asking
				// the network for a denominator would be a request made to draw a sentence.
				knownSets = mRepository.cachedSetList(vGame, vLanguage).orEmpty(),
			)
			dispatch(
				CardGridContract.Intent.Loaded(
					generation = vGeneration,
					cards = vResults.cards,
					// Never a set, so never complete: "all of this set is here" is not a claim
					// this branch is in a position to make about anything.
					isCompleteSet = false,
					cachedCardCount = vResults.cards.size,
					knownSetSize = null,
					origin = DataOrigin.CACHE,
					isStale = false,
					error = null,
					isFinal = true,
				),
			)
			dispatch(
				CardGridContract.Intent.SearchCoverage(
					searched = vResults.searchedSetCount,
					known = vResults.knownSetCount,
					isTruncated = vResults.hasMore,
				),
			)
			if (stateFlow.value.requestGeneration == vGeneration) {
				mSession.publish(snapshot.browseKey, vResults.cards)
			}
			// And top the filter values up if this result knows something they do not.
			//
			// They are read once per game, which is right -- five DISTINCT queries over a hundred
			// thousand rows is not a per-keystroke cost. But "once" was also once *ever*, so values
			// that arrived after the read -- the rest of an import, a set downloaded since -- were
			// invisible until the screen was recreated. Comparing what just came back against what
			// the sheet is offering is a pass over a page already in memory, and it is the only
			// evidence available that the store has grown.
			if (vResults.cards.any { it.isOutside(stateFlow.value.facets) }) {
				mStoredFacetGame = null
				loadStoredFacets(vGame, snapshot.game?.rarityLadder.orEmpty())
			}
		}
	}

	/**
	 * What the game's routed source can filter on, for the search that is not one set.
	 *
	 * The set branch resolves this from the provider the *set id* names, which a game-wide search
	 * does not have -- so `supportedFilters` stayed empty, and a sheet that offers only what its
	 * source supports offered nothing. The whole global search had a sort order and no filters.
	 *
	 * The language half of `CapabilitiesResolved` is deliberately the source's own list here and
	 * carries no confirmation. Those are per-set claims -- which editions *this set* has -- and
	 * there is no set to make them about; a search across a game's cards is not a claim that any
	 * one of them is published in what the menu shows.
	 */
	private fun resolveGameCapabilities(game: GameProfile) {
		val vProvider = mRegistry.resolve(game) ?: return
		dispatch(
			CardGridContract.Intent.CapabilitiesResolved(
				supportedFilters = vProvider.capabilities.filtering.supported,
				game = game,
				languages = vProvider.capabilities.data.languages,
				language = mPreferences.preferences.value.primaryLanguage,
			),
		)
	}

	/** True when this card carries a filterable value the sheet is not currently offering. */
	private fun CardPrinting.isOutside(facets: CardFacets): Boolean =
		classification.rarity?.let { it !in facets.rarities } == true ||
			classification.type?.let { it !in facets.cardTypes } == true ||
			classification.domains.any { it !in facets.domains } ||
			attributes.cost?.let { it !in facets.costs } == true

	private fun loadStoredFacets(game: GameId, rarityLadder: List<String>) {
		if (mStoredFacetGame == game) return
		viewModelScope.launch {
			dispatch(CardGridContract.Intent.FacetsLoading(true))
			val vStored = mRepository.searchFacets(game)
			if (vStored.cardTypes.isEmpty() && vStored.rarities.isEmpty() &&
				vStored.domains.isEmpty() && vStored.costs.isEmpty()
			) {
				dispatch(CardGridContract.Intent.FacetsLoading(false))
				return@launch
			}
			mStoredFacetGame = game
			dispatch(
				CardGridContract.Intent.FacetsComputed(
					CardFacets(
						domains = vStored.domains,
						cardTypes = vStored.cardTypes,
						// The game's own order, so the chips read Common to Showcase rather than
						// alphabetically -- the same treatment `CardFilters.facetsOf` gives the
						// per-set ones, which is what makes the two sheets look like one control.
						rarities = RarityLadder.sorted(rarityLadder, vStored.rarities),
						// The costs that occur, not the span between the extremes. Expanding a
						// range was a crash: Magic's Gleemax has a mana value of 1,000,000.
						costs = vStored.costs,
						treatments = vStored.treatments.mapNotNull { vName ->
							ArtworkTreatment.entries.firstOrNull { it.name == vName }
						},
					),
				),
			)
		}
	}

	/**
	 * Which sets this game has anything stored for, so the filter can widen beyond this one.
	 *
	 * In its own coroutine: it is a catalogue read and a file check per set, and nothing on screen
	 * waits for it -- the set that was opened is already ticked. The same lesson the search screen
	 * learned the hard way.
	 */
	private fun loadSetOptions(game: GameProfile) {
		viewModelScope.launch {
			val vLanguage = mPreferences.preferences.value.primaryLanguage
			val vSets = mRepository.cachedSetList(game.id, vLanguage).orEmpty()
			if (vSets.isEmpty()) return@launch
			val vSaved = mRepository.savedSetIds(game.id, vSets, vLanguage)
			dispatch(
				CardGridContract.Intent.SetOptionsLoaded(
					vSets.filter { it.id.qualified in vSaved }
						.map { CardGridContract.SetChoice(it.id.qualified, it.name) },
				),
			)
		}
	}

	private fun gameOf(qualifiedSetId: String): GameProfile? =
		SourceId.parse(qualifiedSetId)?.let { mRegistry.gameFor(it.provider) }

	companion object {

		/** Long enough to swallow typing, short enough not to feel laggy. */
		private const val TEXT_DEBOUNCE_MILLIS = 300L
	}
}
