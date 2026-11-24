package eu.kanade.tachiyomi.ui.reader.translation

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import androidx.core.graphics.scale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Has Gemini find and translate the text of a page from its image, with the lines translated before in the same
 * chapter as context, so names, tone and running jokes carry over between pages. Blocking.
 */
class GeminiTextTranslator(
    private val client: OkHttpClient,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** The latest lines translated in each chapter, oldest first. */
    private val chapterLines = ConcurrentHashMap<Long, ArrayDeque<String>>()

    fun translate(bitmap: Bitmap, settings: TranslationSettings, chapterId: Long?): List<TranslatedText> {
        val lines = chapterId?.let { chapterLines.getOrPut(it) { ArrayDeque() } }
        val translations = mutableListOf<TranslatedText>()
        forEachTile(bitmap) { top, tile ->
            val context = lines?.let { synchronized(it) { it.joinToString("\n") } }.orEmpty()
            val bubbles = request(tile, settings, context)
            bubbles.mapNotNullTo(translations) { bubble ->
                val (yMin, xMin, yMax, xMax) = bubble.box.takeIf { it.size == 4 } ?: return@mapNotNullTo null
                val bounds = Rect(
                    tile.width * xMin / BOX_SCALE,
                    top + tile.height * yMin / BOX_SCALE,
                    tile.width * xMax / BOX_SCALE,
                    top + tile.height * yMax / BOX_SCALE,
                )
                val text = bubble.translation.trim()
                if (bounds.isEmpty || text.isEmpty()) null else TranslatedText(bounds, text)
            }
            if (lines != null) {
                synchronized(lines) {
                    bubbles.forEach { lines.addLast("${it.original.trim()} => ${it.translation.trim()}") }
                    while (lines.size > MAX_CONTEXT_LINES) lines.removeFirst()
                }
            }
        }
        return translations
    }

    private fun request(image: Bitmap, settings: TranslationSettings, context: String): List<Bubble> {
        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", prompt(settings, context)) }
                        addJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", "image/jpeg")
                                put("data", encode(image))
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("responseSchema", RESPONSE_SCHEMA)
            }
        }

        val request = Request.Builder()
            .url("$API_URL/${settings.geminiModel.trim()}:generateContent")
            .header("x-goog-api-key", settings.geminiApiKey.trim())
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val result = client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${errorMessage(text)}")
            json.decodeFromString<GenerateContentResponse>(text)
        }
        val answer = result.candidates.firstOrNull()?.content?.parts?.joinToString("") { it.text.orEmpty() }
            ?: return emptyList()
        return json.decodeFromString<List<Bubble>>(answer)
    }

    /**
     * The message of a Gemini error body, such as a model that doesn't exist or a used up free quota.
     */
    private fun errorMessage(body: String): String {
        val message = try {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }
        return (message ?: body).take(MAX_ERROR_LENGTH)
    }

    private fun prompt(settings: TranslationSettings, context: String): String {
        val from = Locale.forLanguageTag(settings.from.language).getDisplayName(Locale.ENGLISH)
        val to = Locale.forLanguageTag(settings.to).getDisplayName(Locale.ENGLISH)
        return buildString {
            append("This is a page of a manga or comic, mostly written in $from. ")
            append("Find every speech bubble, thought bubble, caption and narration box that holds text, ")
            append("leaving out sound effects drawn into the art. ")
            append("For each, give box_2d as [ymin, xmin, ymax, xmax] scaled to 0-1000, tightly around its text, ")
            append("the original text, and a natural $to translation that reads like a professional ")
            append("localization: keep character names, honorifics and tone consistent with the story so far. ")
            append("Keep translations about as long as the original so they fit the bubble.")
            if (context.isNotEmpty()) {
                append("\n\nLines translated earlier in this chapter, as original => translation:\n")
                append(context)
            }
        }
    }

    private fun encode(image: Bitmap): String {
        val scale = MAX_UPLOAD_SIZE.toFloat() / max(image.width, image.height)
        val upload = if (scale < 1f) {
            image.scale((image.width * scale).roundToInt(), (image.height * scale).roundToInt())
        } else {
            image
        }
        val bytes = ByteArrayOutputStream().use {
            upload.compress(Bitmap.CompressFormat.JPEG, UPLOAD_QUALITY, it)
            it.toByteArray()
        }
        if (upload !== image) upload.recycle()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    @Serializable
    private data class GenerateContentResponse(val candidates: List<Candidate> = emptyList())

    @Serializable
    private data class Candidate(val content: Content? = null)

    @Serializable
    private data class Content(val parts: List<Part> = emptyList())

    @Serializable
    private data class Part(val text: String? = null)

    @Serializable
    private data class Bubble(
        @SerialName("box_2d") val box: List<Int>,
        val original: String = "",
        val translation: String = "",
    )

    private companion object {
        const val MAX_ERROR_LENGTH = 160
        const val API_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        const val BOX_SCALE = 1000
        const val MAX_UPLOAD_SIZE = 1536
        const val UPLOAD_QUALITY = 85
        const val MAX_CONTEXT_LINES = 80

        val RESPONSE_SCHEMA = buildJsonObject {
            put("type", "ARRAY")
            putJsonObject("items") {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("box_2d") {
                        put("type", "ARRAY")
                        putJsonObject("items") { put("type", "INTEGER") }
                    }
                    putJsonObject("original") { put("type", "STRING") }
                    putJsonObject("translation") { put("type", "STRING") }
                }
                putJsonArray("required") {
                    add("box_2d")
                    add("original")
                    add("translation")
                }
            }
        }
    }
}
