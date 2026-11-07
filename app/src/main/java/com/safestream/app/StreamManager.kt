package com.safestream.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.library.generic.GenericStream

/**
 * UPDATED: prepare() previously caught only IllegalArgumentException and
 * returned a plain false on failure, silently swallowing the actual
 * reason. Confirmed from RootEncoder source: MicrophoneSource throws
 * exactly that exception type when it can't create the audio recorder --
 * e.g. RECORD_AUDIO permission not actually granted -- so a real failure
 * reason was being thrown away every time. Now caught, logged, and
 * exposed via lastError so the UI can show something diagnosable instead
 * of a generic "failed" toast.
 */
class StreamManager(
    context: Context,
    connectChecker: ConnectChecker
) {
    private val liveBitmapSource = LiveBitmapSource()
    private val genericStream = GenericStream(context, connectChecker, liveBitmapSource, MicrophoneSource())

    var isPrepared = false
        private set

    var lastError: String? = null
        private set

    fun prepare(width: Int, height: Int, videoBitrateBps: Int = 2_500_000, fps: Int = 20): Boolean {
        lastError = null
        isPrepared = try {
            val videoOk = genericStream.prepareVideo(width, height, videoBitrateBps, fps)
            if (!videoOk) lastError = "Video encoder rejected width=$width height=$height (unsupported resolution?)"

            val audioOk = genericStream.prepareAudio(44100, true, 128_000)
            if (!audioOk && lastError == null) lastError = "Audio encoder setup failed"

            videoOk && audioOk
        } catch (e: Exception) {
            // Catches IllegalArgumentException (e.g. RECORD_AUDIO not granted,
            // unsupported params) AND anything else instead of only one type.
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e("SafeStream_Stream", "prepare() failed", e)
            false
        }
        return isPrepared
    }

    fun pushFrame(bitmap: Bitmap) {
        liveBitmapSource.updateFrame(bitmap)
    }

    fun startLocalRecording(outputFilePath: String, onStatus: (String) -> Unit = {}) {
        genericStream.startRecord(outputFilePath) { status -> onStatus(status.toString()) }
    }

    fun stopLocalRecording() {
        if (genericStream.isRecording) genericStream.stopRecord()
    }

    fun startRtmpsStream(url: String) {
        if (!genericStream.isStreaming) genericStream.startStream(url)
    }

    fun stopStream() {
        if (genericStream.isStreaming) genericStream.stopStream()
    }

    val isRecording: Boolean get() = genericStream.isRecording
    val isStreaming: Boolean get() = genericStream.isStreaming

    fun release() {
        genericStream.release()
    }
}