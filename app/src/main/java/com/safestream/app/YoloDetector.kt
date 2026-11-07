package com.safestream.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * Wraps the YOLOv8-Nano face detection model using LiteRT.
 * Handles both Float32 and Quantized models, NCHW/NHWC, and transposed outputs.
 */
class YoloDetector(context: Context, modelPath: String) {

    companion object {
        const val INPUT_SIZE = 320
        const val CONF_THRESHOLD = 0.1f
        const val IOU_THRESHOLD = 0.45f
    }

    data class Detection(val box: RectF, val confidence: Float)

    private var interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null
    
    private var numAnchors: Int = 2100
    private var numChannels: Int = 20
    private var isNCHW: Boolean = true
    private var isQuantized: Boolean = false
    private var isTransposed: Boolean = false
    
    private var inputBuffer: ByteBuffer
    private var outputBuffer: ByteBuffer

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

        // 1. Inspect Input Tensor to handle different YOLOv8 export formats
        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape() // [1, 3, 320, 320] (NCHW) or [1, 320, 320, 3] (NHWC)
        isNCHW = inputShape[1] == 3
        isQuantized = inputTensor.dataType() == DataType.UINT8 || inputTensor.dataType() == DataType.INT8

        // 2. Inspect Output Tensor (YOLOv8 often transposes [1, channels, anchors])
        val outputTensor = interpreter.getOutputTensor(0)
        val outputShape = outputTensor.shape() 
        if (outputShape[1] < outputShape[2]) {
            numChannels = outputShape[1]
            numAnchors = outputShape[2]
            isTransposed = false
        } else {
            numChannels = outputShape[2]
            numAnchors = outputShape[1]
            isTransposed = true
        }

        // 3. Allocate Buffers
        val bytesPerElement = if (isQuantized) 1 else 4
        inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * bytesPerElement).apply {
            order(ByteOrder.nativeOrder())
        }
        // Output is typically Float32 [1, 20, 2100]
        outputBuffer = ByteBuffer.allocateDirect(1 * numChannels * numAnchors * 4).apply {
            order(ByteOrder.nativeOrder())
        }
    }

    private fun loadModelFile(context: Context, modelPath: String): MappedByteBuffer {
        val fd = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val channel = inputStream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val resizedBitmap = if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        }
        
        convertBitmapToByteBuffer(resizedBitmap)
        
        if (resizedBitmap !== bitmap) {
            resizedBitmap.recycle()
        }

        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)

        return processOutput()
    }

    /**
     * Converts ARGB Bitmap to RGB and handles Normalization (0-1) or Quantization (0-255).
     */
    private fun convertBitmapToByteBuffer(bitmap: Bitmap) {
        inputBuffer.rewind()
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        
        if (isQuantized) {
            // Quantized models expect raw 0-255 values (UINT8)
            if (isNCHW) {
                for (pixel in pixels) inputBuffer.put(((pixel shr 16) and 0xFF).toByte())
                for (pixel in pixels) inputBuffer.put(((pixel shr 8) and 0xFF).toByte())
                for (pixel in pixels) inputBuffer.put((pixel and 0xFF).toByte())
            } else {
                for (pixel in pixels) {
                    inputBuffer.put(((pixel shr 16) and 0xFF).toByte())
                    inputBuffer.put(((pixel shr 8) and 0xFF).toByte())
                    inputBuffer.put((pixel and 0xFF).toByte())
                }
            }
        } else {
            // Float32 models expect normalized 0.0 - 1.0 values
            val floatBuffer = inputBuffer.asFloatBuffer()
            if (isNCHW) {
                for (pixel in pixels) floatBuffer.put(((pixel shr 16) and 0xFF) / 255.0f)
                for (pixel in pixels) floatBuffer.put(((pixel shr 8) and 0xFF) / 255.0f)
                for (pixel in pixels) floatBuffer.put((pixel and 0xFF) / 255.0f)
            } else {
                for (pixel in pixels) {
                    floatBuffer.put(((pixel shr 16) and 0xFF) / 255.0f)
                    floatBuffer.put(((pixel shr 8) and 0xFF) / 255.0f)
                    floatBuffer.put((pixel and 0xFF) / 255.0f)
                }
            }
        }
    }

    private fun processOutput(): List<Detection> {
        val detections = mutableListOf<Detection>()
        outputBuffer.rewind()
        val output = FloatArray(numChannels * numAnchors)
        outputBuffer.asFloatBuffer().get(output)

        var totalCandidates = 0

        for (i in 0 until numAnchors) {
            val confidence: Float
            val cx: Float
            val cy: Float
            val w: Float
            val h: Float

            // Handle Transposed vs Non-Transposed output tensors
            if (isTransposed) {
                confidence = output[i * numChannels + 4] // 5th element is confidence in YOLOv8-face
                if (confidence < CONF_THRESHOLD) continue
                totalCandidates++
                cx = output[i * numChannels + 0]
                cy = output[i * numChannels + 1]
                w = output[i * numChannels + 2]
                h = output[i * numChannels + 3]
            } else {
                confidence = output[4 * numAnchors + i]
                if (confidence < CONF_THRESHOLD) continue
                totalCandidates++
                cx = output[0 * numAnchors + i]
                cy = output[1 * numAnchors + i]
                w = output[2 * numAnchors + i]
                h = output[3 * numAnchors + i]
            }

            // Bounding box calculations (Already in 0.0 - 1.0 range)
            val left = cx - w / 2f
            val top = cy - h / 2f
            val right = cx + w / 2f
            val bottom = cy + h / 2f

            detections.add(Detection(
                RectF(max(0f, left), max(0f, top), min(1f, right), min(1f, bottom)),
                confidence
            ))
        }

        val finalDetections = applyNMS(detections)

        android.util.Log.d("YoloDetector", "Raw candidates > $CONF_THRESHOLD: $totalCandidates, Final detections after NMS: ${finalDetections.size}")
        finalDetections.forEachIndexed { index, det ->
            android.util.Log.d("YoloDetector", "  Det $index: Conf=${"%.3f".format(det.confidence)}, Box=${det.box}")
        }

        return finalDetections
    }

    private fun applyNMS(detections: List<Detection>): List<Detection> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val result = mutableListOf<Detection>()
        while (sorted.isNotEmpty()) {
            val first = sorted.removeAt(0)
            result.add(first)
            sorted.removeAll { intersectionOverUnion(first.box, it.box) > IOU_THRESHOLD }
        }
        return result
    }

    private fun intersectionOverUnion(box1: RectF, box2: RectF): Float {
        val interLeft = max(box1.left, box2.left)
        val interTop = max(box1.top, box2.top)
        val interRight = min(box1.right, box2.right)
        val interBottom = min(box1.bottom, box2.bottom)

        if (interLeft < interRight && interTop < interBottom) {
            val interArea = (interRight - interLeft) * (interBottom - interTop)
            val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
            val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
            return interArea / (box1Area + box2Area - interArea)
        }
        return 0f
    }

    fun close() {
        interpreter.close()
        gpuDelegate?.close()
    }
}
