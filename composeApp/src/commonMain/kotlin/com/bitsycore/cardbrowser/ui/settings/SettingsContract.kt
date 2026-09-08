package com.bitsycore.cardbrowser.ui.settings

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.data.cache.CacheManager
import com.bitsycore.cardbrowser.data.cache.CacheUsage
import com.bitsycore.cardbrowser.data.settings.BrowsingPreferences
import com.bitsycore.lib.pulse.container.ContainerContract

/** Settings state: cache readings and the card-language preference order. */
object SettingsContract :
	ContainerContract<SettingsContract.UiState, SettingsContract.Intent, SettingsContract.Effect>() {

	data class UiState(
		val metadataBytes: Long = 0,
		val metadataEntries: Int = 0,
		val metadataLimitBytes: Long = 0,
		val imageBytes: Long = 0,
		val imageLimitBytes: Long = CacheManager.DEFAULT_IMAGE_CACHE_MAX_BYTES,
		val preferredLanguages: List<CardLanguage> = CardLanguage.PREFERENCE_ORDER,
		val providerAttribution: String? = null,
		val prefetchRadius: Int = BrowsingPreferences.DEFAULT_PREFETCH_RADIUS,
		val revalidateSetsOnLaunch: Boolean = true,
	)

	sealed interface Intent {

		data object Refresh : Intent

		data class UsageRead(val usage: CacheUsage) : Intent

		data class PreferencesRead(val preferences: BrowsingPreferences) : Intent

		/** A new ceiling for downloaded card art. Takes effect on the next launch. */
		data class ImageCacheLimitChosen(val bytes: Long) : Intent

		/** A new ceiling for cached card records. Takes effect on the next write. */
		data class MetadataCacheLimitChosen(val bytes: Long) : Intent

		/** How many cards either side of the open one to fetch ahead. Zero switches it off. */
		data class PrefetchRadiusChosen(val radius: Int) : Intent

		data class RevalidateOnLaunchChanged(val isEnabled: Boolean) : Intent

		data class AttributionRead(val text: String?) : Intent

		/** Moves a language to the front of the preference order. */
		data class PromoteLanguage(val language: CardLanguage) : Intent

		data object ClearMetadata : Intent

		data object ClearImages : Intent
	}

	sealed interface Effect

	override fun reduce(state: UiState, intent: Intent): UiState = when (intent) {

		Intent.Refresh -> state

		is Intent.UsageRead -> state.copy(
			metadataBytes = intent.usage.metadataBytes,
			metadataEntries = intent.usage.metadataEntries,
			metadataLimitBytes = intent.usage.metadataLimitBytes,
			imageBytes = intent.usage.imageBytes,
			imageLimitBytes = intent.usage.imageLimitBytes,
		)

		is Intent.PreferencesRead -> state.copy(
			preferredLanguages = intent.preferences.preferredLanguages,
			metadataLimitBytes = intent.preferences.metadataCacheLimitBytes,
			imageLimitBytes = intent.preferences.imageCacheLimitBytes,
			prefetchRadius = intent.preferences.prefetchRadius,
			revalidateSetsOnLaunch = intent.preferences.revalidateSetsOnLaunch,
		)

		is Intent.ImageCacheLimitChosen -> state.copy(imageLimitBytes = intent.bytes)

		is Intent.MetadataCacheLimitChosen -> state.copy(metadataLimitBytes = intent.bytes)

		is Intent.PrefetchRadiusChosen -> state.copy(prefetchRadius = intent.radius)

		is Intent.RevalidateOnLaunchChanged -> state.copy(revalidateSetsOnLaunch = intent.isEnabled)

		is Intent.AttributionRead -> state.copy(providerAttribution = intent.text)

		// Promotion is a reorder, not a filter: the other three keep their relative order behind
		// the promoted one, so tapping through them cycles predictably.
		is Intent.PromoteLanguage -> state.copy(
			preferredLanguages = listOf(intent.language) +
				state.preferredLanguages.filter { it != intent.language },
		)

		Intent.ClearMetadata, Intent.ClearImages -> state
	}
}
