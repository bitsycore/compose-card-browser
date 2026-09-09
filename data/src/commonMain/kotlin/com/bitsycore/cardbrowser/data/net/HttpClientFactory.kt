package com.bitsycore.cardbrowser.data.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The one HTTP stack the app has.
 *
 * Shared by every provider adapter and by the image loader, so there is a single place that sets a
 * User-Agent, a single timeout policy and a single retry policy. A provider needing different
 * behaviour gets it by configuring its own client from [create] rather than by building one from
 * scratch -- see [ProviderHttpPolicy].
 *
 * The engine is not named here. Each platform source set puts exactly one Ktor engine on the
 * classpath (OkHttp on Android, Darwin on iOS, Java on desktop) and Ktor selects it, which keeps
 * this file free of expect/actual for something no caller cares about.
 */
object HttpClientFactory {

	/**
	 * Lenient on purpose.
	 *
	 * `ignoreUnknownKeys` because a provider that adds a field must not break a shipped app --
	 * Riftcodex describes itself as "an active work in progress". `explicitNulls = false` so a DTO
	 * default is used when a key is absent rather than the parse failing. `coerceInputValues` turns
	 * an unexpected null in a non-null position into the declared default for the same reason.
	 */
	val json: Json = Json {
		ignoreUnknownKeys = true
		explicitNulls = false
		coerceInputValues = true
		isLenient = true
	}

	/**
	 * Builds the shared client.
	 *
	 * @param policy per-provider knobs. The default is deliberately conservative: providers here
	 *   are free community APIs and the app is a browser, not a scraper
	 */
	fun create(
		policy: ProviderHttpPolicy = ProviderHttpPolicy(),
		stats: ApiCallStats? = null,
	): HttpClient = HttpClient {
		// Let non-2xx statuses come back as responses rather than exceptions where a caller wants
		// to read the code, and as exceptions where it does not. `mapProviderErrors` handles both.
		expectSuccess = true

		install(ContentNegotiation) {
			json(json)
		}

		install(HttpTimeout) {
			requestTimeoutMillis = policy.requestTimeoutMillis
			connectTimeoutMillis = policy.connectTimeoutMillis
			socketTimeoutMillis = policy.socketTimeoutMillis
		}

		install(UserAgent) {
			// Providers here are volunteer-run. An honest, contactable User-Agent is the least a
			// client owes them, and several community APIs ask for one explicitly.
			agent = policy.userAgent
		}

		// Counted before anything else, so the number is what left the device rather than what a
		// caller asked for -- retries included, because a retried timeout really did cost two.
		if (stats != null) install(requestCounter(stats))

		// A minimum gap between requests, where a provider documents one.
		//
		// Not installed at all when the interval is zero, which is the default -- and which the
		// *shared* client keeps, because the image loader uses it. Throttling that would serialise
		// every thumbnail in a grid behind a 100 ms queue.
		if (policy.minRequestInterval > Duration.ZERO) {
			install(requestThrottle(policy.minRequestInterval))
		}

		install(HttpRequestRetry) {
			// Bounded, and only for failures where the same request could plausibly succeed later.
			// A 4xx is never retried: it would be the same answer and wasted traffic against a
			// provider we are asked to respect.
			maxRetries = policy.maxRetries
			retryOnServerErrors(maxRetries = policy.maxRetries)
			retryOnExceptionIf { _, vCause -> vCause is io.ktor.client.plugins.HttpRequestTimeoutException }
			exponentialDelay(base = policy.retryBackoffBase, maxDelayMs = policy.retryMaxDelayMillis)
			// Honour a Retry-After when the provider sends one, rather than backing off blind.
			modifyRequest { it.headers.remove("Retry-After") }
		}

		defaultRequest {
			policy.defaultHeaders.forEach { (vName, vValue) -> headers.append(vName, vValue) }
		}
	}
}

/** Counts every request this client sends, by host. See [ApiCallStats] for why by host. */
private fun requestCounter(stats: ApiCallStats) = createClientPlugin("RequestCounter") {
	onRequest { vRequest, _ -> stats.record(vRequest.url.host) }
}

/**
 * A plugin that keeps at least [minInterval] between the requests one client sends.
 *
 * Serialising is the point: requests queue through a mutex and each waits out whatever is left of
 * the interval since the last one went. That is what a documented "please leave 100 ms between
 * requests" asks for, and it cannot be done by delaying each request independently -- three
 * coroutines each sleeping 100 ms in parallel still send three requests at once.
 *
 * Monotonic rather than wall-clock, so a clock adjustment cannot make the gap negative or enormous.
 */
private fun requestThrottle(minInterval: Duration) = createClientPlugin("RequestThrottle") {
	val vLock = Mutex()
	var vLastSentAt: TimeSource.Monotonic.ValueTimeMark? = null

	onRequest { _, _ ->
		vLock.withLock {
			vLastSentAt?.let { vMark ->
				val vRemaining = minInterval - vMark.elapsedNow()
				if (vRemaining.isPositive()) delay(vRemaining)
			}
			vLastSentAt = TimeSource.Monotonic.markNow()
		}
	}
}

/**
 * Per-provider HTTP behaviour.
 *
 * Exists so a provider with a documented rate limit or a required header can say so without the
 * shared factory growing a branch per provider. Riftcodex uses the defaults: it documents no rate
 * limit and returns no rate-limit headers, which is not a licence to hammer it.
 */
data class ProviderHttpPolicy(
	val userAgent: String = DEFAULT_USER_AGENT,
	val requestTimeoutMillis: Long = 20_000,
	val connectTimeoutMillis: Long = 10_000,
	val socketTimeoutMillis: Long = 20_000,
	/** Total extra attempts, not total attempts. Three is enough to ride out a blip. */
	val maxRetries: Int = 2,
	val retryBackoffBase: Double = 2.0,
	val retryMaxDelayMillis: Long = 4_000,
	/**
	 * The least time to leave between two requests from the same client. Zero disables it.
	 *
	 * Only set by a provider whose terms ask for a gap. A client that carries one must not be the
	 * shared client: the image loader uses that, and a grid of thumbnails would queue behind it.
	 */
	val minRequestInterval: Duration = Duration.ZERO,
	val defaultHeaders: Map<String, String> = emptyMap(),
) {

	companion object {

		const val DEFAULT_USER_AGENT: String = "CardBrowser/1.0 (+https://github.com/bitsycore)"

		/** Scryfall's documented ask: an identifying User-Agent and 50-100 ms between requests. */
		val SCRYFALL: ProviderHttpPolicy = ProviderHttpPolicy(
			userAgent = "CardBrowser/1.0 (github.com/bitsycore)",
			minRequestInterval = 100.milliseconds,
		)

		/**
		 * YGOPRODeck's documented ceiling is 20 requests per second.
		 *
		 * 50 ms is that ceiling exactly. The app is nowhere near it -- a set is one request per
		 * page -- so this is a guard rather than a brake.
		 */
		val YGOPRODECK: ProviderHttpPolicy = ProviderHttpPolicy(minRequestInterval = 50.milliseconds)
	}
}
