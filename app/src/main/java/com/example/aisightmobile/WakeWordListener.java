package com.example.aisightmobile;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Offline Vosk microphone listener. The caller should stop it when the activity leaves foreground. */
final class WakeWordListener implements AutoCloseable {
    interface Callbacks {
        void onPhrase(String phrase);
        void onMicFault(String message);
        void onMicRestored();
        void onListenerError(String message);
    }

    private static final String ASSET_MODEL = "vosk-model-small-en-us-0.15";
    private static final int SAMPLE_RATE = 16000;
    private final Context appContext;
    private final Callbacks callbacks;
    private volatile boolean running;
    private volatile AudioRecord activeRecord;
    private Thread worker;

    WakeWordListener(Context context, Callbacks callbacks) {
        this.appContext = context.getApplicationContext();
        this.callbacks = callbacks;
    }

    synchronized void start() {
        if (running) return;
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            callbacks.onListenerError("Microphone permission was not granted.");
            return;
        }
        running = true;
        worker = new Thread(this::runLoop, "AISight-Vosk-WakeWord");
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
    }

    private void runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        Model model = null;
        Recognizer recognizer = null;
        boolean faulted = false;
        try {
            File modelDir = new File(appContext.getFilesDir(), ASSET_MODEL);
            try {
                copyAssetFolder(appContext.getAssets(), ASSET_MODEL, modelDir);
            } catch (Exception ex) {
                callbacks.onListenerError("Could not prepare offline Vosk model: " + message(ex));
                return;
            }
            try {
                model = new Model(modelDir.getAbsolutePath());
                recognizer = new Recognizer(model, SAMPLE_RATE);
            } catch (Exception ex) {
                callbacks.onListenerError("Could not load offline Vosk model: " + message(ex));
                return;
            }

            while (running) {
                AudioRecord record = null;
                try {
                    int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    if (minBuffer <= 0) minBuffer = 8192;
                    int bufferSize = Math.max(minBuffer * 2, 8192);
                    record = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
                    activeRecord = record;
                    if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                        throw new IllegalStateException("Android could not initialize the microphone stream");
                    }
                    record.startRecording();
                    if (faulted) { faulted = false; callbacks.onMicRestored(); }
                    byte[] buffer = new byte[4096];
                    while (running && record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        int count = record.read(buffer, 0, buffer.length);
                        if (count > 0 && recognizer.acceptWaveForm(buffer, count)) {
                            String phrase = resultText(recognizer.getResult());
                            if (!phrase.isEmpty()) callbacks.onPhrase(phrase);
                        } else if (count < 0) {
                            throw new IllegalStateException("Microphone read failed (code " + count + ")");
                        }
                    }
                } catch (SecurityException | IllegalStateException ex) {
                    if (running && !faulted) { faulted = true; callbacks.onMicFault(message(ex)); }
                    sleepQuietly(1500);
                } finally {
                    activeRecord = null;
                    if (record != null) {
                        try { if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop(); }
                        catch (Exception ignored) { }
                        try { record.release(); } catch (Exception ignored) { }
                    }
                }
            }
        } catch (Exception ex) {
            if (running) callbacks.onListenerError(message(ex));
        } finally {
            running = false;
            if (recognizer != null) try { recognizer.close(); } catch (Exception ignored) { }
            if (model != null) try { model.close(); } catch (Exception ignored) { }
        }
    }

    private static String resultText(String json) {
        try { return new JSONObject(json).optString("text", "").trim().toLowerCase(java.util.Locale.US); }
        catch (Exception ignored) { return ""; }
    }

    private static void copyAssetFolder(AssetManager assets, String assetPath, File destination) throws IOException {
        String[] children = assets.list(assetPath);
        if (children != null && children.length > 0) {
            if (!destination.exists() && !destination.mkdirs()) throw new IOException("Cannot create " + destination);
            for (String child : children) copyAssetFolder(assets, assetPath + "/" + child, new File(destination, child));
            return;
        }
        if (destination.exists() && destination.length() > 0) return;
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        try (InputStream in = assets.open(assetPath); FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static void sleepQuietly(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    @Override public synchronized void close() {
        running = false;
        AudioRecord record = activeRecord;
        if (record != null) {
            try { record.stop(); } catch (Exception ignored) { }
        }
        Thread oldWorker = worker;
        if (oldWorker != null) oldWorker.interrupt();
        worker = null;
        if (oldWorker != null && oldWorker != Thread.currentThread()) {
            try { oldWorker.join(350L); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
    }
}
