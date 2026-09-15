package com.bitsycore.tcgexplorer.ui.screen.sets

import androidx.lifecycle.viewModelScope
import com.bitsycore.tcgexplorer.core.model.CardLanguage
import com.bitsycore.tcgexplorer.core.model.CardSet
import com.bitsycore.tcgexplorer.data.download.DownloadKind
import com.bitsycore.tcgexplorer.data.download.DownloadRequest
import com.bitsycore.tcgexplorer.core.model.GameId
import com.bitsycore.tcgexplorer.core.provider.ProviderRegistry
import com.bitsycore.tcgexplorer.data.repository.CardRepository
import com.bitsycore.tcgexplorer.data.repository.DataOrigin
import com.bitsycore.tcgexplorer.data.repository.SetFactsWarmer
import com.bitsycore.tcgexplorer.data.settings.PreferencesStore
import com.bitsycore.lib.pulse.viewmodel.PulseViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import com.bitsycore.tcgexplorer.data.download.DownloadManager
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
	private val mWarmer: SetFactsWarmer,
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
		// The queue as state, so `SetListContent` is a function of its state and nothing else.
		viewModelScope.launch {
			mDownloads.jobs.collect { dispatch(SetListContract.Intent.DownloadsChanged(it)) }
		}

		// What the routed source can do. Read once here rather than out of the registry in the
		// composition, which is what kept the download dialog un-previewable in any other state.
		mRegistry.resolve(mArgs.game)?.capabilities?.data?.let { vData ->
			dispatch(
				SetListContract.Intent.CapabilitiesRead(
					isCardDataBundled = vData.bundledCardData,
					isCardInfoBulkOnly = vData.cardInfoFromBulkOnly,
					hasThumbnails = vData.thumbnailImages,
				),
			)
		}

		viewModelScope.launch {
			mDownloads.jobs
				.map { vJobs -> vJobs.count { !it.isActive } }
				.distinctUntilChanged()
				.drop(1)
				.collect {
					val vState = stateFlow.value
					val vGame = vState.game ?: return@collect
					// What was warmed describes a device that has since changed.
					mWarmer.invalidate(vGame.id)
					// A finished whole-game import has written every set in the catalogue, so the
					// list itself is stale and not only its marks. Re-reading the marks alone
					// would leave every row's count saying what it said before the import ran.
					if (mDownloads.jobs.value.any { it.request.isWholeGameImport && !it.isActive }) {
						dispatch(SetListContract.Intent.Refresh)
					}
					resolveSavedSets(
						vGame.id,
						mPreferences.preferences.value.primaryLanguage,
						stateFlow.value.sets,
					)
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
				resolveSavedSets(
					vGame.id,
					mPreferences.preferences.value.primaryLanguage,
					stateFlow.value.sets,
				)
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

			is SetListContract.Intent.DownloadRequested -> enqueueDownload(intent)

			is SetListContract.Intent.DownloadCancelled -> mDownloads.cancel(intent.jobId)

			SetListContract.Intent.AllDownloadsCancelled -> mDownloads.cancelAll()

			SetListContract.Intent.FinishedDownloadsCleared -> mDownloads.clearFinished()

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
	/**
	 * Turns "download this set" into queue jobs.
	 *
	 * One job per language, because everything downstream is per language: a cache key embeds it
	 * and so does an image-download record. Splitting here is what makes "card info in every
	 * language, thumbnails in the two you read" a thing the queue can express.
	 *
	 * The two halves differ on purpose. Card records are small and the point of holding them is
	 * being able to switch language on a card you already have, so they go in every language asked
	 * for. Thumbnails are a request per card per language, so they go only where they were asked
	 * for -- and only where the set states that language.
	 *
	 * The language is the source's answer for the user's preference, never the preference itself.
	 * Riftcodex serves English whatever you prefer, so a French-preferring reader downloading
	 * Riftbound used to queue a job labelled "French" for records that come back English: the
	 * repository normalises before it builds a key, so the file was right and only the screen lied.
	 */
	private fun enqueueDownload(intent: SetListContract.Intent.DownloadRequested) {
		val vSet = intent.set
		val vPrimary = mRegistry.effectiveLanguage(
			mArgs.game,
			mPreferences.preferences.value.primaryLanguage,
		)
		// Empty only when a caller asks for nothing, and then the preference stands in.
		val vForInfo = intent.infoLanguages.ifEmpty { setOfNotNull(vPrimary) }
		val vForArt = intent.artLanguages.ifEmpty { setOfNotNull(vPrimary) }
			.filter { it in vSet.languages || vSet.languages.isEmpty() }

		val vInfoKinds = intent.kinds.filterNot { it.isImagery }.toSet()
		val vArtKinds = intent.kinds.filter { it.isImagery }.toSet()

		if (vInfoKinds.isNotEmpty()) {
			for (vLanguage in vForInfo) {
				mDownloads.enqueue(downloadOf(vSet, vInfoKinds, vLanguage))
			}
		}
		if (vArtKinds.isNotEmpty()) {
			for (vLanguage in vForArt) {
				mDownloads.enqueue(downloadOf(vSet, vArtKinds, vLanguage))
			}
		}
	}

	/**
	 * One job.
	 *
	 * The language is passed rather than left null, always. A cache key embeds it, so a download
	 * written under `null` and a grid reading under `fr` are different records: the set comes down
	 * and is then not found by the search that was the reason for downloading it. The repository
	 * normalises once against what the provider can really answer, so passing a language is right
	 * even for a source that cannot serve it. See the note in `CardGridViewModel.startLoad`.
	 */
	private fun downloadOf(set: CardSet, kinds: Set<DownloadKind>, language: CardLanguage?) =
		DownloadRequest(
			setId = set.id,
			game = set.game,
			setName = set.name,
			kinds = kinds,
			language = language,
		)

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
			// What has already been looked up, so the network emission does not repeat a lookup
			// the cached one just did over the same sets.
			var vResolved: Set<String>? = null
			mRepository.setList(vGame.id, vLanguage).collectLatest { vSnapshot ->
				val vSets = vSnapshot.value.orEmpty()
				dispatch(
					SetListContract.Intent.Loaded(
						generation = vGeneration,
						sets = vSets,
						origin = vSnapshot.origin,
						isStale = vSnapshot.isStale,
						error = vSnapshot.error,
						// Whether *this emission* settles the screen. Whether the load is over is a
						// separate question, answered by LoadFinished below.
						isFinal = vSnapshot.origin != DataOrigin.CACHE || vSnapshot.error != null,
					),
				)
				// On *this* emission rather than after the whole flow. The marks need no network --
				// they are a lookup over the list that is already on screen -- and waiting for the
				// fetch meant a game whose catalogue was cached still spent its first seconds
				// unable to say what it held.
				val vIds = vSets.mapTo(mutableSetOf()) { it.id.qualified }
				if (vIds.isNotEmpty() && vIds != vResolved) {
					resolveSavedSets(vGame.id, vLanguage, vSets)
					vResolved = vIds
				}
			}
			dispatch(SetListContract.Intent.LoadFinished(vGeneration))

			// Only if nothing was emitted to look at: an error, or an empty catalogue.
			if (stateFlow.value.requestGeneration == vGeneration && vResolved == null) {
				resolveSavedSets(vGame.id, vLanguage, stateFlow.value.sets)
			}
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
	 * for the images. Cheaper still when the startup sweep has already read them -- see
	 * `SetFactsWarmer`, which is why this screen can arrive already knowing what it holds.
	 */
	private suspend fun resolveSavedSets(
		game: GameId,
		language: CardLanguage,
		sets: List<CardSet>,
	) {
		val vSets = sets
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
		// reads preferences directly and has to do it here. Same function, same answer -- and the
		// function is the repository's, not the registry's.
		//
		// `ProviderRegistry.effectiveLanguage` ends in `?: language`, which hands back the reader's
		// raw preference for a source that states none. OPTCG and TCGCSV do, so One Piece's
		// pictures were filed under whichever language the reader happened to browse in, and
		// changing that in Settings lost the record and re-offered the download.
		val vEffective = mRepository.storageLanguageFor(game, language)
		// Read ahead by the startup sweep where it got there first, and looked up now where it did
		// not. Same call either way -- `localSetFacts` is the one place the question is asked.
		val vFacts = mWarmer.peek(game, language, vSets)
			?: mRepository.localSetFacts(game, vSets, language)
		dispatch(
			SetListContract.Intent.SavedSetsResolved(
				setIds = vFacts.savedSetIds,
				// Per language as well as per set, because the dialog's question is "do I have the
				// edition I am about to fetch?" and `setIds` only answers "is any of it here?".
				savedLanguages = vFacts.savedLanguages,
				// What each set really holds in the language it opens in, where a fetch has
				// established it. The source's own figure counts the English printing and is the
				// wrong number to print beside a row that will open in French.
				confirmedCardCounts = vFacts.confirmedCardCounts,
				// Which sets have nothing left to fetch, so the row can stop offering a download.
				// "Complete", not "saved": a set fetched part-way is on disk and is not finished.
				completeSetIds = vFacts.completeSetIds,
				// What is already known about each set's languages, with no requests: the record
				// left by opening it, or the claim the catalogue came with. See
				// `CardRepository.availableLanguages`.
				availableLanguages = vFacts.availableLanguages,
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
