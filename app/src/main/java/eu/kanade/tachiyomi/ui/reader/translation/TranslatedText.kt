package eu.kanade.tachiyomi.ui.reader.translation

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * The translation of the text found within [bounds] of a page.
 */
data class TranslatedText(val bounds: Rect, val text: String)

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
