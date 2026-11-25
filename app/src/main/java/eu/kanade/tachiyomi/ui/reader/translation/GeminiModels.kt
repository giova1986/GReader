package eu.kanade.tachiyomi.ui.reader.translation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * The ids of the Gemini models [apiKey] can use to translate a page, newest first, from Google's own list so it
 * follows new models and retired ones. Blocking.
 */
fun fetchGeminiModels(client: OkHttpClient, apiKey: String): List<String> {
    val ids = mutableListOf<String>()
    var pageToken: String? = null
    do {
        val url = buildString {
            append("$MODELS_URL?pageSize=1000")
            pageToken?.let { append("&pageToken=$it") }
        }
        val request = Request.Builder().url(url).header("x-goog-api-key", apiKey.trim()).build()
        val page = client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IOException(errorOf(text) ?: "HTTP ${response.code}")
            json.decodeFromString<ModelPage>(text)
        }
        page.models
            .filter { model ->
                "generateContent" in model.supportedGenerationMethods &&
                    model.name.startsWith("models/gemini") &&
                    NOT_FOR_PAGES.none { it in model.name }
            }
            .mapTo(ids) { it.name.removePrefix("models/") }
        pageToken = page.nextPageToken
    } while (!pageToken.isNullOrEmpty())
    return ids.distinct().sortedDescending()
}

/** Google's own reason, such as an invalid key, which tells the user more than the HTTP status. */
private fun errorOf(body: String): String? {
    return try {
        json.decodeFromString<ErrorBody>(body).error?.message?.take(MAX_ERROR_LENGTH)
    } catch (_: Exception) {
        null
    }
}

@Serializable
private data class ErrorBody(val error: Error? = null) {
    @Serializable
    data class Error(val message: String? = null)
}

@Serializable
private data class ModelPage(
    val models: List<Model> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
private data class Model(
    val name: String,
    val supportedGenerationMethods: List<String> = emptyList(),
)

private val json = Json { ignoreUnknownKeys = true }

private const val MAX_ERROR_LENGTH = 160

private const val MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models"

/** Parts of the ids of models that generate content but can't read a page and answer in text. */
private val NOT_FOR_PAGES = listOf("embedding", "tts", "image", "audio", "live", "robotics", "computer-use")
