package eu.kanade.tachiyomi.ui.reader.translation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * An online engine refused a request as too many were sent, and asks to wait [retryAfterMillis].
 */
class RateLimitedException(val retryAfterMillis: Long, message: String) : IOException(message)

/**
 * The [RateLimitedException] for [response] when it's a refusal for too many requests, with [body] as read.
 */
internal fun rateLimitOf(response: Response, body: String): RateLimitedException? {
    if (response.code != HTTP_TOO_MANY_REQUESTS) return null
    val retryAfter = response.header("Retry-After")?.toLongOrNull()?.times(1000)
        ?: retryDelayOf(body)
        ?: DEFAULT_RETRY_MILLIS
    return RateLimitedException(retryAfter, apiErrorMessage(body) ?: "HTTP $HTTP_TOO_MANY_REQUESTS")
}

/**
 * The wait Gemini gives in its error details, such as `"retryDelay": "37s"`.
 */
private fun retryDelayOf(body: String): Long? {
    return try {
        val details = (Json.parseToJsonElement(body) as? JsonObject)
            ?.let { it["error"] as? JsonObject }
            ?.let { it["details"] as? JsonArray }
            ?: return null
        details.firstNotNullOfOrNull { detail ->
            (detail as? JsonObject)?.get("retryDelay")?.jsonPrimitive?.content
                ?.removeSuffix("s")?.toDoubleOrNull()?.times(1000)?.toLong()
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Sends the requests of the online engines one at a time and spaced out, as the reader translates pages ahead and
 * downloads translate whole chapters, which would otherwise run into the free tiers' requests per minute. A request
 * refused for that is sent again after the wait asked for when it's short, and online engines are left alone for
 * a longer one, so pages are translated on the device meanwhile rather than refused again.
 */
internal object OnlineLimiter {

    private val lock = ReentrantLock(true)
    private var lastRequest = 0L

    @Volatile
    private var pausedUntil = 0L

    fun <T> run(request: () -> T): T {
        throwIfPaused()
        lock.withLock {
            throwIfPaused()
            val wait = lastRequest + MIN_INTERVAL_MILLIS - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            var attempt = 0
            while (true) {
                try {
                    return request()
                } catch (e: RateLimitedException) {
                    if (attempt >= MAX_RETRIES || e.retryAfterMillis > MAX_WAIT_MILLIS) {
                        pausedUntil = System.currentTimeMillis() + e.retryAfterMillis
                        throw e
                    }
                    attempt++
                    Thread.sleep(e.retryAfterMillis)
                } finally {
                    lastRequest = System.currentTimeMillis()
                }
            }
        }
    }

    private fun throwIfPaused() {
        val wait = pausedUntil - System.currentTimeMillis()
        if (wait > 0) throw RateLimitedException(wait, "Waiting for the request limit")
    }

    private const val MIN_INTERVAL_MILLIS = 4_000L
    private const val MAX_WAIT_MILLIS = 20_000L
    private const val MAX_RETRIES = 2
}

private const val HTTP_TOO_MANY_REQUESTS = 429
private const val DEFAULT_RETRY_MILLIS = 30_000L
