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

    private final int bg = Color.rgb(9, 7, 14);
    private final int panel = Color.rgb(22, 17, 32);
    private final int purple = Color.rgb(169, 126, 255);
    private final int white = Color.rgb(245, 242, 250);
    private final int muted = Color.rgb(177, 169, 191);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService modelExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean readTextRequested = new AtomicBoolean(false);
    private final Map<String, Long> pausedClasses = new ConcurrentHashMap<>();

    private LinearLayout root, header, controls, topControls, bottomControls;
    private TextView statusView, detectionsView, headlessMessage;
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
    private boolean cameraFaulted;
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
    private long lastFrameAt;
    private long cameraBindAt;
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
                    if (!cameraFaulted) {
                        cameraFaulted = true;
                        setStatus("CAMERA TIMEOUT — RETRYING");
                        announceFault("Camera disconnected. Trying to recover.");
                    }
                    if (now - lastCameraRetryAt > 6000L && cameraProvider != null) {
                        lastCameraRetryAt = now;
                        cameraBindAt = 0L;
                        try { cameraProvider.unbindAll(); } catch (Exception ignored) { }
                        startCamera();
                    }
                } else if (cameraFaulted && lastFrameAt > 0) {
                    cameraFaulted = false;
                    speakPriority("Camera restored.");
                    setStatus("CAMERA RESTORED");
                }
            } else if (!isFinishing() && foreground && permissionsChecked
                    && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    && now - lastCameraRetryAt > 6000L) {
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
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("AISIGHT  /  MOBILE"); title.setTextColor(white); title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        settingsButton = makeButton("SETTINGS");
        settingsButton.setOnClickListener(v -> showSettings());
        header.addView(settingsButton, new LinearLayout.LayoutParams(dp(105), dp(42)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(48)));

        statusView = new TextView(this);
        statusView.setTextColor(muted); statusView.setTextSize(12); statusView.setPadding(dp(3), dp(3), dp(3), dp(8));
        statusView.setText(detectorStatus + (usingDepthModel ? " + METRIC DEPTH" : " + DEPTH MODEL MISSING"));
        root.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout viewport = new FrameLayout(this);
        viewport.setBackgroundColor(Color.BLACK);
        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        viewport.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        overlayView = new DetectionOverlayView(this);
        viewport.addView(overlayView, new FrameLayout.LayoutParams(-1, -1));
        headlessMessage = new TextView(this);
        headlessMessage.setBackgroundColor(Color.BLACK);
        headlessMessage.setTextColor(Color.BLACK);
        headlessMessage.setText("");
        viewport.addView(headlessMessage, new FrameLayout.LayoutParams(-1, -1));
        root.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));

        detectionsView = new TextView(this);
        detectionsView.setTextColor(white); detectionsView.setTextSize(14); detectionsView.setLineSpacing(dp(3), 1.0f);
        detectionsView.setBackgroundColor(panel); detectionsView.setPadding(dp(12), dp(10), dp(12), dp(10));
        detectionsView.setText("LATEST DETECTIONS\nWaiting for camera frames...");
        LinearLayout.LayoutParams detLp = new LinearLayout.LayoutParams(-1, -2);
        detLp.setMargins(0, dp(8), 0, dp(8)); root.addView(detectionsView, detLp);

        controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL); controls.setGravity(Gravity.CENTER);
        topControls = new LinearLayout(this); topControls.setGravity(Gravity.CENTER);
        bottomControls = new LinearLayout(this); bottomControls.setGravity(Gravity.CENTER);
        viewButton = makeButton("HEADLESS");
        viewButton.setOnClickListener(v -> { headless = !headless; saveBoolean("headless", headless); applyDisplayMode(); });
        readTextButton = makeButton("READ TEXT");
        readTextButton.setOnClickListener(v -> requestReadText());
        startPauseButton = makeButton(announcementsArmed ? "PAUSE VOICE" : "START VOICE");
        startPauseButton.setOnClickListener(v -> setAnnouncementsArmed(!announcementsArmed, true));
        recordButton = makeButton("RECORD 10S"); recordButton.setOnClickListener(v -> {
            if (recordingInProgress) stopRecording(true); else startRecording(10);
        });
        voiceButton = makeButton(voiceEnabled ? "VOICE ON" : "VOICE OFF");
        voiceButton.setOnClickListener(v -> {
            voiceEnabled = !voiceEnabled; saveBoolean("voice", voiceEnabled);
            voiceButton.setText(voiceEnabled ? "VOICE ON" : "VOICE OFF");
            if (voiceEnabled) speakPriority(tagalog ? "Boses naka-on." : "Voice announcements on.");
        });
        silenceButton = makeButton("SILENCE ALARM"); silenceButton.setOnClickListener(v -> stopAlarm());
        Button settingsShortcut = makeButton("SETTINGS"); settingsShortcut.setOnClickListener(v -> showSettings());
        topControls.addView(viewButton, weightedButton()); topControls.addView(readTextButton, weightedButton());
        topControls.addView(startPauseButton, weightedButton());
        bottomControls.addView(recordButton, weightedButton()); bottomControls.addView(voiceButton, weightedButton());
        bottomControls.addView(settingsShortcut, weightedButton());
        controls.addView(topControls); controls.addView(bottomControls);
        root.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1f);
        p.setMargins(dp(2), dp(2), dp(2), dp(2)); return p;
    }

    private Button makeButton(String text) {
        Button button = new Button(this); button.setText(text); button.setTextSize(10.5f);
        button.setTextColor(white); button.setAllCaps(false); button.setPadding(dp(3), 0, dp(3), 0);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(48, 34, 68)));
        return button;
    }

    private void applyDisplayMode() {
        if (root == null) return;
        if (headless) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            WindowManager.LayoutParams lp = getWindow().getAttributes(); lp.screenBrightness = 0.025f; getWindow().setAttributes(lp);
            header.setVisibility(View.GONE); statusView.setVisibility(View.GONE); detectionsView.setVisibility(View.GONE);
            // Keep PreviewView attached so CameraX continues analysis; the opaque black view hides its pixels.
            previewView.setVisibility(View.VISIBLE); overlayView.setVisibility(View.VISIBLE); headlessMessage.setVisibility(View.VISIBLE);
            viewButton.setText("SHOW CAMERA");
            controls.removeAllViews();
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER);
            Button show = makeButton("SHOW CAMERA"); show.setOnClickListener(v -> {
                headless = false; saveBoolean("headless", false); applyDisplayMode();
            });
            Button start = makeButton(announcementsArmed ? "PAUSE" : "START");
            start.setOnClickListener(v -> setAnnouncementsArmed(!announcementsArmed, true));
            Button settings = makeButton("SETTINGS"); settings.setOnClickListener(v -> showSettings());
            row.addView(show, weightedButton()); row.addView(start, weightedButton()); row.addView(settings, weightedButton());
            controls.addView(row);
            if (alarmActive) {
                Button silence = makeButton("SILENCE ALARM"); silence.setOnClickListener(v -> stopAlarm());
                controls.addView(silence, new LinearLayout.LayoutParams(-1, dp(44)));
            }
            controls.setVisibility(View.VISIBLE);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(0);
            WindowManager.LayoutParams lp = getWindow().getAttributes(); lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
            header.setVisibility(View.VISIBLE); statusView.setVisibility(View.VISIBLE); detectionsView.setVisibility(View.VISIBLE);
            previewView.setVisibility(View.VISIBLE); overlayView.setVisibility(View.VISIBLE); headlessMessage.setVisibility(View.GONE);
            controls.removeAllViews();
            topControls.removeAllViews(); bottomControls.removeAllViews();
            viewButton.setText("HEADLESS"); startPauseButton.setText(announcementsArmed ? "PAUSE VOICE" : "START VOICE");
            recordButton.setText(recordingInProgress ? "STOP RECORDING" : "RECORD 10S");
            voiceButton.setText(voiceEnabled ? "VOICE ON" : "VOICE OFF");
            topControls.addView(viewButton, weightedButton()); topControls.addView(readTextButton, weightedButton());
            topControls.addView(startPauseButton, weightedButton());
            bottomControls.addView(recordButton, weightedButton()); bottomControls.addView(voiceButton, weightedButton());
            Button settings = makeButton("SETTINGS"); settings.setOnClickListener(v -> showSettings());
            bottomControls.addView(settings, weightedButton());
            controls.addView(topControls); controls.addView(bottomControls);
            if (alarmActive) {
                Button silence = makeButton("SILENCE ALARM"); silence.setOnClickListener(v -> stopAlarm());
                controls.addView(silence, new LinearLayout.LayoutParams(-1, dp(42)));
            }
            statusView.setText(detectorStatus + (usingDepthModel ? " + DEPTH" : " + NO DEPTH MODEL"));
        }
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
                cameraFaulted = false; cameraStartInFlight = false;
                setStatus("CAMERA ACTIVE · " + detectorStatus + (usingDepthModel ? " + DEPTH" : " + DEPTH UNAVAILABLE"));
            } catch (Exception ex) {
                cameraStartInFlight = false;
                setStatus("CAMERA ERROR · " + message(ex)); announceFault("Camera could not start. Check camera permission and try again.");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(@NonNull ImageProxy proxy) {
        long now = SystemClock.elapsedRealtime();
        long minInterval = headless ? 850L : 280L;
        if (now - lastAnalysisAt < minInterval) { proxy.close(); return; }
        lastAnalysisAt = now; lastFrameAt = now;
        if (modelsLoading || activityDestroyed) { proxy.close(); return; }
        if (cameraFaulted) {
            cameraFaulted = false; mainHandler.post(() -> speakPriority("Camera restored."));
        }
        Bitmap frame;
        try { frame = YoloTfliteDetector.imageProxyToUprightBitmap(proxy); }
        catch (Exception ex) {
            proxy.close(); setStatus("CAMERA FRAME ERROR · " + message(ex)); return;
        }
        frameCount++;
        if (readTextRequested.compareAndSet(true, false)) {
            InputImage input = InputImage.fromBitmap(frame, 0);
            textRecognizer.process(input)
                    .addOnSuccessListener(this::onTextRead)
                    .addOnFailureListener(e -> { setStatus("TEXT READING FAILED"); speakPriority(tagalog ? "Hindi mabasa ang teksto." : "Could not read text."); })
                    .addOnCompleteListener(task -> { frame.recycle(); proxy.close(); });
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
                if (!modelErrorSpoken) { modelErrorSpoken = true; speakPriority("Object detector error. Check AISight model settings."); }
            } finally { frame.recycle(); proxy.close(); }
            return;
        }
        if (fallbackDetector == null) { frame.recycle(); proxy.close(); return; }
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
                .addOnCompleteListener(task -> { frame.recycle(); proxy.close(); });
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

        StringBuilder visual = new StringBuilder(usingCustomModel ? "AISIGHT CUSTOM MODEL · LATEST DETECTIONS" : "GENERIC DETECTOR FALLBACK");
        StringBuilder spoken = new StringBuilder();
        if (speakList.isEmpty()) visual.append("\nNo objects above the confidence threshold.");
        for (int i = 0; i < speakList.size(); i++) {
            DetectionInfo d = speakList.get(i);
            String label = translateLabel(d.label, tagalog);
            String distance = isFinite(d.meters) ? String.format(Locale.US, "%.1f m", d.meters) : "distance unavailable";
            visual.append("\n").append(i + 1).append(". ").append(label).append(" · ")
                    .append(d.pos3).append(" · ").append(Math.round(d.confidence * 100)).append("% · ").append(distance);
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
            visual.append("\nNAV: ").append(localizedAdvice);
            if (spoken.length() > 0) spoken.append(". ");
            spoken.append(localizedAdvice);
        }
        if (depthMap == null) visual.append("\nDEPTH MODEL UNAVAILABLE — distances and wall checks disabled.");
        latestVisual(visual.toString(), detections.size());
        maybeSpeak(spoken.toString(), "navigation".equals(phraseMode));
    }

    private void latestVisual(String visual, int count) {
        runOnUiThread(() -> {
            if (!headless) {
                detectionsView.setText(visual);
                statusView.setText((usingCustomModel ? "AISIGHT YOLO26N" : "GENERIC FALLBACK") + " · " + count
                        + " OBJECTS · " + (usingDepthModel && latestDepthMap != null ? "DEPTH ON" : "DEPTH OFF"));
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
        if (startPauseButton != null) startPauseButton.setText(value ? "PAUSE VOICE" : "START VOICE");
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

    private void startAlarm(long durationMs) {
        alarmActive = true;
        alarmDeadline = Math.max(alarmDeadline, SystemClock.elapsedRealtime() + Math.min(durationMs, MAX_ALARM_MS));
        if (vibrator != null) {
            try {
                long[] pattern = {0, 350, 180, 350, 450};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                else vibrator.vibrate(pattern, 0);
            } catch (Exception ignored) { }
        }
        mainHandler.removeCallbacks(alarmToneRunnable);
        mainHandler.post(alarmToneRunnable);
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
        if (recordButton != null) recordButton.setText(recordingInProgress ? "STOP RECORDING" : "RECORD 10S");
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
        box.setPadding(dp(20), dp(12), dp(20), dp(12)); box.setBackgroundColor(panel);
        TextView title = new TextView(this); title.setText("AISIGHT SETTINGS"); title.setTextColor(white); title.setTextSize(19);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); title.setPadding(0, 0, 0, dp(10)); box.addView(title);

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
    }

    private interface StringSetter { void set(String value); }
    private void addCycleSetting(LinearLayout box, String label, String[] choices, String initial, StringSetter setter) {
        Button row = makeButton(label + ": " + initial.toUpperCase(Locale.US)); row.setTextSize(13);
        row.setOnClickListener(v -> {
            String current = row.getText().toString();
            int index = 0; for (int i = 0; i < choices.length; i++) if (current.endsWith(choices[i].toUpperCase(Locale.US))) index = i;
            String next = choices[(index + 1) % choices.length]; setter.set(next);
            row.setText(label + ": " + next.toUpperCase(Locale.US));
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
        runOnUiThread(() -> { if (statusView != null && !headless) statusView.setText(text); });
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
