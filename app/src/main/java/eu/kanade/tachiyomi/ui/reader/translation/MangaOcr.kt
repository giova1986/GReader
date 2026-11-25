package eu.kanade.tachiyomi.ui.reader.translation

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Reads Japanese manga text with kha-white's manga-ocr, a vision encoder and text decoder under Apache 2.0 trained
 * on manga, which reads vertical text, furigana and stylised lettering better than ML Kit. Runs on the device; the
 * models are downloaded once into [directory]. Blocking.
 */
class MangaOcr(private val client: OkHttpClient, private val directory: File) : Closeable {

    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private var models: Models? = null

    /**
     * The text of each of [regions] of [bitmap], read from the text boxes the detector found, each in the bubble
     * holding it.
     */
    @Synchronized
    fun read(bitmap: Bitmap, regions: List<TextRegion>): List<TranslatedText> {
        val current = models ?: load().also { models = it }
        val bubbles = regions.filter { it.isBubble }
        val texts = regions.filter { it.kind != TextRegion.Kind.BUBBLE }
        // A bubble the detector found no text box in is read whole
        val unread = bubbles.filter { bubble -> texts.none { overlap(it.bounds, bubble.bounds) >= MIN_OVERLAP } }
        return (texts + unread).mapNotNull { region ->
            val crop = Rect(region.bounds).apply { intersect(0, 0, bitmap.width, bitmap.height) }
            if (crop.width() < MIN_SIZE || crop.height() < MIN_SIZE) return@mapNotNull null
            val image = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height())
            val text = recognize(current, image)
            if (text.none(Char::isLetter)) return@mapNotNull null
            val bubble = if (region.isBubble) {
                region
            } else {
                bubbles.maxByOrNull { overlap(region.bounds, it.bounds) }
                    ?.takeIf { overlap(region.bounds, it.bounds) >= MIN_OVERLAP }
            }
            TranslatedText(bounds = crop, text = text, original = text, bubble = bubble?.bounds)
        }
    }

    @Synchronized
    override fun close() {
        models?.close()
        models = null
    }

    private fun load(): Models {
        val vocabulary = downloadOnce(client, directory, VOCAB_NAME, VOCAB_URL).readLines()
        return OrtSession.SessionOptions().use { options ->
            Models(
                encoder = environment.createSession(
                    downloadOnce(client, directory, ENCODER_NAME, ENCODER_URL).path,
                    options,
                ),
                decoder = environment.createSession(
                    downloadOnce(client, directory, DECODER_NAME, DECODER_URL).path,
                    options,
                ),
                vocabulary = vocabulary,
            )
        }
    }

    private fun recognize(models: Models, crop: Bitmap): String {
        val hidden = try {
            encode(models.encoder, crop)
        } finally {
            crop.recycle()
        }
        hidden.use { states ->
            val tokens = mutableListOf(START_TOKEN)
            while (tokens.size < MAX_TOKENS) {
                val next = nextToken(models.decoder, tokens, states)
                if (next == END_TOKEN) break
                tokens += next
            }
            return detokenize(tokens.drop(1), models.vocabulary)
        }
    }

    /**
     * The encoder's hidden states for [crop], grey and stretched to the model's square input as it was trained,
     * scaled from -1 to 1.
     */
    private fun encode(encoder: OrtSession, crop: Bitmap): OnnxTensor {
        val scaled = Bitmap.createScaledBitmap(crop, INPUT_SIZE, INPUT_SIZE, true)
        val colors = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(colors, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (scaled !== crop) scaled.recycle()
        val area = colors.size
        val pixels = FloatBuffer.allocate(area * 3)
        colors.forEachIndexed { index, color ->
            val grey = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
            val value = grey / 255f * 2 - 1
            pixels.put(index, value)
            pixels.put(area + index, value)
            pixels.put(area * 2 + index, value)
        }
        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        OnnxTensor.createTensor(environment, pixels, shape).use { input ->
            encoder.run(mapOf(encoder.inputNames.first() to input)).use { result ->
                // The hidden states outlive the result they come in, and floatBuffer is a copy of them
                val states = result.get(0) as OnnxTensor
                return OnnxTensor.createTensor(environment, states.floatBuffer, states.info.shape)
            }
        }
    }

    /**
     * The most likely token after [tokens], taken greedily, which reads a bubble's short text as well as a search.
     */
    private fun nextToken(decoder: OrtSession, tokens: List<Long>, states: OnnxTensor): Long {
        val length = tokens.size.toLong()
        val inputs = mutableMapOf<String, OnnxTensor>()
        try {
            decoder.inputInfo.forEach { (name, node) ->
                val info = node.info as TensorInfo
                inputs[name] = when {
                    info.type == OnnxJavaType.FLOAT -> states
                    "mask" in name -> {
                        val size = if ("encoder" in name) states.info.shape[1].toInt() else tokens.size
                        val ones = LongArray(size) { 1 }
                        OnnxTensor.createTensor(environment, LongBuffer.wrap(ones), longArrayOf(1, size.toLong()))
                    }
                    else -> OnnxTensor.createTensor(
                        environment,
                        LongBuffer.wrap(tokens.toLongArray()),
                        longArrayOf(1, length),
                    )
                }
            }
            decoder.run(inputs).use { result ->
                val logits = result.get(0) as OnnxTensor
                val vocabularySize = logits.info.shape[2].toInt()
                val scores = logits.floatBuffer
                val last = (tokens.size - 1) * vocabularySize
                return (0 until vocabularySize).maxBy { scores[last + it] }.toLong()
            }
        } finally {
            inputs.values.filter { it !== states }.forEach(OnnxTensor::close)
        }
    }

    /**
     * [tokens] as text, as manga-ocr gives it: without its tokenizer's spaces and word piece marks.
     */
    private fun detokenize(tokens: List<Long>, vocabulary: List<String>): String {
        return tokens
            .filter { it >= FIRST_TEXT_TOKEN }
            .mapNotNull { vocabulary.getOrNull(it.toInt()) }
            .joinToString("") { it.removePrefix("##") }
            .replace("…", "...")
            .filterNot(Char::isWhitespace)
    }

    private class Models(val encoder: OrtSession, val decoder: OrtSession, val vocabulary: List<String>) : Closeable {
        override fun close() {
            encoder.close()
            decoder.close()
        }
    }

    private companion object {
        const val REPOSITORY = "https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/main/onnx"
        const val ENCODER_NAME = "manga-ocr-encoder-quantized.onnx"
        const val ENCODER_URL = "$REPOSITORY/encoder_model_quantized.onnx"
        const val DECODER_NAME = "manga-ocr-decoder-quantized.onnx"
        const val DECODER_URL = "$REPOSITORY/decoder_model_quantized.onnx"
        const val VOCAB_NAME = "manga-ocr-vocab.txt"
        const val VOCAB_URL = "https://huggingface.co/kha-white/manga-ocr-base/resolve/main/vocab.txt"

        const val INPUT_SIZE = 224
        const val START_TOKEN = 2L
        const val END_TOKEN = 3L

        /** Tokens below are [PAD], [UNK], [CLS], [SEP] and [MASK]. */
        const val FIRST_TEXT_TOKEN = 5L
        const val MAX_TOKENS = 300
        const val MIN_SIZE = 8
        const val MIN_OVERLAP = 0.5f
    }
}
