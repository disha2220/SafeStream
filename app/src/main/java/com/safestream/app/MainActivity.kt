package com.safestream.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.Size
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import org.opencv.android.OpenCVLoader
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: androidx.camera.view.PreviewView
    private lateinit var blurOverlay: BlurOverlayView
    private lateinit var detector: YoloDetector
    private lateinit var cameraExecutor: ExecutorService
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private lateinit var faceEmbedder: FaceEmbedder
    private lateinit var enrollmentStore: EnrollmentStore
    @Volatile private lateinit var identityMatcher: IdentityMatcher
    private lateinit var enrollmentHelper: EnrollmentHelper

    // ---- Stage A: local recording ----
    private lateinit var streamManager: StreamManager
    @Volatile private var isRecording = false
    @Volatile private var pendingRecordingStart = false
    private var currentRecordingFile: File? = null

    // ---- Stage B: live RTMPS ----
    @Volatile private var pendingStreamStart = false
    private var pendingStreamUrl: String = ""

    // ---- Background detection (keeps the video path at a steady pace) ----
    // Face detection + matching used to run inside the frame callback on every
    // 3rd frame. Those frames took far longer than the others, which made the
    // stream stutter. Now detection runs on its own thread and the frame path
    // just applies the most recent blur boxes.
    private val detectExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val detectBusy = AtomicBoolean(false)
    @Volatile private var latestBoxes: List<RectF> = emptyList()
    @Volatile private var detectionReady = false
    @Volatile private var lastDetectionAtMs = 0L
    // TFLite interpreters are not thread-safe; detector + embedder are shared
    // between the enrollment path and the detection thread.
    private val modelLock = Any()
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            Log.d("SafeStream_Stream", "Connection started: $url")
        }
        override fun onConnectionSuccess() {
            Log.d("SafeStream_Stream", "Connection success")
            runOnUiThread {
                findViewById<Button>(R.id.btnGoLive)?.text = "Stop Live"
                Toast.makeText(this@MainActivity, "Live!", Toast.LENGTH_SHORT).show()
            }
        }
        override fun onConnectionFailed(reason: String) {
            Log.e("SafeStream_Stream", "Connection failed: $reason")
            runOnUiThread {
                findViewById<Button>(R.id.btnGoLive)?.text = "Go Live"
                Toast.makeText(this@MainActivity, "Stream failed: $reason", Toast.LENGTH_LONG).show()
            }
        }
        override fun onDisconnect() {
            Log.d("SafeStream_Stream", "Disconnected")
            runOnUiThread {
                findViewById<Button>(R.id.btnGoLive)?.text = "Go Live"
            }
        }
        override fun onAuthError() {
            Log.e("SafeStream_Stream", "Auth error")
            runOnUiThread {
                findViewById<Button>(R.id.btnGoLive)?.text = "Go Live"
                Toast.makeText(this@MainActivity, "Auth error - check your stream key", Toast.LENGTH_LONG).show()
            }
        }
        override fun onAuthSuccess() {
            Log.d("SafeStream_Stream", "Auth success")
        }
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val cameraGranted = results[Manifest.permission.CAMERA] == true
            val audioGranted = results[Manifest.permission.RECORD_AUDIO] == true
            if (cameraGranted) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            }
            if (!audioGranted) {
                Toast.makeText(this, "Microphone permission needed for recording", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        OpenCVLoader.initDebug()

        previewView = findViewById(R.id.previewView)
        blurOverlay = findViewById(R.id.blurOverlay)
        detector = YoloDetector(this, "yolov8n-face.tflite")
        faceEmbedder = FaceEmbedder(this, "mobilefacenet.tflite")
        enrollmentStore = EnrollmentStore(this)
        identityMatcher = IdentityMatcher(faceEmbedder, enrollmentStore)
        enrollmentHelper = EnrollmentHelper(faceEmbedder, enrollmentStore)
        streamManager = StreamManager(this, connectChecker)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Stream URL box: no auto-capitalization / auto-correct, so the
        // keyboard can't turn "rtmp://" into "Rtmp://".
        findViewById<EditText>(R.id.etStreamUrl).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }

        Toast.makeText(this, "Welcome to SafeStream", Toast.LENGTH_SHORT).show()

        // Logout button is optional: add a Button with android:id="@+id/btnLogout"
        // to activity_main.xml to enable it. Looked up by name so the app still
        // builds before you add the button.
        val logoutId = resources.getIdentifier("btnLogout", "id", packageName)
        if (logoutId != 0) {
            findViewById<Button>(logoutId)?.setOnClickListener { logoutToLogin() }
        }

        if (!enrollmentStore.isEnrolled()) {
            Toast.makeText(
                this,
                "Register your face first - until then every face, including yours, is blurred",
                Toast.LENGTH_LONG
            ).show()
        }

        findViewById<ImageButton>(R.id.switchCameraButton).setOnClickListener {
            toggleCamera()
        }

        findViewById<Button>(R.id.btnRegisterFace)?.setOnClickListener {
            enrollmentHelper.startEnrollment(
                onProgress = { secondsLeft ->
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Only you in view. Slowly turn your head... $secondsLeft sec left",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onComplete = {
                    identityMatcher = IdentityMatcher(faceEmbedder, enrollmentStore)
                    val count = enrollmentHelper.samplesCaptured()
                    runOnUiThread {
                        val msg = if (count < MIN_ENROLL_SAMPLES)
                            "Couldn't capture your face ($count samples). Keep only your face in view with good light and try again."
                        else
                            "Whitelisting complete! Streamer registered ($count samples)."
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        findViewById<Button>(R.id.btnRecord)?.setOnClickListener { view ->
            if (!isRecording) {
                pendingRecordingStart = true
                (view as? Button)?.text = "Starting..."
            } else {
                streamManager.stopLocalRecording()
                isRecording = false
                (view as? Button)?.text = "Start Recording"
                Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
                currentRecordingFile?.let { f ->
                    MediaSaver.saveToGalleryAsync(this, f) { ok ->
                        Toast.makeText(
                            this,
                            if (ok) "Saved to Gallery > Movies/SafeStream"
                            else "Saved in app folder only: ${f.absolutePath}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnGoLive)?.setOnClickListener { view ->
            if (!streamManager.isStreaming) {
                val url = normalizeStreamUrl(
                    findViewById<EditText>(R.id.etStreamUrl).text.toString()
                )
                if (url.isEmpty()) {
                    Toast.makeText(this, "Paste your stream URL + key first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!url.startsWith("rtmp://") && !url.startsWith("rtmps://")) {
                    Toast.makeText(
                        this,
                        "URL must start with rtmp:// or rtmps:// (yours starts with: ${url.take(8)})",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                // Common mistake: server address pasted without the stream key.
                if (url.removePrefix("rtmps://").removePrefix("rtmp://").trimEnd('/').count { it == '/' } < 2) {
                    Toast.makeText(
                        this,
                        "Add your stream key at the end: .../live2/YOUR-KEY",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                pendingStreamUrl = url
                pendingStreamStart = true
                (view as? Button)?.text = "Connecting..."
            } else {
                streamManager.stopStream()
                (view as? Button)?.text = "Go Live"
                Toast.makeText(this, "Live stream stopped", Toast.LENGTH_SHORT).show()
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionsLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    /** Removes spaces/line breaks/invisible characters and lowercases the scheme ("Rtmp://" -> "rtmp://"). The key part keeps its original case. */
    private fun normalizeStreamUrl(raw: String): String {
        val cleaned = raw.replace(Regex("\\s+"), "").replace("\u200B", "")
        val idx = cleaned.indexOf("://")
        return if (idx > 0) cleaned.substring(0, idx).lowercase() + cleaned.substring(idx) else cleaned
    }

    private fun toggleCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        // Old boxes belong to the other camera's picture; don't send frames
        // out until the new camera has been checked for faces.
        detectionReady = false
        lastDetectionAtMs = 0L
        latestBoxes = emptyList()
        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val previewResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(previewResolutionSelector)
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val analysisResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(analysisResolutionSelector)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, FrameAnalyzer())
                }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e("MainActivity", "Binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private var dimsLogCounter = 0
    private var timingLogCounter = 0
    private var detectLogCounter = 0

    /**
     * PRIVACY FIX: the old cropToSquare() kept only the CENTER square of the
     * frame. In a portrait 360x640 frame that is a 360x360 band, so faces in
     * the top and bottom ~22% of the picture were never detected and never
     * blurred. Instead we now pad the whole frame into a square (black bars)
     * so the detector sees everything. The normalized box coordinates map
     * back to the frame with the same formula as before (see mapBoxToFrame).
     * The square is also capped at DETECT_SIDE so a higher camera resolution
     * later does not slow detection down.
     */
    private fun padToSquare(bitmap: Bitmap): Bitmap {
        val srcSide = maxOf(bitmap.width, bitmap.height)
        val scale = DETECT_SIDE.toFloat() / srcSide
        val out = Bitmap.createBitmap(DETECT_SIDE, DETECT_SIDE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        val dst = RectF(
            (DETECT_SIDE - dw) / 2f, (DETECT_SIDE - dh) / 2f,
            (DETECT_SIDE + dw) / 2f, (DETECT_SIDE + dh) / 2f
        )
        canvas.drawBitmap(bitmap, null, dst, scalePaint)
        return out
    }

    /** Square-normalized detector box -> frame-normalized blur box (+margin). */
    private fun mapBoxToFrame(b: RectF, w: Float, h: Float): RectF {
        val side = maxOf(w, h)
        val ox = (w - side) / 2f
        val oy = (h - side) / 2f
        val l = (b.left * side + ox) / w
        val t = (b.top * side + oy) / h
        val r = (b.right * side + ox) / w
        val bt = (b.bottom * side + oy) / h
        // The box is up to a few frames old when applied, so grow it a bit
        // to keep covering a face that has moved.
        val padX = (r - l) * BLUR_MARGIN
        val padY = (bt - t) * BLUR_MARGIN
        return RectF(
            (l - padX).coerceIn(0f, 1f),
            (t - padY).coerceIn(0f, 1f),
            (r + padX).coerceIn(0f, 1f),
            (bt + padY).coerceIn(0f, 1f)
        )
    }

    inner class FrameAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            val tFrameStart = System.nanoTime()
            try {
                val isFrontCamera = lensFacing == CameraSelector.LENS_FACING_FRONT

                val tConvertStart = System.nanoTime()
                val fullBitmap = imageProxy.toUprightBitmap(isFrontCamera) ?: run {
                    Log.e("TEST", "Failed to convert ImageProxy to Bitmap")
                    return
                }
                val tConvertEnd = System.nanoTime()

                dimsLogCounter++
                if (dimsLogCounter % 30 == 0) {
                    Log.d(
                        "SafeStream_Dims",
                        "fullBitmap: ${fullBitmap.width}x${fullBitmap.height}, " +
                                "imageProxy: ${imageProxy.width}x${imageProxy.height}, " +
                                "rotation: ${imageProxy.imageInfo.rotationDegrees}"
                    )
                }

                if (pendingRecordingStart) {
                    pendingRecordingStart = false
                    val ready = if (streamManager.isPrepared) true
                    else streamManager.prepare(fullBitmap.width, fullBitmap.height)
                    if (ready) {
                        val outputFile = File(
                            getExternalFilesDir(null),
                            "safestream_${System.currentTimeMillis()}.mp4"
                        )
                        currentRecordingFile = outputFile
                        streamManager.startLocalRecording(outputFile.absolutePath) { status ->
                            Log.d("SafeStream_Record", "Status: $status")
                        }
                        isRecording = true
                        runOnUiThread {
                            findViewById<Button>(R.id.btnRecord)?.text = "Stop Recording"
                            Toast.makeText(
                                this@MainActivity,
                                "Recording: ${outputFile.name}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        runOnUiThread {
                            findViewById<Button>(R.id.btnRecord)?.text = "Start Recording"
                            Toast.makeText(this@MainActivity, "Failed: ${streamManager.lastError}", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                if (pendingStreamStart) {
                    pendingStreamStart = false
                    val ready = if (streamManager.isPrepared) true
                    else streamManager.prepare(fullBitmap.width, fullBitmap.height)
                    if (ready) {
                        streamManager.startRtmpsStream(pendingStreamUrl)
                    } else {
                        runOnUiThread {
                            findViewById<Button>(R.id.btnGoLive)?.text = "Go Live"
                            Toast.makeText(this@MainActivity, "Failed: ${streamManager.lastError}", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                val w = fullBitmap.width.toFloat()
                val h = fullBitmap.height.toFloat()

                // ---------------- Enrollment ----------------
                if (enrollmentHelper.isEnrolling) {
                    // Only run the detector when a sample is actually due.
                    if (enrollmentHelper.readyForSample()) {
                        val squareBitmap = padToSquare(fullBitmap)
                        try {
                            synchronized(modelLock) {
                                // Largest face only, so a bystander is never whitelisted.
                                val biggest = detector.detect(squareBitmap)
                                    .maxByOrNull { it.box.width() * it.box.height() }
                                if (biggest != null) {
                                    val faceCrop = FaceCropUtils.cropNormalized(squareBitmap, biggest.box)
                                    if (faceCrop != null) {
                                        enrollmentHelper.processFrameForEnrollment(faceCrop)
                                        if (faceCrop !== squareBitmap) faceCrop.recycle()
                                    }
                                }
                            }
                        } finally {
                            squareBitmap.recycle()
                        }
                    }

                    val shown = blurOverlay.applyBlurToBoxes(fullBitmap, emptyList())
                    if (shown !== fullBitmap) fullBitmap.recycle()
                    runOnUiThread { blurOverlay.setFrame(shown) }
                    return
                }

                // ---------------- Kick off detection in the background ----------------
                // If the previous detection is still running, skip: the frame
                // path never waits for it.
                if (detectBusy.compareAndSet(false, true)) {
                    val snapshot = padToSquare(fullBitmap)
                    val matcher = identityMatcher
                    detectExecutor.execute {
                        val t0 = System.nanoTime()
                        try {
                            val toBlur: List<RectF> = synchronized(modelLock) {
                                val detections = detector.detect(snapshot)
                                matcher.classify(snapshot, detections)
                                    .filter { !it.isStreamer }
                                    .map { it.detection.box }
                            }
                            latestBoxes = toBlur.map { mapBoxToFrame(it, w, h) }
                            lastDetectionAtMs = android.os.SystemClock.elapsedRealtime()
                            detectionReady = true

                            detectLogCounter++
                            if (detectLogCounter % 10 == 0) {
                                Log.d(
                                    "SafeStream_Timing",
                                    "detect+match=${"%.1f".format((System.nanoTime() - t0) / 1_000_000.0)}ms " +
                                            "blurBoxes=${toBlur.size}"
                                )
                            }
                        } catch (e: Exception) {
                            // Privacy-first fallback: if detection fails, blur the full frame
                            // until a successful detection refreshes the face boxes.
                            latestBoxes = listOf(RectF(0f, 0f, 1f, 1f))
                            lastDetectionAtMs = android.os.SystemClock.elapsedRealtime()
                            detectionReady = true
                            Log.e("TEST", "Detection error: ${e.message}", e)
                        } finally {
                            snapshot.recycle()
                            detectBusy.set(false)
                        }
                    }
                }

                // ---------------- Privacy-first fast path ----------------
                // Face boxes are normalized (0f..1f). Never reuse cached boxes
                // after detection has become stale: a moving bystander could
                // otherwise leave the old blur region while their face is clear.
                val nowMs = android.os.SystemClock.elapsedRealtime()
                val detectionAgeMs = nowMs - lastDetectionAtMs
                val boxes: List<RectF> = if (
                    !detectionReady ||
                    lastDetectionAtMs == 0L ||
                    detectionAgeMs > MAX_DETECTION_AGE_MS
                ) {
                    // Fail closed: blur the entire frame until detection refreshes.
                    listOf(RectF(0f, 0f, 1f, 1f))
                } else {
                    latestBoxes
                }

                val tBlurStart = System.nanoTime()
                val processedBitmap = blurOverlay.applyBlurToBoxes(fullBitmap, boxes)
                val tBlurEnd = System.nanoTime()

                if (processedBitmap !== fullBitmap) fullBitmap.recycle()

                val tPushStart = System.nanoTime()
                // Fail closed: nothing leaves the phone until the first face
                // check has finished, otherwise the first fraction of a second
                // would go out with no bystander blur at all.
                if (detectionReady && (isRecording || streamManager.isStreaming)) {
                    streamManager.pushFrame(processedBitmap)
                }
                val tPushEnd = System.nanoTime()

                runOnUiThread {
                    blurOverlay.setFrame(processedBitmap)
                }

                val tFrameEnd = System.nanoTime()

                timingLogCounter++
                if (timingLogCounter % 15 == 0) {
                    fun ms(start: Long, end: Long) = "%.1f".format((end - start) / 1_000_000.0)
                    Log.d(
                        "SafeStream_Timing",
                        "TOTAL=${ms(tFrameStart, tFrameEnd)}ms | " +
                                "convert=${ms(tConvertStart, tConvertEnd)} " +
                                "blur=${ms(tBlurStart, tBlurEnd)} (boxes=${boxes.size}) " +
                                "push=${ms(tPushStart, tPushEnd)}"
                    )
                }
            } catch (e: Exception) {
                Log.e("TEST", "Error in FrameAnalyzer: ${e.message}", e)
            } finally {
                imageProxy.close()
            }
        }
    }

    /** Return to login without deleting registered users or enrolled face data. */
    private fun logoutToLogin() {
        val intent = android.content.Intent(this, LoginActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        enrollmentHelper.close()
        if (isRecording) streamManager.stopLocalRecording()
        if (streamManager.isStreaming) streamManager.stopStream()
        streamManager.release()
        cameraExecutor.shutdown()
        detectExecutor.shutdownNow()
        synchronized(modelLock) {
            detector.close()
            faceEmbedder.close()
        }
    }

    companion object {
        private const val DETECT_SIDE = 640
        private const val BLUR_MARGIN = 0.20f
        private const val MAX_DETECTION_AGE_MS = 450L
        private const val MIN_ENROLL_SAMPLES = 5
    }
}

fun ImageProxy.toUprightBitmap(mirror: Boolean = false): Bitmap? {
    val bitmap = try {
        this.toBitmap()
    } catch (e: Exception) {
        Log.e("MainActivity", "toBitmap failed", e)
        null
    } ?: return null

    val rotation = imageInfo.rotationDegrees.toFloat()
    val matrix = Matrix()

    if (rotation != 0f) matrix.postRotate(rotation)
    if (mirror) matrix.postScale(-1f, 1f)

    return if (rotation != 0f || mirror) {
        val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        bitmap.recycle()
        result
    } else {
        bitmap
    }
}
