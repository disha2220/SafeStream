package com.safestream.app

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import kotlin.math.max
import kotlin.math.min

data class IdentifiedDetection(
    val detection: YoloDetector.Detection,
    val isStreamer: Boolean,
    val similarity: Float
)

class IdentityMatcher(
    private val faceEmbedder: FaceEmbedder,
    private val enrollmentStore: EnrollmentStore,
    private val similarityThreshold: Float = 0.58f
) {
    // Dynamically fetch embeddings so re-enrollment takes effect immediately
    private val enrolledEmbeddings: List<FloatArray>
        get() = enrollmentStore.loadAll()

    private val holdChecks = 10
    private var consecutiveStreamerFrames = 0

    private var lastStreamerBox: RectF? = null
    private var lastBestBox: RectF? = null

    private val keepAliveThreshold = 0.42f
    private val iouRescueThreshold = 0.45f
    private val trackIouThreshold = 0.30f
    private val emaAlpha = 0.4f

    private var emaScore = 0f

    @Synchronized
    fun classify(
        squareBitmap: Bitmap,
        detections: List<YoloDetector.Detection>
    ): List<IdentifiedDetection> {

        val embeddings = enrolledEmbeddings

        // Fail closed: blur all detected faces if nobody is enrolled.
        if (embeddings.isEmpty()) {
            return detections.map {
                IdentifiedDetection(it, false, 0f)
            }
        }

        // Age the hold window when no face is detected.
        if (detections.isEmpty()) {
            lastBestBox = null

            if (consecutiveStreamerFrames > 0) {
                consecutiveStreamerFrames--
            }

            if (consecutiveStreamerFrames == 0) {
                lastStreamerBox = null
                emaScore = 0f
            }

            return emptyList()
        }

        // Score every detected face against enrolled embeddings
        val scored = detections.map { detection ->
            detection to scoreFace(squareBitmap, detection, embeddings)
        }

        val best = scored.maxByOrNull { it.second } ?: return emptyList()
        val bestDetection = best.first
        val bestScore = best.second

        // Exponential Moving Average tracking for spatial continuity
        val previousBestBox = lastBestBox
        val sameTrack = previousBestBox != null &&
                calculateIoU(previousBestBox, bestDetection.box) > trackIouThreshold

        emaScore = if (sameTrack) {
            emaAlpha * bestScore + (1f - emaAlpha) * emaScore
        } else {
            bestScore
        }

        lastBestBox = RectF(bestDetection.box)

        var confirmedThisCall = false

        // Evaluate each face based on identity similarity matching
        val results = scored.map { (detection, score) ->
            var isStreamer = false

            if (score >= similarityThreshold) {
                isStreamer = true
                if (detection === bestDetection) {
                    confirmedThisCall = true
                    consecutiveStreamerFrames = holdChecks
                    lastStreamerBox = RectF(detection.box)
                }
            } else if (detection === bestDetection) {
                // Rescue mechanism if primary streamer face experiences temporary lighting drop
                val previousStreamerBox = lastStreamerBox
                if (consecutiveStreamerFrames > 0 && previousStreamerBox != null) {
                    val iou = calculateIoU(detection.box, previousStreamerBox)
                    if (iou > iouRescueThreshold && emaScore >= keepAliveThreshold) {
                        isStreamer = true
                        lastStreamerBox = RectF(detection.box)
                    }
                }
            }

            IdentifiedDetection(detection, isStreamer, score)
        }

        // Age hold window if main streamer was not positively confirmed this frame
        if (!confirmedThisCall && consecutiveStreamerFrames > 0) {
            consecutiveStreamerFrames--
        }

        if (consecutiveStreamerFrames == 0) {
            lastStreamerBox = null
        }

        Log.d(
            "SafeStream_Match",
            "faces=${detections.size}, " +
                    "best=${"%.2f".format(bestScore)}, " +
                    "ema=${"%.2f".format(emaScore)}, " +
                    "hold=$consecutiveStreamerFrames, " +
                    "streamer=${results.any { it.isStreamer }}"
        )

        return results
    }

    private fun scoreFace(
        squareBitmap: Bitmap,
        detection: YoloDetector.Detection,
        embeddings: List<FloatArray>
    ): Float {
        val faceCrop = FaceCropUtils.cropNormalized(
            squareBitmap,
            detection.box
        ) ?: return 0f

        return try {
            val liveEmbedding = faceEmbedder.embed(faceCrop)

            embeddings.maxOfOrNull { storedEmbedding ->
                FaceEmbedder.cosineSimilarity(
                    liveEmbedding,
                    storedEmbedding
                )
            } ?: 0f
        } catch (e: Exception) {
            Log.e("SafeStream_Match", "Face scoring failed", e)
            0f
        } finally {
            if (faceCrop !== squareBitmap && !faceCrop.isRecycled) {
                faceCrop.recycle()
            }
        }
    }

    private fun calculateIoU(
        box1: RectF,
        box2: RectF
    ): Float {
        val left = max(box1.left, box2.left)
        val top = max(box1.top, box2.top)
        val right = min(box1.right, box2.right)
        val bottom = min(box1.bottom, box2.bottom)

        val intersectionWidth = max(0f, right - left)
        val intersectionHeight = max(0f, bottom - top)
        val intersectionArea = intersectionWidth * intersectionHeight

        val area1 = max(0f, box1.width()) * max(0f, box1.height())
        val area2 = max(0f, box2.width()) * max(0f, box2.height())
        val unionArea = area1 + area2 - intersectionArea

        return if (unionArea <= 0f) {
            0f
        } else {
            intersectionArea / unionArea
        }
    }
}