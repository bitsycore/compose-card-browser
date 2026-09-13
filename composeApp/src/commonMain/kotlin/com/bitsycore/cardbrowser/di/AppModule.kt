package com.bitsycore.cardbrowser.di

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.provider.CardProvider
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.core.provider.ProviderRoute
import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.data.cache.CacheManager
import com.bitsycore.cardbrowser.data.cache.CacheReconciler
import com.bitsycore.cardbrowser.data.cache.MetadataCache
import com.bitsycore.cardbrowser.data.cache.SetRecordStore
import com.bitsycore.cardbrowser.data.cache.SqlSetRecordStore
import com.bitsycore.cardbrowser.sqlstore.CardStoreFactory
import com.bitsycore.cardbrowser.sqlstore.OpenedStore
import com.bitsycore.cardbrowser.data.net.ApiCallStats
import com.bitsycore.cardbrowser.data.net.HttpClientFactory
import com.bitsycore.cardbrowser.data.net.OkioHttpCacheStorage
import com.bitsycore.cardbrowser.data.repository.CardRepository
import com.bitsycore.cardbrowser.data.repository.SetCatalogueWarmer
import com.bitsycore.cardbrowser.data.repository.SetFactsWarmer
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import com.bitsycore.cardbrowser.games.riftbound.RiftboundArt
import com.bitsycore.cardbrowser.games.pokemon.PokemonGame
import com.bitsycore.cardbrowser.games.pokemon.PokemonArt
import com.bitsycore.cardbrowser.games.magic.MagicGame
import com.bitsycore.cardbrowser.games.magic.MagicArt
import com.bitsycore.cardbrowser.games.onepiece.OnePieceGame
import com.bitsycore.cardbrowser.games.onepiece.OnePieceArt
import com.bitsycore.cardbrowser.games.altered.AlteredGame
import com.bitsycore.cardbrowser.games.altered.AlteredArt
import com.bitsycore.cardbrowser.games.yugioh.YuGiOhGame
import com.bitsycore.cardbrowser.games.yugioh.YuGiOhArt
import com.bitsycore.cardbrowser.games.cyberpunk.CyberpunkArt
import com.bitsycore.cardbrowser.games.cyberpunk.CyberpunkGame
import com.bitsycore.cardbrowser.games.lorcana.LorcanaArt
import com.bitsycore.cardbrowser.games.lorcana.LorcanaGame
import com.bitsycore.cardbrowser.games.wowtcg.WowTcgArt
import com.bitsycore.cardbrowser.games.wowtcg.WowTcgGame
import com.bitsycore.cardbrowser.games.wutheringwaves.WutheringWavesGame
import com.bitsycore.cardbrowser.games.wutheringwaves.WutheringWavesArt
import com.bitsycore.cardbrowser.ui.games.GameArtRegistry
import com.bitsycore.cardbrowser.providers.altered.AlteredProvider
import com.bitsycore.cardbrowser.providers.optcg.OptcgProvider
import com.bitsycore.cardbrowser.providers.riftcodex.RiftcodexProvider
import com.bitsycore.cardbrowser.providers.scryfall.ScryfallProvider
import com.bitsycore.cardbrowser.providers.tcgdex.TcgdexProvider
import com.bitsycore.cardbrowser.providers.tcgcsv.CyberpunkTcgCsvProvider
import com.bitsycore.cardbrowser.providers.tcgcsv.LorcanaTcgCsvProvider
import com.bitsycore.cardbrowser.providers.tcgcsv.WowTcgCsvProvider
import com.bitsycore.cardbrowser.providers.wuwa.WuwaProvider
import com.bitsycore.cardbrowser.providers.ygoprodeck.YgoprodeckProvider
import com.bitsycore.cardbrowser.ui.browse.BrowseSession
import com.bitsycore.cardbrowser.ui.cards.CardGridViewModel
import com.bitsycore.cardbrowser.ui.detail.CardDetailArgs
import com.bitsycore.cardbrowser.ui.detail.CardDetailViewModel
import com.bitsycore.cardbrowser.ui.games.GameListViewModel
import com.bitsycore.cardbrowser.ui.setup.SetupViewModel
import com.bitsycore.cardbrowser.ui.sets.SetListArgs
import com.bitsycore.cardbrowser.ui.sets.SetListViewModel
import com.bitsycore.cardbrowser.ui.settings.SettingsViewModel
import com.bitsycore.cardbrowser.ui.storage.StorageViewModel
import com.bitsycore.cardbrowser.data.download.DownloadManager
import com.bitsycore.cardbrowser.ui.common.CoilImagePrefetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * The object graph, minus the three things only a platform can supply.
 *
 * See `platformModule()` for the file system, the storage roots and the link opener.
 */
val appModule = module {

	// ============
	//  Infrastructure

	single { HttpClientFactory.json }

	// One counter behind every client, so the stats screen reports what actually left the device.
	single { ApiCallStats() }

	// Response bodies, so a revalidation can come back as a 304 rather than a full download.
	single { OkioHttpCacheStorage(get()) }

	// Two clients, and the split matters.
	//
	// The default one is the image loader's: no response cache, because Coil keeps its own 1 GB
	// disk cache of artwork and storing every card twice would blow the HTTP budget in one grid.
	single { HttpClientFactory.create(stats = get()) }

	// The providers': same policy, plus the response cache. Reopening a set the next day
	// re-requests its pages, and against an origin that sends an ETag -- all of ours do -- those
	// come back empty instead of carrying the whole set again.
	single(named(PROVIDER_CLIENT)) {
		HttpClientFactory.create(stats = get(), httpCache = get<OkioHttpCacheStorage>())
	}

	single { PreferencesStore(get(), get(), Dispatchers.Default) }

	single {
		MetadataCache(
			mStorage = get(),
			mJson = get(),
			mIoDispatcher = Dispatchers.Default,
			// Not the user's limit. This cache holds only small, always-evictable records now --
			// set lists, card detail, search pages, language probes -- and the ceiling the settings
			// screen shows governs the card store, which is where the bytes are. Two ceilings
			// dividing one number between them is a limit that means neither thing.
			mMaxBytes = { MetadataCache.DEFAULT_MAX_BYTES },
			mClock = { nowEpochMillis() },
		)
	}

	// The card store, in three bindings because the middle one can fail and has to say so.
	//
	// `open` verifies the database and recreates it if it is damaged, so this is where a corrupt
	// store turns into a fact -- `wasRecovered` -- rather than into a crash on the first query.
	// `CacheReconciler` is what acts on that fact; see `App()`.
	single { CardStoreFactory(get()) }
	single {
		val vStorage: AppStorage = get()
		get<CardStoreFactory>().open(vStorage.databaseFile.toString())
	}
	single<SetRecordStore> {
		val vOpened: OpenedStore = get()
		SqlSetRecordStore(vOpened.store, Dispatchers.Default, wasRecovered = vOpened.wasRecovered)
	}
	single { CacheReconciler(mMetadataCache = get(), mPreferences = get()) }

	single {
		val vPreferences: PreferencesStore = get()
		CacheManager(
			mStorage = get(),
			mMetadataCache = get(),
			mSetStore = get(),
			mIoDispatcher = Dispatchers.Default,
			mMetadataLimitBytes = { vPreferences.preferences.value.metadataCacheLimitBytes },
			mImageCacheMaxBytes = { vPreferences.preferences.value.imageCacheLimitBytes },
		)
	}

	// ============
	//  Providers
	//
	// This block, and the routing table below it, are the whole of what registering a provider
	// costs. A second adapter is one `single` here, one entry in `providerRoutes`, and nothing
	// else -- the browse and detail screens never name a provider.

	// Each is declared under its *own* type and then bound to `CardProvider`.
	//
	// Not `single<CardProvider> { ... }` ten times. That gives ten definitions with the same
	// primary type and no qualifier, so they overwrite one another and `getAll<CardProvider>()`
	// finds only the last -- which the registry then rejects at startup with "routing table names
	// providers that are not registered". Distinct primary types plus `bind` is what makes
	// `getAll` see all ten.
	single { RiftcodexProvider(mClient = get(named(PROVIDER_CLIENT))) } bind CardProvider::class
	single { TcgdexProvider(mClient = get(named(PROVIDER_CLIENT))) } bind CardProvider::class
	// Their own clients, not the shared one, because these two carry a request throttle and the
	// shared client is also the image loader's -- throttling that would queue every thumbnail in a
	// grid behind the gap. See `ProviderHttpPolicy.minRequestInterval`.
	single {
		ScryfallProvider(
			mClient = HttpClientFactory.create(
				policy = ScryfallProvider.HTTP_POLICY,
				stats = get(),
				httpCache = get<OkioHttpCacheStorage>(),
			),
			// Scryfall is the only source here that publishes a bulk dump, and the import needs
			// somewhere to put 75 MB while it reads it. See `ScryfallBulk`.
			mStorage = get(),
		)
	} bind CardProvider::class
	single { OptcgProvider(mClient = get(named(PROVIDER_CLIENT))) } bind CardProvider::class
	single { AlteredProvider(mClient = get(named(PROVIDER_CLIENT))) } bind CardProvider::class
	single {
		YgoprodeckProvider(
			mClient = HttpClientFactory.create(
				policy = YgoprodeckProvider.HTTP_POLICY,
				stats = get(),
				httpCache = get<OkioHttpCacheStorage>(),
			),
		)
	} bind CardProvider::class
	single { WuwaProvider() } bind CardProvider::class
	// Three games from one adapter. Each is a distinct class with its own primary type, which is
	// what lets all three sit in the graph beside each other under the same `bind`.
	single { LorcanaTcgCsvProvider(mClient = get(named(PROVIDER_CLIENT))) } bind CardProvider::class
	single { CyberpunkTcgCsvProvider(mClient = get(named(PROVIDER_CLIENT))) } bind CardProvider::class
	single { WowTcgCsvProvider(mClient = get(named(PROVIDER_CLIENT))) } bind CardProvider::class

	// Every game's mark, gathered from the game modules themselves.
	//
	// The only place the app enumerates games, and it enumerates *art* rather than games: which
	// games exist is still whatever the routing table below routes. A game module that ships a
	// profile but no entry here simply draws no logo; it does not disappear.
	single {
		GameArtRegistry(
			listOf(
				RiftboundArt,
				PokemonArt,
				MagicArt,
				OnePieceArt,
				AlteredArt,
				YuGiOhArt,
				WutheringWavesArt,
				CyberpunkArt,
				LorcanaArt,
				WowTcgArt,
			),
		)
	}

	single {
		ProviderRegistry(
			providers = getAll<CardProvider<GameProfile>>(),
			routes = providerRoutes,
		)
	}

	single {
		val vPreferences: PreferencesStore = get()
		CardRepository(
			mRegistry = get(),
			mCache = get(),
			mSetStore = get(),
			mClock = { nowEpochMillis() },
			// Only the bulk import uses these; every other path is unaffected by their absence,
			// which is why they are optional on the constructor.
			mStorage = get(),
			mJson = get(),
			mSetListRevalidateAfterMillis = {
				// Switching the check off makes every cached list "recent enough" forever.
				if (vPreferences.preferences.value.revalidateSetsOnLaunch) {
					CardRepository.DEFAULT_SET_LIST_REVALIDATE_MILLIS
				} else {
					Long.MAX_VALUE
				}
			},
		)
	}

	// ============
	//  Presentation

	// What the grid is showing, so the detail screen can swipe through the same list. A single
	// rather than a view-model field because it outlives both screens' view models, which are
	// scoped to their own back-stack entries.
	single { BrowseSession() }

	// What each game holds on disk, read while the picker is on screen rather than when a set list
	// opens. No network: it is the store and the cache.
	single { SetFactsWarmer(get()) }

	// Warms every game's set catalogue at startup, so the picker leads to an already-populated
	// list rather than to a spinner and late-arriving set symbols.
	single {
		SetCatalogueWarmer(
			mRepository = get(),
			mRegistry = get(),
			mPreferences = get(),
			mFacts = get(),
			mScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
		)
	}

	// The download queue, and the bridge that lets it reach Coil's cache from `:data`.
	//
	// Its scope is the application's, not a screen's: a download must survive the set list being
	// closed, which is the whole point of queueing one.
	single { CoilImagePrefetcher() }
	single {
		DownloadManager(
			mRepository = get(),
			mImagePrefetcher = get<CoilImagePrefetcher>(),
			mPreferences = get(),
			mScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
		)
	}

	viewModel { SetupViewModel(get(), get()) }
	viewModel { (vArgs: SetListArgs) ->
		SetListViewModel(get(), get(), get(), get(), get(), vArgs)
	}
	viewModel { GameListViewModel(get(), get()) }
	viewModel { CardGridViewModel(get(), get(), get(), get()) }
	viewModel { (vArgs: CardDetailArgs) ->
		CardDetailViewModel(get(), get(), get(), get(), get(), vArgs)
	}
	viewModel { SettingsViewModel(get(), get(), get(), get()) }
	viewModel { StorageViewModel(get(), get(), get(), get()) }
}

/**
 * Which provider answers for which game, and optionally for which language.
 *
 * One authoritative route per game. Nothing here merges two providers or fails over between them.
 *
 * A later provider filling a language gap is added as a second entry with a `language` set -- for
 * example a Korean-capable Riftbound source would be:
 *
 * ```kotlin
 * ProviderRoute(RiftboundGame.id, SomeProvider.PROVIDER_ID, language = CardLanguage.KOREAN)
 * ```
 *
 * Games with no entry are not offered by the app at all; there are no dead menu items.
 */
val providerRoutes: List<ProviderRoute> = listOf(
	ProviderRoute(game = RiftboundGame.id, provider = RiftcodexProvider.PROVIDER_ID),
	ProviderRoute(game = PokemonGame.id, provider = TcgdexProvider.PROVIDER_ID),
	ProviderRoute(game = MagicGame.id, provider = ScryfallProvider.PROVIDER_ID),
	ProviderRoute(game = OnePieceGame.id, provider = OptcgProvider.PROVIDER_ID),
	ProviderRoute(game = AlteredGame.id, provider = AlteredProvider.PROVIDER_ID),
	ProviderRoute(game = YuGiOhGame.id, provider = YgoprodeckProvider.PROVIDER_ID),
	ProviderRoute(game = WutheringWavesGame.id, provider = WuwaProvider.PROVIDER_ID),
	ProviderRoute(game = LorcanaGame.id, provider = LorcanaTcgCsvProvider.PROVIDER_ID),
	ProviderRoute(game = CyberpunkGame.id, provider = CyberpunkTcgCsvProvider.PROVIDER_ID),
	ProviderRoute(game = WowTcgGame.id, provider = WowTcgCsvProvider.PROVIDER_ID),
	// Duel Masters has no entry, and that is the mechanism working rather than an omission: no
	// source was found that carries card images, and a browser with no pictures is not one. Because
	// `ProviderRegistry.games` is derived from this table, it simply does not appear in the picker
	// -- there is no half-built game to hide. See docs/PROVIDER_RESEARCH.md for what was measured.
)

/**
 * Wall-clock milliseconds.
 *
 * A function rather than a direct call at each site so tests can bind a fixed clock and assert on
 * staleness without sleeping.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
fun nowEpochMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

/**
 * Names the provider HTTP client, as distinct from the image loader's.
 *
 * They differ in one thing -- a response cache -- and that one thing is why they cannot be the
 * same client. See the two `single`s above.
 */
const val PROVIDER_CLIENT: String = "provider-http-client"
