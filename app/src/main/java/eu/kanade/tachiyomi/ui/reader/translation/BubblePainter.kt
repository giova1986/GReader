package eu.kanade.tachiyomi.ui.reader.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Paints translations over a page: the original text is covered with the colour around it and the translation is
 * lettered in [typeface], shaped to the speech bubble when one was found.
 */
class BubblePainter(private val typeface: Typeface) {

    fun paint(bitmap: Bitmap, translations: List<TranslatedText>) {
        val shapes = translations.map { shapeOf(it, bitmap.width) }
        // Sampled before painting, as a painted bubble may border the next one
        val backgrounds = shapes.map { backgroundOf(bitmap, it) }
        val canvas = Canvas(bitmap)
        translations.forEachIndexed { index, translation ->
            val shape = shapes[index]
            val background = backgrounds[index]
            erase(canvas, shape, background)
            letter(canvas, shape, background, translation.text)
        }
    }

    /**
     * Where a translation covers the page: the inside of its bubble with its lines, or else the area of its lines.
     */
    private class Shape(val cover: RectF, val oval: RectF?, val lines: List<RectF>, val text: RectF)

    private fun shapeOf(translation: TranslatedText, pageWidth: Int): Shape {
        val lines = translation.lines.map { line ->
            val padding = min(line.width(), line.height()) * LINE_PADDING + MIN_LINE_PADDING
            RectF(line).apply { inset(-padding, -padding) }
        }
        val bubble = translation.bubble
        if (bubble != null) {
            // Inset off the bubble's outline, which the detector's box takes in
            val oval = RectF(bubble).apply { inset(width() * OUTLINE_INSET, height() * OUTLINE_INSET) }
            val text = RectF(oval).apply { inset(width() * TEXT_INSET, height() * TEXT_INSET) }
            return Shape(oval, oval, lines.mapNotNull { line -> line.takeIf { it.intersect(oval) } }, text)
        }

        val area = RectF(translation.bounds)
        lines.forEach { area.union(it) }
        // Vertical Japanese columns are too narrow for horizontal text, so the area is widened around its centre
        val minWidth = area.height() * MIN_WIDTH_TO_HEIGHT
        if (area.width() < minWidth) {
            val extra = (minWidth - area.width()) / 2
            area.left = max(0f, area.left - extra)
            area.right = min(pageWidth.toFloat(), area.right + extra)
        }
        return Shape(area, null, emptyList(), area)
    }

    /**
     * The colour most of [shape] has, which is the bubble's or the background's even where the original text or an
     * outline takes part of it, unlike an average.
     */
    private fun backgroundOf(bitmap: Bitmap, shape: Shape): Int {
        val area = RectF(shape.cover).apply {
            intersect(0f, 0f, bitmap.width - 1f, bitmap.height - 1f)
        }
        val step = max(1f, sqrt(area.width() * area.height() / MAX_SAMPLES))
        val reds = mutableListOf<Int>()
        val greens = mutableListOf<Int>()
        val blues = mutableListOf<Int>()
        var y = area.top
        while (y <= area.bottom) {
            var x = area.left
            while (x <= area.right) {
                if (shape.oval == null || inOval(shape.oval, x, y)) {
                    val pixel = bitmap.getPixel(x.toInt(), y.toInt())
                    reds += Color.red(pixel)
                    greens += Color.green(pixel)
                    blues += Color.blue(pixel)
                }
                x += step
            }
            y += step
        }
        if (reds.isEmpty()) return Color.WHITE
        return Color.rgb(reds.median(), greens.median(), blues.median())
    }

    private fun erase(canvas: Canvas, shape: Shape, background: Int) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
        if (shape.oval != null) {
            canvas.drawOval(shape.oval, fill)
            // Text that reaches into the corners of a square caption is outside the oval
            shape.lines.forEach { canvas.drawRect(it, fill) }
        } else {
            val radius = min(shape.cover.width(), shape.cover.height()) * CORNER_RATIO
            canvas.drawRoundRect(shape.cover, radius, radius, fill)
        }
    }

    private fun letter(canvas: Canvas, shape: Shape, background: Int, text: String) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (ColorUtils.calculateLuminance(background) > 0.5) Color.BLACK else Color.WHITE
            typeface = this@BubblePainter.typeface
        }
        val area = shape.text
        val layout = fitText(text, paint, area, oval = shape.oval != null)
        canvas.save()
        canvas.translate(area.left, area.top + (area.height() - layout.height) / 2)
        layout.draw(canvas)
        canvas.restore()
    }

    /**
     * [text] in the largest size that fits [area], an oval one when [oval], without breaking a word.
     */
    private fun fitText(text: String, paint: TextPaint, area: RectF, oval: Boolean): StaticLayout {
        var low = MIN_TEXT_SIZE
        var high = max(MIN_TEXT_SIZE, area.height())
        while (high - low > 1f) {
            paint.textSize = (low + high) / 2
            val layout = layout(text, paint, area, oval)
            val fits = layout.height <= area.height() && keepsWords(layout, text)
            if (fits) low = paint.textSize else high = paint.textSize
        }
        paint.textSize = low
        return layout(text, paint, area, oval)
    }

    /**
     * [text] laid out in [area], with each line as wide as an oval allows at its height when [oval]. As the lines
     * depend on their widths, the widths are worked out again from the lines a couple of times.
     */
    private fun layout(text: String, paint: TextPaint, area: RectF, oval: Boolean): StaticLayout {
        val width = area.width().roundToInt()
        var layout = layout(text, paint, width, null)
        if (!oval) return layout
        repeat(OVAL_PASSES) {
            val lineHeight = layout.height.toFloat() / layout.lineCount
            val top = (area.height() - layout.height) / 2
            val halfHeight = area.height() / 2
            val indents = IntArray(layout.lineCount) { line ->
                // The edge of the line further from the middle is the one the oval narrows
                val upper = abs(top + line * lineHeight - halfHeight)
                val lower = abs(top + (line + 1) * lineHeight - halfHeight)
                val ratio = (max(upper, lower) / halfHeight).coerceAtMost(1f)
                val chord = area.width() * sqrt(1 - ratio * ratio)
                ((area.width() - chord) / 2).roundToInt().coerceIn(0, width / 2 - 1)
            }
            layout = layout(text, paint, width, indents)
        }
        return layout
    }

    private fun layout(text: String, paint: TextPaint, width: Int, indents: IntArray?): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setIncludePad(false)
            .setIndents(indents, indents)
            .build()
    }

    /**
     * Whether every line of [layout] ends between words, rather than in a word too wide for it.
     */
    private fun keepsWords(layout: StaticLayout, text: String): Boolean {
        return (0 until layout.lineCount - 1).all { line ->
            val end = layout.getLineEnd(line)
            text[end - 1].isWhitespace() || text[end].isWhitespace()
        }
    }

    private fun inOval(oval: RectF, x: Float, y: Float): Boolean {
        val dx = (x - oval.centerX()) / (oval.width() / 2)
        val dy = (y - oval.centerY()) / (oval.height() / 2)
        return dx * dx + dy * dy <= 1f
    }

    private fun List<Int>.median(): Int = sorted()[size / 2]

    private companion object {
        const val MIN_TEXT_SIZE = 8f
        const val LINE_PADDING = 0.15f
        const val MIN_LINE_PADDING = 2f
        const val OUTLINE_INSET = 0.06f
        const val TEXT_INSET = 0.04f
        const val MIN_WIDTH_TO_HEIGHT = 0.7f
        const val CORNER_RATIO = 0.2f
        const val MAX_SAMPLES = 2000f
        const val OVAL_PASSES = 2
    }
}
