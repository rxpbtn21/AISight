# AISight Mobile — expanded Android port

This is a native Android/Java port of the feature set in `thesisfinale.py`. It uses phone hardware and on-device runtimes instead of Raspberry Pi modules. The project includes the uploaded custom 27-class `models/aisight.pt` weights; the official `yolo26n-depth.pt` weights and Vosk model are downloaded automatically in the hosted build workflow.

> **Honest status:** the mobile implementation and automated build pipeline are included, but this environment has no Android SDK/Gradle, so I could not compile or test the APK on an actual phone here. Model export tensor shapes, inference speed, CameraX use-case compatibility, voice recognition, fall thresholds, and distance accuracy all need a successful hosted build and device test before this should be relied on. This is a thesis prototype, not a safety-rated mobility aid.

## Feature coverage

| Original Pi feature | Android implementation in this project |
|---|---|
| Custom YOLO object detector | Exports `models/aisight.pt` to `aisight.tflite` and uses the uploaded model's 27 trained class names for on-device inference. Displays bounding boxes, class, confidence, and estimated depth when preview is enabled. Generic ML Kit fallback exists for diagnosing a build without the custom model. |
| YOLO26n depth estimator | Exports the official `yolo26n-depth.pt` model to `depth.tflite`; runs a dense depth map, smooths successive estimates, samples the median within detected boxes, and checks the central region for persistent near surfaces. Ultralytics documents this released model as predicting depth in meters, but this output remains an estimate—not a calibrated rangefinder. |
| Distance buckets, 3-way/5-way position, class priority | Mirrors the source script's near/mid buckets, left/front/right position bins, class priority tiers, and closeness/area weighting. |
| Default / advanced / navigation modes | Voice commands and settings cycle these modes. Navigation mode uses near detections and simple step/stop advice based on the center/left/right regions. This heuristic has not been validated for safe navigation. |
| English / Tagalog labels | In-app translations and Android TextToSpeech. Tagalog pronunciation depends on an installed device voice; Vosk commands use the bundled small English model. |
| Piper gender/speed/volume | Android TextToSpeech replaces Piper. Speech rate, volume, and best-effort voice selection are adjustable; exact Piper timbre is not available on every phone. |
| Vosk wake word / commands | Offline Vosk Android recognition listens while AISight is foregrounded. Say “AISight” or “Eyesight”, then the command. The hosted workflow bundles `vosk-model-small-en-us-0.15`. |
| Per-class pause/resume | Voice command pauses one object class for 1–5 minutes and can resume it. |
| OCR / “read text” | On-device ML Kit Latin text recognition, triggered by button or voice command. |
| 5/10/30/60-second video recording | CameraX writes silent videos to `Movies/AISight` using MediaStore. Recording is silent by design so Vosk can keep listening. Some camera hardware may not support preview + analysis + recording simultaneously; the app falls back to camera analysis without recording if needed. |
| MPU6050 fall heuristic | Uses the phone accelerometer with freefall-then-impact thresholds inspired by the source script. It may false-trigger or miss falls depending on where/how the phone is carried. It is not medical or emergency-grade fall detection. |
| GPIO buzzer and alarm timeout | Phone vibration plus a repeated alarm tone, with a silence control and a 30-second maximum for fall alarms (shorter 5-second camera/mic failsafes). This is not a separate wearable buzzer. |
| Camera/mic failsafes | Camera heartbeat is tracked before inference, heavy inference runs on a separate worker, and a camera outage alert is latched once until a real frame confirms recovery. Microphone faults also warn/retry. Android cannot reliably confirm that the physical speaker is audible, so the original speaker-failure counter is not reproduced. |
| Battery tracker | Announces current Android battery percentage and AISight's active session duration; warns at 15% battery. Reset command resets the session-duration counter. This replaces the Pi's fixed 8-hour consumption approximation. |
| Headless mode | Black overlay, near-minimum screen brightness, hidden preview/detection text, a slower analysis interval, and a compact high-contrast control row. The visible dashboard uses a status card, live-camera chip, rounded preview, scene snapshot, and larger controls. Keeps the activity in the foreground and leaves Show Camera, Start/Pause, Settings, and conditional Silence Alarm controls. It does not turn off the screen, stop camera inference, or guarantee a particular battery saving. |
| Pi `shutdown -h now` | Android apps cannot normally power off the phone. Voice “shutdown” asks for confirmation and then closes AISight only. |

## Latest UI and failsafe improvements

The main dashboard has been restyled with a high-contrast purple/navy palette, system status indicator, live-camera badge, scene snapshot, and more readable control buttons. The camera fault now alarms once per continuous outage and clears the latch only when CameraX delivers a real frame. Camera callbacks close frames quickly and send heavy object/depth inference to a dedicated single-flight worker; additional frames are dropped while inference is busy, so slow inference does not build a backlog or falsely trip the camera watchdog. The scene snapshot can show up to five objects even when voice announcements are capped to fewer objects. The code changes have not been validated with a fresh hosted APK/device test yet.

## Build the installable APK

The source contains `.github/workflows/build-apk.yml`. The workflow uses `android-actions/setup-android@v4` and installs only `platform-tools` at setup; it then explicitly installs Android Platform 36 and Build Tools 36.0.0 to satisfy CameraX 1.6.2 metadata requirements. This avoids the removed Google SDK package named `tools`, which caused an earlier setup step to fail. The recommended route is:

1. Download and extract the ZIP; open the `AISightMobile` folder.
2. Create a **private** GitHub repository (the ZIP contains your custom `aisight.pt` weights) and put the **contents** of `AISightMobile` at the repository root (`app/`, `models/`, `scripts/`, `.github/`, `build.gradle`, etc.).
3. Push it to `main` or `master`, or run **Actions → Build AISight Mobile APK → Run workflow**.
4. The workflow installs Ultralytics export dependencies, exports both models, downloads/unpacks the Vosk offline model, then runs `gradle assembleDebug`.
5. Download the workflow artifact named **`AISight-Mobile-debug-apk`**. Install `app-debug.apk` on an Android phone and grant Camera and Microphone permissions.

The first hosted build can be large and take several minutes because it downloads/export-converts model weights and bundles the offline speech model. If export fails, open the failed **Export custom AISight + YOLO26 depth models** step; Ultralytics sometimes changes export details between releases and the Java tensor decoder may need to be adjusted to the exact tensors reported by the exporter.

## Build locally

Required: JDK 17, Android SDK Platform 36 and Build Tools 36.0.0, Gradle 8.11.1, Python 3.12, plus internet access to download/export the official depth model and the Vosk model. CameraX is pinned to stable 1.6.2 (listed by Android Developers as the stable release on August 26, 2026); the model exporter is pinned to Ultralytics 8.4.182 for reproducibility.

```bash
python -m pip install -r scripts/requirements-export.txt
python scripts/export_models.py
# Also download and unpack vosk-model-small-en-us-0.15 into:
# app/src/main/assets/vosk-model-small-en-us-0.15/
gradle assembleDebug --no-daemon
```

APK path: `app/build/outputs/apk/debug/app-debug.apk`.

## Voice command examples

Commands must begin with **AISight** or **Eyesight**. The offline recognizer may mishear unusual class labels or numbers; check the detection display when visible.

- `AISight start`, `continue`, `resume`, `stop`, `pause`, `quiet`
- `AISight default`, `advanced`, `navigation`
- `AISight English`, `Tagalog`
- `AISight male voice`, `female voice`, `fast`, `slow`, `normal`, `volume high/normal/low`
- `AISight one/two/three objects`
- `AISight pause person for two minutes`, `AISight resume person`
- `AISight read text` / `AISight OCR`
- `AISight record five/ten/thirty/sixty seconds`, `AISight stop recording`
- `AISight battery`, `AISight reset battery`
- `AISight buzzer stop`
- `AISight shutdown`, then `AISight yes` or `AISight no` to confirm/cancel closing the app

## Important Android behavior and safety limitations

- Headless means a black, dimmed **foreground activity**. The screen remains on (at low brightness) so Android continues allowing camera use. If the app is sent to the background or the screen is locked, Android may suspend its activity-bound camera and microphone. A persistent background camera/microphone service would require a separate foreground-service design and Android's permission/notification behavior; this build does not claim invisible always-on operation.
- The phone sensor is not the MPU6050 and has different orientation/noise characteristics. Phone alarm/vibration is not the Pi buzzer and may be inaudible or unavailable.
- Monocular model depth is not a physical distance sensor. The center-region wall check, stop/step advice, thresholds, and fall detection are heuristics. They have not been evaluated for real-world reliability.
- Keep using a cane, guide dog, or other established mobility support. Do not use this app as the sole means of avoiding obstacles, crossing roads, or responding to falls.
- The app's detector, depth estimator, OCR, and wake-word model run locally after installation. The build workflow downloads model files to construct the APK; runtime camera frames are not sent to a server by this code.

## Project layout

- `models/aisight.pt` — uploaded custom 27-class detector weights
- `scripts/export_models.py` — exports detector and `yolo26n-depth.pt` to `app/src/main/assets/*.tflite`
- `app/src/main/java/.../YoloTfliteDetector.java` — detector inference and non-maximum suppression
- `app/src/main/java/.../DepthTfliteEstimator.java` — dense depth inference, temporal smoothing, object-depth sampling, wall-region check
- `app/src/main/java/.../WakeWordListener.java` — offline Vosk microphone loop
- `app/src/main/java/.../DetectionOverlayView.java` — preview bounding boxes and labels
- `app/src/main/java/.../MainActivity.java` — camera, OCR, voice commands/settings, video recording, fall alerts, battery, failsafe, headless mode
- `.github/workflows/build-apk.yml` — hosted export and debug-APK build


## App icon refresh

The Android launcher now uses the AISight eye-and-microphone logo as both legacy density-specific icons and an Android adaptive icon (API 26+). The launcher label is `AISight`; the app version is `1.3-icon-refresh` (version code 4). Rebuild via GitHub Actions to apply it to the APK.
