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
 * Reads each block of text of a page, usually a speech bubble, and translates it, all on the device. Nothing leaves
 * it but the one time download of each language's translation model. Blocking.
 */
class MlKitTextTranslator : Closeable {

    private val recognizers = ConcurrentHashMap<TranslationSource, TextRecognizer>()
    private val translators = ConcurrentHashMap<Pair<String, String>, Translator>()

    fun translate(bitmap: Bitmap, settings: TranslationSettings): List<TranslatedText> {
        val blocks = recognize(bitmap, settings.from)
        if (blocks.isEmpty()) return emptyList()

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

    private fun recognize(bitmap: Bitmap, source: TranslationSource): List<TranslatedText> {
        val recognizer = recognizers.getOrPut(source) { TextRecognition.getClient(source.recognizerOptions()) }
        val blocks = mutableListOf<TranslatedText>()
        forEachTile(bitmap) { top, tile ->
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(tile, 0)))
            text.textBlocks.mapNotNullTo(blocks) { block ->
                val bounds = block.boundingBox ?: return@mapNotNullTo null
                val content = block.lines.joinToString(source.lineSeparator) { it.text }
                if (content.none(Char::isLetter)) return@mapNotNullTo null
                TranslatedText(Rect(bounds).apply { offset(0, top) }, content)
            }
        }
        return blocks
    }
}
