package com.bitsycore.tcgexplorer.ui.screen.about

import com.bitsycore.tcgexplorer.AppBuild
import com.bitsycore.tcgexplorer.core.provider.ProviderRegistry
import com.bitsycore.tcgexplorer.platform.LinkOpener
import com.bitsycore.lib.pulse.viewmodel.PulseViewModel

/** Reads the credits off the running provider graph, so the page cannot list a source that is gone. */
class AboutViewModel(
	private val mRegistry: ProviderRegistry,
	private val mLinkOpener: LinkOpener,
) : PulseViewModel<AboutContract.UiState, AboutContract.Intent, AboutContract.Effect>(
	initialState = AboutContract.UiState(),
	containerContract = AboutContract,
) {

	init {
		dispatch(AboutContract.Intent.Loaded(version = AppBuild.VERSION, sources = credits()))
	}

	override suspend fun handleIntent(intent: AboutContract.Intent) {
		when (intent) {
			AboutContract.Intent.BackPressed -> emitEffect(AboutContract.Effect.NavigateBack)

			// Opened by the platform, like every other outward link here. A failure is silent:
			// a device with nothing registered for http is not a thing to raise an error about.
			is AboutContract.Intent.LinkOpened -> mLinkOpener.open(intent.url)

			else -> Unit
		}
	}

	/**
	 * Every routed source with the games it serves, in one pass over the registry.
	 *
	 * Grouped by source rather than listed per game, because one source serves several games and
	 * repeating Riftcodex's wording once per game would be five copies of the same sentence. A
	 * source that states no attribution is left out entirely -- an empty credit is not a credit.
	 */
	private fun credits(): List<AboutContract.SourceCredit> = mRegistry.games
		.mapNotNull { vGame -> mRegistry.resolve(vGame)?.let { it to vGame } }
		.groupBy({ it.first }, { it.second })
		.mapNotNull { (vProvider, vGames) ->
			val vAttribution = vProvider.capabilities.attribution ?: return@mapNotNull null
			AboutContract.SourceCredit(
				name = vProvider.displayName,
				games = vGames.map { it.displayName }.distinct().sorted(),
				text = vAttribution.text,
				url = vAttribution.url,
			)
		}
		.sortedBy { it.name }
}
