package com.bitsycore.cardbrowser.ui.settings

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.data.cache.CacheManager
import com.bitsycore.cardbrowser.data.cache.CacheUsage
import com.bitsycore.cardbrowser.data.settings.BrowsingPreferences
import com.bitsycore.cardbrowser.data.settings.ThemeMode
import com.bitsycore.lib.pulse.container.ContainerContract

/** Settings state: cache readings and the card-language preference order. */
object SettingsContract :
	ContainerContract<SettingsContract.UiState, SettingsContract.Intent, SettingsContract.Effect>() {

	data class UiState(
		// Limits only. What is *used* belongs to the storage screen, and was being reported in two
		// places with two layouts -- this one kept the numbers and could not act on them.
		val metadataLimitBytes: Long = 0,
		val imageLimitBytes: Long = CacheManager.DEFAULT_IMAGE_CACHE_MAX_BYTES,
		val preferredLanguages: List<CardLanguage> = CardLanguage.PREFERENCE_ORDER,
		val themeMode: ThemeMode = ThemeMode.SYSTEM,
		/**
		 * Every source the app is routed to, and its own notice.
		 *
		 * All of them, not the first. This screen used to show whichever provider happened to be
		 * routed first -- Riftcodex -- under the heading "Data source", so a user browsing Pokemon
		 * was told their card data came from an unofficial Riot Games fan project. Seven sources
		 * are credited on the cards they supply; the same is true here.
		 */
		val attributions: List<ProviderCredit> = emptyList(),
		val prefetchRadius: Int = BrowsingPreferences.DEFAULT_PREFETCH_RADIUS,
		val revalidateSetsOnLaunch: Boolean = true,
		/** Whether the set list leaves out sets their source states hold no cards. */
		val hideEmptySets: Boolean = true,
		/**
		 * Requests sent per host since launch, highest first.
		 *
		 * Here to make the caching claims checkable rather than merely stated: browse a set twice
		 * and the number should not move the second time.
		 */
		val apiCalls: List<Pair<String, Int>> = emptyList(),
	) {

		/** Every request this session, across every host. */
		val apiCallTotal: Int get() = apiCalls.sumOf { it.second }
	}

	/**
	 * One source, as credited on this screen.
	 *
	 * @property source the provider's own display name, so the notices are distinguishable
	 * @property text the provider's own attribution, verbatim. Never composed here: what a source
	 *   requires to be said about it is the source's to state
	 */
	data class ProviderCredit(val source: String, val text: String)

	sealed interface Intent {

		data object Refresh : Intent

		/** The request counters changed, or were read for the first time. */
		data class ApiCallsRead(val counts: Map<String, Int>) : Intent

		/** Zero the counters, so one interaction can be measured on its own. */
		data object ResetApiCalls : Intent


		data class PreferencesRead(val preferences: BrowsingPreferences) : Intent

		/** A new ceiling for downloaded card art. Takes effect on the next launch. */
		data class ImageCacheLimitChosen(val bytes: Long) : Intent

		/** A new ceiling for cached card records. Takes effect on the next write. */
		data class MetadataCacheLimitChosen(val bytes: Long) : Intent

		/** How many cards either side of the open one to fetch ahead. Zero switches it off. */
		data class PrefetchRadiusChosen(val radius: Int) : Intent

		data class RevalidateOnLaunchChanged(val isEnabled: Boolean) : Intent

		/** Whether the set list hides sets stated to hold no cards. */
		data class HideEmptySetsChanged(val hide: Boolean) : Intent

		/** Light, dark, or whatever the platform says. Applies immediately, not on next launch. */
		data class ThemeModeChosen(val mode: ThemeMode) : Intent

		data class AttributionRead(val credits: List<ProviderCredit>) : Intent

		/** Moves a language to the front of the preference order. */
		data class PromoteLanguage(val language: CardLanguage) : Intent

	}

	sealed interface Effect

	override fun reduce(state: UiState, intent: Intent): UiState = when (intent) {

		Intent.Refresh -> state

		is Intent.ApiCallsRead -> state.copy(
			apiCalls = intent.counts.entries
				.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
				.map { it.key to it.value },
		)

		// The view model does the zeroing; the reducer only has to stop showing the old numbers.
		Intent.ResetApiCalls -> state.copy(apiCalls = emptyList())

		is Intent.PreferencesRead -> state.copy(
			preferredLanguages = intent.preferences.preferredLanguages,
			metadataLimitBytes = intent.preferences.metadataCacheLimitBytes,
			imageLimitBytes = intent.preferences.imageCacheLimitBytes,
			prefetchRadius = intent.preferences.prefetchRadius,
			revalidateSetsOnLaunch = intent.preferences.revalidateSetsOnLaunch,
			hideEmptySets = intent.preferences.hideEmptySets,
			themeMode = intent.preferences.themeMode,
		)

		is Intent.ImageCacheLimitChosen -> state.copy(imageLimitBytes = intent.bytes)

		is Intent.MetadataCacheLimitChosen -> state.copy(metadataLimitBytes = intent.bytes)

		is Intent.PrefetchRadiusChosen -> state.copy(prefetchRadius = intent.radius)

		is Intent.RevalidateOnLaunchChanged -> state.copy(revalidateSetsOnLaunch = intent.isEnabled)

		is Intent.HideEmptySetsChanged -> state.copy(hideEmptySets = intent.hide)

		is Intent.ThemeModeChosen -> state.copy(themeMode = intent.mode)

		is Intent.AttributionRead -> state.copy(attributions = intent.credits)

		// Promotion is a reorder, not a filter: the other three keep their relative order behind
		// the promoted one, so tapping through them cycles predictably.
		is Intent.PromoteLanguage -> state.copy(
			preferredLanguages = listOf(intent.language) +
				state.preferredLanguages.filter { it != intent.language },
		)

	}
}
