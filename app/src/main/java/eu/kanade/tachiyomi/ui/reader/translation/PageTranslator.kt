package eu.kanade.tachiyomi.ui.reader.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.OkHttpClient
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Translates the text of pages with the engine the settings pick, painting each translation over its text on a
 * copy of the image, and keeps translated pages in [directory] so a page is translated once, ahead of reading for
 * downloaded chapters. Blocking, so never call it on the main thread.
 */
class PageTranslator(
    context: Context,
    client: OkHttpClient,
    private val glossaries: SeriesGlossaries,
) : Closeable {

    private val directory = File(context.filesDir, "translation")
    private val mlKit = MlKitTextTranslator()
    private val gemini = GeminiTextTranslator(client)
    private val openRouter = OpenRouterTextTranslator(client)
    private val deepL = DeepLTextTranslator(client)
    private val detector = BubbleDetector(client, directory)
    private val mangaOcr = MangaOcr(client, directory)
    private val painter = BubblePainter(
        ResourcesCompat.getFont(context, R.font.comic_neue_bold) ?: Typeface.DEFAULT_BOLD,
    )
    private val pages = File(directory, "pages")

    private val mutableProblems = MutableSharedFlow<TranslationProblem>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val problems: SharedFlow<TranslationProblem> = mutableProblems

    /** Bumped when translated pages change without the settings changing, so the viewers show them again. */
    val revision = MutableStateFlow(0)

    /** The saved translation of each page of a chapter that was last shown, for it to be corrected. */
    private val shown = ConcurrentHashMap<Pair<Long, Int>, String>()

    /**
     * [image] translated when [settings] enable it, or [image] itself when it can't be: an animated image, or a
     * failure such as the language model not downloading while offline, so the page still shows. A page of
     * [chapter] gets the chapter's earlier pages as context where the engine uses it, and its series' glossary.
     * Giving the [pageIndex] lets its translation be corrected.
     */
    fun translatePage(
        image: BufferedSource,
        settings: TranslationSettings,
        chapter: Chapter?,
        pageIndex: Int? = null,
    ): BufferedSource {
        if (!settings.enabled || ImageUtil.isAnimatedAndSupported(image)) return image
        val hash = image.peek().readByteString().sha256().hex()
        val name = "${chapter?.mangaId ?: 0}-$hash-${settings.pageKey()}"
        if (chapter != null && pageIndex != null) shown[chapter.id to pageIndex] = name
        val saved = File(pages, "$name.jpg")
        val texts = File(pages, "$name.json")
        if (saved.exists()) {
            saved.setLastModified(System.currentTimeMillis())
            texts.setLastModified(System.currentTimeMillis())
            return Buffer().apply { saved.source().use { writeAll(it) } }
        }
        return try {
            val options = BitmapFactory.Options().apply { inMutable = true }
            val bitmap = BitmapFactory.decodeStream(image.peek().inputStream(), null, options) ?: return image
            try {
                // A page translated before, or corrected since, is painted again without asking an engine
                val (translations, complete) = readTexts(texts)?.let { it to true }
                    ?: translate(bitmap, settings, chapter)
                if (translations.isEmpty()) {
                    mutableProblems.tryEmit(TranslationProblem.NoText)
                    return image
                }
                painter.paint(bitmap, translations)
                val translated = Buffer().also {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it.outputStream())
                }
                if (complete) save(translated, saved, translations, texts)
                translated
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to translate page" }
            mutableProblems.tryEmit(TranslationProblem.Failed(e.message ?: e.javaClass.simpleName))
            image
        }
    }

    /**
     * The translations of the page at [pageIndex] of [chapterId] as last shown, or null when it wasn't translated.
     */
    fun translationsOf(chapterId: Long, pageIndex: Int): List<TranslatedText>? {
        val name = shown[chapterId to pageIndex] ?: return null
        return readTexts(File(pages, "$name.json"))
    }

    /**
     * Shows the page at [pageIndex] of [chapterId] with [translations] the user corrected.
     */
    fun correct(chapterId: Long, pageIndex: Int, translations: List<TranslatedText>) {
        val name = shown[chapterId to pageIndex] ?: return
        writeTexts(File(pages, "$name.json"), translations)
        File(pages, "$name.jpg").delete()
        revision.update { it + 1 }
    }

    /**
     * Drops the translated pages of [mangaId], so they're translated again with its changed glossary.
     */
    fun forget(mangaId: Long) {
        pages.listFiles().orEmpty().filter { it.name.startsWith("$mangaId-") }.forEach(File::delete)
        revision.update { it + 1 }
    }

    override fun close() {
        mlKit.close()
        detector.close()
        mangaOcr.close()
    }

    /**
     * The translations of [bitmap], and whether they were made as [settings] ask, rather than on the device after
     * Gemini failed, so a page is only kept when asking again wouldn't do better.
     */
    private fun translate(
        bitmap: Bitmap,
        settings: TranslationSettings,
        chapter: Chapter?,
    ): Pair<List<TranslatedText>, Boolean> {
        val glossary = chapter?.let { glossaries.of(it.mangaId) } ?: Glossary.EMPTY
        val regions by lazy { regions(bitmap, settings) }
        val texts by lazy { read(bitmap, settings, regions) }
        if (settings.engine == TranslationEngine.ML_KIT) {
            return mlKit.translate(texts, settings, glossary) to true
        }
        return try {
            translateOnline(bitmap, settings, chapter, glossary, regions) { texts } to true
        } catch (e: Exception) {
            // Out of free quota or offline, the page is still worth translating on the device
            logcat(LogPriority.WARN, e) { "${settings.engine} failed, translating with ML Kit" }
            val reason = e.message ?: e.javaClass.simpleName
            mutableProblems.tryEmit(TranslationProblem.OnlineFailed(settings.engine, reason))
            mlKit.translate(texts, settings, glossary) to false
        }
    }

    private fun translateOnline(
        bitmap: Bitmap,
        settings: TranslationSettings,
        chapter: Chapter?,
        glossary: Glossary,
        regions: List<TextRegion>,
        readTexts: () -> List<TranslatedText>,
    ): List<TranslatedText> {
        return when (settings.engine) {
            TranslationEngine.ML_KIT -> error("ML Kit is not online")
            TranslationEngine.GEMINI -> {
                val (found, names) = gemini.translate(bitmap, settings, chapter?.id, glossary)
                chapter?.let { glossaries.learn(it.mangaId, names) }
                inBubbles(found, regions)
            }
            TranslationEngine.OPENROUTER -> {
                val texts = readTexts()
                if (texts.isEmpty()) return emptyList()
                val originals = texts.map { it.text }
                val (translations, names) = openRouter.translate(originals, settings, chapter?.id, glossary)
                chapter?.let { glossaries.learn(it.mangaId, names) }
                withTranslations(texts, translations)
            }
            TranslationEngine.DEEPL -> {
                val texts = readTexts()
                if (texts.isEmpty()) return emptyList()
                withTranslations(texts, deepL.translate(texts.map { glossary.applyTo(it.text) }, settings))
            }
        }
    }

    private fun withTranslations(texts: List<TranslatedText>, translations: List<String>): List<TranslatedText> {
        return texts.zip(translations).mapNotNull { (text, translation) ->
            translation.trim().takeIf { it.isNotEmpty() }?.let { text.copy(text = it) }
        }
    }

    /**
     * Keeps [page] as [file] and its [translations] as [texts], then drops the pages read longest ago once they
     * take more than [MAX_SAVED_BYTES].
     */
    private fun save(page: BufferedSource, file: File, translations: List<TranslatedText>, texts: File) {
        pages.mkdirs()
        writeTexts(texts, translations)
        val partial = File(pages, "${file.name}.part")
        partial.sink().buffer().use { it.writeAll(page.peek()) }
        partial.renameTo(file)

        val saved = pages.listFiles().orEmpty().sortedByDescending(File::lastModified)
        var total = 0L
        saved.forEach {
            total += it.length()
            if (total > MAX_SAVED_BYTES) it.delete()
        }
    }

    private fun readTexts(file: File): List<TranslatedText>? {
        if (!file.exists()) return null
        return try {
            json.decodeFromString<List<SavedText>>(file.readText()).map(SavedText::toTranslatedText)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not read a saved translation" }
            null
        }
    }

    private fun writeTexts(file: File, translations: List<TranslatedText>) {
        pages.mkdirs()
        file.writeText(json.encodeToString(translations.map(SavedText::of)))
    }

    @Serializable
    private data class SavedText(
        val bounds: List<Int>,
        val text: String,
        val original: String = "",
        val lines: List<List<Int>> = emptyList(),
        val bubble: List<Int>? = null,
    ) {
        fun toTranslatedText(): TranslatedText {
            val bounds = rectOf(bounds)
            return TranslatedText(
                bounds = bounds,
                text = text,
                original = original,
                lines = lines.map(::rectOf).ifEmpty { listOf(bounds) },
                bubble = bubble?.let(::rectOf),
            )
        }

        companion object {
            fun of(translation: TranslatedText) = SavedText(
                bounds = valuesOf(translation.bounds),
                text = translation.text,
                original = translation.original,
                lines = translation.lines.map(::valuesOf),
                bubble = translation.bubble?.let(::valuesOf),
            )

            private fun valuesOf(rect: Rect) = listOf(rect.left, rect.top, rect.right, rect.bottom)

            private fun rectOf(values: List<Int>) = Rect(values[0], values[1], values[2], values[3])
        }
    }

    /**
     * What a translated page depends on besides the image and the glossary, so changing any of it translates pages
     * again.
     */
    private fun TranslationSettings.pageKey(): String {
        val engineKey = when (engine) {
            TranslationEngine.ML_KIT -> "mlkit-$mangaOcr"
            TranslationEngine.GEMINI -> "gemini-${geminiModel.trim()}"
            TranslationEngine.OPENROUTER -> "openrouter-${openRouterModel.trim()}-$mangaOcr"
            TranslationEngine.DEEPL -> "deepl-$mangaOcr"
        }
        return "$PAINTING-${from.name}-$to-$engineKey-$detectBubbles".replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    /**
     * The bubbles of [bitmap] when [settings] detect them, or none when the detector can't run, such as before its
     * first download while offline, leaving ML Kit's own grouping of the text.
     */
    private fun regions(bitmap: Bitmap, settings: TranslationSettings): List<TextRegion> {
        if (!settings.detectBubbles) return emptyList()
        return try {
            detector.detect(bitmap)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Bubble detection failed" }
            emptyList()
        }
    }

    /**
     * The untranslated text of [bitmap], read by manga-ocr when [settings] ask for it and the detector found where
     * the text is, or else by ML Kit, which is also used when manga-ocr can't run, such as before its download.
     */
    private fun read(bitmap: Bitmap, settings: TranslationSettings, regions: List<TextRegion>): List<TranslatedText> {
        if (settings.mangaOcr && settings.from == TranslationSource.JAPANESE && regions.isNotEmpty()) {
            try {
                return mangaOcr.read(bitmap, regions)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "manga-ocr failed, reading with ML Kit" }
            }
        }
        return mlKit.read(bitmap, settings.from, regions)
    }

    /**
     * [translations] each in the bubble of [regions] that holds it, as Gemini's boxes are only around the text.
     */
    private fun inBubbles(translations: List<TranslatedText>, regions: List<TextRegion>): List<TranslatedText> {
        val bubbles = regions.filter { it.isBubble }
        return translations.map { translation ->
            val bubble = bubbles
                .maxByOrNull { overlap(translation.bounds, it.bounds) }
                ?.takeIf { overlap(translation.bounds, it.bounds) >= MIN_BUBBLE_OVERLAP }
            translation.copy(bubble = bubble?.bounds)
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val JPEG_QUALITY = 95
        const val MAX_SAVED_BYTES = 500L * 1024 * 1024
        const val MIN_BUBBLE_OVERLAP = 0.6f

        /** Changed with how translations are painted, so pages painted before are painted again. */
        const val PAINTING = "p2"
    }
}
