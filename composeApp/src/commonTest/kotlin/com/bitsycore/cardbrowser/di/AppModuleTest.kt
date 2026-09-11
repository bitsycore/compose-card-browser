package com.bitsycore.cardbrowser.di

import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.ui.games.GameArtRegistry
import com.bitsycore.cardbrowser.core.provider.CardProvider
import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.cache.AppStorage
import com.bitsycore.cardbrowser.data.cache.CacheManager
import com.bitsycore.cardbrowser.data.cache.MetadataCache
import com.bitsycore.cardbrowser.data.download.DownloadManager
import com.bitsycore.cardbrowser.data.repository.CardRepository
import com.bitsycore.cardbrowser.data.repository.SetCatalogueWarmer
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import com.bitsycore.cardbrowser.ui.browse.BrowseSession
import kotlin.test.AfterTest
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import com.bitsycore.cardbrowser.games.pokemon.PokemonGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
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
	fun `every adapter is a distinct instance -- not one of them repeated`() {
		val vProviders = graph().getAll<CardProvider<GameProfile>>()

		val vIds = vProviders.map { it.id.value }
		assertEquals(vIds.size, vIds.distinct().size, "Duplicate provider ids: ${'$'}vIds")
		// Against the routing table rather than a literal. The count was written as "seven" and
		// has moved twice since, and a stale floor is a check that stops checking: three adapters
		// could have been dropped and `>= 7` would still have passed.
		assertEquals(
			providerRoutes.map { it.provider }.distinct().size,
			vIds.size,
			"Expected one instance per routed adapter, got ${'$'}vIds",
		)
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

		// Duel Masters is the game deliberately absent -- measured, and recorded in
		// `docs/PROVIDER_RESEARCH.md`: no source has both images and coverage. (Cyberpunk TCG used
		// to be the example here and stopped being one when TCGplayer opened category 92, which is
		// why this illustration is worth keeping current.) There is no longer an enum for a game
		// to be absent *from*: a game exists because a
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
			"lorcana" to null,
			"cyberpunk" to null,
			"wowtcg" to null,
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
	fun `a source states whether it publishes thumbnails -- and only two do not`() {
		// The capability exists because the app falls back to the full image where there is no
		// small one, and a download offering "Thumbnails -- about 3 MB" then fetches six times
		// that.
		//
		// Wuthering Waves was on this list and is not any more, which is the pattern worth
		// noticing: its CDN resizes on request, so the rendition existed and nobody had asked for
		// it. A source with no *second file* may still have a second size.
		//
		// Pinned as a table rather than asserted loosely, so a provider that gains or loses a
		// rendition has to come here and say so.
		val vRegistry = graph().get<ProviderRegistry>()
		val vWithout = vRegistry.games
			.mapNotNull { vRegistry.resolve(it) }
			.distinctBy { it.id }
			.filterNot { it.capabilities.data.thumbnailImages }
			.map { it.id.value }
			.toSet()

		assertEquals(
			setOf("optcg", "altered-db"),
			vWithout,
			"a source changed what renditions it publishes",
		)
	}

	@Test
	fun `an English-only source resolves a French preference to English`() {
		// The key that a download, a cache entry and an image-download record are all filed under.
		// Riftcodex serves English and nothing else, so a French-preferring user must still get
		// `en` -- and every layer must get the same `en`, which is why this is one function on the
		// registry rather than a `resolveLanguage` call in each of them.
		//
		// The bug: the queue started resolving and the set list did not, so a downloaded Riftbound
		// set wrote `en` image records, the set list looked for `fr` ones, and the dialog offered
		// its thumbnails for download again every time.
		val vRegistry = graph().get<ProviderRegistry>()

		assertEquals(
			CardLanguage.ENGLISH,
			vRegistry.effectiveLanguage(RiftboundGame.id, CardLanguage.FRENCH),
		)
		// And a source that really does serve French still gets it.
		assertEquals(
			CardLanguage.FRENCH,
			vRegistry.effectiveLanguage(PokemonGame.id, CardLanguage.FRENCH),
		)
	}

	@Test
	fun `a source states whether its records ship with the app`() {
		// Only Wuthering Waves, and only because UCP publishes no API for records -- the catalogue
		// is a bundled file. Nothing to download, nothing to keep, nothing to clear.
		val vRegistry = graph().get<ProviderRegistry>()
		val vBundled = vRegistry.games
			.mapNotNull { vRegistry.resolve(it) }
			.distinctBy { it.id }
			.filter { it.capabilities.data.bundledCardData }
			.map { it.id.value }
			.toSet()

		assertEquals(setOf("ucp-wuwa"), vBundled)
	}

	@Test
	fun `every offered game ships a mark`() {
		// This is the check that replaced compile-time exhaustiveness. `GameVisual.of` was a total
		// `when` over a closed enum, so a new game could not skip it; art now lives in the game
		// modules and is gathered into a list in the Koin graph, which a new module *can* be left
		// out of. So it is asserted instead.
		//
		// Briefly this test carried a named exemption for Lorcana and the WoW TCG, neither having a
		// freely-licensed wordmark on Commons. The project owner supplied both, so the exemption is
		// gone rather than left standing as a hole a third game could quietly fall into.
		// One `graph()` call: it starts Koin, and starting it twice throws.
		val vGraph = graph()
		val vRegistry = vGraph.get<ProviderRegistry>()
		val vArt = vGraph.get<GameArtRegistry>()

		for (vGame in vRegistry.games) {
			val vVisual = assertNotNull(vArt.forGame(vGame), "${vGame.id} ships no art")
			assertTrue(vVisual.accentArgb != 0L, "${vGame.id} has no accent colour")
			// Tinting to the theme's *foreground* over a fixed plate is the hazard: the mark
			// inverts with the theme while its plate stays put, so a black-on-yellow lockup
			// becomes white-on-yellow on the dark theme. Naming the dark tint removes the hazard,
			// which is what Cyberpunk does, so the rule is "state it" rather than "never both".
			assertTrue(
				!(vVisual.tintLogo && vVisual.backdropArgb != null) ||
					vVisual.logoTintDarkArgb != null,
				"$vGame tints over a plate without saying what the dark theme gets",
			)
			// A plate needs a mark to put on it, and that one is enforced by the type rather
			// than here: `GameArt.logo` is a non-null `DrawableResource`. There used to be an
			// assertion for it and it was vacuous -- the compiler said so, "Condition is always
			// 'true'" -- which is this codebase's own cardinal sin in test form: a check that
			// claims something it cannot verify.
		}
	}

	@Test
	fun `the two shared HTTP clients are each a single instance`() {
		// There are deliberately three kinds of client in this graph, and the split is easy to
		// undo by accident:
		//
		//  - the unnamed one, which is the image loader's -- see `InstallImageLoader`
		//  - `named(PROVIDER_CLIENT)`, shared by the eight adapters that need no throttle
		//  - two built inline by Scryfall and YGOPRODeck, because a rate limit is a fact about a
		//    source and travels with it
		//
		// A duplicate of either shared one means two connection pools serving the same hosts, so
		// both are pinned. The per-adapter pair is deliberately *not* -- being distinct is the
		// point of them.
		val vKoin = graph()
		assertTrue(
			vKoin.get<io.ktor.client.HttpClient>() === vKoin.get<io.ktor.client.HttpClient>(),
			"The image client must be one instance",
		)
		assertTrue(
			vKoin.get<io.ktor.client.HttpClient>(named(PROVIDER_CLIENT)) ===
				vKoin.get<io.ktor.client.HttpClient>(named(PROVIDER_CLIENT)),
			"The shared provider client must be one instance",
		)
		assertTrue(
			vKoin.get<io.ktor.client.HttpClient>() !==
				vKoin.get<io.ktor.client.HttpClient>(named(PROVIDER_CLIENT)),
			"The image client and the provider client must not be the same one",
		)
	}

	@Test
	fun `every application-scoped single resolves`() {
		// The gap this closes: the file existed to catch wiring that compiles and passes every
		// other test, and it only ever resolved the provider half of the graph. A missing binding
		// under `CardRepository` or `DownloadManager` would have reached a device first.
		val vKoin = graph()

		assertNotNull(vKoin.get<CardRepository>())
		assertNotNull(vKoin.get<CacheManager>())
		assertNotNull(vKoin.get<MetadataCache>())
		assertNotNull(vKoin.get<DownloadManager>())
		assertNotNull(vKoin.get<SetCatalogueWarmer>())
		assertNotNull(vKoin.get<PreferencesStore>())
		assertNotNull(vKoin.get<BrowseSession>())
		assertNotNull(vKoin.get<GameArtRegistry>())
	}
}
