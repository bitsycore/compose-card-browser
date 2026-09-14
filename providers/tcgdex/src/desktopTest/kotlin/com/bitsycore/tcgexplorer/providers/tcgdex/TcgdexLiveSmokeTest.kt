package com.bitsycore.tcgexplorer.providers.tcgdex

import io.ktor.client.request.setBody
import io.ktor.http.contentType
import io.ktor.http.appendPathSegments
import io.ktor.http.ContentType
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.post
import com.bitsycore.tcgexplorer.core.model.Availability
import com.bitsycore.tcgexplorer.core.model.CardLanguage
import com.bitsycore.tcgexplorer.core.model.SourceId
import com.bitsycore.tcgexplorer.core.provider.CardPageRequest
import com.bitsycore.tcgexplorer.data.net.HttpClientFactory
import com.bitsycore.tcgexplorer.games.pokemon.PokemonGame
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.head
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Talks to the real TCGdex API.
 *
 * **Not part of the ordinary test run.** Run it deliberately:
 *
 * ```
 * ./gradlew :providers:tcgdex:liveProviderTest
 * ```
 *
 * Assertions are about shape and invariants rather than exact values, except where an anchor is
 * solid enough to pin -- Base Set really does have 102 cards and really was released in 1999.
 */
class TcgdexLiveSmokeTest {

	// One instance for the whole class, and it matters more here than anywhere else in the suite.
	// `fetchCatalogues` costs twelve requests -- eleven locales and a GraphQL query -- and memoises
	// the result on the *instance* for five minutes. A fresh provider per test threw that away, so
	// the six tests that call `listSets` paid for it six times over: 72 requests where 12 do. The
	// client is shared for the same reason it is in `ScryfallLiveSmokeTest`: the throttle lives in
	// the plugin instance, so one client is one budget.
	private fun provider() = mProvider

	@Test
	fun `a Japanese-only set arrives with its cards, whatever the source has no scans for`() =
		runBlocking {
			// The reported symptom: every card in the Japanese MEGA sets drew a placeholder.
			//
			// Measured on 2026-09-13, and it is the source's gap rather than this adapter's. The
			// REST card list for M2 carries `{id, localId, name}` and no `image` at all -- for a
			// set that has scans, such as `en/sv08`, every brief card carries one -- and the
			// per-card endpoint has no `image` field either. Every plausible assets URL 404s.
			//
			// So this pins what the app must still do: serve the set, with its names, and leave
			// the artwork empty rather than inventing a URL that will not load.
			val vPage = provider().listCards(
				CardPageRequest(
					setId = SourceId(TcgdexProvider.PROVIDER_ID, "M2"),
					query = com.bitsycore.tcgexplorer.core.provider.CardQuery(),
					page = 1,
					pageSize = 500,
					language = CardLanguage.JAPANESE,
				),
			)

			assertTrue(vPage.cards.isNotEmpty(), "M2 came back with no cards at all")
			assertTrue(
				vPage.cards.all { it.displayName.isNotBlank() },
				"a set with no scans must still carry its names",
			)
			// Not an assertion that the images are absent -- TCGdex may add them, and this should
			// not fail when it does. What must hold is that whatever is offered is usable.
			val vOffered = vPage.cards.mapNotNull { it.artwork.displayUrl?.ifBlank { null } }
			println("M2: ${vPage.cards.size} cards, ${vOffered.size} with an image URL")
			val vClient = HttpClientFactory.create()
			for (vUrl in vOffered.take(3)) {
				val vResponse: HttpResponse = vClient.head(vUrl)
				assertEquals(
					HttpStatusCode.OK,
					vResponse.status,
					"offered an image URL that does not load: $vUrl",
				)
			}
		}

	@Test
	fun `the GraphQL id filter matches anywhere in an id, not just the start`() = runBlocking {
		// `fullCards` leans on this filter for rarity and type, and the comment above it used to
		// say "prefix". It is a `contains` match: on 2026-09-13 `id: "M2-"` answered with 125
		// `sm2` cards and 125 `gym2` cards and not one `M2`.
		//
		// Pinned because the fix -- filtering the answer to this set's own ids -- looks like
		// belt-and-braces until you know that, and would be the first thing a later reader removed.
		val vClient = HttpClientFactory.create()
		val vBody: String = vClient.post("https://api.tcgdex.net") {
			url { appendPathSegments("v2", "graphql") }
			contentType(ContentType.Application.Json)
			setBody(
				"""{"query":"{ cards(filters: { id: \"M2-\" }, """ +
					"""pagination: { page: 1, itemsPerPage: 50 }) { id } }"}""",
			)
		}.bodyAsText()

		assertTrue(vBody.contains("\"id\""), "the filter returned nothing, so this proves nothing")
		assertTrue(
			!vBody.contains("\"M2-"),
			"the filter now looks like a prefix match -- if TCGdex fixed it, simplify `fullCards`",
		)
	}

	@Test
	fun `the set catalogue loads and carries real release dates`() = runBlocking {
		val vSets = provider().listSets(CardLanguage.ENGLISH)

		assertTrue(vSets.size > 100, "Expected a large catalogue, got ${vSets.size}")

		val vBase = vSets.firstOrNull { it.id.local == "base1" }
		assertNotNull(vBase, "Base Set is missing from the live catalogue")
		assertEquals("Base Set", vBase.name)
		assertEquals(102, vBase.cardCount)

		// The whole reason for the second GraphQL request. If it silently stopped working, every
		// set would come back undated and the list would order alphabetically.
		val vReleased = vBase.releaseDate
		assertNotNull(vReleased, "Release dates are missing -- the GraphQL query failed")
		assertEquals(1999, vReleased.year)
		// Dates come from a GraphQL endpoint that serves the international catalogue only, so the
		// claim is about that line rather than about all 486 sets.
		val vInternational = vSets.filter { it.region == PokemonGame.REGION_INTERNATIONAL }
		assertTrue(
			vInternational.count { it.releaseDate != null } > vInternational.size / 2,
			"Most international sets should have a date; only " +
				"${vInternational.count { it.releaseDate != null }} of ${vInternational.size} do",
		)
	}

	// ============
	//  Product lines

	@Test
	fun `every product line is present at once`() = runBlocking {
		// The bug this pins: the catalogue used to be one locale's, so whichever language the user
		// preferred decided which of Pokemon's four product lines existed at all. Browsing in
		// English hid Japan's 180 sets; preferring Japanese hid the international 221.
		val vSets = provider().listSets(CardLanguage.ENGLISH)

		val vByRegion = vSets.groupBy { it.region }
		for (vRegion in PokemonGame.regions) {
			assertTrue(
				vByRegion[vRegion.key].orEmpty().size > 20,
				"Region ${vRegion.key} has only ${vByRegion[vRegion.key].orEmpty().size} sets",
			)
		}
		assertTrue(vSets.size > 400, "Expected every line merged, got ${vSets.size}")
		assertTrue(vSets.none { it.region == null }, "Every Pokemon set belongs to a line")

		// An international set and a Japan-only one, side by side in the same list.
		assertEquals(
			PokemonGame.REGION_INTERNATIONAL,
			assertNotNull(vSets.firstOrNull { it.id.local == "base1" }).region,
		)
		assertEquals(
			PokemonGame.REGION_JAPAN,
			assertNotNull(vSets.firstOrNull { it.id.local == "SV1a" }).region,
		)
	}

	@Test
	fun `the lines with no published dates still come back in release order`() = runBlocking {
		// 265 of Pokemon's 486 sets carry no date: the GraphQL endpoint that publishes them serves
		// the international catalogue only, and `/v2/ja/graphql` is a 404. Without an order they
		// fell through to code order, which puts the 2013 XY sets after the 2023 SV ones -- an
		// unreadable list of sets whose names are not in a Latin alphabet either.
		//
		// So the catalogue is asked for sorted, and the rank is kept. This checks the rank is
		// really there and really increasing with time.
		val vSets = provider().listSets(CardLanguage.ENGLISH)

		val vJapan = vSets.filter { it.region == PokemonGame.REGION_JAPAN }
		assertTrue(vJapan.all { it.releaseOrder != null }, "Japan-line sets carry no rank")
		// A rank is a position in one catalogue, so within a line they must all be distinct.
		assertEquals(
			vJapan.size,
			vJapan.mapNotNull { it.releaseOrder }.distinct().size,
			"two Japan-line sets share a rank",
		)

		// The anchors: TCGdex's own era ids, which run PMCG (1996) then XY (2013) then SM (2016)
		// then SV (2023). If the rank were meaningless these would not be ordered.
		//
		// `neo1` is deliberately not among them. It is one of the four ids the Japanese and
		// international catalogues genuinely share, so region priority files it under the
		// international line -- which is the intended behaviour and makes it useless as an anchor
		// here.
		val vRankOf = { vLocal: String ->
			val vSet = assertNotNull(
				vJapan.firstOrNull { it.id.local == vLocal },
				"$vLocal is missing from the Japan line",
			)
			assertNotNull(vSet.releaseOrder, "$vLocal has no rank")
		}
		val vSequence = listOf("PMCG1", "XY1a", "SM1S", "SV1a").map(vRankOf)
		assertEquals(
			vSequence.sorted(),
			vSequence,
			"the Japanese line is not in release order: $vSequence",
		)

		// And the international line, where the dates are published, is ordered by them -- the
		// cross-check that the server's sort really is by date.
		val vDated = vSets
			.filter { it.region == PokemonGame.REGION_INTERNATIONAL && it.releaseDate != null }
			.sortedBy { it.releaseOrder }
		assertEquals(
			vDated.mapNotNull { it.releaseDate }.sorted(),
			vDated.mapNotNull { it.releaseDate },
			"rank order and date order disagree",
		)
	}

	@Test
	fun `set ids are case sensitive and name different products`() = runBlocking {
		// `sm10` is international Unbroken Bonds; `SM10` is Japanese Double Blaze. The detail
		// endpoint folds the case and will serve Unbroken Bonds for `/en/sets/SM10`, so if this
		// merge ever folded it too, one of the two would vanish and the other would be offered in
		// languages that show a different set's cards entirely.
		val vSets = provider().listSets(CardLanguage.ENGLISH)

		val vInternational = assertNotNull(vSets.firstOrNull { it.id.local == "sm10" })
		val vJapanese = assertNotNull(vSets.firstOrNull { it.id.local == "SM10" })

		assertEquals(PokemonGame.REGION_INTERNATIONAL, vInternational.region)
		assertEquals(PokemonGame.REGION_JAPAN, vJapanese.region)
		assertTrue(CardLanguage.ENGLISH in vInternational.languages)
		assertTrue(
			CardLanguage.ENGLISH !in vJapanese.languages,
			"The Japanese SM10 has no English edition, whatever the detail endpoint answers",
		)
	}

	@Test
	fun `a claimed language with no cards behind it is not confirmed`() = runBlocking {
		// The residual half of the reported bug. Narrowing eleven locales to the six that list Base
		// Set is not enough: TCGdex *lists* Spanish and Portuguese editions of it, complete with a
		// card count of 102, and serves no cards for either. Nothing in a set list distinguishes
		// that from a real edition, so it is probed.
		val vProvider = provider()
		val vBase = SourceId(TcgdexProvider.PROVIDER_ID, "base1")

		val vConfirmed = vProvider.confirmLanguages(
			setId = vBase,
			candidates = setOf(
				CardLanguage.ENGLISH,
				CardLanguage.FRENCH,
				CardLanguage.GERMAN,
				CardLanguage.ITALIAN,
				CardLanguage.SPANISH,
				CardLanguage.PORTUGUESE,
			),
		)

		assertTrue(CardLanguage.ENGLISH in vConfirmed)
		assertTrue(CardLanguage.FRENCH in vConfirmed)
		assertTrue(CardLanguage.SPANISH !in vConfirmed, "Spanish Base Set has no cards")
		assertTrue(CardLanguage.PORTUGUESE !in vConfirmed, "Portuguese Base Set has no cards")
	}

	@Test
	fun `the Korean catalogue is names only and is not offered`() = runBlocking {
		// Measured across ten Korean sets: every one carries a name and a claimed card count, and
		// every one serves zero cards. So Korean is real in the catalogue and empty in the data,
		// which is why "does this set list the language" cannot be the test.
		val vProvider = provider()

		val vConfirmed = vProvider.confirmLanguages(
			setId = SourceId(TcgdexProvider.PROVIDER_ID, "SM1S"),
			candidates = setOf(CardLanguage.JAPANESE, CardLanguage.KOREAN),
		)

		assertEquals(setOf(CardLanguage.JAPANESE), vConfirmed)
	}

	@Test
	fun `a language with real cards survives confirmation`() = runBlocking {
		// The other side of it: confirmation must not throw away working editions. Traditional
		// Chinese really does hold 73 of Triplet Beat's cards.
		val vConfirmed = provider().confirmLanguages(
			setId = SourceId(TcgdexProvider.PROVIDER_ID, "SV1a"),
			candidates = setOf(
				CardLanguage.JAPANESE,
				CardLanguage.KOREAN,
				CardLanguage.TRADITIONAL_CHINESE,
			),
		)

		assertEquals(
			setOf(CardLanguage.JAPANESE, CardLanguage.TRADITIONAL_CHINESE),
			vConfirmed,
		)
	}

	@Test
	fun `a set states the languages it is really published in`() = runBlocking {
		// The reported bug: the language menu offered all eleven locales for every set, because it
		// was built from what the *source* serves rather than what the *set* has.
		val vSets = provider().listSets(CardLanguage.ENGLISH)

		val vBase = assertNotNull(vSets.firstOrNull { it.id.local == "base1" })
		assertTrue(CardLanguage.ENGLISH in vBase.languages)
		assertTrue(CardLanguage.FRENCH in vBase.languages)
		// Base Set is in no Asian catalogue -- `/ko/sets/base1` is a 404, not an empty set.
		assertTrue(CardLanguage.KOREAN !in vBase.languages, "Base Set has no Korean edition")
		assertTrue(CardLanguage.JAPANESE !in vBase.languages)
		assertTrue(vBase.languages.size < CardLanguage.entries.size)

		// Korea prints the Japanese line, which is why it is a language and not a region.
		val vKorean = vSets.filter { CardLanguage.KOREAN in it.languages }
		assertTrue(vKorean.isNotEmpty(), "No set claims a Korean edition")
		assertTrue(
			vKorean.all { it.region == PokemonGame.REGION_JAPAN },
			"Every Korean set id is a Japanese set id",
		)

		// And no set claims every language, which is what the menu used to assume.
		assertTrue(vSets.none { it.languages.size == CardLanguage.entries.size })
	}

	@Test
	fun `a set arrives complete in one request`() = runBlocking {
		val vProvider = provider()
		val vPage = vProvider.listCards(
			CardPageRequest(
				setId = SourceId(TcgdexProvider.PROVIDER_ID, "swsh3"),
				language = CardLanguage.ENGLISH,
			),
		)

		// The claim the repository relies on: no paging, so `hasMore` false really means complete.
		assertTrue(vPage.cards.size > 150, "Darkness Ablaze should be ~201 cards, got ${vPage.cards.size}")
		assertEquals(vPage.cards.size, vPage.totalCount)
		assertTrue(!vPage.hasMore, "This adapter must never report a second page")

		val vCard = vPage.cards.first()
		assertTrue(vCard.artwork.thumbnailUrl?.endsWith("low.webp") == true, "Thumbnails must be WebP")
		assertTrue(vCard.artwork.displayUrl?.endsWith("high.webp") == true, "Display art must be WebP")
		assertEquals(CardLanguage.ENGLISH, vCard.text.language)
	}

	@Test
	fun `every language the adapter declares returns a catalogue of its own`() = runBlocking {
		val vProvider = provider()
		// The claim that makes TCGdex the best source in this app, and the test the capability list
		// is based on rather than merely described by. A locale it does not serve answers 404 and a
		// locale that silently emptied would show a Pokémon game with no sets at all.
		//
		// The threshold is deliberately low. `ko` carries 95 sets and the Chinese catalogues fewer
		// still, so this asks whether the locale is real, not whether it is complete.
		for (vLanguage in vProvider.capabilities.data.languages) {
			val vSets = vProvider.listSets(vLanguage)
			assertTrue(vSets.size > 3, "${vLanguage.code} returned only ${vSets.size} sets")
		}
	}

	@Test
	fun `a French card is French -- text and image alike`() = runBlocking {
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(TcgdexProvider.PROVIDER_ID, "swsh3"),
				language = CardLanguage.FRENCH,
			),
		)

		val vCard = vPage.cards.first()
		assertEquals(CardLanguage.FRENCH, vCard.text.language)
		assertEquals(Availability.AVAILABLE, vCard.languages.availabilityOf(CardLanguage.FRENCH))
		// Never claimed as absent: TCGdex not listing a Korean record says nothing about whether a
		// Korean printing exists.
		assertEquals(Availability.UNKNOWN, vCard.languages.availabilityOf(CardLanguage.KOREAN))
		// The image comes from the French endpoint, so it is a French scan.
		assertTrue(
			vCard.artwork.displayUrl?.contains("/fr/") == true,
			"French request returned a non-French image: ${vCard.artwork.displayUrl}",
		)
	}

	@Test
	fun `finishes are stated on both sides -- not merely left unknown`() = runBlocking {
		val vCard = provider().cardDetail(
			SourceId(TcgdexProvider.PROVIDER_ID, "swsh3-1"),
			CardLanguage.ENGLISH,
		)

		assertNotNull(vCard, "swsh3-1 (Butterfree V) should exist")
		// TCGdex says `holo: true, normal: false`, which is a real statement rather than a silence.
		// This is the only provider in the app that can populate `absent` from booleans.
		assertTrue(!vCard.finishes.isUnstated, "Finish coverage should be stated for a Pokémon card")
		assertTrue(
			vCard.finishes.confirmed.isNotEmpty() || vCard.finishes.absent.isNotEmpty(),
			"At least one side of the finish coverage should be populated",
		)
	}

	@Test
	fun `a Cardmarket product id really is attached to a printing`() = runBlocking {
		val vCard = provider().cardDetail(
			SourceId(TcgdexProvider.PROVIDER_ID, "swsh3-1"),
			CardLanguage.ENGLISH,
		)

		assertNotNull(vCard)
		val vProduct = vCard.externalIds[com.bitsycore.tcgexplorer.core.model.ExternalIdKey.CARDMARKET_PRODUCT]
		assertTrue(!vProduct.isNullOrEmpty(), "Expected a Cardmarket product id on this card")
		assertTrue(vProduct.first().toLongOrNull() != null, "Product id should be numeric")
	}

	@Test
	fun `set logos resolve -- and the advertised symbol still does not`() = runBlocking<Unit> {
		val vClient = HttpClientFactory.create()
		val vSets = TcgdexProvider(vClient).listSets(CardLanguage.ENGLISH)

		val vWith = vSets.filter { it.symbol != null }
		assertTrue(vWith.size > 100, "Expected most sets to carry a logo, got ${vWith.size}")
		assertTrue(vWith.all { it.symbol!!.url.endsWith(".webp") }, "The extension must be appended")

		val vResponse: HttpResponse = vClient.head(vWith.first().symbol!!.url)
		assertEquals(HttpStatusCode.OK, vResponse.status, "Set logo is not loading")

		// Why `symbol` is unused: TCGdex advertises the field and its CDN serves nothing for it.
		// If this ever starts returning 200, the mapper should switch -- a set symbol suits a small
		// tile far better than a wordmark does.
		// The shared client has `expectSuccess` on, so a 404 arrives as an exception rather than a
		// status. Catching it is the assertion.
		val vSymbolStatus = try {
			vClient.head("https://assets.tcgdex.net/univ/base/base2/symbol.png").status
		} catch (vError: ClientRequestException) {
			vError.response.status
		}
		assertEquals(
			HttpStatusCode.NotFound,
			vSymbolStatus,
			"TCGdex now serves set symbols -- prefer them over the wordmark logo",
		)
	}

	private companion object {

		/** One per JVM, so the catalogue memo and the throttle both span the class. */
		val mProvider by lazy {
			TcgdexProvider(HttpClientFactory.create(policy = TcgdexProvider.HTTP_POLICY))
		}
	}

}
