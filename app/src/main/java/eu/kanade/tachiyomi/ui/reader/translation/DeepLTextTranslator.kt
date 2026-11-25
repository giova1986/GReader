package eu.kanade.tachiyomi.ui.reader.translation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale

/**
 * Has DeepL translate the text read on the device, with the user's key. Blocking.
 */
class DeepLTextTranslator(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    /** [texts] translated, in their order. */
    fun translate(texts: List<String>, settings: TranslationSettings): List<String> {
        val key = settings.deeplApiKey.trim()
        val body = buildJsonObject {
            putJsonArray("text") { texts.forEach { add(it) } }
            put("source_lang", settings.from.language.uppercase(Locale.ROOT))
            put("target_lang", targetOf(settings.to))
        }
        // Keys of the free plan only work on its own host
        val host = if (key.endsWith(":fx")) FREE_URL else PRO_URL
        val request = Request.Builder()
            .url(host)
            .header("Authorization", "DeepL-Auth-Key $key")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val translations = client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IOException(apiErrorMessage(text) ?: "HTTP ${response.code}")
            json.decodeFromString<Response>(text).translations.map { it.text }
        }
        return texts.indices.map { translations.getOrNull(it).orEmpty() }
    }

    /**
     * DeepL's code for the ML Kit language code [language], which for some languages names the variant.
     */
    private fun targetOf(language: String): String {
        return when (language) {
            "en" -> "EN-US"
            "pt" -> "PT-PT"
            "zh" -> "ZH-HANS"
            else -> language.uppercase(Locale.ROOT)
        }
    }

    @Serializable
    private data class Response(val translations: List<Translation> = emptyList())

    @Serializable
    private data class Translation(val text: String = "")

    private companion object {
        const val FREE_URL = "https://api-free.deepl.com/v2/translate"
        const val PRO_URL = "https://api.deepl.com/v2/translate"
    }
}
