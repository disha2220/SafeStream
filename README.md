# SafeStream 🛡️📹

**Real-Time Edge AI Middleware for Privacy-Preserving Live Video Anonymization**

SafeStream is an on-device Android middleware that sits between the camera and the streaming encoder. It detects every face in the live camera feed, recognises the single enrolled streamer, and blurs all other faces **before any frame reaches the encoder or the network**. The streamer stays visible; bystanders do not.

---

## 🔑 Key Features

- **Selective anonymization**: keeps the enrolled streamer visible and redacts every other face.
- **Fully on-device**: detection, identity matching, blurring and encoding all run on the phone. The enrolment gallery never leaves the device.
- **Fail-safe by design**: if the streamer's identity cannot be confirmed, the face is blurred rather than exposed.
- **Stable identity decision**: best-match exclusivity, EMA score smoothing and an IoU-gated hold-over (spatial **and** identity must agree) prevent flicker and bystander "hijacking".
- **Bounded-cost redaction**: padded regions and a capped Gaussian kernel remove latency spikes from close-up faces.
- **Live streaming**: H.264 video + AAC audio published over **RTMPS**, or recorded to a local MP4.
- **User accounts**: login and signup screens; passwords are never stored in plain text (salted PBKDF2-HMAC-SHA256 hashes).
- **Per-account face enrolment**: each account has its own registered face, so one user's enrolment is never used for another.
- **Session handling**: the app remembers the logged-in user and offers a one-tap logout.
- **Private storage**: accounts and face data stay on the device; Android cloud backup is disabled (`allowBackup="false"`).

---

## 🛠️ Tech Stack

| Component | Technology |
| --- | --- |
| Language | Kotlin (app), C++ (OpenCV SDK), Python (model export) |
| Camera | CameraX `ImageAnalysis`, 640×360, keep-only-latest |
| Face detection | YOLOv8n-face, TFLite INT8, 320×320 input, GPU delegate with CPU fallback |
| Identity | MobileFaceNet (192-d embeddings), cosine similarity |
| Redaction | OpenCV Gaussian blur (CPU), 30% box padding, kernel 15–61, two passes |
| Encoding / transport | RootEncoder: H.264 2.5 Mbit/s @ 20 fps, AAC 44.1 kHz, RTMPS or local MP4 |
| Accounts and storage | SharedPreferences (private), PBKDF2-HMAC-SHA256 password hashing |

---

## ⚙️ How It Works

1. **Sign up / log in**: the user creates a local account and logs in. The app remembers the session until logout.
2. **Enrolment (once per account)**: the streamer rotates their head during a short timed window; face embeddings are stored on the device as that account's gallery.
3. **Capture**: CameraX delivers frames; older frames are dropped rather than queued to keep latency bounded.
4. **Detection**: YOLOv8n-face runs on a centre-square crop, **every 3rd frame**; boxes are cached on the frames in between.
5. **Identity scoring**: each face is embedded with MobileFaceNet and scored by its best cosine similarity against the logged-in account's gallery.
6. **Temporal decision**: only the best-scoring face can be the streamer (EMA-smoothed, α = 0.4, threshold 0.58). A hold-over of up to 5 detection frames requires IoU > 0.45 **and** smoothed score ≥ 0.42.
7. **Redaction**: bystander boxes are padded and Gaussian-blurred; frames with no bystanders pass through without a copy.
8. **Output**: the redacted bitmap is shown in the preview and is the **only** thing fed to the encoder, so an unprocessed frame cannot reach the network.

---

## 📊 Results

Evaluated on two live multi-person sessions (up to 3 people in frame, 594 annotated face classifications) on a vivo V2222 under indoor lighting:

| Metric | Value |
| --- | --- |
| Accuracy (streamer vs bystander) | 90.24% |
| Precision / Recall (streamer class) | 92.88% / 90.81% |
| F1-score | 91.83% |
| Bystander redaction rate | 89.36% |
| Mean processing time per frame | 74.1 ms (~13.5 fps throughput) |
| Detection frame / cached-box frame | 152.9 ms / 34.6 ms |

Bounding the blur kernel removed redaction spikes of 200–390 ms (capped at 68.8 ms in profiling).

**Known limitations**

- Streamer-to-bystander handoff is the main residual leak (68% of false positives).
- Processing throughput (~13.5 fps) is below the encoder's 20 fps, which can trigger "insufficient video" warnings on some platforms.
- No automatic RTMPS reconnection yet.
- Accounts and face enrolments are stored locally on each device only (no cloud sync or password recovery).
- Evaluation covers two sessions, three participants, one device and indoor lighting only.
- Gaussian blur is not provably irreversible.

---

## 📁 Repository Structure

```
SafeStream/
├── app/src/main/
│   ├── java/com/safestream/app/
│   │   ├── LoginActivity.kt       # Login screen (launcher), session start
│   │   ├── SignupActivity.kt      # Account creation, hashed passwords
│   │   ├── MainActivity.kt        # CameraX pipeline, camera UI, logout
│   │   ├── YoloDetector.kt        # TFLite face detection
│   │   ├── FaceEmbedder.kt        # MobileFaceNet embeddings
│   │   ├── FaceCropUtils.kt       # Face crop and normalisation
│   │   ├── IdentityMatcher.kt     # Streamer vs bystander decision
│   │   ├── EnrollmentHelper.kt    # Streamer enrolment capture
│   │   ├── EnrollmentStore.kt     # On-device enrolment gallery (per account)
│   │   ├── BlurOverlayView.kt     # Preview overlay and blur
│   │   ├── LiveBitmapSource.kt    # Feeds redacted frames to the encoder
│   │   └── StreamManager.kt       # RTMPS streaming / MP4 recording
│   ├── assets/                    # TFLite models
│   └── res/                       # Layouts (login, signup, camera) and resources
├── scripts/                       # Model export and conversion
├── sdk/                           # OpenCV Android SDK (Gradle module :opencv)
├── gradle/wrapper/                # Gradle wrapper (version config)
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew, gradlew.bat           # Gradle wrapper scripts
├── .gitignore
├── REQUIREMENTS.md
└── README.md
```

---

## 🚀 Getting Started

**Prerequisites**: Android Studio (2024.1+), Android SDK API 24+ (target 34/35), JDK 17 or 21, a physical ARM64 Android device (the emulator lacks GPU delegate support), Python 3.10+ (only for model export). See `REQUIREMENTS.md` for details.

1. Clone the repository:

   ```
   git clone https://github.com/project14team/SafeStream.git
   cd SafeStream
   ```

2. Make sure `yolov8n-face.tflite` and `mobilefacenet.tflite` are in `app/src/main/assets/`.
3. Open the project in Android Studio and let Gradle sync.
4. Connect a physical device with USB debugging enabled and run the app.
5. **Sign up** with your name, email and password, then **log in**.
6. Tap **Register My Face** and slowly rotate your head until enrolment completes (once per account).
7. Start recording, or paste your RTMPS URL and tap **Go Live**. Use **Logout** to switch accounts.

> Accounts and registered faces live only on the phone where they were created, so every new device needs a fresh signup and enrolment. Never commit your stream key to the repository.

---

## 👥 Team

Amrutha, Deeksha K P, Disha, Gagana M R
Guide: Dr. Shwetha C. H., Department of CSE, MITE

## 📜 License

Developed for academic and non-commercial research purposes at the Mangalore Institute of Technology & Engineering (MITE).