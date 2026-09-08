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
import com.bitsycore.cardbrowser.providers.riftcodex.RiftcodexProvider
import com.bitsycore.cardbrowser.ui.browse.BrowseSession
import com.bitsycore.cardbrowser.ui.cards.CardGridViewModel
import com.bitsycore.cardbrowser.ui.detail.CardDetailArgs
import com.bitsycore.cardbrowser.ui.detail.CardDetailViewModel
import com.bitsycore.cardbrowser.ui.sets.SetListViewModel
import com.bitsycore.cardbrowser.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.dsl.viewModel
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

	single<CardProvider> { RiftcodexProvider(mClient = get()) }

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

	viewModel { SetListViewModel(get(), get()) }
	viewModel { CardGridViewModel(get(), get(), get(), get()) }
	viewModel { (vArgs: CardDetailArgs) -> CardDetailViewModel(get(), get(), get(), get(), vArgs) }
	viewModel { SettingsViewModel(get(), get(), get()) }
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
)

/**
 * Wall-clock milliseconds.
 *
 * A function rather than a direct call at each site so tests can bind a fixed clock and assert on
 * staleness without sleeping.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
fun nowEpochMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
