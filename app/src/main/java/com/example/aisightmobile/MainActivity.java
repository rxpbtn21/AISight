package com.example.aisightmobile;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AISight's Android port. Pi-only devices are replaced by the phone camera, microphone,
 * accelerometer, vibration motor, alarm tone, TextToSpeech, and CameraX recording.
 */
public class MainActivity extends ComponentActivity implements SensorEventListener {
    private static final int PERMISSION_REQUEST = 31;
    private static final String PREFS = "aisight_mobile_prefs";
    private static final float DEPTH_WALL_THRESHOLD_M = 1.3f;
    private static final float WALL_COVERAGE_RATIO = 0.50f;
    private static final long WALL_CONFIRM_MS = 3000L;
    private static final long CAMERA_TIMEOUT_MS = 4000L;
    private static final long FAILSAFE_ALARM_MS = 5000L;
    private static final long MAX_ALARM_MS = 30000L;
    private static final long VOICE_COOLDOWN_MS = 4500L;
    private static final long NAV_REPEAT_MS = 12000L;
    private static final float FALL_FREEFALL_RATIO = 0.4f;
    private static final float FALL_IMPACT_RATIO = 2.5f;
    private static final long FALL_WINDOW_MS = 1000L;
    private static final long FALL_COOLDOWN_MS = 30000L;

    private static final String[] MODEL_LABELS = {
            "stop_sign", "e-tricycle", "car", "person", "e-bike", "e-trike", "tricycle", "van",
            "bench", "bike", "bus", "cat", "crosswalk", "dog", "manhole", "hazard_sign", "jeepney",
            "motorcycle", "door", "pit_hole", "puddle", "ramps", "stairs", "tree", "truck",
            "uncovered_manhole", "utility_pole"
    };
    private static final Map<String, String> CLASS_TIER = new HashMap<>();
    private static final Map<String, Float> TIER_WEIGHT = new HashMap<>();
    private static final Map<String, String> SPOKEN_CLASS_ALIASES = new HashMap<>();
    static {
        String high = "person car van bus truck jeepney motorcycle tricycle e-tricycle e-trike e-bike bike stop_sign stairs pit_hole uncovered_manhole hazard_sign utility_pole";
        String medium = "bench cat dog tree door puddle crosswalk ramps";
        String low = "manhole wall/obstruction";
        for (String s : high.split(" ")) CLASS_TIER.put(s, "high");
        for (String s : medium.split(" ")) CLASS_TIER.put(s, "medium");
        for (String s : low.split(" ")) CLASS_TIER.put(s, "low");
        TIER_WEIGHT.put("high", 1.0f); TIER_WEIGHT.put("medium", 0.6f); TIER_WEIGHT.put("low", 0.3f);
        String[][] aliases = {
                {"stop sign", "stop_sign"}, {"electric tricycle", "e-tricycle"}, {"electric trike", "e-trike"},
                {"electric bike", "e-bike"}, {"tricycle", "tricycle"}, {"car", "car"}, {"person", "person"},
                {"people", "person"}, {"van", "van"}, {"bench", "bench"}, {"bike", "bike"},
                {"bicycle", "bike"}, {"bus", "bus"}, {"cat", "cat"}, {"crosswalk", "crosswalk"},
                {"dog", "dog"}, {"manhole", "manhole"}, {"hazard sign", "hazard_sign"},
                {"jeepney", "jeepney"}, {"motorcycle", "motorcycle"}, {"door", "door"},
                {"pit hole", "pit_hole"}, {"pothole", "pit_hole"}, {"puddle", "puddle"},
                {"ramp", "ramps"}, {"ramps", "ramps"}, {"stairs", "stairs"}, {"stair", "stairs"},
                {"tree", "tree"}, {"truck", "truck"}, {"uncovered manhole", "uncovered_manhole"},
                {"utility pole", "utility_pole"}, {"pole", "utility_pole"}, {"wall", "wall/obstruction"},
                {"obstruction", "wall/obstruction"}
        };
        for (String[] alias : aliases) SPOKEN_CLASS_ALIASES.put(alias[0], alias[1]);
        SPOKEN_CLASS_ALIASES.put("e bike", "e-bike");
        SPOKEN_CLASS_ALIASES.put("e trike", "e-trike");
        SPOKEN_CLASS_ALIASES.put("e tricycle", "e-tricycle");
    }

    private final int bg = Color.rgb(8, 10, 18);
    private final int panel = Color.rgb(19, 23, 36);
    private final int purple = Color.rgb(167, 139, 250);
    private final int white = Color.rgb(245, 247, 255);
    private final int muted = Color.rgb(161, 171, 194);
    private final int mint = Color.rgb(153, 246, 228);
    private final int warning = Color.rgb(251, 113, 133);
    private final int stroke = Color.rgb(43, 49, 68);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService modelExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean readTextRequested = new AtomicBoolean(false);
    private final AtomicBoolean inferenceInProgress = new AtomicBoolean(false);
    private final Map<String, Long> pausedClasses = new ConcurrentHashMap<>();

    private LinearLayout root, header, controls, topControls, bottomControls;
    private TextView statusView, detectionsView, headlessMessage;
    private TextView statusDotView, detectionCountView, modelBadgeView, cameraChipView;
    private LinearLayout statusCard, detectionCard;
    private Button batteryButton;
    private volatile String currentStatus = "LOADING MODELS";
    private PreviewView previewView;
    private DetectionOverlayView overlayView;
    private Button viewButton, settingsButton, readTextButton, voiceButton, startPauseButton,
            recordButton, silenceButton;

    private ProcessCameraProvider cameraProvider;
    private ObjectDetector fallbackDetector;
    private TextRecognizer textRecognizer;
    private YoloTfliteDetector yoloDetector;
    private DepthTfliteEstimator depthEstimator;
    private DepthTfliteEstimator.DepthMap latestDepthMap;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private Uri activeRecordingUri;
    private final Handler recordingHandler = new Handler(Looper.getMainLooper());
    private Runnable stopRecordingRunnable;

    private TextToSpeech tts;
    private boolean ttsReady;
    private WakeWordListener wakeWordListener;
    private boolean wakeWordStartPending;
    private boolean micFaulted;
    private volatile boolean cameraFaulted;
    private volatile boolean cameraFaultAlertLatched;
    private boolean modelErrorSpoken;
    private String customModelLoadError = "";
    private String depthModelLoadError = "";
    private boolean usingCustomModel;
    private boolean usingDepthModel;
    private String detectorStatus = "LOADING MODELS";

    private volatile boolean headless;
    private volatile boolean voiceEnabled;
    private volatile boolean tagalog;
    private volatile boolean announcementsArmed;
    private volatile float confidenceThreshold;
    private volatile String phraseMode;
    private volatile String voiceGender;
    private volatile String voiceSpeed;
    private volatile String voiceVolume;
    private volatile int maxSpeak;
    private volatile boolean recordingInProgress;

    private long frameCount;
    private volatile long lastFrameAt;
    private volatile long cameraBindAt;
    private long lastCameraRetryAt;
    private long lastAnalysisAt;
    private long lastSpokenAt;
    private String lastSpokenMessage = "";
    private String lastNavigationSignature = "";
    private long wallCandidateAt;
    private boolean wallDebounced;
    private boolean alarmActive;
    private long alarmDeadline;
    private ToneGenerator toneGenerator;
    private Vibrator vibrator;
    private Runnable alarmToneRunnable;
    private Runnable alarmStopRunnable;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private final float[] calibrationReadings = new float[50];
    private int calibrationCount;
    private float fallBaseline;
    private long freefallAt = -1L;
    private long lastFallAt;

    private int batteryPercent = -1;
    private long batteryAccumulatedMs;
    private long batterySessionStart = SystemClock.elapsedRealtime();
    private long lastBatteryCheckpointAt = SystemClock.elapsedRealtime();
    private long lastBatteryAnnouncementHour;
    private boolean lowBatteryWarned;
    private BroadcastReceiver batteryReceiver;
    private boolean permissionsChecked;
    private boolean cameraStartInFlight;
    private volatile boolean modelsLoading = true;
    private volatile boolean activityDestroyed;

    private final Runnable watchdogRunnable = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            boolean foreground = getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED);
            if (foreground) tickBatteryTracker(now);
            if (!isFinishing() && foreground && cameraBindAt > 0) {
                long reference = lastFrameAt > 0 ? lastFrameAt : cameraBindAt;
                if (now - reference > CAMERA_TIMEOUT_MS) {
                    if (!cameraFaulted) reportCameraFault("Camera disconnected. Trying to recover.");
                    if (!currentStatus.contains("CAMERA DISCONNECTED") && !currentStatus.contains("CAMERA ERROR")) {
                        setStatus("CAMERA DISCONNECTED · RETRYING");
                    }
                    if (now - lastCameraRetryAt > 6000L && cameraProvider != null && !cameraStartInFlight) {
                        lastCameraRetryAt = now;
                        cameraBindAt = 0L;
                        try { cameraProvider.unbindAll(); } catch (Exception ignored) { }
                        startCamera();
                    }
                }
            } else if (!isFinishing() && foreground && permissionsChecked
                    && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    && cameraBindAt <= 0 && !cameraStartInFlight && now - lastCameraRetryAt > 6000L) {
                // Retry even if CameraX created a provider but failed while binding a use case.
                lastCameraRetryAt = now;
                startCamera();
            }
            mainHandler.postDelayed(this, 750L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        SharedPreferences p = prefs();
        headless = p.getBoolean("headless", false);
        voiceEnabled = p.getBoolean("voice", true);
        tagalog = p.getBoolean("tagalog", false);
        announcementsArmed = p.getBoolean("armed", false);
        confidenceThreshold = p.getFloat("confidence", 0.45f);
        phraseMode = p.getString("phraseMode", "advanced");
        voiceGender = p.getString("gender", "female");
        voiceSpeed = p.getString("speed", "normal");
        voiceVolume = p.getString("volume", "normal");
        maxSpeak = p.getInt("maxSpeak", 2);

        createTextRecognizer();
        createSpeech();
        createAlarm();
        createSensors();
        createBatteryTracker();
        buildUi();
        applyDisplayMode();
        loadDetectorsAsync();
        mainHandler.post(watchdogRunnable);
        requestRequiredPermissions();
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }
    private void saveBoolean(String key, boolean value) { prefs().edit().putBoolean(key, value).apply(); }

    /** Load both neural networks off the UI thread so first launch remains responsive. */
    private void loadDetectorsAsync() {
        detectorStatus = "LOADING AISIGHT MODELS";
        setStatus(detectorStatus);
        modelExecutor.execute(() -> {
            YoloTfliteDetector loadedYolo = null;
            ObjectDetector loadedFallback = null;
            DepthTfliteEstimator loadedDepth = null;
            String customError = "";
            String depthError = "";
            boolean customLoaded = false;
            boolean depthLoaded = false;
            try {
                loadedYolo = new YoloTfliteDetector(getApplicationContext());
                customLoaded = true;
            } catch (Exception ex) {
                customError = message(ex);
                try {
                    ObjectDetectorOptions options = new ObjectDetectorOptions.Builder()
                            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                            .enableMultipleObjects().enableClassification().build();
                    loadedFallback = ObjectDetection.getClient(options);
                } catch (Exception fallbackError) {
                    customError += " | Generic fallback failed: " + message(fallbackError);
                }
            }
            try {
                loadedDepth = new DepthTfliteEstimator(getApplicationContext());
                depthLoaded = true;
            } catch (Exception ex) {
                depthError = message(ex);
            }

            final YoloTfliteDetector finalYolo = loadedYolo;
            final ObjectDetector finalFallback = loadedFallback;
            final DepthTfliteEstimator finalDepth = loadedDepth;
            final String finalCustomError = customError;
            final String finalDepthError = depthError;
            final boolean finalCustomLoaded = customLoaded;
            final boolean finalDepthLoaded = depthLoaded;
            runOnUiThread(() -> {
                if (activityDestroyed || isFinishing()) {
                    if (finalYolo != null) finalYolo.close();
                    if (finalFallback != null) finalFallback.close();
                    if (finalDepth != null) finalDepth.close();
                    return;
                }
                yoloDetector = finalYolo;
                fallbackDetector = finalFallback;
                depthEstimator = finalDepth;
                customModelLoadError = finalCustomError;
                depthModelLoadError = finalDepthError;
                usingCustomModel = finalCustomLoaded;
                usingDepthModel = finalDepthLoaded;
                detectorStatus = usingCustomModel ? "CUSTOM AISIGHT YOLO26N" :
                        (fallbackDetector != null ? "GENERIC ML KIT FALLBACK" : "NO DETECTOR AVAILABLE");
                modelsLoading = false;
                setStatus(detectorStatus + (usingDepthModel ? " + DEPTH" : " + DEPTH UNAVAILABLE"));
                refreshModelBadge();
                if (!customModelLoadError.isEmpty()) {
                    android.util.Log.w("AISight", "Custom detector unavailable: " + customModelLoadError);
                }
                if (!depthModelLoadError.isEmpty()) {
                    android.util.Log.w("AISight", "Depth model unavailable: " + depthModelLoadError);
                }
                if (!usingCustomModel && fallbackDetector == null) {
                    announceFault("No object detector could be loaded. Please reinstall the AISight model package.");
                } else if (!usingDepthModel) {
                    speakPriority("Warning. The depth model is unavailable. Distance and wall estimates are disabled.");
                }
                applyDisplayMode();
            });
        });
    }

    private void createTextRecognizer() { textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS); }

    private void createSpeech() {
        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                configureTts();
                speakPriority(tagalog
                        ? "AISight ay nakaandar. Sabihin ang AISight start upang simulan ang mga anunsyo."
                        : "AISight is active. Say AISight start to begin announcements.");
            } else {
                startAlarm(FAILSAFE_ALARM_MS);
            }
        });
    }

    private void configureTts() {
        if (tts == null || !ttsReady) return;
        Locale locale = tagalog ? new Locale("fil", "PH") : Locale.US;
        int result = tts.setLanguage(locale);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US);
        }
        tts.setSpeechRate("fast".equals(voiceSpeed) ? 1.2f : "slow".equals(voiceSpeed) ? 0.78f : 1.0f);
        tts.setPitch("female".equals(voiceGender) ? 1.07f : 0.92f);
        try {
            List<Voice> voices = new ArrayList<>(tts.getVoices());
            String wantedLocale = tagalog ? "fil" : "en-us";
            Voice selected = null;
            for (Voice voice : voices) {
                String name = voice.getName().toLowerCase(Locale.US);
                String voiceLocale = voice.getLocale().toLanguageTag().toLowerCase(Locale.US);
                if (!voiceLocale.startsWith(wantedLocale)) continue;
                boolean matchingName;
                if ("female".equals(voiceGender)) {
                    matchingName = name.contains("female") || name.contains("woman") || name.contains("_f_");
                } else {
                    matchingName = (name.contains("male") && !name.contains("female")) || name.contains("_m_") || name.contains("man");
                }
                if (matchingName && !voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) {
                    selected = voice; break;
                }
            }
            if (selected != null) tts.setVoice(selected);
        } catch (Exception ignored) { }
    }

    private void createAlarm() {
        try { toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 90); }
        catch (Exception ignored) { toneGenerator = null; }
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        alarmToneRunnable = new Runnable() {
            @Override public void run() {
                if (!alarmActive) return;
                long remaining = alarmDeadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) { stopAlarm(); return; }
                try { if (toneGenerator != null) toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800); }
                catch (Exception ignored) { }
                mainHandler.postDelayed(this, 1100L);
            }
        };
        alarmStopRunnable = this::stopAlarm;
    }

    private void createSensors() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    private void createBatteryTracker() {
        batteryAccumulatedMs = Math.max(0L, prefs().getLong("batteryElapsedMs", 0L));
        batterySessionStart = SystemClock.elapsedRealtime();
        lastBatteryCheckpointAt = batterySessionStart;
        lastBatteryAnnouncementHour = batteryAccumulatedMs / 3600000L;
        batteryReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                int level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) batteryPercent = Math.round(100f * level / scale);
                if (batteryPercent >= 0 && batteryPercent <= 15 && !lowBatteryWarned) {
                    lowBatteryWarned = true;
                    speakPriority(tagalog ? "Babala. Labinlimang porsiyento o mas mababa na ang baterya." :
                            "Warning. Phone battery is at fifteen percent or lower.");
                } else if (batteryPercent > 18) lowBatteryWarned = false;
            }
        };
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(14), dp(6), dp(14), dp(10));

        // Brand/header
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = new TextView(this);
        mark.setText("A"); mark.setTextSize(21); mark.setGravity(Gravity.CENTER);
        mark.setTextColor(bg); mark.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        mark.setBackground(roundedBackground(purple, 14, purple));
        header.addView(mark, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(dp(11), 0, 0, 0);
        TextView title = new TextView(this);
        title.setText("AISIGHT"); title.setTextColor(white); title.setTextSize(20);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        TextView subtitle = new TextView(this);
        subtitle.setText("VISION ASSISTANT  /  MOBILE"); subtitle.setTextColor(muted); subtitle.setTextSize(9.5f);
        subtitle.setLetterSpacing(0.08f);
        brand.addView(title); brand.addView(subtitle);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));

        settingsButton = makeButton("⚙");
        settingsButton.setTextSize(21); settingsButton.setContentDescription("Open AISight settings");
        settingsButton.setOnClickListener(v -> showSettings());
        header.addView(settingsButton, new LinearLayout.LayoutParams(dp(48), dp(44)));
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(-1, dp(52));
        headerLp.bottomMargin = dp(9);
        root.addView(header, headerLp);

        // Status card with a live health indicator and model badge
        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setPadding(dp(13), dp(10), dp(12), dp(10));
        statusCard.setBackground(roundedBackground(panel, 15, stroke));
        statusDotView = new TextView(this); statusDotView.setText("●"); statusDotView.setTextSize(15);
        statusDotView.setTextColor(purple);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(18), -2);
        dotLp.rightMargin = dp(8); statusCard.addView(statusDotView, dotLp);

        LinearLayout statusColumn = new LinearLayout(this); statusColumn.setOrientation(LinearLayout.VERTICAL);
        TextView statusLabel = sectionText("SYSTEM STATUS");
        statusView = new TextView(this); statusView.setTextColor(white); statusView.setTextSize(12);
        statusView.setMaxLines(2); statusView.setText(currentStatus);
        statusColumn.addView(statusLabel); statusColumn.addView(statusView);
        statusCard.addView(statusColumn, new LinearLayout.LayoutParams(0, -2, 1f));

        modelBadgeView = new TextView(this); modelBadgeView.setTextSize(9); modelBadgeView.setGravity(Gravity.CENTER);
        modelBadgeView.setPadding(dp(9), dp(7), dp(9), dp(7));
        modelBadgeView.setTextColor(mint); modelBadgeView.setBackground(roundedBackground(Color.rgb(22, 47, 48), 10, Color.rgb(40, 84, 81)));
        statusCard.addView(modelBadgeView, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.bottomMargin = dp(10); root.addView(statusCard, statusLp);

        // Camera viewport and visual detections
        FrameLayout viewport = new FrameLayout(this);
        viewport.setBackground(roundedBackground(Color.BLACK, 18, stroke));
        viewport.setClipToOutline(true);
        viewport.setElevation(dp(2));
        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setBackgroundColor(Color.BLACK); previewView.setClipToOutline(true);
        viewport.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        overlayView = new DetectionOverlayView(this);
        viewport.addView(overlayView, new FrameLayout.LayoutParams(-1, -1));

        cameraChipView = new TextView(this); cameraChipView.setText("●  LIVE CAMERA"); cameraChipView.setTextSize(9.5f);
        cameraChipView.setTextColor(mint); cameraChipView.setPadding(dp(10), dp(7), dp(10), dp(7));
        cameraChipView.setBackground(roundedBackground(Color.rgb(12, 20, 25), 10, Color.rgb(42, 72, 76)));
        FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        chipLp.setMargins(dp(10), dp(10), dp(10), dp(10)); viewport.addView(cameraChipView, chipLp);

        headlessMessage = new TextView(this);
        headlessMessage.setBackgroundColor(Color.BLACK);
        headlessMessage.setTextColor(Color.BLACK);
        headlessMessage.setText("");
        headlessMessage.setVisibility(View.GONE);
        viewport.addView(headlessMessage, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams viewportLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        viewportLp.bottomMargin = dp(10); viewport.setMinimumHeight(dp(200));
        root.addView(viewport, viewportLp);

        detectionCard = new LinearLayout(this);
        detectionCard.setOrientation(LinearLayout.VERTICAL);
        detectionCard.setPadding(dp(13), dp(11), dp(13), dp(11));
        detectionCard.setBackground(roundedBackground(panel, 15, stroke));
        LinearLayout detectionHeading = new LinearLayout(this);
        detectionHeading.setOrientation(LinearLayout.HORIZONTAL); detectionHeading.setGravity(Gravity.CENTER_VERTICAL);
        TextView detectionTitle = sectionText("SCENE SNAPSHOT");
        detectionHeading.addView(detectionTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        detectionCountView = new TextView(this); detectionCountView.setText("0 OBJECTS"); detectionCountView.setTextColor(purple);
        detectionCountView.setTextSize(9.5f); detectionCountView.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        detectionCountView.setPadding(dp(9), dp(5), dp(9), dp(5));
        detectionCountView.setBackground(roundedBackground(Color.rgb(40, 31, 62), 9, Color.rgb(67, 52, 99)));
        detectionHeading.addView(detectionCountView);
        detectionCard.addView(detectionHeading);

        detectionsView = new TextView(this); detectionsView.setTextColor(white); detectionsView.setTextSize(12.5f);
        detectionsView.setLineSpacing(dp(3), 1.0f); detectionsView.setMaxLines(4);
        detectionsView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        detectionsView.setText("Waiting for camera frames…");
        LinearLayout.LayoutParams detectionBodyLp = new LinearLayout.LayoutParams(-1, -2);
        detectionBodyLp.topMargin = dp(7); detectionCard.addView(detectionsView, detectionBodyLp);
        LinearLayout.LayoutParams detectionLp = new LinearLayout.LayoutParams(-1, -2);
        detectionLp.bottomMargin = dp(8); root.addView(detectionCard, detectionLp);

        controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL);
        topControls = new LinearLayout(this); topControls.setOrientation(LinearLayout.HORIZONTAL); topControls.setGravity(Gravity.CENTER);
        bottomControls = new LinearLayout(this); bottomControls.setOrientation(LinearLayout.HORIZONTAL); bottomControls.setGravity(Gravity.CENTER);

        viewButton = makeButton("HEADLESS");
        viewButton.setOnClickListener(v -> { headless = true; saveBoolean("headless", true); applyDisplayMode(); });
        readTextButton = makeButton("READ TEXT"); readTextButton.setOnClickListener(v -> requestReadText());
        startPauseButton = makeButton(announcementsArmed ? "PAUSE VOICE" : "START VOICE");
        startPauseButton.setOnClickListener(v -> setAnnouncementsArmed(!announcementsArmed, true));
        recordButton = makeButton("RECORD 10S");
        recordButton.setOnClickListener(v -> { if (recordingInProgress) stopRecording(true); else startRecording(10); });
        voiceButton = makeButton(voiceEnabled ? "VOICE ON" : "VOICE OFF");
        voiceButton.setOnClickListener(v -> {
            voiceEnabled = !voiceEnabled; saveBoolean("voice", voiceEnabled);
            voiceButton.setText(voiceEnabled ? "VOICE ON" : "VOICE OFF"); refreshButtonStyle(voiceButton);
            if (voiceEnabled) speakPriority(tagalog ? "Boses naka-on." : "Voice announcements on.");
        });
        batteryButton = makeButton("BATTERY"); batteryButton.setOnClickListener(v -> speakPriority(batteryStatus()));
        silenceButton = makeButton("SILENCE ALARM"); silenceButton.setOnClickListener(v -> stopAlarm());

        controls.addView(topControls); controls.addView(bottomControls);
        root.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        applyDisplayMode();
        updateStatusIndicator(currentStatus);
        refreshModelBadge();
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1f);
        p.setMargins(dp(3), dp(3), dp(3), dp(3)); return p;
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text); button.setTextSize(11.5f); button.setTextColor(white); button.setAllCaps(false);
        button.setPadding(dp(5), 0, dp(5), 0); button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(44)); button.setBackground(buttonBackground(text));
        button.setContentDescription(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) button.setElevation(dp(1));
        return button;
    }

    private GradientDrawable roundedBackground(int color, int radiusDp, int borderColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), borderColor);
        return drawable;
    }

    private GradientDrawable buttonBackground(String text) {
        String t = text == null ? "" : text.toUpperCase(Locale.US);
        int fill = Color.rgb(31, 36, 53);
        int edge = Color.rgb(55, 62, 84);
        if (t.contains("SILENCE")) { fill = Color.rgb(76, 29, 45); edge = Color.rgb(137, 55, 77); }
        else if (t.contains("START") || t.contains("READ TEXT") || t.contains("SHOW CAMERA")) { fill = Color.rgb(101, 70, 177); edge = Color.rgb(159, 132, 241); }
        else if (t.contains("PAUSE") || t.contains("VOICE ON")) { fill = Color.rgb(48, 39, 74); edge = Color.rgb(91, 73, 132); }
        return roundedBackground(fill, 13, edge);
    }

    private void refreshButtonStyle(Button button) {
        if (button != null) button.setBackground(buttonBackground(String.valueOf(button.getText())));
    }

    private TextView sectionText(String text) {
        TextView label = new TextView(this); label.setText(text); label.setTextColor(muted);
        label.setTextSize(9.5f); label.setLetterSpacing(0.09f);
        label.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return label;
    }

    private void refreshModelBadge() {
        if (modelBadgeView == null) return;
        String model = modelsLoading ? "LOADING" : (usingCustomModel ? "AISIGHT YOLO26" : "ML KIT FALLBACK");
        modelBadgeView.setText(model + (usingDepthModel ? " · DEPTH" : " · NO DEPTH"));
        modelBadgeView.setTextColor(usingCustomModel && usingDepthModel ? mint : (modelsLoading ? purple : warning));
    }

    private void updateStatusIndicator(String message) {
        if (statusDotView == null) return;
        String text = message == null ? "" : message.toUpperCase(Locale.US);
        boolean bad = text.contains("ERROR") || text.contains("FAILED") || text.contains("DISCONNECT")
                || text.contains("TIMEOUT") || text.contains("UNAVAILABLE") || text.contains("MISSING");
        boolean good = text.contains("ACTIVE") || text.contains("RESTORED") || text.contains("READY")
                || text.contains("SAVED") || text.contains("CUSTOM AISIGHT");
        statusDotView.setTextColor(bad ? warning : (good ? mint : purple));
        if (cameraChipView != null) {
            if (text.contains("CAMERA DISCONNECTED") || text.contains("CAMERA ERROR") || text.contains("CAMERA TIMEOUT")) {
                cameraChipView.setText("●  CAMERA RECOVERING");
                cameraChipView.setTextColor(warning);
            } else if ((text.contains("CAMERA ACTIVE") && !cameraFaulted && !cameraFaultAlertLatched)
                    || text.contains("CAMERA RESTORED") || text.contains("LIVE FEED OK")) {
                cameraChipView.setText("●  LIVE CAMERA");
                cameraChipView.setTextColor(mint);
            }
        }
    }

    private void applyDisplayMode() {
        if (root == null) return;
        refreshModelBadge();
        if (headless) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            WindowManager.LayoutParams lp = getWindow().getAttributes(); lp.screenBrightness = 0.025f; getWindow().setAttributes(lp);
            header.setVisibility(View.GONE); statusCard.setVisibility(View.GONE); detectionCard.setVisibility(View.GONE);
            previewView.setVisibility(View.VISIBLE); overlayView.setVisibility(View.VISIBLE); headlessMessage.setVisibility(View.VISIBLE);
            controls.removeAllViews(); topControls.removeAllViews(); bottomControls.removeAllViews();
            viewButton.setText("SHOW CAMERA"); viewButton.setContentDescription("Show camera preview");
            viewButton.setOnClickListener(v -> { headless = false; saveBoolean("headless", false); applyDisplayMode(); });
            refreshButtonStyle(viewButton);
            startPauseButton.setText(announcementsArmed ? "PAUSE" : "START");
            startPauseButton.setContentDescription(announcementsArmed ? "Pause spoken announcements" : "Start spoken announcements");
            refreshButtonStyle(startPauseButton);
            Button settings = makeButton("SETTINGS"); settings.setOnClickListener(v -> showSettings());
            topControls.addView(viewButton, weightedButton()); topControls.addView(startPauseButton, weightedButton());
            topControls.addView(settings, weightedButton()); controls.addView(topControls);
            if (alarmActive) controls.addView(silenceButton, new LinearLayout.LayoutParams(-1, dp(46)));
        } else {
            getWindow().getDecorView().setSystemUiVisibility(0);
            WindowManager.LayoutParams lp = getWindow().getAttributes(); lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
            header.setVisibility(View.VISIBLE); statusCard.setVisibility(View.VISIBLE); detectionCard.setVisibility(View.VISIBLE);
            previewView.setVisibility(View.VISIBLE); overlayView.setVisibility(View.VISIBLE); headlessMessage.setVisibility(View.GONE);
            controls.removeAllViews(); topControls.removeAllViews(); bottomControls.removeAllViews();
            viewButton.setText("HEADLESS"); viewButton.setContentDescription("Hide camera preview and use headless mode");
            viewButton.setOnClickListener(v -> { headless = true; saveBoolean("headless", true); applyDisplayMode(); });
            refreshButtonStyle(viewButton);
            startPauseButton.setText(announcementsArmed ? "PAUSE VOICE" : "START VOICE");
            startPauseButton.setContentDescription(announcementsArmed ? "Pause spoken announcements" : "Start spoken announcements");
            refreshButtonStyle(startPauseButton);
            updateRecordButton();
            voiceButton.setText(voiceEnabled ? "VOICE ON" : "VOICE OFF"); refreshButtonStyle(voiceButton);
            topControls.addView(viewButton, weightedButton()); topControls.addView(readTextButton, weightedButton());
            topControls.addView(startPauseButton, weightedButton());
            bottomControls.addView(recordButton, weightedButton()); bottomControls.addView(voiceButton, weightedButton());
            bottomControls.addView(batteryButton, weightedButton());
            controls.addView(topControls); controls.addView(bottomControls);
            if (alarmActive) controls.addView(silenceButton, new LinearLayout.LayoutParams(-1, dp(44)));
            statusView.setText(currentStatus);
            updateStatusIndicator(currentStatus);
        }
        controls.setVisibility(View.VISIBLE);
    }

    private void requestRequiredPermissions() {
        List<String> need = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.CAMERA);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (need.isEmpty()) { permissionsChecked = true; startCamera(); startWakeWordListener(); }
        else ActivityCompat.requestPermissions(this, need.toArray(new String[0]), PERMISSION_REQUEST);
    }

    private void startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (cameraStartInFlight || (cameraProvider != null && cameraBindAt > 0)) return;
        cameraStartInFlight = true;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                Preview preview = new Preview.Builder().build(); preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);
                Recorder recorder = new Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.SD)).build();
                videoCapture = VideoCapture.withOutput(recorder);
                cameraProvider.unbindAll();
                try {
                    cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, videoCapture);
                } catch (Exception threeUseCaseFailure) {
                    videoCapture = null;
                    cameraProvider.unbindAll();
                    cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
                    setStatus("CAMERA ACTIVE · VIDEO RECORDING UNSUPPORTED ON THIS DEVICE");
                }
                cameraBindAt = SystemClock.elapsedRealtime(); lastFrameAt = 0;
                cameraStartInFlight = false;
                setStatus("CAMERA ACTIVE · " + detectorStatus + (usingDepthModel ? " + DEPTH" : " + DEPTH UNAVAILABLE"));
            } catch (Exception ex) {
                cameraStartInFlight = false;
                cameraBindAt = 0L;
                setStatus("CAMERA ERROR · " + message(ex)); reportCameraFault("Camera could not start. Check camera permission and try again.");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(@NonNull ImageProxy proxy) {
        // Heartbeat is updated as soon as CameraX hands over a frame, before any AI work.
        // Neural inference is offloaded so slow depth/object models cannot look like a disconnected camera.
        long now = SystemClock.elapsedRealtime();
        lastFrameAt = now;
        if (cameraFaulted || cameraFaultAlertLatched) {
            mainHandler.post(() -> {
                if (cameraFaulted || cameraFaultAlertLatched) {
                    cameraFaulted = false;
                    cameraFaultAlertLatched = false;
                    speakPriority("Camera restored.");
                    setStatus("CAMERA RESTORED · LIVE FEED OK");
                }
            });
        }
        if (modelsLoading || activityDestroyed) { proxy.close(); return; }

        long minInterval = headless ? 850L : 280L;
        if (now - lastAnalysisAt < minInterval || !inferenceInProgress.compareAndSet(false, true)) {
            // Keep accepting the camera heartbeat while deliberately dropping queued frames.
            proxy.close();
            return;
        }
        lastAnalysisAt = now;

        Bitmap frame = null;
        boolean queued = false;
        try {
            frame = YoloTfliteDetector.imageProxyToUprightBitmap(proxy);
            final Bitmap capturedFrame = frame;
            modelExecutor.execute(() -> analyzeBitmap(capturedFrame));
            queued = true;
            frame = null; // the inference worker now owns this bitmap
        } catch (Exception ex) {
            setStatus("CAMERA FRAME ERROR · " + message(ex));
        } finally {
            try { proxy.close(); } catch (Exception ignored) { }
            if (!queued) {
                if (frame != null && !frame.isRecycled()) frame.recycle();
                inferenceInProgress.set(false);
            }
        }
    }

    private void analyzeBitmap(Bitmap frame) {
        if (activityDestroyed || frame == null || frame.isRecycled()) {
            releaseInferenceFrame(frame);
            return;
        }
        frameCount++;
        try {
            if (readTextRequested.compareAndSet(true, false)) {
                InputImage input = InputImage.fromBitmap(frame, 0);
                textRecognizer.process(input)
                        .addOnSuccessListener(this::onTextRead)
                        .addOnFailureListener(e -> {
                            setStatus("TEXT READING FAILED");
                            speakPriority(tagalog ? "Hindi mabasa ang teksto." : "Could not read text.");
                        })
                        .addOnCompleteListener(task -> releaseInferenceFrame(frame));
                return;
            }

            if (usingDepthModel && depthEstimator != null && (latestDepthMap == null || frameCount % 2 == 0)) {
                try { latestDepthMap = depthEstimator.estimate(frame); }
                catch (Exception ex) {
                    depthModelLoadError = message(ex);
                    if (frameCount % 20 == 0) setStatus("DEPTH ERROR · " + depthModelLoadError);
                }
            }
            final DepthTfliteEstimator.DepthMap depthForFrame = latestDepthMap;

            if (usingCustomModel && yoloDetector != null) {
                try {
                    List<YoloTfliteDetector.Detection> objects = yoloDetector.detectBitmap(frame, confidenceThreshold);
                    processDetections(objects, frame.getWidth(), frame.getHeight(), depthForFrame);
                } catch (Exception ex) {
                    setStatus("CUSTOM MODEL ERROR · " + message(ex));
                    if (!modelErrorSpoken) {
                        modelErrorSpoken = true;
                        speakPriority("Object detector error. Check AISight model settings.");
                    }
                } finally { releaseInferenceFrame(frame); }
                return;
            }

            if (fallbackDetector == null) {
                setStatus("NO OBJECT DETECTOR AVAILABLE");
                releaseInferenceFrame(frame);
                return;
            }
            InputImage input = InputImage.fromBitmap(frame, 0);
            fallbackDetector.process(input)
                    .addOnSuccessListener(objects -> {
                        List<YoloTfliteDetector.Detection> generic = new ArrayList<>();
                        for (DetectedObject object : objects) {
                            String label = "object"; float score = 0.0f;
                            if (!object.getLabels().isEmpty()) {
                                label = object.getLabels().get(0).getText().toLowerCase(Locale.US);
                                score = object.getLabels().get(0).getConfidence();
                                if (score < confidenceThreshold) continue;
                            }
                            Rect r = object.getBoundingBox();
                            generic.add(new YoloTfliteDetector.Detection(label, score, -1,
                                    new RectF(r.left, r.top, r.right, r.bottom)));
                        }
                        processDetections(generic, frame.getWidth(), frame.getHeight(), depthForFrame);
                    })
                    .addOnFailureListener(ex -> setStatus("DETECTION ERROR · " + message(ex)))
                    .addOnCompleteListener(task -> releaseInferenceFrame(frame));
        } catch (Exception ex) {
            setStatus("ANALYSIS ERROR · " + message(ex));
            releaseInferenceFrame(frame);
        }
    }

    private void releaseInferenceFrame(Bitmap frame) {
        try {
            if (frame != null && !frame.isRecycled()) frame.recycle();
        } catch (Exception ignored) { }
        finally { inferenceInProgress.set(false); }
    }

    private void processDetections(List<YoloTfliteDetector.Detection> detected, int width, int height,
                                   DepthTfliteEstimator.DepthMap depthMap) {
        long now = SystemClock.elapsedRealtime();
        List<DetectionInfo> detections = new ArrayList<>();
        removeExpiredPauses(now);
        for (YoloTfliteDetector.Detection object : detected) {
            String label = object.label;
            if (usingCustomModel && !CLASS_TIER.containsKey(label)) continue;
            if (isClassPaused(label, now)) continue;
            float areaRatio = Math.max(0f, object.bounds.width() * object.bounds.height()) / Math.max(1f, width * (float) height);
            float meters = depthMap == null ? Float.POSITIVE_INFINITY : depthMap.sampleMedian(object.bounds);
            String pos3 = position3(object.bounds.centerX(), width);
            String pos5 = position5(object.bounds.centerX(), width);
            String tier = CLASS_TIER.getOrDefault(label, "medium");
            float closeness = isFinite(meters) ? 1f - Math.min(Math.max(meters, 0.05f) / 6f, 1f) : 0f;
            float priority = TIER_WEIGHT.getOrDefault(tier, 0.6f) * (areaRatio * 0.3f + closeness * 0.7f);
            detections.add(new DetectionInfo(label, object.confidence, object.bounds, meters, pos3, pos5, priority));
        }

        if (depthMap != null) {
            DepthTfliteEstimator.WallResult wall = depthMap.detectWall(DEPTH_WALL_THRESHOLD_M);
            if (wall.rawWall) {
                if (wallCandidateAt == 0) wallCandidateAt = now;
                wallDebounced = now - wallCandidateAt >= WALL_CONFIRM_MS;
            } else { wallCandidateAt = 0; wallDebounced = false; }
            if (wallDebounced && !isClassPaused("wall/obstruction", now)) {
                RectF centerBox = new RectF(width * 0.35f, height * 0.25f, width * 0.65f, height * 0.85f);
                detections.add(new DetectionInfo("wall/obstruction", 1f, centerBox, wall.meanMeters,
                        position3(width * 0.5f, width), position5(width * 0.5f, width),
                        TIER_WEIGHT.get("low") * (0.15f + (1f - Math.min(wall.meanMeters / 6f, 1f)) * 0.7f)));
            }
        } else { wallCandidateAt = 0; wallDebounced = false; }
        Collections.sort(detections, (a, b) -> Float.compare(b.priority, a.priority));

        List<DetectionInfo> speakList = new ArrayList<>();
        for (DetectionInfo d : detections) {
            if ("navigation".equals(phraseMode) && (!isFinite(d.meters) || d.meters > 16.7f)) continue;
            speakList.add(d);
            int cap = "navigation".equals(phraseMode) ? Math.min(2, maxSpeak) : maxSpeak;
            if (speakList.size() >= cap) break;
        }

        String advice = null;
        if ("navigation".equals(phraseMode) && !speakList.isEmpty()) advice = navigationAdvice(speakList);
        List<DetectionOverlayView.Item> overlayItems = new ArrayList<>();
        for (DetectionInfo d : detections) {
            if (overlayItems.size() >= 20) break;
            String detail = isFinite(d.meters) ? String.format(Locale.US, "%.1f m", d.meters) : "distance unavailable";
            overlayItems.add(new DetectionOverlayView.Item(translateLabel(d.label, false), d.confidence, d.bounds, detail));
        }
        overlayView.update(overlayItems, width, height);

        StringBuilder visual = new StringBuilder();
        StringBuilder spoken = new StringBuilder();
        if (detections.isEmpty()) {
            visual.append("No objects above the confidence threshold.");
        } else {
            int displayLimit = Math.min(5, detections.size());
            for (int i = 0; i < displayLimit; i++) {
                DetectionInfo d = detections.get(i);
                String label = translateLabel(d.label, tagalog);
                String distance = isFinite(d.meters) ? String.format(Locale.US, "%.1f m", d.meters) : "distance unavailable";
                if (visual.length() > 0) visual.append("\n");
                visual.append(i + 1).append(". ").append(label).append(" · ")
                        .append(d.pos3).append(" · ").append(Math.round(d.confidence * 100)).append("% · ").append(distance);
            }
            if (detections.size() > displayLimit) visual.append("\n+ ").append(detections.size() - displayLimit).append(" more object(s)");
        }

        for (int i = 0; i < speakList.size(); i++) {
            DetectionInfo d = speakList.get(i);
            String label = translateLabel(d.label, tagalog);
            if (i > 0) spoken.append(". ");
            if ("default".equals(phraseMode)) {
                spoken.append(label).append(", ").append(translatePosition(distanceBucket(d.meters), tagalog)).append(", ").append(translatePosition(d.pos3, tagalog));
            } else {
                spoken.append(label).append(", ").append(translatePosition(d.pos5, tagalog)).append(", ");
                if (isFinite(d.meters)) spoken.append(String.format(Locale.US, "%.1f %s", d.meters, tagalog ? "metro" : "meters"));
                else spoken.append(tagalog ? "hindi matukoy ang distansya" : "distance unavailable");
            }
        }
        if ("navigation".equals(phraseMode) && advice != null) {
            String localizedAdvice = translateAdvice(advice, tagalog);
            if (visual.length() > 0) visual.append("\n");
            visual.append("NAV · ").append(localizedAdvice);
            if (spoken.length() > 0) spoken.append(". ");
            spoken.append(localizedAdvice);
        }
        if (depthMap == null) {
            if (visual.length() > 0) visual.append("\n");
            visual.append("Distance estimates unavailable — depth model not active.");
        }
        latestVisual(visual.toString(), detections.size());
        maybeSpeak(spoken.toString(), "navigation".equals(phraseMode));
    }

    private void latestVisual(String visual, int count) {
        runOnUiThread(() -> {
            if (!headless && detectionsView != null) {
                detectionsView.setText(visual);
                boolean textResult = visual.startsWith("TEXT FOUND") || visual.startsWith("TEXT READING");
                if (detectionCountView != null) detectionCountView.setText(textResult ? "OCR RESULT" : count + (count == 1 ? " OBJECT" : " OBJECTS"));
                currentStatus = textResult ? (visual.startsWith("TEXT FOUND") ? "TEXT RECOGNITION COMPLETE" : "TEXT READING COMPLETE")
                        : (usingCustomModel ? "CUSTOM AISIGHT MODEL" : "GENERIC FALLBACK") + " · " + count
                        + " OBJECTS · " + (usingDepthModel && latestDepthMap != null ? "DEPTH ACTIVE" : "DEPTH UNAVAILABLE");
                if (statusView != null) statusView.setText(currentStatus);
                updateStatusIndicator(currentStatus);
                refreshModelBadge();
            }
        });
    }

    private void maybeSpeak(String phrase, boolean navigation) {
        if (!announcementsArmed || !voiceEnabled || phrase == null || phrase.trim().isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        boolean cooled = now - lastSpokenAt >= (navigation ? VOICE_COOLDOWN_MS : VOICE_COOLDOWN_MS);
        String signature = phrase.trim();
        boolean should = cooled && (!signature.equals(lastSpokenMessage)
                || (navigation && now - lastSpokenAt >= NAV_REPEAT_MS));
        if (should) {
            lastSpokenMessage = signature; lastSpokenAt = now; speak(signature);
        }
    }

    private String navigationAdvice(List<DetectionInfo> top) {
        if (!usingDepthModel || latestDepthMap == null) return tagalog
                ? "hindi matukoy ang distansya; huwag sundin ang direksiyon" : "distance unavailable; do not rely on navigation advice";
        List<DetectionInfo> blocking = new ArrayList<>();
        for (DetectionInfo d : top) if (d.meters <= 6.7f) blocking.add(d);
        if (blocking.isEmpty()) return "center is clear";
        boolean center = false, left = false, right = false, veryCloseCenter = false;
        for (DetectionInfo d : blocking) {
            if ("front".equals(d.pos5)) { center = true; if (d.meters <= 1.2f) veryCloseCenter = true; }
            if ("left".equals(d.pos5)) left = true;
            if ("right".equals(d.pos5)) right = true;
        }
        if (veryCloseCenter) return "stop";
        if (center || (left && !right)) return "step right";
        if (right && !left) return "step left";
        return "center is clear";
    }

    private void onTextRead(Text result) {
        String text = result.getText() == null ? "" : result.getText().trim();
        if (text.isEmpty()) {
            latestVisual("TEXT READING\nNo readable text found.", 0);
            speakPriority(tagalog ? "Walang nakitang mababasang teksto." : "No readable text found."); return;
        }
        String limited = text.length() > 500 ? text.substring(0, 500) : text;
        latestVisual("TEXT FOUND\n" + limited, 0);
        speakPriority((tagalog ? "Nakasulat: " : "Text reads: ") + limited);
    }

    private void requestReadText() {
        readTextRequested.set(true); setStatus("TEXT READING REQUESTED · WAITING FOR FRAME");
        speakPriority(tagalog ? "Binabasa ang teksto." : "Reading text.");
    }

    private void setAnnouncementsArmed(boolean value, boolean announce) {
        announcementsArmed = value; saveBoolean("armed", value);
        if (startPauseButton != null) { startPauseButton.setText(value ? (headless ? "PAUSE" : "PAUSE VOICE") : (headless ? "START" : "START VOICE")); refreshButtonStyle(startPauseButton); }
        if (announce) speakPriority(value ? (tagalog ? "Ipagpapatuloy ang mga anunsyo." : "AISight announcements resumed.")
                : (tagalog ? "Tahimik muna." : "AISight going quiet. Say AISight start to resume."));
        if (headless) applyDisplayMode();
    }

    private void speak(String text) {
        if (!voiceEnabled || !ttsReady || tts == null || text == null || text.trim().isEmpty()) return;
        mainHandler.post(() -> {
            if (!voiceEnabled || !ttsReady || tts == null) return;
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,
                    "high".equals(voiceVolume) ? 1.0f : "low".equals(voiceVolume) ? 0.3f : 0.6f);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "aisight-" + SystemClock.elapsedRealtime());
        });
    }

    private void speakPriority(String text) {
        if (!ttsReady || tts == null || text == null || text.trim().isEmpty()) return;
        mainHandler.post(() -> {
            if (!ttsReady || tts == null) return;
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,
                    "high".equals(voiceVolume) ? 1.0f : "low".equals(voiceVolume) ? 0.3f : 0.6f);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "aisight-priority-" + SystemClock.elapsedRealtime());
        });
    }

    private void announceFault(String text) {
        startAlarm(FAILSAFE_ALARM_MS);
        speakPriority(text);
    }

    /** Announce only once per continuous camera outage. Recovery is confirmed by a real frame. */
    private void reportCameraFault(String text) {
        cameraFaulted = true;
        if (cameraFaultAlertLatched) return;
        cameraFaultAlertLatched = true;
        announceFault(text);
    }

    private void startAlarm(long durationMs) {
        boolean wasActive = alarmActive;
        long now = SystemClock.elapsedRealtime();
        alarmActive = true;
        alarmDeadline = Math.max(alarmDeadline, now + Math.min(durationMs, MAX_ALARM_MS));
        if (!wasActive) {
            if (vibrator != null) {
                try {
                    long[] pattern = {0, 350, 180, 350, 450};
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                    else vibrator.vibrate(pattern, 0);
                } catch (Exception ignored) { }
            }
            mainHandler.removeCallbacks(alarmToneRunnable);
            mainHandler.post(alarmToneRunnable);
        }
        mainHandler.removeCallbacks(alarmStopRunnable);
        mainHandler.postDelayed(alarmStopRunnable, Math.max(1000, alarmDeadline - SystemClock.elapsedRealtime()));
        if (root != null) applyDisplayMode();
    }

    private void stopAlarm() {
        alarmActive = false; alarmDeadline = 0;
        mainHandler.removeCallbacks(alarmToneRunnable); mainHandler.removeCallbacks(alarmStopRunnable);
        try { if (toneGenerator != null) toneGenerator.stopTone(); } catch (Exception ignored) { }
        try { if (vibrator != null) vibrator.cancel(); } catch (Exception ignored) { }
        if (root != null) applyDisplayMode();
    }

    private void startWakeWordListener() {
        if (wakeWordListener != null || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        wakeWordStartPending = false;
        wakeWordListener = new WakeWordListener(this, new WakeWordListener.Callbacks() {
            @Override public void onPhrase(String phrase) { handleRecognizedSpeech(phrase); }
            @Override public void onMicFault(String details) {
                runOnUiThread(() -> {
                    if (!micFaulted) {
                        micFaulted = true; setStatus("MICROPHONE DISCONNECTED · RETRYING");
                        announceFault("Warning. Microphone disconnected. Trying to reconnect.");
                    }
                });
            }
            @Override public void onMicRestored() {
                runOnUiThread(() -> {
                    if (micFaulted) { micFaulted = false; speakPriority("Microphone restored."); setStatus("MICROPHONE RESTORED"); }
                });
            }
            @Override public void onListenerError(String details) {
                runOnUiThread(() -> {
                    setStatus("VOICE COMMANDS UNAVAILABLE · " + details);
                    if (!modelErrorSpoken) { modelErrorSpoken = true; speakPriority("Offline voice commands are unavailable. Check the Vosk model in the build."); }
                });
            }
        });
        wakeWordListener.start();
    }

    private void handleRecognizedSpeech(String heard) {
        if (heard == null || heard.trim().isEmpty()) return;
        String normalized = normalizeSpeech(heard);
        int wakeAt = wakeIndex(normalized);
        if (wakeAt < 0) return;
        String command = normalized.substring(Math.min(normalized.length(), wakeAt + wakeTokenLength(normalized, wakeAt))).trim();
        if (command.isEmpty()) { speakPriority(announcementsArmed ? "Listening." : "Say start to begin announcements."); return; }
        runOnUiThread(() -> applyVoiceCommand(command));
    }

    private static String normalizeSpeech(String value) {
        return value.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim()
                .replace("eye sight", "eyesight").replace("i sight", "aisight").replace("a i sight", "aisight");
    }
    private static int wakeIndex(String value) {
        int a = value.indexOf("aisight"); int e = value.indexOf("eyesight");
        if (a < 0) return e; if (e < 0) return a; return Math.min(a, e);
    }
    private static int wakeTokenLength(String value, int start) {
        if (value.startsWith("eyesight", start)) return 8;
        if (value.startsWith("aisight", start)) return 7;
        return 7;
    }

    private void applyVoiceCommand(String command) {
        String cmd = command.trim();
        long now = SystemClock.elapsedRealtime();
        if (awaitingShutdownConfirmation) {
            if (now > shutdownConfirmationDeadline) {
                awaitingShutdownConfirmation = false; speakPriority("Shutdown request timed out and was cancelled.");
            } else if (containsWord(cmd, "yes") || containsWord(cmd, "sure")) {
                awaitingShutdownConfirmation = false; speakPriority("Closing AISight. The phone will stay on.");
                mainHandler.postDelayed(this::finish, 900L); return;
            } else if (containsWord(cmd, "no") || containsWord(cmd, "cancel")) {
                awaitingShutdownConfirmation = false; speakPriority("Shutdown cancelled."); return;
            } else { speakPriority("Please say AISight yes, or AISight no."); return; }
        }

        if (cmd.contains("buzzer") && anyWord(cmd, "stop", "off", "silence", "quiet", "shut")) {
            stopAlarm(); speakPriority("Alarm silenced."); return;
        }
        if (containsWord(cmd, "record") || containsWord(cmd, "recording")) {
            if (containsWord(cmd, "stop")) { stopRecording(true); return; }
            int duration = parseRecordingSeconds(cmd);
            startRecording(duration); return;
        }
        if (anyWord(cmd, "read", "text", "ocr")) { requestReadText(); return; }
        String label = findClassInCommand(cmd);
        if (label != null) {
            if (containsWord(cmd, "pause")) {
                int minutes = parsePauseMinutes(cmd);
                pausedClasses.put(label, now + minutes * 60000L);
                speakPriority("Pausing " + spokenClass(label, false) + " for " + minutes + (minutes == 1 ? " minute." : " minutes."));
                return;
            }
            if (anyWord(cmd, "resume", "continue")) {
                if (pausedClasses.remove(label) != null) speakPriority(spokenClass(label, false) + " announcements resumed.");
                else speakPriority(spokenClass(label, false) + " was not paused.");
                return;
            }
        }
        if (cmd.contains("shut down") || cmd.contains("shutdown") || (containsWord(cmd, "turn") && containsWord(cmd, "off"))) {
            awaitingShutdownConfirmation = true; shutdownConfirmationDeadline = now + 15000L;
            speakPriority("Are you sure you want to close AISight? Say AISight yes, or AISight no. The phone will not power off."); return;
        }
        if (cmd.contains("reset battery")) {
            batteryAccumulatedMs = 0L; batterySessionStart = now; lastBatteryCheckpointAt = now;
            lastBatteryAnnouncementHour = 0L; lowBatteryWarned = false;
            prefs().edit().putLong("batteryElapsedMs", 0L).apply();
            speakPriority("AISight usage counter reset."); return;
        }
        if (containsWord(cmd, "battery")) { speakPriority(batteryStatus()); return; }
        if (containsWord(cmd, "tagalog")) {
            tagalog = true; prefs().edit().putBoolean("tagalog", true).apply(); configureTts();
            speakPriority("Switching to Tagalog. Papalitan sa Tagalog."); return;
        }
        if (containsWord(cmd, "english")) {
            tagalog = false; prefs().edit().putBoolean("tagalog", false).apply(); configureTts();
            speakPriority("Switching to English."); return;
        }
        if (containsWord(cmd, "navigation") || containsWord(cmd, "navigate")) { setPhraseMode("navigation"); speakPriority("Navigation mode."); return; }
        if (containsWord(cmd, "advanced") || containsWord(cmd, "advance")) { setPhraseMode("advanced"); speakPriority("Advanced mode."); return; }
        if (containsWord(cmd, "default") || containsWord(cmd, "basic")) { setPhraseMode("default"); speakPriority("Default mode."); return; }
        if (containsWord(cmd, "female")) { setVoiceGender("female"); speakPriority("Switching to female voice."); return; }
        if (containsWord(cmd, "male")) { setVoiceGender("male"); speakPriority("Switching to male voice."); return; }
        if (containsWord(cmd, "fast")) { setVoiceSpeed("fast"); speakPriority("Speed set to fast."); return; }
        if (containsWord(cmd, "slow")) { setVoiceSpeed("slow"); speakPriority("Speed set to slow."); return; }
        if (containsWord(cmd, "normal") && !containsWord(cmd, "volume")) { setVoiceSpeed("normal"); speakPriority("Speed set to normal."); return; }
        if (containsWord(cmd, "volume")) {
            setVoiceVolume(containsWord(cmd, "high") ? "high" : containsWord(cmd, "low") ? "low" : "normal");
            speakPriority("Volume " + voiceVolume + "."); return;
        }
        if (containsWord(cmd, "object") || containsWord(cmd, "objects") || containsWord(cmd, "detection")) {
            if (containsWord(cmd, "one") || containsWord(cmd, "1")) maxSpeak = 1;
            else if (containsWord(cmd, "three") || containsWord(cmd, "3")) maxSpeak = 3;
            else if (containsWord(cmd, "two") || containsWord(cmd, "2")) maxSpeak = 2;
            prefs().edit().putInt("maxSpeak", maxSpeak).apply(); speakPriority("Announcing " + maxSpeak + " objects at a time."); return;
        }
        if (anyWord(cmd, "stop", "pause", "quiet") || cmd.contains("shut up")) { setAnnouncementsArmed(false, true); return; }
        if (anyWord(cmd, "continue", "resume", "start", "go", "begin", "launch") || (containsWord(cmd, "turn") && containsWord(cmd, "on"))) {
            setAnnouncementsArmed(true, true); return;
        }
        speakPriority("Command not recognized. Say AISight, then a command.");
    }

    private boolean awaitingShutdownConfirmation;
    private long shutdownConfirmationDeadline;

    private void setPhraseMode(String value) { phraseMode = value; prefs().edit().putString("phraseMode", value).apply(); }
    private void setVoiceGender(String value) { voiceGender = value; prefs().edit().putString("gender", value).apply(); configureTts(); }
    private void setVoiceSpeed(String value) { voiceSpeed = value; prefs().edit().putString("speed", value).apply(); configureTts(); }
    private void setVoiceVolume(String value) { voiceVolume = value; prefs().edit().putString("volume", value).apply(); }

    private long totalBatteryUsageMs() {
        return Math.max(0L, batteryAccumulatedMs + SystemClock.elapsedRealtime() - batterySessionStart);
    }

    private void tickBatteryTracker(long now) {
        long elapsed = totalBatteryUsageMs();
        if (now - lastBatteryCheckpointAt >= 300000L) {
            batteryAccumulatedMs = elapsed;
            batterySessionStart = now;
            lastBatteryCheckpointAt = now;
            prefs().edit().putLong("batteryElapsedMs", batteryAccumulatedMs).apply();
        }
        long hourMark = elapsed / 3600000L;
        if (announcementsArmed && hourMark > lastBatteryAnnouncementHour) {
            lastBatteryAnnouncementHour = hourMark;
            speakPriority(batteryStatus());
        }
    }

    private String batteryStatus() {
        long minutes = totalBatteryUsageMs() / 60000L;
        String duration = minutes >= 60 ? (minutes / 60) + " hours and " + (minutes % 60) + " minutes" : minutes + " minutes";
        if (batteryPercent < 0) return "AISight has been used for " + duration + ". Phone battery percentage is unavailable.";
        return "Phone battery is at " + batteryPercent + " percent. AISight has been used for " + duration + ".";
    }

    private void startRecording(int seconds) {
        final int requestedSeconds = Math.max(5, Math.min(60, seconds));
        if (videoCapture == null) { speakPriority("Video recording is not available on this phone camera configuration."); return; }
        if (activeRecording != null) { speakPriority("Already recording. Say AISight stop recording to stop it."); return; }
        ContentValues values = new ContentValues();
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        String displayName = "AISight_" + stamp + ".mp4";
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AISight");
        } else {
            File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "AISight");
            if (!directory.exists() && !directory.mkdirs()) {
                speakPriority("Cannot create the AISight recordings folder.");
                setStatus("RECORDING FAILED · STORAGE DIRECTORY UNAVAILABLE");
                return;
            }
            values.put(MediaStore.Video.Media.DATA, new File(directory, displayName).getAbsolutePath());
        }
        MediaStoreOutputOptions output = new MediaStoreOutputOptions.Builder(getContentResolver(),
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build();
        try {
            PendingRecording pending = videoCapture.getOutput().prepareRecording(this, output);
            // Intentionally do not enable microphone audio: Vosk keeps listening for voice commands.
            activeRecording = pending.start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    recordingInProgress = true; updateRecordButton();
                    setStatus("RECORDING VIDEO · " + requestedSeconds + " SECONDS MAX");
                }
                if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                    recordingInProgress = false; activeRecording = null;
                    if (fin.hasError()) {
                        speakPriority("Recording failed."); setStatus("RECORDING FAILED · " + fin.getError());
                    } else {
                        activeRecordingUri = fin.getOutputResults().getOutputUri();
                        speakPriority("Recording saved in Movies AISight."); setStatus("RECORDING SAVED · MOVIES/AISIGHT");
                    }
                    updateRecordButton();
                }
            });
            if (stopRecordingRunnable != null) recordingHandler.removeCallbacks(stopRecordingRunnable);
            stopRecordingRunnable = () -> stopRecording(false);
            recordingHandler.postDelayed(stopRecordingRunnable, requestedSeconds * 1000L);
            speakPriority("Recording for " + requestedSeconds + " seconds.");
        } catch (Exception ex) {
            activeRecording = null; recordingInProgress = false; updateRecordButton();
            speakPriority("Recording failed to start."); setStatus("RECORDING ERROR · " + message(ex));
        }
    }

    private void stopRecording(boolean announce) {
        if (stopRecordingRunnable != null) recordingHandler.removeCallbacks(stopRecordingRunnable);
        stopRecordingRunnable = null;
        Recording recording = activeRecording;
        if (recording == null) {
            if (announce) speakPriority("Not recording.");
            return;
        }
        try { recording.stop(); } catch (Exception ignored) { }
        if (announce) speakPriority("Stopping the recording.");
    }

    private void updateRecordButton() {
        if (recordButton != null) {
            recordButton.setText(recordingInProgress ? "STOP RECORDING" : "RECORD 10S");
            refreshButtonStyle(recordButton);
        }
    }

    private int parseRecordingSeconds(String command) {
        String[] words = command.split(" ");
        int wanted = 10;
        if (containsWord(command, "minute") || containsWord(command, "minutes")) {
            wanted = 60;
            for (String word : words) {
                if ("one".equals(word) || "1".equals(word)) wanted = 60;
                else if ("two".equals(word) || "2".equals(word)) wanted = 60;
            }
        } else {
            for (String word : words) {
                if ("five".equals(word) || "5".equals(word)) wanted = 5;
                else if ("ten".equals(word) || "10".equals(word)) wanted = 10;
                else if ("thirty".equals(word) || "30".equals(word)) wanted = 30;
                else if ("sixty".equals(word) || "60".equals(word)) wanted = 60;
            }
        }
        return Math.min(60, Math.max(5, wanted));
    }

    private int parsePauseMinutes(String command) {
        int n = 1;
        for (String token : command.split(" ")) {
            if ("one".equals(token) || "1".equals(token)) n = 1;
            else if ("two".equals(token) || "2".equals(token)) n = 2;
            else if ("three".equals(token) || "3".equals(token)) n = 3;
            else if ("four".equals(token) || "4".equals(token)) n = 4;
            else if ("five".equals(token) || "5".equals(token)) n = 5;
        }
        return Math.max(1, Math.min(5, n));
    }

    private String findClassInCommand(String command) {
        List<String> aliases = new ArrayList<>(SPOKEN_CLASS_ALIASES.keySet());
        aliases.sort((a, b) -> Integer.compare(b.length(), a.length()));
        String padded = " " + command + " ";
        for (String alias : aliases) if (padded.contains(" " + alias + " ")) return SPOKEN_CLASS_ALIASES.get(alias);
        if (padded.contains(" wall ") || padded.contains(" obstruction ")) return "wall/obstruction";
        return null;
    }

    private void removeExpiredPauses(long now) {
        for (Map.Entry<String, Long> e : pausedClasses.entrySet()) if (e.getValue() <= now) pausedClasses.remove(e.getKey(), e.getValue());
    }
    private boolean isClassPaused(String label, long now) {
        Long until = pausedClasses.get(label);
        if (until == null) return false;
        if (until <= now) { pausedClasses.remove(label, until); return false; }
        return true;
    }

    private void showSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(16)); box.setBackground(roundedBackground(panel, 20, stroke));
        TextView title = new TextView(this); title.setText("SETTINGS"); title.setTextColor(white); title.setTextSize(22);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); title.setPadding(0, 0, 0, dp(3)); box.addView(title);
        TextView subtitle = new TextView(this); subtitle.setText("Tune the way AISight sees and speaks.");
        subtitle.setTextColor(muted); subtitle.setTextSize(12); subtitle.setPadding(0, 0, 0, dp(12)); box.addView(subtitle);

        Switch headlessSwitch = makeSwitch("Headless mode — black screen, dim display, lower analysis rate", headless);
        headlessSwitch.setOnCheckedChangeListener((button, checked) -> { headless = checked; saveBoolean("headless", checked); applyDisplayMode(); }); box.addView(headlessSwitch);
        Switch voiceSwitch = makeSwitch("Speak detected objects and status messages", voiceEnabled);
        voiceSwitch.setOnCheckedChangeListener((button, checked) -> { voiceEnabled = checked; saveBoolean("voice", checked); if (checked) speakPriority("Voice announcements on."); }); box.addView(voiceSwitch);
        Switch tagalogSwitch = makeSwitch("Tagalog spoken labels", tagalog);
        tagalogSwitch.setOnCheckedChangeListener((button, checked) -> { tagalog = checked; saveBoolean("tagalog", checked); configureTts(); }); box.addView(tagalogSwitch);
        Switch armedSwitch = makeSwitch("Start spoken detection announcements", announcementsArmed);
        armedSwitch.setOnCheckedChangeListener((button, checked) -> setAnnouncementsArmed(checked, true)); box.addView(armedSwitch);

        addCycleSetting(box, "Phrase mode", new String[]{"default", "advanced", "navigation"}, phraseMode,
                v -> setPhraseMode(v));
        addCycleSetting(box, "Voice", new String[]{"female", "male"}, voiceGender, this::setVoiceGender);
        addCycleSetting(box, "Speech speed", new String[]{"slow", "normal", "fast"}, voiceSpeed, this::setVoiceSpeed);
        addCycleSetting(box, "Speech volume", new String[]{"low", "normal", "high"}, voiceVolume, this::setVoiceVolume);
        addCycleSetting(box, "Objects announced at once", new String[]{"1", "2", "3"}, String.valueOf(maxSpeak), v -> {
            maxSpeak = Integer.parseInt(v); prefs().edit().putInt("maxSpeak", maxSpeak).apply();
        });

        TextView confidenceTitle = new TextView(this); confidenceTitle.setTextColor(white); confidenceTitle.setPadding(0, dp(12), 0, dp(3));
        confidenceTitle.setText("Minimum confidence: " + Math.round(confidenceThreshold * 100) + "%"); box.addView(confidenceTitle);
        SeekBar confidence = new SeekBar(this); confidence.setMax(80); confidence.setProgress(Math.max(10, Math.min(80, Math.round((confidenceThreshold - 0.1f) * 100))));
        confidence.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                confidenceThreshold = 0.1f + progress / 100f; confidenceTitle.setText("Minimum confidence: " + Math.round(confidenceThreshold * 100) + "%");
                prefs().edit().putFloat("confidence", confidenceThreshold).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        }); box.addView(confidence);

        TextView modelInfo = new TextView(this); modelInfo.setTextColor(muted); modelInfo.setTextSize(12); modelInfo.setPadding(0, dp(14), 0, dp(4));
        String info = "Detector: " + detectorStatus + (customModelLoadError.isEmpty() ? "" : "\n" + customModelLoadError)
                + "\nDepth: " + (usingDepthModel ? "YOLO26n-depth active (estimated meters)" : "unavailable — " + depthModelLoadError)
                + "\nVoice: Offline Vosk command listener; Android TextToSpeech output."
                + "\nFall detection: phone accelerometer heuristic. Alert: phone alarm tone + vibration."
                + "\nRecording: local silent video (microphone remains available for commands)."
                + "\nNote: the phone is not a GPIO buzzer or MPU6050; these substitutes are not safety-rated. Depth is monocular estimation, not a rangefinder. Test extensively before any real mobility use.";
        modelInfo.setText(info); box.addView(modelInfo);
        scroll.addView(box);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this).setView(scroll)
                .setPositiveButton("DONE", (d, w) -> applyDisplayMode()).create();
        dialog.show(); dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(purple);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    }

    private interface StringSetter { void set(String value); }
    private void addCycleSetting(LinearLayout box, String label, String[] choices, String initial, StringSetter setter) {
        Button row = makeButton(label + ": " + initial.toUpperCase(Locale.US)); row.setTextSize(13);
        row.setOnClickListener(v -> {
            String current = row.getText().toString();
            int index = 0; for (int i = 0; i < choices.length; i++) if (current.endsWith(choices[i].toUpperCase(Locale.US))) index = i;
            String next = choices[(index + 1) % choices.length]; setter.set(next);
            row.setText(label + ": " + next.toUpperCase(Locale.US)); refreshButtonStyle(row);
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(44)); p.setMargins(0, dp(3), 0, dp(3)); box.addView(row, p);
    }

    private Switch makeSwitch(String label, boolean checked) {
        Switch s = new Switch(this); s.setText(label); s.setTextColor(white); s.setTextSize(13);
        s.setPadding(0, dp(5), 0, dp(5)); s.setChecked(checked); return s;
    }

    private void createSensorRegistration() {
        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        float magnitude = (float) Math.sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2]);
        if (calibrationCount < calibrationReadings.length) {
            calibrationReadings[calibrationCount++] = magnitude;
            if (calibrationCount == calibrationReadings.length) {
                float sum = 0f; for (float v : calibrationReadings) sum += v;
                fallBaseline = Math.max(1f, sum / calibrationReadings.length);
            }
            return;
        }
        if (fallBaseline <= 0f) return;
        long now = SystemClock.elapsedRealtime();
        float ratio = magnitude / fallBaseline;
        if (ratio <= FALL_FREEFALL_RATIO) freefallAt = now;
        else if (freefallAt > 0) {
            if (now - freefallAt > FALL_WINDOW_MS) freefallAt = -1L;
            else if (ratio >= FALL_IMPACT_RATIO && now - lastFallAt > FALL_COOLDOWN_MS) {
                freefallAt = -1L; lastFallAt = now;
                startAlarm(MAX_ALARM_MS);
                speakPriority("Possible fall detected. Are you okay? Say AISight buzzer stop to silence the alarm.");
            }
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private String translateLabel(String label, boolean fil) {
        String lower = label.toLowerCase(Locale.US);
        if (!fil) return lower.replace('_', ' ').replace('-', ' ');
        switch (lower) {
            case "person": return "tao"; case "car": return "kotse"; case "van": return "van";
            case "bus": return "bus"; case "truck": return "trak"; case "jeepney": return "jeepney";
            case "motorcycle": return "motorsiklo"; case "tricycle": return "traysikel";
            case "e-tricycle": return "de-kuryenteng traysikel"; case "e-trike": return "e-trike";
            case "e-bike": return "de-kuryenteng bisikleta"; case "bike": return "bisikleta";
            case "stop_sign": return "karatula ng hinto"; case "stairs": return "hagdan";
            case "pit_hole": return "butas sa daan"; case "uncovered_manhole": return "bukas na manhole";
            case "hazard_sign": return "karatula ng babala"; case "utility_pole": return "poste ng kuryente";
            case "bench": return "bangko"; case "cat": return "pusa"; case "dog": return "aso";
            case "tree": return "puno"; case "door": return "pinto"; case "puddle": return "tubig sa daan";
            case "crosswalk": return "tawiran"; case "ramps": return "rampa"; case "manhole": return "manhole";
            case "wall/obstruction": return "hadlang"; case "object": return "bagay";
            default: return lower.replace('_', ' ').replace('-', ' ');
        }
    }

    private String spokenClass(String label, boolean fil) { return translateLabel(label, fil); }
    private String translatePosition(String pos, boolean fil) {
        if (!fil) {
            switch (pos) {
                case "front": return "straight ahead"; case "left": return "left"; case "right": return "right";
                case "far left": return "far left"; case "far right": return "far right";
                default: return pos;
            }
        }
        switch (pos) {
            case "on your left": return "sa kaliwa mo"; case "on your right": return "sa kanan mo";
            case "straight ahead": return "sa harap mo"; case "far left": return "malayong kaliwa";
            case "left": return "kaliwa"; case "front": return "harap"; case "right": return "kanan";
            case "far right": return "malayong kanan"; case "nearby": return "malapit";
            case "very close": return "napakalapit"; case "far": return "malayo";
            case "distance unavailable": return "hindi matukoy ang distansya";
            default: return pos;
        }
    }
    private String translateAdvice(String advice, boolean fil) {
        if (!fil) return advice;
        switch (advice) {
            case "step left": return "humakbang pakaliwa"; case "step right": return "humakbang pakanan";
            case "stop": return "huminto"; case "center is clear": return "deretcho lang";
            case "distance unavailable; do not rely on navigation advice": return "hindi matukoy ang distansya; huwag umasa sa direksiyon";
            default: return advice;
        }
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static String distanceBucket(float meters) {
        if (!isFinite(meters)) return "distance unavailable";
        if (meters <= 1.0f) return "very close";
        if (meters <= 2.5f) return "nearby";
        return "far";
    }
    private static String position3(float cx, int width) {
        float ratio = cx / Math.max(1f, width);
        if (ratio < 0.38f) return "on your left";
        if (ratio > 0.62f) return "on your right";
        return "straight ahead";
    }
    private static String position5(float cx, int width) {
        float ratio = cx / Math.max(1f, width);
        if (ratio < 0.20f) return "far left"; if (ratio < 0.40f) return "left";
        if (ratio < 0.60f) return "front"; if (ratio < 0.80f) return "right"; return "far right";
    }
    private static boolean containsWord(String text, String word) { return (" " + text + " ").contains(" " + word + " "); }
    private static boolean anyWord(String text, String... words) { for (String w : words) if (containsWord(text, w)) return true; return false; }
    private static String message(Throwable e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private void setStatus(String text) {
        currentStatus = text == null ? "" : text;
        runOnUiThread(() -> {
            if (statusView != null && !headless) statusView.setText(currentStatus);
            updateStatusIndicator(currentStatus);
            refreshModelBadge();
        });
    }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST) return;
        boolean cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        permissionsChecked = true;
        if (cameraGranted) startCamera();
        else { setStatus("CAMERA PERMISSION REQUIRED"); Toast.makeText(this, "Camera permission is required for AISight.", Toast.LENGTH_LONG).show(); }
        if (micGranted) startWakeWordListener();
        else { setStatus("MICROPHONE PERMISSION DENIED · VOICE COMMANDS DISABLED"); Toast.makeText(this, "Microphone permission is required for wake-word commands.", Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onStart() {
        super.onStart();
        if (cameraProvider != null && cameraBindAt > 0) {
            cameraBindAt = SystemClock.elapsedRealtime();
            lastFrameAt = 0L;
        }
        if (permissionsChecked) startWakeWordListener();
    }

    @Override protected void onResume() {
        super.onResume(); createSensorRegistration();
        if (permissionsChecked && cameraProvider == null) startCamera();
        if (permissionsChecked) startWakeWordListener();
    }

    @Override protected void onPause() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        super.onPause();
    }

    @Override protected void onStop() {
        if (wakeWordListener != null) { wakeWordListener.close(); wakeWordListener = null; }
        if (activeRecording != null) stopRecording(false);
        super.onStop();
    }

    @Override protected void onDestroy() {
        activityDestroyed = true;
        modelsLoading = true;
        modelExecutor.shutdownNow();
        mainHandler.removeCallbacks(watchdogRunnable); stopAlarm();
        if (wakeWordListener != null) wakeWordListener.close();
        if (cameraProvider != null) cameraProvider.unbindAll();
        cameraExecutor.shutdown();
        if (fallbackDetector != null) fallbackDetector.close();
        if (yoloDetector != null) yoloDetector.close();
        if (depthEstimator != null) depthEstimator.close();
        if (textRecognizer != null) textRecognizer.close();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (toneGenerator != null) toneGenerator.release();
        if (batteryReceiver != null) try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) { }
        WindowManager.LayoutParams lp = getWindow().getAttributes(); lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        super.onDestroy();
    }

    private static final class DetectionInfo {
        final String label; final float confidence; final RectF bounds; final float meters;
        final String pos3, pos5; final float priority;
        DetectionInfo(String label, float confidence, RectF bounds, float meters, String pos3, String pos5, float priority) {
            this.label = label; this.confidence = confidence; this.bounds = new RectF(bounds); this.meters = meters;
            this.pos3 = pos3; this.pos5 = pos5; this.priority = priority;
        }
    }
}
