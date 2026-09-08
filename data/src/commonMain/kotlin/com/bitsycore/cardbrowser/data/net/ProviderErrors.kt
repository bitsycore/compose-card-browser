package com.bitsycore.cardbrowser.data.net

import com.bitsycore.cardbrowser.core.provider.ProviderError
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.http.HttpHeaders
import io.ktor.serialization.ContentConvertException
import kotlinx.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/**
 * Runs [block] and translates anything it throws into a [ProviderError].
 *
 * Shared by every adapter so that Ktor's exception vocabulary stops at the provider boundary and
 * the layers above deal in one closed set of failures.
 *
 * [CancellationException] is rethrown untouched and first. A cancelled request is not a failed one:
 * when the user changes set, the in-flight load for the previous set unwinds through here, and
 * turning that into a `ProviderError` would paint an error over the screen they just opened.
 *
 * @param operation named in [ProviderError.Unknown] so an unexpected failure says where it happened
 */
suspend inline fun <T> mapProviderErrors(
	operation: String,
	crossinline block: suspend () -> T,
): T = try {
	block()
} catch (vCancellation: CancellationException) {
	throw vCancellation
} catch (vAlready: ProviderError) {
	// An adapter that classified something itself is not second-guessed.
	throw vAlready
} catch (vTimeout: HttpRequestTimeoutException) {
	throw ProviderError.Timeout(vTimeout)
} catch (vClient: ClientRequestException) {
	val vStatus = vClient.response.status.value
	if (vStatus == HTTP_TOO_MANY_REQUESTS) {
		throw ProviderError.RateLimited(
			retryAfterSeconds = vClient.response.headers[HttpHeaders.RetryAfter]?.toLongOrNull(),
			cause = vClient,
		)
	}
	throw ProviderError.BadRequest(vStatus, vClient)
} catch (vServer: ServerResponseException) {
	throw ProviderError.ServerError(vServer.response.status.value, vServer)
} catch (vConvert: ContentConvertException) {
	// Ktor's ContentNegotiation wraps a decoding failure in its own exception, which is *not* a
	// SerializationException -- so without this branch unreadable JSON was falling through to
	// Unknown and being reported to the user as "something went wrong" rather than as a bad
	// response from the provider.
	throw ProviderError.MalformedResponse(vConvert.message ?: "unreadable response", vConvert)
} catch (vSerialization: SerializationException) {
	// The provider answered with something the mapping does not understand. Distinct from a
	// transport failure because retrying it is pointless.
	throw ProviderError.MalformedResponse(vSerialization.message ?: "unreadable JSON", vSerialization)
} catch (vIo: IOException) {
	// Ktor reports a dead socket, an unresolvable host and a dropped connection all as IOException.
	throw ProviderError.Offline(vIo)
} catch (vOther: Exception) {
	throw ProviderError.Unknown("$operation failed: ${vOther.message ?: vOther::class.simpleName}", vOther)
}

/** Public only because [mapProviderErrors] is `inline` and reads it. Not part of the API. */
@PublishedApi
internal const val HTTP_TOO_MANY_REQUESTS: Int = 429
