package eu.kanade.tachiyomi.ui.reader.translation

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * The translation of the [original] text found within [bounds] of a page, as [lines] there, in the speech [bubble]
 * holding it when one was found.
 */
data class TranslatedText(
    val bounds: Rect,
    val text: String,
    val original: String = "",
    val lines: List<Rect> = listOf(bounds),
    val bubble: Rect? = null,
)

/**
 * The share of [inner] that lies within [outer].
 */
internal fun overlap(inner: Rect, outer: Rect): Float {
    val intersection = Rect()
    if (!intersection.setIntersect(inner, outer)) return 0f
    val area = inner.width().toFloat() * inner.height()
    return if (area == 0f) 0f else intersection.width().toFloat() * intersection.height() / area
}

/**
 * Runs [action] on parts of [bitmap] from the top, each with the y it starts at. Long strips are split, as both
 * engines scale an image down to their input size and would lose the text of a strip.
 */
internal inline fun forEachTile(bitmap: Bitmap, action: (top: Int, tile: Bitmap) -> Unit) {
    val tileHeight = max(bitmap.width * 2, MIN_TILE_HEIGHT)
    var top = 0
    while (top < bitmap.height) {
        val height = min(tileHeight, bitmap.height - top)
        if (height == bitmap.height) {
            action(top, bitmap)
        } else {
            val tile = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
            try {
                action(top, tile)
            } finally {
                tile.recycle()
            }
        }
        top += height
    }
}

private const val MIN_TILE_HEIGHT = 2048
