package com.bitsycore.cardbrowser.ui.settings

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.data.cache.CacheManager
import com.bitsycore.cardbrowser.data.cache.CacheUsage
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
	)

	sealed interface Intent {

		data object Refresh : Intent

		data class UsageRead(val usage: CacheUsage) : Intent

		data class PreferencesRead(val languages: List<CardLanguage>) : Intent

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

		is Intent.PreferencesRead -> state.copy(preferredLanguages = intent.languages)

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
