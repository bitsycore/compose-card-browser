package com.bitsycore.cardbrowser.ui.cards

import androidx.lifecycle.viewModelScope
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.model.CardLanguage
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
				emitEffect(CardGridContract.Effect.OpenCard(intent.card))

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
		val vSetId = SourceId.parse(vSnapshot.setId) ?: return
		val vGeneration = vSnapshot.requestGeneration
		val vQuery = vSnapshot.query
		// The state's language, which starts as the user's preference and can be changed from the
		// top bar. Never narrowed here: doing that was a bug -- it dropped the language to `null`
		// whenever the provider could not serve it while the set list passed the preference
		// unchanged, and since a cache key embeds the language the two wrote and read different
		// files, so no set ever showed as saved. `CardRepository` normalises it once, for every
		// caller, against what the provider will really answer in.
		val vLanguage = vSnapshot.language ?: mPreferences.preferences.value.primaryLanguage
		val vGame = gameOf(vSnapshot.setId) ?: return

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
					mSession.publish(vSnapshot.setId, vCards?.cards.orEmpty())
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
	 * The game a set id belongs to, via the provider that issued it.
	 *
	 * `null` when the id will not parse or names a provider this build does not route -- both of
	 * which are reachable from a restored back stack, and neither of which should crash.
	 */
	private fun gameOf(qualifiedSetId: String): GameProfile? =
		SourceId.parse(qualifiedSetId)?.let { mRegistry.gameFor(it.provider) }

	companion object {

		/** Long enough to swallow typing, short enough not to feel laggy. */
		private const val TEXT_DEBOUNCE_MILLIS = 300L
	}
}
