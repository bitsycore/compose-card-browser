package com.bitsycore.cardbrowser.di

import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.provider.CardProvider
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.core.provider.ProviderRoute
import com.bitsycore.cardbrowser.data.cache.CacheManager
import com.bitsycore.cardbrowser.data.cache.MetadataCache
import com.bitsycore.cardbrowser.data.net.HttpClientFactory
import com.bitsycore.cardbrowser.data.repository.CardRepository
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.cardbrowser.providers.altered.AlteredProvider
import com.bitsycore.cardbrowser.providers.optcg.OptcgProvider
import com.bitsycore.cardbrowser.providers.riftcodex.RiftcodexProvider
import com.bitsycore.cardbrowser.providers.scryfall.ScryfallProvider
import com.bitsycore.cardbrowser.providers.tcgdex.TcgdexProvider
import com.bitsycore.cardbrowser.providers.wuwa.WuwaProvider
import com.bitsycore.cardbrowser.providers.ygoprodeck.YgoprodeckProvider
import com.bitsycore.cardbrowser.ui.browse.BrowseSession
import com.bitsycore.cardbrowser.ui.cards.CardGridViewModel
import com.bitsycore.cardbrowser.ui.detail.CardDetailArgs
import com.bitsycore.cardbrowser.ui.detail.CardDetailViewModel
import com.bitsycore.cardbrowser.ui.search.SearchArgs
import com.bitsycore.cardbrowser.ui.search.SearchViewModel
import com.bitsycore.cardbrowser.ui.games.GameListViewModel
import com.bitsycore.cardbrowser.ui.sets.SetListArgs
import com.bitsycore.cardbrowser.ui.sets.SetListViewModel
import com.bitsycore.cardbrowser.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.dsl.viewModel
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
	single { HttpClientFactory.create() }

	single { PreferencesStore(get(), get(), Dispatchers.Default) }

	single {
		val vPreferences: PreferencesStore = get()
		MetadataCache(
			mStorage = get(),
			mJson = get(),
			mIoDispatcher = Dispatchers.Default,
			// Read through a function rather than captured, so changing the limit in settings takes
			// effect on the next write instead of the next launch.
			mMaxBytes = { vPreferences.preferences.value.metadataCacheLimitBytes },
			mClock = { nowEpochMillis() },
		)
	}

	single {
		val vPreferences: PreferencesStore = get()
		CacheManager(
			mStorage = get(),
			mMetadataCache = get(),
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
	// Not `single<CardProvider> { ... }` seven times. That gives seven definitions with the same
	// primary type and no qualifier, so they overwrite one another and `getAll<CardProvider>()`
	// finds only the last -- which the registry then rejects at startup with "routing table names
	// providers that are not registered". Distinct primary types plus `bind` is what makes
	// `getAll` see all seven.
	single { RiftcodexProvider(mClient = get()) } bind CardProvider::class
	single { TcgdexProvider(mClient = get()) } bind CardProvider::class
	single { ScryfallProvider(mClient = get()) } bind CardProvider::class
	single { OptcgProvider(mClient = get()) } bind CardProvider::class
	single { AlteredProvider(mClient = get()) } bind CardProvider::class
	single { YgoprodeckProvider(mClient = get()) } bind CardProvider::class
	single { WuwaProvider() } bind CardProvider::class

	single {
		ProviderRegistry(
			providers = getAll<CardProvider>(),
			routes = providerRoutes,
		)
	}

	single {
		val vPreferences: PreferencesStore = get()
		CardRepository(
			mRegistry = get(),
			mCache = get(),
			mClock = { nowEpochMillis() },
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

	viewModel { (vArgs: SetListArgs) -> SetListViewModel(get(), get(), get(), vArgs) }
	viewModel { GameListViewModel(get(), get()) }
	viewModel { CardGridViewModel(get(), get(), get(), get()) }
	viewModel { (vArgs: CardDetailArgs) ->
		CardDetailViewModel(get(), get(), get(), get(), get(), vArgs)
	}
	viewModel { SettingsViewModel(get(), get(), get()) }
	viewModel { (vArgs: SearchArgs) -> SearchViewModel(get(), get(), get(), vArgs) }
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
 * ProviderRoute(Game.RIFTBOUND, SomeProvider.PROVIDER_ID, language = CardLanguage.KOREAN)
 * ```
 *
 * Games with no entry are not offered by the app at all; there are no dead menu items.
 */
val providerRoutes: List<ProviderRoute> = listOf(
	ProviderRoute(game = Game.RIFTBOUND, provider = RiftcodexProvider.PROVIDER_ID),
	ProviderRoute(game = Game.POKEMON, provider = TcgdexProvider.PROVIDER_ID),
	ProviderRoute(game = Game.MAGIC, provider = ScryfallProvider.PROVIDER_ID),
	ProviderRoute(game = Game.ONE_PIECE, provider = OptcgProvider.PROVIDER_ID),
	ProviderRoute(game = Game.ALTERED, provider = AlteredProvider.PROVIDER_ID),
	ProviderRoute(game = Game.YU_GI_OH, provider = YgoprodeckProvider.PROVIDER_ID),
	ProviderRoute(game = Game.WUTHERING_WAVES, provider = WuwaProvider.PROVIDER_ID),
	// Cyberpunk TCG has no entry, and that is the mechanism working rather than an omission: the
	// game does not reach retail until November 2026 and no data source for it exists. Because
	// `ProviderRegistry.games` is derived from this table, it simply does not appear in the game
	// switcher. See docs/PROVIDER_RESEARCH.md.
)

/**
 * Wall-clock milliseconds.
 *
 * A function rather than a direct call at each site so tests can bind a fixed clock and assert on
 * staleness without sleeping.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
fun nowEpochMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
