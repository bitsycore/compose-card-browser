package com.bitsycore.cardbrowser.render

import com.bitsycore.cardbrowser.AppBuild
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.net.HttpClientFactory
import com.bitsycore.cardbrowser.di.providerRoutes
import com.bitsycore.cardbrowser.providers.altered.AlteredProvider
import com.bitsycore.cardbrowser.providers.optcg.OptcgProvider
import com.bitsycore.cardbrowser.providers.riftcodex.RiftcodexProvider
import com.bitsycore.cardbrowser.providers.scryfall.ScryfallProvider
import com.bitsycore.cardbrowser.providers.tcgcsv.CyberpunkTcgCsvProvider
import com.bitsycore.cardbrowser.providers.tcgcsv.LorcanaTcgCsvProvider
import com.bitsycore.cardbrowser.providers.tcgcsv.WowTcgCsvProvider
import com.bitsycore.cardbrowser.providers.tcgdex.TcgdexProvider
import com.bitsycore.cardbrowser.providers.wuwa.WuwaProvider
import com.bitsycore.cardbrowser.providers.ygoprodeck.YgoprodeckProvider
import com.bitsycore.cardbrowser.ui.screen.about.AboutContract
import com.bitsycore.cardbrowser.ui.screen.about.AboutContent
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the About page off-screen, with the **real** credits.
 *
 * The sources are read from the actual provider graph rather than typed into a fixture, because the
 * thing worth looking at is whether ten games' worth of real attribution text reads as a page or as
 * a wall -- and a fixture of two invented ones cannot show that.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests '*AboutRenderer*'
 * ```
 */
class AboutRenderer {

	@Test
	@Ignore("A rendering tool, not a check. Remove the annotation to write the PNGs.")
	fun `writes the about page to build slash render`() {
		val vOut = File("build/render")
		vOut.mkdirs()

		// The real adapters, built directly rather than through Koin: the graph wants a platform
		// driver and a storage root, and none of that changes a word of the attribution text. No
		// request is made -- a provider only talks to its host when asked for cards.
		val vClient = HttpClientFactory.create()
		val vRegistry = ProviderRegistry(
			providers = listOf(
				RiftcodexProvider(vClient),
				// No storage, which makes it a provider that simply does not offer a bulk dump.
				// Nothing on this page asks about bulk.
				ScryfallProvider(vClient),
				TcgdexProvider(vClient),
				OptcgProvider(vClient),
				AlteredProvider(vClient),
				YgoprodeckProvider(vClient),
				WuwaProvider(),
				LorcanaTcgCsvProvider(vClient),
				CyberpunkTcgCsvProvider(vClient),
				WowTcgCsvProvider(vClient),
			),
			routes = providerRoutes,
		)
		val vSources = vRegistry.games
			.mapNotNull { vGame -> vRegistry.resolve(vGame)?.let { it to vGame } }
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

		println("about: ${vSources.size} credited sources for ${vRegistry.games.size} games")
		renderToPng(vOut, "about", width = 660, height = 4600, density = 1.65f) {
			AboutContent(
				state = AboutContract.UiState(version = AppBuild.VERSION, sources = vSources),
				dispatch = {},
			)
		}

		assertTrue(vOut.listFiles().orEmpty().any { it.length() > 0 }, "nothing was rendered")
		println("Wrote ${vOut.absolutePath}")
	}
}
