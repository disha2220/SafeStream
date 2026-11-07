package com.safestream.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.view.Surface
import com.pedro.encoder.input.sources.OrientationConfig
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.sources.video.VideoSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.time.Duration.Companion.nanoseconds

/**
 * ROOT CAUSE (confirmed across two test recordings, not a guess):
 * - OrientationForced.PORTRAIT (cameraOrientation=90) -> frame fills the
 *   canvas correctly, but rotated 90 deg (measured: needs 90 CCW to correct)
 * - OrientationForced.LANDSCAPE (cameraOrientation=0) -> no rotation, but
 *   back to letterboxing
 * These two are coupled: the compositor only computes a correct fill when
 * cameraOrientation=90, which means it's internally treating our
 * SurfaceTexture buffer as landscape-shaped (matching a real phone
 * camera's native sensor convention) and rotating 90 to fit the portrait
 * encoder canvas -- same as how a REAL camera source works.
 *
 * FIX: match that convention instead of fighting it. Declare the
 * SurfaceTexture buffer in the LANDSCAPE shape the compositor expects
 * (height x width, swapped), and pre-rotate our own already-upright
 * content -90 degrees before drawing it into that swapped buffer. When
 * the compositor then applies its own +90 rotation to fit the portrait
 * encoder canvas, the two cancel out, giving a correctly-filled, correctly
 * oriented result.
 */
class LiveBitmapSource : VideoSource() {

    private val currentFrame = AtomicReference<Bitmap?>(null)

    @Volatile private var running = false
    private var job: Job? = null
    private var surface: Surface? = null
    private val paint = Paint()

    override fun getOrientationConfig() = OrientationConfig(forced = OrientationForced.PORTRAIT)

    /**
     * width/height here are the PORTRAIT encoder dimensions (e.g. 360x640).
     * We pre-rotate the incoming (already upright) frame -90 degrees and
     * draw it into a buffer sized height x width (the swapped, landscape
     * shape the compositor expects), so its own +90 rotation cancels out.
     */
    fun updateFrame(bitmap: Bitmap) {
        if (width <= 0 || height <= 0) return

        val matrix = Matrix().apply { postRotate(-90f) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

        // rotated is now roughly height x width (swapped) -- stretch-fit
        // to the exact swapped target size to be safe regardless of any
        // rounding.
        val copy = Bitmap.createBitmap(height, width, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(copy)
        canvas.drawBitmap(rotated, null, Rect(0, 0, height, width), null)
        rotated.recycle()

        currentFrame.set(copy)
    }

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean = true

    override fun start(surfaceTexture: SurfaceTexture) {
        this.surfaceTexture = surfaceTexture
        if (isRunning()) return
        // Swapped (landscape shape) to match what the compositor expects
        // under cameraOrientation=90 -- see class-level note.
        surfaceTexture.setDefaultBufferSize(height, width)
        surface = Surface(surfaceTexture)
        running = true
        job = CoroutineScope(Dispatchers.IO).launch {
            val frameInterval = 1_000_000_000L / fps
            var time = System.nanoTime()
            while (running) {
                try {
                    val bmp = currentFrame.get()
                    if (bmp != null) {
                        val canvas = surface?.lockCanvas(null)
                        canvas?.drawBitmap(bmp, 0f, 0f, paint)
                        surface?.unlockCanvasAndPost(canvas)
                    }
                } catch (_: Exception) {
                } finally {
                    time += frameInterval
                    val now = System.nanoTime()
                    if (now - time > frameInterval) time = now
                    delay(max(time - now, 0).nanoseconds)
                }
            }
        }
    }

    override fun stop() {
        running = false
        runBlocking { job?.cancelAndJoin() }
        surface?.release()
        surface = null
    }

    override fun release() {
        currentFrame.set(null)
    }

    override fun isRunning(): Boolean = running
}