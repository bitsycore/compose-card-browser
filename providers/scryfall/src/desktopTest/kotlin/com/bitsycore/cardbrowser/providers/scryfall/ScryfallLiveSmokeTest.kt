package com.bitsycore.cardbrowser.providers.scryfall

import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import com.bitsycore.cardbrowser.core.provider.CardSearchRequest
import com.bitsycore.cardbrowser.data.net.HttpClientFactory
import com.bitsycore.cardbrowser.data.net.ProviderHttpPolicy
import com.bitsycore.cardbrowser.games.magic.MagicGame
import io.ktor.client.request.head
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Talks to the real Scryfall API.
 *
 * **Not part of the ordinary test run.** Run it deliberately:
 *
 * ```
 * ./gradlew :providers:scryfall:liveProviderTest
 * ```
 */
class ScryfallLiveSmokeTest {

	/**
	 * The provider on **one throttled client shared by every test here**.
	 *
	 * Both halves matter, and neither was true before.
	 *
	 * *Throttled*, because a plain `create()` ignores the app's own policy, which is no way to
	 * treat a free API and is how this class earned a 60-second rate-limit and a warning that
	 * repeating it would get the network blocked.
	 *
	 * *Shared*, because the throttle is per client: it holds a mutex and the timestamp of the last
	 * request it sent, so a fresh client per test -- and the test runner builds a new instance of
	 * this class per test method -- means N independent budgets and no spacing at all between one
	 * test's last request and the next test's first.
	 */
	private fun provider() = ScryfallProvider(mClient)

	// ============
	//  Per-set languages

	@Test
	fun `a set states only the languages it was really printed in`() = runBlocking {
		// Scryfall serves eleven languages, so the language menu offered eleven for every set --
		// including Alpha, printed in 1993 in English only. Nine of those then fell back to English
		// and the screen had to explain itself, once per language the user tried.
		//
		val vProvider = provider()

		// Three candidates rather than all eleven, so this costs six requests instead of
		// twenty-two. Chosen to cover every distinct case exactly once: a language the set has, a
		// language it does not, and one whose Scryfall tag differs from this app's.
		//
		// Kept deliberately small because Scryfall means what it says about rate limits. An
		// earlier version of this class ran unthrottled and earned a sustained refusal from which
		// even correctly paced requests came back 429 -- so a live test here has to be frugal, not
		// merely compliant.
		val vCandidates = setOf(
			CardLanguage.ENGLISH,
			CardLanguage.JAPANESE,
			CardLanguage.SIMPLIFIED_CHINESE,
		)

		val vAlpha = vProvider.confirmLanguages(
			setId = SourceId(ScryfallProvider.PROVIDER_ID, "lea"),
			candidates = vCandidates,
		)
		// Limited Edition Alpha, 1993: English, and nothing else, ever. This is the assertion that
		// would have failed before -- the menu offered all eleven for it.
		assertEquals(setOf(CardLanguage.ENGLISH), vAlpha)

		val vDominaria = vProvider.confirmLanguages(
			setId = SourceId(ScryfallProvider.PROVIDER_ID, "dom"),
			candidates = vCandidates,
		)
		// A modern set really is printed widely, so confirmation must not throw away what exists.
		assertEquals(vCandidates, vDominaria)
		// And Simplified Chinese only passes if `zhs` is being sent rather than this app's own
		// `zh-cn`, which Scryfall does not know and would answer 404 for -- reading as "absent".
		assertTrue(
			CardLanguage.SIMPLIFIED_CHINESE in vDominaria,
			"the Scryfall language tag mapping is wrong: $vDominaria",
		)
	}

	@Test
	fun `the set catalogue loads and excludes digital-only sets`() = runBlocking {
		val vSets = provider().listSets()

		assertTrue(vSets.size > 500, "Expected a large catalogue, got ${vSets.size}")

		val vBloomburrow = vSets.firstOrNull { it.id.local == "blb" }
		assertNotNull(vBloomburrow, "Bloomburrow is missing from the live catalogue")
		assertEquals("Bloomburrow", vBloomburrow.name)
		val vReleased = vBloomburrow.releaseDate
		assertNotNull(vReleased)
		assertEquals(2024, vReleased.year)
	}

	@Test
	fun `a set pages with a real total and a real hasMore`() = runBlocking {
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(ScryfallProvider.PROVIDER_ID, "blb"),
				language = CardLanguage.ENGLISH,
			),
		)

		assertTrue(vPage.cards.isNotEmpty())
		assertNotNull(vPage.totalCount, "Scryfall states a total; losing it would break completeness")
		// Bloomburrow has ~400 printings and the page size is 175, so there must be more.
		assertTrue(vPage.hasMore, "A 400-card set should not fit in one 175-card page")
	}

	@Test
	fun `oracle_id gives the only real card identity in this app`() = runBlocking {
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(ScryfallProvider.PROVIDER_ID, "blb"),
				language = CardLanguage.ENGLISH,
			),
		)

		val vWithIdentity = vPage.cards.count { it.identity != null }
		assertTrue(
			vWithIdentity > vPage.cards.size / 2,
			"Most printings should carry an oracle_id; only $vWithIdentity of ${vPage.cards.size} do",
		)
	}

	@Test
	fun `French printings come back French -- with printed names`() = runBlocking {
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(ScryfallProvider.PROVIDER_ID, "blb"),
				language = CardLanguage.FRENCH,
			),
		)

		assertTrue(vPage.cards.isNotEmpty(), "Bloomburrow should have French printings")
		val vCard = vPage.cards.first()
		assertEquals(CardLanguage.FRENCH, vCard.text.language)
		// `printed_name` is the localised one. If the mapper preferred `name`, every French card
		// would show an English title and the whole language feature would be cosmetic.
		assertTrue(
			vPage.cards.any { it.displayName != it.identity?.name },
			"At least one French card should show a printed name differing from the English one",
		)
	}

	@Test
	fun `Chinese comes back Chinese -- under Scryfall's own spelling of it`() = runBlocking {
		// The one language where Scryfall's tag and the app's disagree: `zhs` against `zh-cn`. Asking
		// for `lang:zh-cn` matches nothing, and Scryfall answers nothing with a 404 -- so a broken
		// mapping here does not fail loudly, it silently falls back to English.
		val vPage = provider().searchAllSets(
			CardSearchRequest(text = "Lightning Bolt",
				language = CardLanguage.SIMPLIFIED_CHINESE,
			),
		)

		assertTrue(vPage.cards.isNotEmpty(), "Lightning Bolt has Simplified Chinese printings")
		assertTrue(
			vPage.cards.any { it.text.language == CardLanguage.SIMPLIFIED_CHINESE },
			"A zhs record must be read back as Simplified Chinese, not left unmapped",
		)
	}

	@Test
	fun `every language the adapter declares is one Scryfall answers`() = runBlocking {
		// The capability list is a promise. This is the test that keeps it a measurement: each
		// language is asked for by itself, and a tag Scryfall rejects returns nothing at all.
		val vProvider = provider()
		for (vLanguage in vProvider.capabilities.data.languages) {
			val vPage = vProvider.searchAllSets(
				CardSearchRequest(text = "Forest", language = vLanguage),
			)
			assertTrue(
				vPage.cards.any { it.text.language == vLanguage },
				"Scryfall returned no ${vLanguage.displayName} printings of Forest",
			)
		}
	}

	@Test
	fun `a language with no printings falls back to English rather than erroring`() = runBlocking {
		// Scryfall answers a search that matches nothing with 404, not an empty list. Without the
		// fallback in `listCards`, asking for a language a set was never printed in would show an
		// error over a set that is perfectly browsable.
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(ScryfallProvider.PROVIDER_ID, "blb"),
				language = CardLanguage.KOREAN,
			),
		)

		assertTrue(vPage.cards.isNotEmpty(), "Should have fallen back rather than returning nothing")
		// And the fallback is labelled honestly: these are English records and say so.
		assertTrue(
			vPage.cards.all { it.text.language == CardLanguage.KOREAN || it.text.language == CardLanguage.ENGLISH },
			"Fallback records must state the language they actually are",
		)
	}

	@Test
	fun `finishes are exhaustive -- so absence is a real statement`() = runBlocking {
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(ScryfallProvider.PROVIDER_ID, "blb"),
				language = CardLanguage.ENGLISH,
			),
		)

		val vCard = vPage.cards.first { !it.finishes.isUnstated }
		assertTrue(vCard.finishes.confirmed.isNotEmpty())
		// The property that justifies populating `absent` here and nowhere else.
		assertTrue(
			vCard.finishes.confirmed.intersect(vCard.finishes.absent).isEmpty(),
			"A finish cannot be both confirmed and absent",
		)
	}

	@Test
	fun `cross-set search spans printings from many sets`() = runBlocking {
		val vPage = provider().searchAllSets(
			CardSearchRequest(text = "Lightning Bolt",
				language = CardLanguage.ENGLISH,
			),
		)

		assertTrue(vPage.cards.isNotEmpty())
		assertTrue(
			vPage.cards.map { it.setId }.distinct().size > 1,
			"Lightning Bolt has been printed in many sets",
		)
	}

	@Test
	fun `images are the WebP variants`() = runBlocking {
		val vPage = provider().listCards(
			CardPageRequest(
				setId = SourceId(ScryfallProvider.PROVIDER_ID, "blb"),
				language = CardLanguage.ENGLISH,
			),
		)

		val vArtwork = vPage.cards.first { it.artwork.thumbnailUrl != null }.artwork
		assertTrue(
			vArtwork.thumbnailUrl?.contains(".webp") == true,
			"Grid art should be WebP, got ${vArtwork.thumbnailUrl}",
		)
		assertTrue(
			vArtwork.displayUrl?.contains(".webp") == true,
			"Display art should be WebP, got ${vArtwork.displayUrl}",
		)
	}
	@Test
	fun `every paper set carries a set symbol that actually loads`() = runBlocking<Unit> {
		val vClient = mClient
		val vSets = ScryfallProvider(vClient).listSets()

		val vWithSymbol = vSets.count { it.symbol != null }
		assertEquals(vSets.size, vWithSymbol, "Every paper set should publish an icon")
		assertTrue(vSets.all { it.symbol?.isMonochrome == true })

		// The symbols are SVG and nothing else, so the app needs an SVG decoder registered or
		// every Magic set silently falls back to its code.
		val vUrl = vSets.first { it.symbol != null }.symbol!!.url
		// Scryfall appends a cache-busting query, so the extension is not at the end of the string.
		assertTrue(vUrl.substringBefore('?').endsWith(".svg"), "Expected an SVG, got $vUrl")
		val vResponse: HttpResponse = vClient.head(vUrl)
		assertEquals(HttpStatusCode.OK, vResponse.status, "Set symbol is not loading")
	}

	private companion object {

		/** One per JVM, so the throttle spans the class rather than one test method. */
		val mClient by lazy { HttpClientFactory.create(policy = ScryfallProvider.HTTP_POLICY) }
	}
}
