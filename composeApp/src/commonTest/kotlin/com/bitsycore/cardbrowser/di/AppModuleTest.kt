package com.bitsycore.cardbrowser.di

import com.bitsycore.cardbrowser.core.model.Game
import com.bitsycore.cardbrowser.core.provider.CardProvider
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.data.net.HttpClientFactory
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * That the object graph actually assembles.
 *
 * This exists because of a failure nothing else could catch. Seven providers were registered as
 * `single<CardProvider> { ... }`, which gives seven definitions sharing one primary type and no
 * qualifier -- so Koin kept only the last, `getAll<CardProvider>()` returned one adapter, and
 * `ProviderRegistry` threw at startup with "routing table names providers that are not registered".
 *
 * It compiled cleanly, every unit test passed, and every provider worked in isolation against its
 * live API. The app crashed on launch. Wiring is not covered by testing the things being wired, so
 * it is covered here.
 */
class AppModuleTest {

	@AfterTest
	fun tearDown() {
		stopKoin()
	}

	/** The graph, with the two platform bindings replaced by in-memory ones. */
	private fun graph() = startKoin {
		modules(
			appModule,
			module {
				single {
					val vFileSystem = FakeFileSystem()
					AppStorage(
						fileSystem = vFileSystem,
						cacheRoot = "/cache".toPath(),
						preferencesRoot = "/preferences".toPath(),
					).also { it.prepare() }
				}
				single<com.bitsycore.cardbrowser.platform.LinkOpener> {
					object : com.bitsycore.cardbrowser.platform.LinkOpener {
						override fun open(url: String): Boolean = true
					}
				}
			},
		)
	}.koin

	@Test
	fun `every routed provider is actually registered`() {
		val vRegistry = graph().get<ProviderRegistry>()

		// The assertion that would have failed. `ProviderRegistry`'s own `init` already rejects a
		// routing table naming an unregistered provider, so merely constructing it is most of the
		// check -- but stating the count makes the failure message useful.
		assertEquals(
			providerRoutes.map { it.provider }.distinct().size,
			vRegistry.all.size,
			"Registered adapters: ${vRegistry.all.map { it.id.value }}",
		)
	}

	@Test
	fun `every adapter is a distinct instance -- not the same one seven times`() {
		val vProviders = graph().getAll<CardProvider>()

		val vIds = vProviders.map { it.id.value }
		assertEquals(vIds.size, vIds.distinct().size, "Duplicate provider ids: $vIds")
		assertTrue(vIds.size >= 7, "Expected all seven adapters, got $vIds")
	}

	@Test
	fun `every game the switcher offers resolves to a provider that serves it`() {
		val vRegistry = graph().get<ProviderRegistry>()

		for (vGame in vRegistry.games) {
			val vProvider = vRegistry.resolve(vGame)
			assertNotNull(vProvider, "$vGame is offered but resolves to nothing")
			// A route pointing at an adapter that does not claim the game would produce a screen
			// that loads forever, or a `require` failure on the first request.
			assertTrue(
				vGame in vProvider.capabilities.games,
				"${vProvider.id} is routed for $vGame but only serves ${vProvider.capabilities.games}",
			)
		}
	}

	@Test
	fun `a set id can be traced back to its game`() {
		// How the grid and the detail screen work out which game they are looking at. If a
		// provider were routed for two games this would be ambiguous and they would fall back.
		val vRegistry = graph().get<ProviderRegistry>()

		for (vGame in vRegistry.games) {
			val vProvider = vRegistry.require(vGame)
			assertEquals(vGame, vRegistry.gameFor(vProvider.id), "${vProvider.id} traced wrongly")
		}
	}

	@Test
	fun `a game with no adapter is not offered at all`() {
		val vRegistry = graph().get<ProviderRegistry>()

		// Cyberpunk TCG is deliberately absent -- the game is unreleased and no data source for it
		// exists. It is in the `Game` enum so the routing table has something to name later, and
		// the switcher must not show it in the meantime.
		assertTrue(
			Game.entries.any { it !in vRegistry.games } || vRegistry.games.size == Game.entries.size,
			"This assertion only documents the mechanism",
		)
		assertEquals(
			providerRoutes.map { it.game }.toSet(),
			vRegistry.games,
			"The offered games must be exactly the routed ones",
		)
	}

	@Test
	fun `every game has a fallback mark -- logo or not`() {
		// `GameVisual.of` is exhaustive, so a new game cannot skip this by omission -- it fails to
		// compile instead. What this checks is the invariant the class documents: the Material mark
		// is always present, because only four of the seven games have a freely-licensed logo and
		// the other three rely on the fallback permanently.
		val vRegistry = graph().get<ProviderRegistry>()

		for (vGame in vRegistry.games) {
			val vVisual = com.bitsycore.cardbrowser.ui.games.GameVisual.of(vGame)
			assertNotNull(vVisual.icon, "$vGame has no fallback mark")
			// Neither presentation flag means anything without a logo to apply it to.
			assertTrue(
				!vVisual.tintLogo || vVisual.logo != null,
				"$vGame is marked tintable but has no logo to tint",
			)
			assertTrue(
				!vVisual.prefersDarkBackdrop || vVisual.logo != null,
				"$vGame asks for a dark backdrop but has no logo to put on it",
			)
			// Tinting recolours the whole image, so a logo that needs its own colours kept must
			// not also be tinted -- the two flags answer different questions.
			assertTrue(
				!(vVisual.tintLogo && vVisual.prefersDarkBackdrop),
				"$vGame is both recoloured and given a dark plate; pick one",
			)
		}
	}

	@Test
	fun `the shared HTTP client is a single instance`() {
		// Every adapter takes `get()` for its client. Seven clients would mean seven connection
		// pools and seven copies of the image cache's transport.
		val vKoin = graph()
		assertTrue(vKoin.get<io.ktor.client.HttpClient>() === vKoin.get<io.ktor.client.HttpClient>())
		assertNotNull(HttpClientFactory.json)
	}
}
