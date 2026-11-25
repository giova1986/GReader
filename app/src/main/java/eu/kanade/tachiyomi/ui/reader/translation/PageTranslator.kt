package eu.kanade.tachiyomi.ui.reader.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
    private val detector = BubbleDetector(client, directory)
    private val painter = BubblePainter(
        ResourcesCompat.getFont(context, R.font.comic_neue_bold) ?: Typeface.DEFAULT_BOLD,
    )
    private val pages = File(directory, "pages")

    private val mutableProblems = MutableSharedFlow<TranslationProblem>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val problems: SharedFlow<TranslationProblem> = mutableProblems

    /**
     * [image] translated when [settings] enable it, or [image] itself when it can't be: an animated image, or a
     * failure such as the language model not downloading while offline, so the page still shows. A page of
     * [chapter] gets the chapter's earlier pages as context where the engine uses it, and its series' glossary.
     */
    fun translatePage(image: BufferedSource, settings: TranslationSettings, chapter: Chapter?): BufferedSource {
        if (!settings.enabled || ImageUtil.isAnimatedAndSupported(image)) return image
        val hash = image.peek().readByteString().sha256().hex()
        val saved = File(pages, "${chapter?.mangaId ?: 0}-$hash-${settings.pageKey()}.jpg")
        if (saved.exists()) {
            saved.setLastModified(System.currentTimeMillis())
            return Buffer().apply { saved.source().use { writeAll(it) } }
        }
        return try {
            val (translated, complete) = translate(image, settings, chapter)
            if (complete) save(translated, saved)
            translated
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to translate page" }
            mutableProblems.tryEmit(TranslationProblem.Failed(e.message ?: e.javaClass.simpleName))
            image
        }
    }

    /**
     * Drops the translated pages of [mangaId], so they're translated again with its changed glossary.
     */
    fun forget(mangaId: Long) {
        pages.listFiles().orEmpty().filter { it.name.startsWith("$mangaId-") }.forEach(File::delete)
    }

    override fun close() {
        mlKit.close()
        detector.close()
    }

    /**
     * [image] translated, and whether it was translated as [settings] ask, rather than on the device after Gemini
     * failed, so a page is only kept when asking again wouldn't do better.
     */
    private fun translate(
        image: BufferedSource,
        settings: TranslationSettings,
        chapter: Chapter?,
    ): Pair<BufferedSource, Boolean> {
        val options = BitmapFactory.Options().apply { inMutable = true }
        val bitmap = BitmapFactory.decodeStream(image.peek().inputStream(), null, options) ?: return image to false
        try {
            var complete = true
            val glossary = chapter?.let { glossaries.of(it.mangaId) } ?: Glossary.EMPTY
            val regions by lazy { regions(bitmap, settings) }
            val translations = when (settings.engine) {
                TranslationEngine.ML_KIT -> mlKit.translate(bitmap, settings, regions, glossary)
                TranslationEngine.GEMINI -> try {
                    val (found, names) = gemini.translate(bitmap, settings, chapter?.id, glossary)
                    chapter?.let { glossaries.learn(it.mangaId, names) }
                    inBubbles(found, regions)
                } catch (e: Exception) {
                    // Out of free quota or offline, the page is still worth translating on the device
                    logcat(LogPriority.WARN, e) { "Gemini failed, translating with ML Kit" }
                    mutableProblems.tryEmit(TranslationProblem.GeminiFailed(e.message ?: e.javaClass.simpleName))
                    complete = false
                    mlKit.translate(bitmap, settings, regions, glossary)
                }
            }
            if (translations.isEmpty()) {
                mutableProblems.tryEmit(TranslationProblem.NoText)
                return image to false
            }

            painter.paint(bitmap, translations)

            val translated = Buffer().also {
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it.outputStream())
            }
            return translated to complete
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Keeps [page] as [file], then drops the pages read longest ago once they take more than [MAX_SAVED_BYTES].
     */
    private fun save(page: BufferedSource, file: File) {
        pages.mkdirs()
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

    /**
     * What a translated page depends on besides the image and the glossary, so changing any of it translates pages
     * again.
     */
    private fun TranslationSettings.pageKey(): String {
        val engineKey = when (engine) {
            TranslationEngine.ML_KIT -> "mlkit"
            TranslationEngine.GEMINI -> "gemini-${geminiModel.trim()}"
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
        const val JPEG_QUALITY = 95
        const val MAX_SAVED_BYTES = 500L * 1024 * 1024
        const val MIN_BUBBLE_OVERLAP = 0.6f

        /** Changed with how translations are painted, so pages painted before are painted again. */
        const val PAINTING = "p2"
    }
}
