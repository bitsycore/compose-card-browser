package com.bitsycore.cardbrowser.ui.sets

import androidx.lifecycle.viewModelScope
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.data.download.DownloadKind
import com.bitsycore.cardbrowser.data.download.DownloadRequest
import com.bitsycore.cardbrowser.core.model.GameId
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.repository.CardRepository
import com.bitsycore.cardbrowser.data.repository.DataOrigin
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.lib.pulse.viewmodel.PulseViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import com.bitsycore.cardbrowser.data.download.DownloadManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Which game this set list is for. Passed at construction so the first frame already knows. */
data class SetListArgs(val game: GameId)

/**
 * Loads the set list for the chosen game, and remembers both the game and the set last opened.
 *
 * The only asynchronous work on this screen. Everything it learns re-enters the state through
 * [SetListContract.Intent.Loaded], so the state machine stays in one readable place.
 */
class SetListViewModel(
	private val mRepository: CardRepository,
	private val mPreferences: PreferencesStore,
	private val mRegistry: ProviderRegistry,
	private val mDownloads: DownloadManager,
	private val mArgs: SetListArgs,
) : PulseViewModel<SetListContract.UiState, SetListContract.Intent, SetListContract.Effect>(
	// Seeded at construction from the route, so the first frame already names the right game
	// rather than showing "Riftbound" for a moment on the way to Pokémon.
	initialState = SetListContract.UiState(game = mRegistry.profileFor(mArgs.game)),
	containerContract = SetListContract,
) {

	/**
	 * The load in flight.
	 *
	 * Cancelled before a new one starts, so a refresh tapped twice does not leave two collectors
	 * racing to write the same state. The generation counter in the contract is the second half of
	 * the same defence and covers the response that is already past cancellation.
	 */
	private var mLoadJob: Job? = null

	init {
		viewModelScope.launch {
			mPreferences.load()
			val vPreferences = mPreferences.preferences.value

			// Straight from the registry, which orders them as the routing table lists them. There
			// is no enum to order by any more, and the routing table is now the only statement
			// anywhere of which games this build offers.
			val vGames = mRegistry.games
			// The route wins over the remembered game: the user just picked one, and honouring a
			// stale preference over an explicit choice would open the wrong game. A route naming a
			// game this build does not serve falls back to the first it does.
			val vGame = vGames.firstOrNull { it.id == mArgs.game } ?: vGames.firstOrNull() ?: return@launch

			dispatch(SetListContract.Intent.GamesRestored(games = vGames, game = vGame))
			resolveBrowsingLanguage(vGame.id)
			dispatch(SetListContract.Intent.LastOpenedSetRestored(vPreferences.lastSetId))
			dispatch(SetListContract.Intent.FavouritesRestored(vPreferences.favouriteSets))
			dispatch(SetListContract.Intent.HideEmptyToggled(vPreferences.hideEmptySets))
			// A 3 KB question asked once, so the download-all dialog can state the size before
			// anything large is fetched. Silent on failure: a source that will not answer about
			// its bulk file simply does not offer one, which is the same as not having one.
			runCatching { mRepository.bulkVariants(vGame.id) }
				.getOrNull()
				?.takeIf { it.isNotEmpty() }
				?.let { dispatch(SetListContract.Intent.BulkAvailable(it)) }
			// Started only once the game is known, so the first request is not fired against
			// whichever game the initial state happened to name.
			dispatch(SetListContract.Intent.Refresh)
		}

		// Re-check what is on disk whenever a download stops running.
		//
		// The saved marks are a snapshot taken after a load, not a flow over the cache, so without
		// this a set downloaded while looking at the list only showed its marks after leaving the
		// screen and coming back. Keyed on how many jobs are *finished* rather than on the job list
		// itself, so a progress tick -- which changes the list several times a second -- does not
		// re-run a file-existence check over every row.
		viewModelScope.launch {
			mDownloads.jobs
				.map { vJobs -> vJobs.count { !it.isActive } }
				.distinctUntilChanged()
				.drop(1)
				.collect {
					val vState = stateFlow.value
					val vGame = vState.game ?: return@collect
					// A finished whole-game import has written every set in the catalogue, so the
					// list itself is stale and not only its marks. Re-reading the marks alone
					// would leave every row's count saying what it said before the import ran.
					if (mDownloads.jobs.value.any { it.request.isWholeGameImport && !it.isActive }) {
						dispatch(SetListContract.Intent.Refresh)
					}
					resolveSavedSets(vGame.id, mPreferences.preferences.value.primaryLanguage)
				}
		}

		// The option lives in settings, so this follows it rather than owning it. Without this the
		// list kept whatever it read at construction and only picked up a change on the next visit
		// that happened to rebuild this view model.
		viewModelScope.launch {
			mPreferences.preferences
				.map { it.hideEmptySets }
				.distinctUntilChanged()
				.collect { dispatch(SetListContract.Intent.HideEmptyToggled(it)) }
		}
	}

	override suspend fun handleIntent(intent: SetListContract.Intent) {
		when (intent) {
			SetListContract.Intent.Refresh -> startLoad()

			SetListContract.Intent.BulkUpdateCheckRequested -> {
				val vGame = stateFlow.value.game ?: return
				// Re-read the manifest, then re-derive what counts as imported from it. A source
				// that will not answer leaves the previous list in place rather than clearing it:
				// failing to check is not evidence that the import is stale.
				runCatching { mRepository.bulkVariants(vGame.id) }
					.getOrNull()
					?.takeIf { it.isNotEmpty() }
					?.let { dispatch(SetListContract.Intent.BulkAvailable(it)) }
					?: dispatch(SetListContract.Intent.BulkAvailable(stateFlow.value.bulkVariants))
				resolveSavedSets(vGame.id, mPreferences.preferences.value.primaryLanguage)
			}

			is SetListContract.Intent.BrowsingLanguageSelected -> {
				// The app-wide preference, promoted rather than replaced, which is exactly what
				// the card grid's language control does after a successful switch. One preference
				// for the whole app was already the design; this makes it reachable from the one
				// screen that shows every set it applies to.
				mPreferences.update { vPreferences ->
					vPreferences.copy(
						preferredLanguages = listOf(intent.language) +
							vPreferences.preferredLanguages.filter { it != intent.language },
					)
				}
				val vGame = stateFlow.value.game ?: return
				resolveBrowsingLanguage(vGame.id)
				// The list itself is per language -- names, card counts and which sets exist at
				// all -- so this is a reload, not a relabel.
				startLoad()
			}

			is SetListContract.Intent.SetOpened -> {
				mPreferences.update { it.copy(lastSetId = intent.set.id.qualified) }
				emitEffect(SetListContract.Effect.OpenSet(intent.set))
			}

			SetListContract.Intent.BackPressed ->
				emitEffect(SetListContract.Effect.NavigateBack)

			SetListContract.Intent.SettingsRequested ->
				emitEffect(SetListContract.Effect.OpenSettings)

			SetListContract.Intent.StorageRequested ->
				emitEffect(SetListContract.Effect.OpenStorage)

			SetListContract.Intent.DownloadsRequested ->
				emitEffect(SetListContract.Effect.OpenDownloads)

			// Silently ignored when the route named a game this build no longer serves, which is
			// the same state in which the rest of the screen shows an error rather than a list.
			SetListContract.Intent.SearchRequested ->
				stateFlow.value.game?.let { emitEffect(SetListContract.Effect.OpenSearch(it)) }

			// Read back off the reduced state rather than recomputed here: `SetFavourites` has
			// already been applied by the reducer, and applying it twice is how the two would drift.
			is SetListContract.Intent.BulkImportRequested -> {
				// Onto the download queue, which is the one scope that outlives this screen.
				//
				// It used to run here, in `viewModelScope`, and Navigation 3 scopes a view model
				// to its back-stack entry -- so going back to the game picker cancelled the
				// import and `ScryfallBulk` deleted the 74 MB it had already fetched. The queue's
				// scope is the application's. It is also simply where a user looks for a download.
				mDownloads.enqueue(
					DownloadRequest(
						setId = null,
						game = mArgs.game,
						setName = stateFlow.value.game?.displayName ?: mArgs.game.value,
						kinds = setOf(DownloadKind.CARD_INFO),
						isWholeGameImport = true,
						bulkVariantId = intent.variantId,
					),
				)
			}

			is SetListContract.Intent.FavouriteToggled,
			is SetListContract.Intent.FavouriteMovedTo,
			-> {
				val vFavourites = stateFlow.value.favouriteIds
				mPreferences.update { it.copy(favouriteSets = vFavourites) }
			}

			is SetListContract.Intent.GameChanged -> {
				mPreferences.update { it.copy(lastGame = intent.game.id.value) }
				// The reducer has already bumped the generation and cleared the list; this starts
				// the load for the game it moved to.
				startLoad()
			}

			else -> Unit
		}
	}

	/** Starts a load for the generation and game the reducer has just moved to. */
	private fun startLoad() {
		// Read after the reducer ran, so these are the generation and game this load owns.
		val vState = stateFlow.value
		val vGeneration = vState.requestGeneration
		// Nothing to load until the registry has answered. A route naming an unrouted game leaves
		// this null, and an empty set list is the honest outcome.
		val vGame = vState.game ?: return
		val vLanguage = mPreferences.preferences.value.primaryLanguage

		mLoadJob?.cancel()
		mLoadJob = viewModelScope.launch {
			// collectLatest rather than collect: the repository emits cache then network, and if a
			// newer refresh supersedes this one mid-flight the collector unwinds instead of
			// finishing work nobody is waiting for.
			mRepository.setList(vGame.id, vLanguage).collectLatest { vSnapshot ->
				dispatch(
					SetListContract.Intent.Loaded(
						generation = vGeneration,
						sets = vSnapshot.value.orEmpty(),
						origin = vSnapshot.origin,
						isStale = vSnapshot.isStale,
						error = vSnapshot.error,
						// Whether *this emission* settles the screen. Whether the load is over is a
						// separate question, answered by LoadFinished below.
						isFinal = vSnapshot.origin != DataOrigin.CACHE || vSnapshot.error != null,
					),
				)
			}
			dispatch(SetListContract.Intent.LoadFinished(vGeneration))

			// After the list settles, because it is a lookup *over* the list.
			if (stateFlow.value.requestGeneration == vGeneration) resolveSavedSets(vGame.id, vLanguage)
		}
	}

	/**
	 * Resolves what the routed source will *actually* answer in, and what it can be asked for.
	 *
	 * Not simply the user's preference: Riftcodex serves English whoever is reading, so a bar that
	 * showed "FR" over a list of English sets would be the same lie the download queue used to
	 * tell. `effectiveLanguage` is the function the queue and the repository already use.
	 */
	private suspend fun resolveBrowsingLanguage(game: GameId) {
		val vPreferred = mPreferences.preferences.value.primaryLanguage
		dispatch(
			SetListContract.Intent.BrowsingLanguageResolved(
				language = mRegistry.effectiveLanguage(game, vPreferred) ?: vPreferred,
				options = mRegistry.resolve(game)?.capabilities?.data?.languages.orEmpty(),
			),
		)
	}

	/**
	 * Works out which sets are on disk, and re-dispatches it.
	 *
	 * Called after a load *and* whenever a download finishes. Without the second trigger the marks
	 * only appeared after leaving the screen and coming back, because this is a snapshot taken once
	 * rather than a flow that watches the cache.
	 *
	 * Cheap enough to repeat: one file-existence check per set for the records, and a map lookup
	 * for the images.
	 */
	private suspend fun resolveSavedSets(game: GameId, language: CardLanguage) {
		val vSets = stateFlow.value.sets
		if (vSets.isEmpty()) return
		val vPreferences = mPreferences.preferences.value
		// The language the *source* will answer in, which is what a download is recorded under.
		//
		// An image-download record is keyed by (set, language, kind) and written by the queue with
		// the job's own language. Once the queue started resolving that against the provider --
		// Riftcodex serves English only -- a French-preferring user wrote `en` records and read
		// `fr` ones, so a downloaded set went on offering its thumbnails for download forever.
		//
		// The repository resolves this for itself in `savedSetIds` and the rest; the image side
		// reads preferences directly and has to do it here. Same function, same answer.
		val vEffective = mRegistry.effectiveLanguage(game, language) ?: language
		dispatch(
			SetListContract.Intent.SavedSetsResolved(
				setIds = mRepository.savedSetIds(game, vSets, language),
				// Per language as well as per set, because the dialog's question is "do I have the
				// edition I am about to fetch?" and `setIds` only answers "is any of it here?".
				savedLanguages = mRepository.savedLanguages(game, vSets, language),
				// What each set really holds in the language it opens in, where a fetch has
				// established it. The source's own figure counts the English printing and is the
				// wrong number to print beside a row that will open in French.
				confirmedCardCounts = mRepository.confirmedCardCounts(game, vSets, language),
				// What is already known about each set's languages, with no requests: the record
				// left by opening it, or the claim the catalogue came with. See
				// `CardRepository.availableLanguages`.
				availableLanguages = mRepository.availableLanguages(game, vSets, language),
				// The recorded import against what the source is currently publishing. Equal
				// means there is nothing to fetch; different -- or absent -- means there is.
				// The recorded import against what the source is currently publishing, matched on
				// *both* the file and its edition. Taking the English dump is no reason to stop
				// offering the every-language one, and last week's edition of either is worth
				// taking again -- Scryfall rebuilds daily.
				importedVariantIds = vPreferences.bulkImports[game.value]
					?.let { vRecord ->
						stateFlow.value.bulkVariants
							.filter {
								it.id == vRecord.variantId &&
									(it.updatedAt?.toString() ?: "-") == vRecord.updatedAt
							}
							.mapTo(mutableSetOf()) { it.id }
					}
					.orEmpty(),
				// Read straight from preferences rather than measured: see
				// `BrowsingPreferences.imageDownloads` for why the image side cannot be checked
				// cheaply, and what the record therefore does and does not mean.
				imageDownloads = vSets.mapNotNull { vSet ->
					val vStatus = SetImageStatus(
						thumbnails = vPreferences.imageDownloadFor(
							vSet.id.qualified,
							vEffective,
							DownloadKind.GRID_THUMBNAILS.name,
						),
					)
					if (vStatus.isEmpty) null else vSet.id.qualified to vStatus
				}.toMap(),
			),
		)
	}
}
