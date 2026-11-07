package com.safestream.app

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Loads a MobileFaceNet .tflite model and converts a cropped face Bitmap
 * into a normalized embedding vector.
 *
 * CHANGED: now tries a GPU delegate first (same pattern as YoloDetector),
 * falling back to 4-thread CPU only if the device doesn't support it. Log
 * timing showed embed() costing ~40-60ms per call on CPU alone -- with two
 * faces in frame that's 80-120ms just for embeddings, which was a large
 * chunk of the live-streaming lag. GPU delegate should cut this
 * meaningfully on devices that support it; on devices that don't, it
 * silently falls back to the old CPU path so nothing breaks.
 */
class FaceEmbedder(
    context: Context,
    modelPath: String = "mobilefacenet.tflite",
    private val inputSize: Int = 112
) {
    private val interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null
    private val embeddingDim: Int
    private val normalizeToMinus1_1 = true // see note below if results look wrong

    init {
        val model = loadModelFile(context, modelPath)
        val options = Interpreter.Options()

        val compatList = CompatibilityList()
        if (compatList.isDelegateSupportedOnThisDevice) {
            gpuDelegate = GpuDelegate(compatList.bestOptionsForThisDevice)
            options.addDelegate(gpuDelegate)
        } else {
            options.setNumThreads(4)
        }

        interpreter = Interpreter(model, options)
        embeddingDim = interpreter.getOutputTensor(0).shape().last()
    }

    private fun loadModelFile(context: Context, modelPath: String): ByteBuffer {
        val fd = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val channel = inputStream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    /** faceBitmap should already be cropped tightly to the detected face box. */
    fun embed(faceBitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(faceBitmap, inputSize, inputSize, true)
        val inputBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputSize * inputSize)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            if (normalizeToMinus1_1) {
                inputBuffer.putFloat((r - 127.5f) / 128.0f)
                inputBuffer.putFloat((g - 127.5f) / 128.0f)
                inputBuffer.putFloat((b - 127.5f) / 128.0f)
            } else {
                inputBuffer.putFloat(r / 255.0f)
                inputBuffer.putFloat(g / 255.0f)
                inputBuffer.putFloat(b / 255.0f)
            }
        }
        inputBuffer.rewind()

        val output = Array(1) { FloatArray(embeddingDim) }
        interpreter.run(inputBuffer, output)

        if (scaled !== faceBitmap) {
            scaled.recycle()
        }

        return l2Normalize(output[0])
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSquares = 0f
        for (v in vector) sumSquares += v * v
        val norm = sqrt(sumSquares).coerceAtLeast(1e-6f)
        return FloatArray(vector.size) { vector[it] / norm }
    }

    companion object {
        /** Both vectors must already be L2-normalized (embed() does this). */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            return dot
        }
    }

    fun close() {
        interpreter.close()
        gpuDelegate?.close()
    }
}