package com.bitsycore.cardbrowser.di

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.ui.games.GameArtRegistry
import com.bitsycore.cardbrowser.core.provider.CardProvider
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.data.net.HttpClientFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

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
		val vProviders = graph().getAll<CardProvider<GameProfile>>()

		val vIds = vProviders.map { it.id.value }
		assertEquals(vIds.size, vIds.distinct().size, "Duplicate provider ids: $vIds")
		assertTrue(vIds.size >= 7, "Expected all seven adapters, got $vIds")
	}

	@Test
	fun `every game the switcher offers resolves to a provider that serves it`() {
		val vRegistry = graph().get<ProviderRegistry>()

		for (vGame in vRegistry.games) {
			val vProvider = vRegistry.resolve(vGame)
			assertNotNull(vProvider, "${vGame.id} is offered but resolves to nothing")
			// A route pointing at an adapter that serves a different game would produce a screen
			// that loads forever. The registry now rejects that at construction -- the provider's
			// own type is the authority -- so this asserts the invariant that check protects.
			assertEquals(
				vGame.id,
				vProvider.game.id,
				"${vProvider.id} is routed for ${vGame.id} but serves ${vProvider.game.id}",
			)
		}
	}

	@Test
	fun `a set id can be traced back to its game`() {
		// How the grid and the detail screen work out which game they are looking at. If a
		// provider were routed for two games this would be ambiguous and they would fall back.
		val vRegistry = graph().get<ProviderRegistry>()

		for (vGame in vRegistry.games) {
			val vProvider = vRegistry.require(vGame.id)
			assertEquals(
				vGame.id,
				vRegistry.gameFor(vProvider.id)?.id,
				"${vProvider.id} traced wrongly",
			)
		}
	}

	@Test
	fun `a game with no adapter is not offered at all`() {
		val vRegistry = graph().get<ProviderRegistry>()

		// Cyberpunk TCG is deliberately absent -- the game is unreleased and no data source for it
		// exists. There is no longer an enum for it to be absent *from*: a game exists because a
		// `:games:*` module declares it and the routing table routes it, so an unserved game is
		// simply a module nobody wrote, and the switcher cannot show it.
		assertEquals(
			providerRoutes.map { it.game }.toSet(),
			vRegistry.games.map { it.id }.toSet(),
			"The offered games must be exactly the routed ones",
		)
	}

	@Test
	fun `each game either has a confirmed Cardmarket section or declares it has none`() {
		// Every slug here has been read off a real Cardmarket URL, because the site answers 403 to
		// scripted requests and a browser is the only oracle. Two games declare `null`, and mean
		// different things by it that both come out as no button: Altered is not sold on Cardmarket
		// at all, and Wuthering Waves is too new to have a section yet.
		val vRegistry = graph().get<ProviderRegistry>()
		val vExpected = mapOf(
			"riftbound" to "Riftbound",
			"pokemon" to "Pokemon",
			"magic" to "Magic",
			"onepiece" to "OnePiece",
			"yugioh" to "YuGiOh",
			"altered" to null,
			"wuwa" to null,
		)

		for (vGame in vRegistry.games) {
			assertTrue(
				vExpected.containsKey(vGame.id.value),
				"${vGame.id} is offered but its Cardmarket status has never been checked",
			)
			assertEquals(
				vExpected[vGame.id.value],
				vGame.cardmarketSlug,
				"${vGame.id}'s Cardmarket slug changed -- confirm the new one against a real page",
			)
		}
	}

	@Test
	fun `every offered game ships a mark`() {
		// This is the check that replaced compile-time exhaustiveness. `GameVisual.of` was a total
		// `when` over a closed enum, so a new game could not skip it; art now lives in the game
		// modules and is gathered into a list in the Koin graph, which a new module *can* be left
		// out of. So it is asserted instead.
		// One `graph()` call: it starts Koin, and starting it twice throws.
		val vGraph = graph()
		val vRegistry = vGraph.get<ProviderRegistry>()
		val vArt = vGraph.get<GameArtRegistry>()

		for (vGame in vRegistry.games) {
			val vVisual = assertNotNull(vArt.forGame(vGame), "${vGame.id} ships no art")
			assertTrue(vVisual.accentArgb != 0L, "${vGame.id} has no accent colour")
			assertTrue(
				!vVisual.prefersDarkBackdrop || vVisual.logo != null,
				"${vGame.id} asks for a dark backdrop but has no logo to put on it",
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
