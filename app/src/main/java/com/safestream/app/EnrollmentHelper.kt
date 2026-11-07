
package com.safestream.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EnrollmentHelper(
    private val faceEmbedder: FaceEmbedder,
    private val enrollmentStore: EnrollmentStore,
    private val durationSeconds: Int = 8,
    private val sampleIntervalMs: Long = 250L
) {
    @Volatile
    var isEnrolling: Boolean = false
        private set

    @Volatile
    private var lastSampleMs = 0L

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )

    private var job: Job? = null

    @Synchronized
    fun startEnrollment(
        onProgress: (secondsLeft: Int) -> Unit,
        onComplete: () -> Unit
    ) {
        if (isEnrolling) return

        enrollmentStore.clear()
        lastSampleMs = 0L
        isEnrolling = true

        job = scope.launch {
            try {
                for (secondsLeft in durationSeconds downTo 1) {
                    if (!isEnrolling) return@launch

                    withContext(Dispatchers.Main) {
                        onProgress(secondsLeft)
                    }

                    delay(1000L)
                }

                isEnrolling = false

                withContext(Dispatchers.Main) {
                    onComplete()
                }
            } catch (e: Exception) {
                Log.e(
                    "SafeStream_Enrollment",
                    "Enrollment timer failed",
                    e
                )
            } finally {
                isEnrolling = false
            }
        }
    }

    fun readyForSample(): Boolean {
        return isEnrolling &&
                SystemClock.elapsedRealtime() - lastSampleMs >=
                sampleIntervalMs
    }

    @Synchronized
    fun processFrameForEnrollment(faceCrop: Bitmap) {
        if (!isEnrolling || !readyForSample()) return

        // Reserve this sampling interval so repeated camera callbacks
        // cannot start multiple embeddings at once.
        lastSampleMs = SystemClock.elapsedRealtime()

        try {
            val embedding = faceEmbedder.embed(faceCrop)
            enrollmentStore.addSample(embedding)
        } catch (e: Exception) {
            Log.e(
                "SafeStream_Enrollment",
                "Could not process enrollment sample",
                e
            )
        }
    }

    fun samplesCaptured(): Int {
        return enrollmentStore.sampleCount()
    }

    @Synchronized
    fun cancelEnrollment() {
        isEnrolling = false
        job?.cancel()
        job = null
    }

    fun close() {
        cancelEnrollment()
        scope.cancel()
    }
}
