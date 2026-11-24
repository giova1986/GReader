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
import logcat.LogPriority
import okhttp3.OkHttpClient
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Translates the text of pages with the engine the settings pick, painting each translation over its text on a
 * copy of the image. Blocking, so never call it on the main thread.
 */
class PageTranslator(client: OkHttpClient) : Closeable {

    private val mlKit = MlKitTextTranslator()
    private val gemini = GeminiTextTranslator(client)

    /**
     * [image] translated when [settings] enable it, or [image] itself when it can't be: an animated image, or a
     * failure such as the language model not downloading while offline, so the page still shows. A page of
     * [chapterId] gets the chapter's earlier pages as context where the engine uses it.
     */
    fun translatePage(image: BufferedSource, settings: TranslationSettings, chapterId: Long?): BufferedSource {
        if (!settings.enabled || ImageUtil.isAnimatedAndSupported(image)) return image
        return try {
            translate(image, settings, chapterId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to translate page" }
            image
        }
    }

    override fun close() {
        mlKit.close()
    }

    private fun translate(image: BufferedSource, settings: TranslationSettings, chapterId: Long?): BufferedSource {
        val options = BitmapFactory.Options().apply { inMutable = true }
        val bitmap = BitmapFactory.decodeStream(image.peek().inputStream(), null, options) ?: return image
        try {
            val translations = when (settings.engine) {
                TranslationEngine.ML_KIT -> mlKit.translate(bitmap, settings)
                TranslationEngine.GEMINI -> try {
                    gemini.translate(bitmap, settings, chapterId)
                } catch (e: Exception) {
                    // Out of free quota or offline, the page is still worth translating on the device
                    logcat(LogPriority.WARN, e) { "Gemini failed, translating with ML Kit" }
                    mlKit.translate(bitmap, settings)
                }
            }
            if (translations.isEmpty()) return image

            // Sampled before painting, as a painted bubble may border the next one
            val backgrounds = translations.map { backgroundOf(bitmap, it.bounds) }
            val canvas = Canvas(bitmap)
            translations.forEachIndexed { index, translation ->
                paint(canvas, translation.bounds, backgrounds[index], translation.text)
            }

            return Buffer().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it.outputStream()) }
        } finally {
            bitmap.recycle()
        }
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

    private companion object {
        const val JPEG_QUALITY = 95
        const val MIN_TEXT_SIZE = 8f
        const val PADDING_RATIO = 0.1f
        const val MIN_WIDTH_TO_HEIGHT = 0.7f
        const val BORDER_SAMPLE_OFFSET = 4
        const val BORDER_SAMPLE_STEP = 4
    }
}
