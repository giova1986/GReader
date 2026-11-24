package eu.kanade.tachiyomi.ui.reader.translation

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads the text of a page and translates it a speech bubble at a time, all on the device. Nothing leaves it but the
 * one time download of each language's translation model. Blocking.
 */
class MlKitTextTranslator : Closeable {

    private val recognizers = ConcurrentHashMap<TranslationSource, TextRecognizer>()
    private val translators = ConcurrentHashMap<Pair<String, String>, Translator>()

    /**
     * Lines within one of [regions] are read together and the translation fills the region, while other lines are
     * grouped as ML Kit sees blocks of text.
     */
    fun translate(
        bitmap: Bitmap,
        settings: TranslationSettings,
        regions: List<TextRegion> = emptyList(),
    ): List<TranslatedText> {
        val lines = recognize(bitmap, settings.from)
        if (lines.isEmpty()) return emptyList()
        val blocks = group(lines, regions, settings.from)

        val translator = translators.getOrPut(settings.from.language to settings.to) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(settings.from.language)
                    .setTargetLanguage(settings.to)
                    .build(),
            )
        }
        Tasks.await(translator.downloadModelIfNeeded())

        return blocks.mapNotNull { block ->
            val translated = Tasks.await(translator.translate(block.text)).trim()
            if (translated.isEmpty()) null else TranslatedText(block.bounds, translated)
        }
    }

    override fun close() {
        recognizers.values.forEach(TextRecognizer::close)
        translators.values.forEach(Translator::close)
    }

    private fun recognize(bitmap: Bitmap, source: TranslationSource): List<Line> {
        val recognizer = recognizers.getOrPut(source) { TextRecognition.getClient(source.recognizerOptions()) }
        val lines = mutableListOf<Line>()
        var blockId = 0
        forEachTile(bitmap) { top, tile ->
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(tile, 0)))
            text.textBlocks.forEach { block ->
                blockId++
                block.lines.mapNotNullTo(lines) { line ->
                    val bounds = line.boundingBox ?: return@mapNotNullTo null
                    if (line.text.none(Char::isLetter)) return@mapNotNullTo null
                    Line(Rect(bounds).apply { offset(0, top) }, line.text, blockId)
                }
            }
        }
        return lines
    }

    private fun group(lines: List<Line>, regions: List<TextRegion>, source: TranslationSource): List<TranslatedText> {
        val byRegion = lines.groupBy { line -> regions.maxByOrNull { overlap(line.bounds, it.bounds) } }
        return byRegion.flatMap { (region, members) ->
            val inRegion = members.filter { region != null && overlap(it.bounds, region.bounds) >= MIN_OVERLAP }
            val outside = members - inRegion.toSet()
            val grouped = if (region != null && inRegion.isNotEmpty()) {
                val bounds = Rect(inRegion.first().bounds).apply { inRegion.forEach { union(it.bounds) } }
                if (region.isBubble) {
                    // The bubble's inside gives a longer translation more room than its original lines
                    val inside = Rect(region.bounds).apply {
                        inset((width() * BUBBLE_INSET).toInt(), (height() * BUBBLE_INSET).toInt())
                    }
                    bounds.union(inside)
                }
                listOf(TranslatedText(bounds, readingOrder(inRegion).joinToString(source.lineSeparator) { it.text }))
            } else {
                emptyList()
            }
            grouped + outside.groupBy { it.blockId }.values.map { block ->
                val bounds = Rect(block.first().bounds).apply { block.forEach { union(it.bounds) } }
                TranslatedText(bounds, block.joinToString(source.lineSeparator) { it.text })
            }
        }
    }

    /**
     * [lines] in the order they're read: right to left for vertical columns, top to bottom otherwise.
     */
    private fun readingOrder(lines: List<Line>): List<Line> {
        val vertical = lines.count { it.bounds.height() > it.bounds.width() } * 2 > lines.size
        return if (vertical) lines.sortedByDescending { it.bounds.right } else lines.sortedBy { it.bounds.top }
    }

    /**
     * The share of [line] that lies within [region].
     */
    private fun overlap(line: Rect, region: Rect): Float {
        val intersection = Rect()
        if (!intersection.setIntersect(line, region)) return 0f
        val area = line.width().toFloat() * line.height()
        return if (area == 0f) 0f else intersection.width().toFloat() * intersection.height() / area
    }

    private data class Line(val bounds: Rect, val text: String, val blockId: Int)

    private companion object {
        const val MIN_OVERLAP = 0.5f
        const val BUBBLE_INSET = 0.15f
    }
}
