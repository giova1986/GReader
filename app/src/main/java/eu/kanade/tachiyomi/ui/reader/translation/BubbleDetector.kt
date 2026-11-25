package eu.kanade.tachiyomi.ui.reader.translation

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.Rect
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Finds the speech bubbles and the free text of a page with ogkalu's comic text and bubble detector, an RT-DETR-v2
 * model under Apache 2.0 that runs on the device. The model is downloaded once into [directory]. Blocking.
 */
class BubbleDetector(private val client: OkHttpClient, private val directory: File) : Closeable {

    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null

    @Synchronized
    fun detect(bitmap: Bitmap): List<TextRegion> {
        val current = session ?: OrtSession.SessionOptions().use { options ->
            environment.createSession(downloadOnce(client, directory, MODEL_NAME, MODEL_URL).path, options)
        }.also { session = it }

        val regions = mutableListOf<TextRegion>()
        forEachTile(bitmap) { top, tile ->
            detectTile(current, tile).mapTo(regions) { it.copy(bounds = Rect(it.bounds).apply { offset(0, top) }) }
        }
        return regions
    }

    @Synchronized
    override fun close() {
        session?.close()
        session = null
    }

    private fun detectTile(session: OrtSession, tile: Bitmap): List<TextRegion> {
        val inputs = session.inputInfo.mapValues { (_, node) ->
            val info = node.info as TensorInfo
            if (info.type == OnnxJavaType.INT64) {
                // Exports with RT-DETR's own post-processing scale the boxes to this size
                OnnxTensor.createTensor(
                    environment,
                    LongBuffer.wrap(longArrayOf(tile.width.toLong(), tile.height.toLong())),
                    longArrayOf(1, 2),
                )
            } else {
                val channelsFirst = info.shape[1] == 3L
                OnnxTensor.createTensor(environment, pixels(tile, channelsFirst), imageShape(channelsFirst))
            }
        }
        try {
            session.run(inputs).use { result ->
                val outputs = result.associate { it.key to it.value as OnnxTensor }
                val detections = if ("pred_boxes" in outputs) {
                    fromLogits(outputs.getValue("logits"), outputs.getValue("pred_boxes"))
                } else {
                    fromPostProcessed(outputs.getValue("labels"), outputs.getValue("boxes"), outputs.getValue("scores"))
                }
                return detections
                    .filter { it.score >= MIN_SCORE }
                    .mapNotNull { it.toRegion(tile.width, tile.height) }
            }
        } finally {
            inputs.values.forEach(OnnxTensor::close)
        }
    }

    private fun imageShape(channelsFirst: Boolean): LongArray {
        return if (channelsFirst) {
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        } else {
            longArrayOf(1, INPUT_SIZE.toLong(), INPUT_SIZE.toLong(), 3)
        }
    }

    /**
     * [tile] stretched to the model's square input, as it was trained, in RGB from 0 to 1.
     */
    private fun pixels(tile: Bitmap, channelsFirst: Boolean): FloatBuffer {
        val scaled = Bitmap.createScaledBitmap(tile, INPUT_SIZE, INPUT_SIZE, true)
        val colors = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(colors, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (scaled !== tile) scaled.recycle()

        val area = colors.size
        val buffer = FloatBuffer.allocate(area * 3)
        colors.forEachIndexed { index, color ->
            val red = (color shr 16 and 0xFF) / 255f
            val green = (color shr 8 and 0xFF) / 255f
            val blue = (color and 0xFF) / 255f
            if (channelsFirst) {
                buffer.put(index, red)
                buffer.put(area + index, green)
                buffer.put(area * 2 + index, blue)
            } else {
                buffer.put(index * 3, red)
                buffer.put(index * 3 + 1, green)
                buffer.put(index * 3 + 2, blue)
            }
        }
        return buffer
    }

    /**
     * Detections of the Hugging Face export: class logits and normalised centre boxes.
     */
    private fun fromLogits(logits: OnnxTensor, boxes: OnnxTensor): List<Detection> {
        val classes = logits.info.shape[2].toInt()
        val scores = logits.floatBuffer
        val coordinates = boxes.floatBuffer
        return (0 until logits.info.shape[1].toInt()).map { query ->
            val label = (0 until classes).maxBy { scores[query * classes + it] }
            val centreX = coordinates[query * 4]
            val centreY = coordinates[query * 4 + 1]
            val halfWidth = coordinates[query * 4 + 2] / 2
            val halfHeight = coordinates[query * 4 + 3] / 2
            Detection(
                label = label,
                score = sigmoid(scores[query * classes + label]),
                left = centreX - halfWidth,
                top = centreY - halfHeight,
                right = centreX + halfWidth,
                bottom = centreY + halfHeight,
                normalised = true,
            )
        }
    }

    /**
     * Detections of RT-DETR's own export: labels, scores and corner boxes in the size it was given.
     */
    private fun fromPostProcessed(labels: OnnxTensor, boxes: OnnxTensor, scores: OnnxTensor): List<Detection> {
        val count = scores.info.shape[1].toInt()
        val labelOf: (Int) -> Int = if (labels.info.type == OnnxJavaType.INT64) {
            labels.longBuffer.let { buffer -> { index: Int -> buffer[index].toInt() } }
        } else {
            labels.floatBuffer.let { buffer -> { index: Int -> buffer[index].toInt() } }
        }
        val scoreBuffer = scores.floatBuffer
        val coordinates = boxes.floatBuffer
        val detections = (0 until count).map {
            Detection(
                label = labelOf(it),
                score = scoreBuffer[it],
                left = coordinates[it * 4],
                top = coordinates[it * 4 + 1],
                right = coordinates[it * 4 + 2],
                bottom = coordinates[it * 4 + 3],
                normalised = false,
            )
        }
        // Some exports leave the boxes normalised despite the size input
        val normalised = detections.all { it.right <= 1.5f && it.bottom <= 1.5f }
        return if (normalised) detections.map { it.copy(normalised = true) } else detections
    }

    private fun Detection.toRegion(width: Int, height: Int): TextRegion? {
        val scaleX = if (normalised) width.toFloat() else 1f
        val scaleY = if (normalised) height.toFloat() else 1f
        val bounds = Rect(
            (left * scaleX).roundToInt().coerceIn(0, width),
            (top * scaleY).roundToInt().coerceIn(0, height),
            (right * scaleX).roundToInt().coerceIn(0, width),
            (bottom * scaleY).roundToInt().coerceIn(0, height),
        )
        if (bounds.isEmpty) return null
        val kind = when (label) {
            BUBBLE -> TextRegion.Kind.BUBBLE
            TEXT_BUBBLE -> TextRegion.Kind.BUBBLE_TEXT
            TEXT_FREE -> TextRegion.Kind.FREE_TEXT
            else -> return null
        }
        return TextRegion(bounds, kind)
    }

    private fun sigmoid(value: Float) = 1 / (1 + exp(-value))

    private data class Detection(
        val label: Int,
        val score: Float,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val normalised: Boolean,
    )

    private companion object {
        const val MODEL_NAME = "comic-text-and-bubble-detector-v4-s-int8.onnx"
        const val MODEL_URL =
            "https://huggingface.co/ogkalu/comic-text-and-bubble-detector/resolve/main/detector-v4-s_int8.onnx"
        const val INPUT_SIZE = 640
        const val MIN_SCORE = 0.4f
        const val BUBBLE = 0
        const val TEXT_BUBBLE = 1
        const val TEXT_FREE = 2
    }
}

/**
 * A speech bubble, the text within one, or text outside of one, whose lines are read and translated together.
 */
data class TextRegion(val bounds: Rect, val kind: Kind) {

    val isBubble: Boolean
        get() = kind == Kind.BUBBLE

    enum class Kind { BUBBLE, BUBBLE_TEXT, FREE_TEXT }
}
