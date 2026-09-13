package com.bitsycore.toploader.ui

import com.bitsycore.toploader.core.provider.ProviderRegistry
import com.bitsycore.toploader.data.net.HttpClientFactory
import com.bitsycore.toploader.di.providerRoutes
import com.bitsycore.toploader.platform.LinkOpener
import com.bitsycore.toploader.providers.altered.AlteredProvider
import com.bitsycore.toploader.providers.optcg.OptcgProvider
import com.bitsycore.toploader.providers.riftcodex.RiftcodexProvider
import com.bitsycore.toploader.providers.scryfall.ScryfallProvider
import com.bitsycore.toploader.providers.tcgcsv.CyberpunkTcgCsvProvider
import com.bitsycore.toploader.providers.tcgcsv.LorcanaTcgCsvProvider
import com.bitsycore.toploader.providers.tcgcsv.WowTcgCsvProvider
import com.bitsycore.toploader.providers.tcgdex.TcgdexProvider
import com.bitsycore.toploader.providers.wuwa.WuwaProvider
import com.bitsycore.toploader.providers.ygoprodeck.YgoprodeckProvider
import com.bitsycore.toploader.ui.screen.about.AboutViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
Every game the app serves names the source its data came from.

Attribution is a condition of using these APIs, not a nicety, and the About page is the only place
it is now said. A provider added without an `Attribution` would drop silently off that page and
nothing else would notice -- so this asks the real registry rather than a fixture.
*/
@OptIn(ExperimentalCoroutinesApi::class)
class AboutCreditsTest {

	@BeforeTest
	fun setUp() {
		// A view model's scope is `Dispatchers.Main.immediate`, which a plain JVM test has not got.
		Dispatchers.setMain(StandardTestDispatcher())
	}

	@AfterTest
	fun tearDown() = Dispatchers.resetMain()

	@Test
	fun `every routed game is credited to a source`() {
		val vRegistry = registry()
		val vModel = AboutViewModel(vRegistry, NoopLinkOpener)

		val vCredited = vModel.stateFlow.value.sources.flatMap { it.games }.toSet()
		val vExpected = vRegistry.games.map { it.displayName }.toSet()

		assertEquals(
			vExpected,
			vCredited,
			"a game with no credited source would vanish from the About page silently",
		)
	}

	@Test
	fun `no credit is blank, and each carries the source's own wording`() {
		val vModel = AboutViewModel(registry(), NoopLinkOpener)

		val vSources = vModel.stateFlow.value.sources
		assertTrue(vSources.isNotEmpty(), "no sources at all")
		for (vSource in vSources) {
			assertTrue(vSource.name.isNotBlank(), "a source with no name")
			assertTrue(vSource.text.isNotBlank(), "${vSource.name} has an empty attribution")
			assertTrue(vSource.games.isNotEmpty(), "${vSource.name} answers for no game")
		}
	}

	@Test
	fun `the version is the one the build generated`() {
		// Reads the generated constant rather than a literal, so this cannot pass against a stale
		// string typed into the screen.
		assertEquals(
			com.bitsycore.toploader.AppBuild.VERSION,
			AboutViewModel(registry(), NoopLinkOpener).stateFlow.value.version,
		)
	}

	/** The real adapters. None of them talks to its host until asked for cards. */
	private fun registry(): ProviderRegistry {
		val vClient = HttpClientFactory.create()
		return ProviderRegistry(
			providers = listOf(
				RiftcodexProvider(vClient),
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
	}

	private object NoopLinkOpener : LinkOpener {

		override fun open(url: String): Boolean = true
	}
}
