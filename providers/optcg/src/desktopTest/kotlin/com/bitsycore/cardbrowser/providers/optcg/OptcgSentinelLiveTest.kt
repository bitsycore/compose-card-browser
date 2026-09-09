package com.bitsycore.cardbrowser.providers.optcg

import com.bitsycore.cardbrowser.core.model.SourceId
import com.bitsycore.cardbrowser.core.provider.CardPageRequest
import com.bitsycore.cardbrowser.data.net.HttpClientFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The six sets that would not load, against the live API.
 *
 * **Not part of the ordinary test run.** Run it with
 * `./gradlew :providers:optcg:liveProviderTest`.
 *
 * Each of these carries at least one Leader whose `life` is the literal string `"NULL"`, which the
 * DTO declared as `Int?`. Lenient parsing accepts a quoted `"5"` and cannot do anything with
 * `"NULL"`, so the whole response failed to deserialise and the set reported a server error.
 */
class OptcgSentinelLiveSmokeTest {

	private fun provider() = OptcgProvider(HttpClientFactory.create())

	@Test
	fun `every set that carried a NULL life now loads`() = runBlocking {
		val vProvider = provider()

		for (vSet in listOf("OP-02", "OP-03", "OP-04", "OP-05", "OP-07", "OP-08")) {
			val vPage = vProvider.listCards(
				CardPageRequest(setId = SourceId(OptcgProvider.PROVIDER_ID, vSet), pageSize = 1000),
			)

			assertTrue(vPage.cards.size > 100, "$vSet came back with ${vPage.cards.size} cards")
			assertTrue(
				vPage.cards.none { it.text.rules == "NULL" },
				"$vSet still shows the literal string NULL as rules text",
			)
			assertTrue(
				vPage.cards.any { it.attributes.secondary != null },
				"$vSet should have Leaders with a real life total",
			)
		}
	}
}
