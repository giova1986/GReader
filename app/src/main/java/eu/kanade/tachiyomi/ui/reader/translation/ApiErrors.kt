package eu.kanade.tachiyomi.ui.reader.translation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The reason a translation API gives in an error [body], such as an invalid key or a used up quota, which tells the
 * user more than the HTTP status, or null when it gives none.
 */
internal fun apiErrorMessage(body: String): String? {
    val root = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return null
    val message = (root["error"] as? JsonObject)?.get("message") ?: root["message"]
    return try {
        message?.jsonPrimitive?.content?.take(MAX_ERROR_LENGTH)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private const val MAX_ERROR_LENGTH = 160
