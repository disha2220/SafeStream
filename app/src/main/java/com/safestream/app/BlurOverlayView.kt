package com.safestream.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class BlurOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var currentBitmap: Bitmap? = null

    // 30% padding on every side to ensure full coverage of jaw, hair, and ears
    private val blurBoxPadding = 0.30f

    // Strong blur kernel cap for high anonymization without performance lag
    private val blurKernelMax = 61

    fun setFrame(blurredBitmap: Bitmap) {
        val oldBmp = currentBitmap
        currentBitmap = blurredBitmap
        if (oldBmp != null && oldBmp !== blurredBitmap) {
            oldBmp.recycle()
        }
        invalidate()
    }

    /**
     * Heavy OpenCV processing. Should be called from a background thread.
     * Returns a copy of source with blurred boxes, or source directly if empty.
     */
    fun applyBlurToBoxes(source: Bitmap, normalizedBoxes: List<RectF>): Bitmap {
        if (normalizedBoxes.isEmpty()) {
            return source
        }

        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)

        for (box in normalizedBoxes) {
            // Expand the box by padding on each side scaling with face size
            val boxW = box.right - box.left
            val boxH = box.bottom - box.top
            val padX = boxW * blurBoxPadding
            val padY = boxH * blurBoxPadding

            val paddedLeftN = box.left - padX
            val paddedTopN = box.top - padY
            val paddedRightN = box.right + padX
            val paddedBottomN = box.bottom + padY

            val left = (paddedLeftN * source.width).toInt().coerceIn(0, source.width - 1)
            val top = (paddedTopN * source.height).toInt().coerceIn(0, source.height - 1)
            val right = (paddedRightN * source.width).toInt().coerceIn(left + 1, source.width)
            val bottom = (paddedBottomN * source.height).toInt().coerceIn(top + 1, source.height)

            if (right <= left || bottom <= top) continue

            val w = right - left
            val h = bottom - top

            try {
                val patch = Bitmap.createBitmap(output, left, top, w, h)
                val mat = Mat()
                Utils.bitmapToMat(patch, mat)

                val k = (minOf(w, h) / 3)
                    .let { if (it % 2 == 0) it + 1 else it }
                    .coerceIn(15, blurKernelMax)

                // Double pass for extra anonymization without lag spikes
                Imgproc.GaussianBlur(mat, mat, Size(k.toDouble(), k.toDouble()), 0.0)
                Imgproc.GaussianBlur(mat, mat, Size(k.toDouble(), k.toDouble()), 0.0)
                Utils.matToBitmap(mat, patch)
                mat.release()

                canvas.drawBitmap(patch, left.toFloat(), top.toFloat(), null)
                patch.recycle()
            } catch (e: Exception) {
                // Safeguard against invalid crop bounds or memory allocation failures
                e.printStackTrace()
            }
        }

        return output
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        currentBitmap?.let { bmp ->
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            val bmpWidth = bmp.width.toFloat()
            val bmpHeight = bmp.height.toFloat()

            if (viewWidth == 0f || viewHeight == 0f) return

            val scale = Math.max(viewWidth / bmpWidth, viewHeight / bmpHeight)
            val dx = (viewWidth - bmpWidth * scale) / 2f
            val dy = (viewHeight - bmpHeight * scale) / 2f

            val matrix = Matrix()
            matrix.postScale(scale, scale)
            matrix.postTranslate(dx, dy)

            canvas.drawBitmap(bmp, matrix, null)
        }
    }
}