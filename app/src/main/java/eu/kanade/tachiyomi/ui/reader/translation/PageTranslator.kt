package eu.kanade.tachiyomi.ui.reader.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.ColorUtils
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Translates the text of pages on the device: ML Kit reads each block of text, usually a speech bubble, translates
 * it, and the translation is painted over the block on a copy of the image. Nothing leaves the device but the one
 * time download of each language's translation model.
 *
 * Blocking, so never call it on the main thread.
 */
class PageTranslator : Closeable {

    private val recognizers = ConcurrentHashMap<TranslationSource, TextRecognizer>()
    private val translators = ConcurrentHashMap<Pair<String, String>, Translator>()

    /**
     * [image] translated when [settings] enable it, or [image] itself when it can't be: an animated image, or a
     * failure such as the language model not downloading while offline, so the page still shows.
     */
    fun translatePage(image: BufferedSource, settings: TranslationSettings): BufferedSource {
        if (!settings.enabled || ImageUtil.isAnimatedAndSupported(image)) return image
        return try {
            translate(image, settings)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to translate page" }
            image
        }
    }

    /**
     * [image] with its text translated as [settings] say, or [image] itself when it has no text.
     */
    private fun translate(image: BufferedSource, settings: TranslationSettings): BufferedSource {
        val options = BitmapFactory.Options().apply { inMutable = true }
        val bitmap = BitmapFactory.decodeStream(image.peek().inputStream(), null, options) ?: return image
        try {
            val regions = recognize(bitmap, settings.from)
            if (regions.isEmpty()) return image

            val translator = translators.getOrPut(settings.from.language to settings.to) {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(settings.from.language)
                        .setTargetLanguage(settings.to)
                        .build(),
                )
            }
            Tasks.await(translator.downloadModelIfNeeded())

            // Sampled before painting, as a painted bubble may border the next one
            val backgrounds = regions.map { backgroundOf(bitmap, it.bounds) }
            val canvas = Canvas(bitmap)
            regions.forEachIndexed { index, region ->
                val translated = Tasks.await(translator.translate(region.text)).trim()
                if (translated.isNotEmpty()) {
                    paint(canvas, region.bounds, backgrounds[index], translated)
                }
            }

            return Buffer().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it.outputStream()) }
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        recognizers.values.forEach(TextRecognizer::close)
        translators.values.forEach(Translator::close)
    }

    private fun recognize(bitmap: Bitmap, source: TranslationSource): List<TextRegion> {
        val recognizer = recognizers.getOrPut(source) { TextRecognition.getClient(source.recognizerOptions()) }
        // Long strips are read in parts, as the recognizer scales the whole image down to its input size
        val tileHeight = max(bitmap.width * 2, MIN_TILE_HEIGHT)
        val regions = mutableListOf<TextRegion>()
        var top = 0
        while (top < bitmap.height) {
            val height = min(tileHeight, bitmap.height - top)
            val tile = if (height == bitmap.height) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
            }
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(tile, 0)))
            if (tile !== bitmap) tile.recycle()

            text.textBlocks.mapNotNullTo(regions) { block ->
                val bounds = block.boundingBox ?: return@mapNotNullTo null
                val content = block.lines.joinToString(source.lineSeparator) { it.text }
                if (content.none(Char::isLetter)) return@mapNotNullTo null
                TextRegion(Rect(bounds).apply { offset(0, top) }, content)
            }
            top += height
        }
        return regions
    }

    /**
     * The average colour just around [bounds], which in a speech bubble is the bubble's.
     */
    private fun backgroundOf(bitmap: Bitmap, bounds: Rect): Int {
        val area = Rect(
            max(0, bounds.left - BORDER_SAMPLE_OFFSET),
            max(0, bounds.top - BORDER_SAMPLE_OFFSET),
            min(bitmap.width - 1, bounds.right + BORDER_SAMPLE_OFFSET),
            min(bitmap.height - 1, bounds.bottom + BORDER_SAMPLE_OFFSET),
        )
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        fun sample(x: Int, y: Int) {
            val pixel = bitmap.getPixel(x, y)
            red += Color.red(pixel)
            green += Color.green(pixel)
            blue += Color.blue(pixel)
            count++
        }
        for (x in area.left..area.right step BORDER_SAMPLE_STEP) {
            sample(x, area.top)
            sample(x, area.bottom)
        }
        for (y in area.top..area.bottom step BORDER_SAMPLE_STEP) {
            sample(area.left, y)
            sample(area.right, y)
        }
        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun paint(canvas: Canvas, bounds: Rect, background: Int, text: String) {
        val padding = min(bounds.width(), bounds.height()) * PADDING_RATIO
        val area = RectF(bounds).apply { inset(-padding, -padding) }
        // Vertical Japanese columns are too narrow for horizontal text, so the bubble is widened around its centre
        val minWidth = area.height() * MIN_WIDTH_TO_HEIGHT
        if (area.width() < minWidth) {
            val extra = (minWidth - area.width()) / 2
            area.left = max(0f, area.left - extra)
            area.right = min(canvas.width.toFloat(), area.right + extra)
        }

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
        canvas.drawRoundRect(area, padding * 2, padding * 2, fill)

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (ColorUtils.calculateLuminance(background) > 0.5) Color.BLACK else Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
        }
        val layout = fitText(text, textPaint, area.width().roundToInt(), area.height())

        canvas.save()
        canvas.translate(area.left, area.centerY() - layout.height / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    /**
     * [text] in the largest size that fits [width] by [height] without breaking a word.
     */
    private fun fitText(text: String, paint: TextPaint, width: Int, height: Float): StaticLayout {
        val words = text.split(' ')
        var low = MIN_TEXT_SIZE
        var high = max(MIN_TEXT_SIZE, height)
        while (high - low > 1f) {
            paint.textSize = (low + high) / 2
            val fits = layout(text, paint, width).height <= height &&
                words.all { paint.measureText(it) <= width }
            if (fits) low = paint.textSize else high = paint.textSize
        }
        paint.textSize = low
        return layout(text, paint, width)
    }

    private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
    }

    private data class TextRegion(val bounds: Rect, val text: String)

    private companion object {
        const val JPEG_QUALITY = 95
        const val MIN_TILE_HEIGHT = 2048
        const val MIN_TEXT_SIZE = 8f
        const val PADDING_RATIO = 0.1f
        const val MIN_WIDTH_TO_HEIGHT = 0.7f
        const val BORDER_SAMPLE_OFFSET = 4
        const val BORDER_SAMPLE_STEP = 4
    }
}
