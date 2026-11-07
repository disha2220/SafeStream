package com.safestream.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/** Copies a completed SafeStream MP4 into the phone's Gallery/Movies/SafeStream folder. */
object MediaSaver {
    private const val TAG = "SafeStream_MediaSaver"
    private val ioExecutor = Executors.newSingleThreadExecutor()

    fun saveToGalleryAsync(
        context: Context,
        file: File,
        callback: (Boolean) -> Unit
    ) {
        val appContext = context.applicationContext
        ioExecutor.execute {
            var insertedUri: Uri? = null
            var success = false

            try {
                if (!file.exists() || file.length() == 0L) {
                    throw IOException("Recording file does not exist or is empty: ${file.absolutePath}")
                }

                // MediaStore's scoped-storage flow is supported on Android 10 and newer.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = appContext.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(
                            MediaStore.Video.Media.RELATIVE_PATH,
                            "${Environment.DIRECTORY_MOVIES}/SafeStream"
                        )
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }

                    insertedUri = resolver.insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        values
                    ) ?: throw IOException("Could not create Gallery media entry")

                    resolver.openOutputStream(insertedUri!!, "w").use { output ->
                        if (output == null) throw IOException("Could not open Gallery output stream")
                        file.inputStream().use { input -> input.copyTo(output) }
                    }

                    val publishValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    resolver.update(insertedUri!!, publishValues, null, null)
                    success = true
                } else {
                    // Avoid writing to public storage on older Android versions without
                    // checking/requesting their legacy storage permission. The original
                    // recording remains in the app's Movies/files directory.
                    Log.w(TAG, "Gallery copy requires Android 10+ in this implementation")
                    success = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save recording to Gallery", e)
                if (insertedUri != null) {
                    try {
                        appContext.contentResolver.delete(insertedUri!!, null, null)
                    } catch (deleteError: Exception) {
                        Log.w(TAG, "Could not remove incomplete Gallery entry", deleteError)
                    }
                }
                success = false
            }

            val result = success
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                callback(result)
            }
        }
    }
}
