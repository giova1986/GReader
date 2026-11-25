package eu.kanade.tachiyomi.ui.reader.translation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Has a language model chosen on OpenRouter, with the user's key, translate the text read on the device, with the
 * lines translated before in the same chapter as context. Only text is sent, so any model can be used. Blocking.
 */
class OpenRouterTextTranslator(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    /** The latest lines translated in each chapter, oldest first. */
    private val chapterLines = ConcurrentHashMap<Long, ArrayDeque<String>>()

    /**
     * [texts] translated, in their order, and the names the model found with how it translated them.
     */
    fun translate(
        texts: List<String>,
        settings: TranslationSettings,
        chapterId: Long?,
        glossary: Glossary,
    ): Pair<List<String>, Map<String, String>> {
        if (settings.openRouterModel.isBlank()) throw IOException("No OpenRouter model chosen")
        val lines = chapterId?.let { chapterLines.getOrPut(it) { ArrayDeque() } }
        val context = lines?.let { synchronized(it) { it.joinToString("\n") } }.orEmpty()

        val body = buildJsonObject {
            put("model", settings.openRouterModel.trim())
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", prompt(settings, context, glossary))
                }
                addJsonObject {
                    put("role", "user")
                    put("content", json.encodeToString(texts))
                }
            }
            putJsonObject("response_format") { put("type", "json_object") }
        }
        val request = Request.Builder()
            .url(API_URL)
            .header("Authorization", "Bearer ${settings.openRouterApiKey.trim()}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val answer = OnlineLimiter.run {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                rateLimitOf(response, text)?.let { throw it }
                if (!response.isSuccessful) throw IOException(apiErrorMessage(text) ?: "HTTP ${response.code}")
                json.decodeFromString<ChatResponse>(text).choices.firstOrNull()?.message?.content.orEmpty()
            }
        }
        // Not every model keeps to plain JSON, some wrap it in a code block
        val result = json.decodeFromString<Result>(answer.substringAfter('{').substringBeforeLast('}').let { "{$it}" })

        val translations = texts.indices.map { result.translations.getOrNull(it).orEmpty() }
        if (lines != null) {
            synchronized(lines) {
                texts.zip(translations).forEach { (original, translation) ->
                    lines.addLast("$original => $translation")
                }
                while (lines.size > MAX_CONTEXT_LINES) lines.removeFirst()
            }
        }
        return translations to result.names.associate { it.original.trim() to it.translation.trim() }
    }

    private fun prompt(settings: TranslationSettings, context: String, glossary: Glossary): String {
        val from = Locale.forLanguageTag(settings.from.language).getDisplayName(Locale.ENGLISH)
        val to = Locale.forLanguageTag(settings.to).getDisplayName(Locale.ENGLISH)
        return buildString {
            append("You translate manga and comics from $from to $to like a professional localizer. ")
            append("The user sends a JSON array with the text of each speech bubble of a page, read by OCR, so ")
            append("fix obvious reading mistakes. Answer only with a JSON object: \"translations\", an array with ")
            append("the natural $to translation of each bubble in the same order, about as long as the original, ")
            append("and \"names\", an array of {\"original\", \"translation\"} for every character, place or other ")
            append("proper name. Keep names, honorifics and tone consistent with the story so far.")
            if (glossary.terms.isNotEmpty()) {
                append("\n\nAlways translate these names and terms as given, as original = translation:\n")
                append(glossary.format())
            }
            if (context.isNotEmpty()) {
                append("\n\nLines translated earlier in this chapter, as original => translation:\n")
                append(context)
            }
        }
    }

    @Serializable
    private data class ChatResponse(val choices: List<Choice> = emptyList())

    @Serializable
    private data class Choice(val message: Message? = null)

    @Serializable
    private data class Message(val content: String? = null)

    @Serializable
    private data class Result(val translations: List<String> = emptyList(), val names: List<Name> = emptyList())

    @Serializable
    private data class Name(val original: String = "", val translation: String = "")

    private companion object {
        const val API_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val MAX_CONTEXT_LINES = 80
    }
}

/**
 * The ids of the models on OpenRouter, free ones first, as any of them can translate text. Blocking.
 */
fun fetchOpenRouterModels(client: OkHttpClient): List<String> {
    val request = Request.Builder().url(OPENROUTER_MODELS_URL).build()
    val models = client.newCall(request).execute().use { response ->
        val text = response.body.string()
        if (!response.isSuccessful) throw IOException(apiErrorMessage(text) ?: "HTTP ${response.code}")
        modelsJson.decodeFromString<ModelList>(text).data.map { it.id }
    }
    return models.sortedWith(compareBy<String> { !it.endsWith(":free") }.thenBy { it })
}

@Serializable
private data class ModelList(val data: List<ModelId> = emptyList())

@Serializable
private data class ModelId(val id: String)

private val modelsJson = Json { ignoreUnknownKeys = true }

private const val OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models"
