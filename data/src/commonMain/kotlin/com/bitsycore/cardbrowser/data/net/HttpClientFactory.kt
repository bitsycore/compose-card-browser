package com.bitsycore.cardbrowser.data.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

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
	fun create(policy: ProviderHttpPolicy = ProviderHttpPolicy()): HttpClient = HttpClient {
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
	val defaultHeaders: Map<String, String> = emptyMap(),
) {

	companion object {

		const val DEFAULT_USER_AGENT: String = "CardBrowser/1.0 (+https://github.com/bitsycore)"
	}
}
