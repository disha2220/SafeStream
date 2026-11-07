package com.safestream.app

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Shared crop logic — correctly handles YoloDetector.Detection.box, which
 * is normalized 0.0-1.0 RELATIVE TO WHATEVER BITMAP WAS PASSED TO
 * detector.detect(). In this project that's the square-cropped bitmap
 * (see MainActivity.FrameAnalyzer.cropToSquare), not the full camera frame.
 */
object FaceCropUtils {

    fun cropNormalized(bitmap: Bitmap, box: RectF, margin: Float = 0.15f): Bitmap? {
        val boxWpx = box.width() * bitmap.width
        val boxHpx = box.height() * bitmap.height

        val left = ((box.left * bitmap.width) - boxWpx * margin)
            .toInt().coerceIn(0, bitmap.width - 1)
        val top = ((box.top * bitmap.height) - boxHpx * margin)
            .toInt().coerceIn(0, bitmap.height - 1)
        val right = ((box.right * bitmap.width) + boxWpx * margin)
            .toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ((box.bottom * bitmap.height) + boxHpx * margin)
            .toInt().coerceIn(top + 1, bitmap.height)

        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null

        return Bitmap.createBitmap(bitmap, left, top, w, h)
    }
}
