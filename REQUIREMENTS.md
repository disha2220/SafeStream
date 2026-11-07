# SafeStream - System Requirements & Environment Specifications

This document lists the hardware, software, dependencies and configuration needed to build, run and export models for the **SafeStream** project.

---

## 📱 Hardware Requirements

### Target Mobile Device
* **Android version**: Android 8.0 (API 26) or higher.
* **Processor (CPU)**: ARM64-v8a octa-core (Qualcomm Snapdragon, MediaTek Dimensity, Samsung Exynos or Google Tensor). A physical device is required; the emulator does not support the GPU delegate.
* **Graphics (GPU)**: A GPU supported by the LiteRT GPU delegate (Adreno / Mali / Xclipse with OpenGL ES 3.1+ or OpenCL). If the GPU delegate is unavailable, detection falls back to the CPU automatically (slower).
* **RAM**: Minimum 4 GB (6 GB or higher recommended).
* **Camera**: Front or rear camera. The app analyses frames at 640×360 (16:9).
* **Microphone**: Required for audio in recordings and live streams.
* **Network**: Internet connection with stable upload for live streaming (the video stream is 2.5 Mbit/s plus audio). Not needed for on-device use or local recording.

**Evaluated on**: vivo V2222, indoor lighting, ~13.5 fps end-to-end processing throughput.

### Permissions Used
* `CAMERA` - live camera feed
* `RECORD_AUDIO` - audio capture
* `INTERNET` - RTMPS streaming

---

## 💻 Development Workstation Requirements

* **Operating System**: Windows 10/11 (64-bit), macOS 12+ or Ubuntu 22.04 LTS.
* **RAM**: Minimum 16 GB recommended.
* **Disk Space**: At least 15 GB free (Android SDK, Gradle cache, project build files).

---

## 🔧 Software & Toolchain Specifications

| Tool / Component | Version / Specification |
| :--- | :--- |
| **Android Studio** | Latest stable release (must support compileSdk 37) |
| **JDK** | 17 or newer (Android Studio's bundled JDK works) |
| **Language** | Kotlin; Java source/target compatibility 11 |
| **Min SDK Level** | API 26 (Android 8.0) |
| **Target SDK Level** | API 36 |
| **Compile SDK Level** | API 37 (minor API level 1) |
| **Android Gradle Plugin (AGP)** | 9.1.1 |
| **Gradle** | 9.3.1 (downloaded automatically by the project's Gradle wrapper `gradlew`; do not install manually) |

---

## 📦 Core Libraries & Dependencies

### 1. CameraX
```kotlin
val cameraxVersion = "1.3.4"
implementation("androidx.camera:camera-core:$cameraxVersion")
implementation("androidx.camera:camera-camera2:$cameraxVersion")
implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
implementation("androidx.camera:camera-view:$cameraxVersion")
```

### 2. LiteRT (successor to TensorFlow Lite) & GPU acceleration
```kotlin
implementation("com.google.ai.edge.litert:litert:1.1.0")
implementation("com.google.ai.edge.litert:litert-gpu:1.1.0")
```

### 3. Streaming and recording (RootEncoder)
```kotlin
implementation("com.github.pedroSG94.RootEncoder:library:2.8.0")
```
* Video: H.264, 2.5 Mbit/s at 20 fps. Audio: AAC, 44.1 kHz.
* Transport: RTMPS (e.g. YouTube Live) or local MP4 recording.
* Requires the JitPack repository (`maven { url = uri("https://jitpack.io") }`) in `settings.gradle.kts`.

### 4. OpenCV Android SDK
* Included in the repository as the Gradle module `:opencv` (`implementation(project(":opencv"))`).
* Used for Gaussian-blur redaction of bystander faces.

### 5. Other libraries
* AndroidX Core KTX 1.18.0
* AndroidX AppCompat 1.7.1
* AndroidX Activity 1.13.0
* AndroidX ConstraintLayout 2.2.1
* Material Components 1.13.0
* Gson 2.10.1
* Testing: JUnit 4.13.2, AndroidX JUnit 1.3.0, Espresso 3.7.0

All versions except CameraX, LiteRT, RootEncoder and Gson are managed in `gradle/libs.versions.toml`.

### Build configuration notes
* `.tflite` files are stored uncompressed (`androidResources { noCompress += "tflite" }`) - do not remove this.
* ML model binding is enabled (`buildFeatures { mlModelBinding = true }`).

---

## 🤖 Neural Network Model Specs

Both models live in `app/src/main/assets/`.

### Face detection - `yolov8n-face.tflite`
* **Architecture**: YOLOv8-Nano face detection
* **Input**: 320 × 320 × 3 (RGB)
* **Precision**: Quantized INT8 (Float32 version supported for export)
* **Output tensor**: `[1, 20, 2100]` / `[1, 2100, 20]` (bounding box, confidence and landmarks)
* **Cadence**: runs on every 3rd frame; boxes are cached between detections

### Face recognition - `mobilefacenet.tflite`
* **Architecture**: MobileFaceNet
* **Output**: 192-dimensional face embedding
* **Matching**: cosine similarity against the logged-in account's enrolled samples (match threshold 0.58, keep threshold 0.42)

---

## 🐍 Python Environment (Model Export & Conversion)

Only needed if you want to re-export the detection model; not needed to build or run the app.

* **Python Version**: 3.10 or newer
* **Required Packages**:
  ```bash
  pip install ultralytics torch onnx
  ```
* **Export Execution**:
  ```bash
  python scripts/export.py
  ```