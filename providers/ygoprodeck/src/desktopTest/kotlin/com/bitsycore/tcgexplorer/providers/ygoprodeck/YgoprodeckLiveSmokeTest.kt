package com.bitsycore.tcgexplorer.providers.ygoprodeck

import com.bitsycore.tcgexplorer.core.model.CardLanguage
import com.bitsycore.tcgexplorer.core.model.SourceId
import com.bitsycore.tcgexplorer.core.provider.CardPageRequest
import com.bitsycore.tcgexplorer.data.net.HttpClientFactory
import io.ktor.client.request.head
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Talks to the real YGOPRODeck API.
 *
 * **Not part of the ordinary test run.** Run it deliberately:
 *
 * ```
 * ./gradlew :providers:ygoprodeck:liveProviderTest
 * ```
 */
class YgoprodeckLiveSmokeTest {

	@Test
	fun `sets that publish box art carry it as a symbol`() = runBlocking<Unit> {
		val vClient = HttpClientFactory.create(policy = YgoprodeckProvider.HTTP_POLICY)
		val vSets = YgoprodeckProvider(vClient).listSets()

		val vWith = vSets.filter { it.symbol != null }
		assertTrue(vWith.isNotEmpty(), "Some sets should publish box art")
		assertTrue(vWith.none { it.symbol!!.isMonochrome }, "These are full-colour JPEGs")

		val vResponse: HttpResponse = vClient.head(vWith.first().symbol!!.url)
		assertEquals(HttpStatusCode.OK, vResponse.status, "Set image is not loading")
	}

	private companion object {

		/** One per JVM, so the 50 ms throttle spans the class rather than one test method. */
		val mProvider by lazy {
			YgoprodeckProvider(HttpClientFactory.create(policy = YgoprodeckProvider.HTTP_POLICY))
		}
	}

}
