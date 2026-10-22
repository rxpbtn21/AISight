package com.example.aisightmobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import androidx.annotation.NonNull;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

/** On-device runner for an Ultralytics YOLO26n-depth LiteRT/TFLite export. */
final class DepthTfliteEstimator implements AutoCloseable {
    private static final float SMOOTHING_ALPHA = 0.55f;
    private final Interpreter interpreter;
    private final int inputWidth;
    private final int inputHeight;
    private final boolean inputNhwc;
    private final int[] outputShape;
    private final int outputWidth;
    private final int outputHeight;
    private final boolean outputNhwc;
    private final ByteBuffer inputBuffer;
    private final ByteBuffer outputBuffer;
    private final int[] inputPixels;
    private float[] smoothedOutput;

    DepthTfliteEstimator(@NonNull Context context) throws IOException {
        File modelFile = new File(context.getFilesDir(), "depth.tflite");
        if (!modelFile.exists() || modelFile.length() == 0) {
            try (InputStream in = context.getAssets().open("depth.tflite");
                 FileOutputStream out = new FileOutputStream(modelFile)) {
                byte[] chunk = new byte[64 * 1024];
                int count;
                while ((count = in.read(chunk)) != -1) out.write(chunk, 0, count);
            }
        }
        try (FileInputStream stream = new FileInputStream(modelFile);
             FileChannel channel = stream.getChannel()) {
            ByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            interpreter = new Interpreter(mapped, new Interpreter.Options().setNumThreads(3));
        } catch (RuntimeException ex) {
            throw new IOException("Could not load YOLO26 depth model: " + message(ex), ex);
        }

        Tensor input = interpreter.getInputTensor(0);
        int[] inShape = input.shape();
        if (input.dataType() != DataType.FLOAT32 || inShape.length != 4) {
            interpreter.close();
            throw new IOException("Depth model must have rank-4 FLOAT32 input; got " + Arrays.toString(inShape)
                    + " / " + input.dataType());
        }
        inputNhwc = inShape[3] == 3;
        if (inputNhwc) {
            inputHeight = inShape[1]; inputWidth = inShape[2];
        } else if (inShape[1] == 3) {
            inputHeight = inShape[2]; inputWidth = inShape[3];
        } else {
            interpreter.close();
            throw new IOException("Unrecognized depth-model input layout: " + Arrays.toString(inShape));
        }

        Tensor output = interpreter.getOutputTensor(0);
        outputShape = output.shape();
        if (output.dataType() != DataType.FLOAT32) {
            interpreter.close();
            throw new IOException("Depth output must be FLOAT32; got " + output.dataType());
        }
        int[] normalized = outputShape;
        if (normalized.length == 4 && normalized[0] == 1) {
            if (normalized[1] == 1 && normalized[2] > 4 && normalized[3] > 4) {
                outputNhwc = false; outputHeight = normalized[2]; outputWidth = normalized[3];
            } else if (normalized[3] == 1 && normalized[1] > 4 && normalized[2] > 4) {
                outputNhwc = true; outputHeight = normalized[1]; outputWidth = normalized[2];
            } else {
                interpreter.close();
                throw new IOException("Cannot infer depth output channel layout: " + Arrays.toString(outputShape));
            }
        } else if (normalized.length == 3 && normalized[0] == 1) {
            // A dense depth map is often returned as [1, height, width].
            outputNhwc = true; outputHeight = normalized[1]; outputWidth = normalized[2];
        } else {
            interpreter.close();
            throw new IOException("Unsupported depth output layout: " + Arrays.toString(outputShape));
        }
        int outputElements = 1;
        for (int dim : outputShape) outputElements *= dim;
        if (outputWidth <= 0 || outputHeight <= 0 || outputElements < outputWidth * outputHeight) {
            interpreter.close();
            throw new IOException("Invalid depth output dimensions: " + Arrays.toString(outputShape));
        }
        inputBuffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * 4).order(ByteOrder.nativeOrder());
        outputBuffer = ByteBuffer.allocateDirect(outputElements * 4).order(ByteOrder.nativeOrder());
        inputPixels = new int[inputWidth * inputHeight];
    }

    synchronized DepthMap estimate(@NonNull Bitmap frame) throws IOException {
        float scale = Math.min(inputWidth / (float) frame.getWidth(), inputHeight / (float) frame.getHeight());
        int resizedWidth = Math.max(1, Math.min(inputWidth, Math.round(frame.getWidth() * scale)));
        int resizedHeight = Math.max(1, Math.min(inputHeight, Math.round(frame.getHeight() * scale)));
        int padX = (inputWidth - resizedWidth) / 2;
        int padY = (inputHeight - resizedHeight) / 2;

        Bitmap resized = Bitmap.createScaledBitmap(frame, resizedWidth, resizedHeight, true);
        Bitmap letterboxed = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(letterboxed);
        canvas.drawColor(Color.rgb(114, 114, 114));
        canvas.drawBitmap(resized, padX, padY, new Paint(Paint.FILTER_BITMAP_FLAG));
        if (resized != frame) resized.recycle();
        letterboxed.getPixels(inputPixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);
        letterboxed.recycle();

        inputBuffer.clear();
        if (inputNhwc) {
            for (int pixel : inputPixels) {
                inputBuffer.putFloat(((pixel >> 16) & 0xff) / 255.0f);
                inputBuffer.putFloat(((pixel >> 8) & 0xff) / 255.0f);
                inputBuffer.putFloat((pixel & 0xff) / 255.0f);
            }
        } else {
            for (int shift : new int[]{16, 8, 0}) {
                for (int pixel : inputPixels) inputBuffer.putFloat(((pixel >> shift) & 0xff) / 255.0f);
            }
        }
        inputBuffer.rewind();
        outputBuffer.clear();
        try {
            interpreter.run(inputBuffer, outputBuffer);
        } catch (RuntimeException ex) {
            throw new IOException("Depth inference failed: " + message(ex), ex);
        }
        outputBuffer.rewind();
        FloatBuffer floatView = outputBuffer.asFloatBuffer();
        float[] raw = new float[floatView.remaining()];
        floatView.get(raw);
        int needed = outputWidth * outputHeight;
        if (raw.length < needed) throw new IOException("Depth output buffer is smaller than its spatial map.");
        if (smoothedOutput == null || smoothedOutput.length != raw.length) {
            smoothedOutput = raw.clone();
        } else {
            for (int i = 0; i < raw.length; i++) {
                smoothedOutput[i] = SMOOTHING_ALPHA * smoothedOutput[i] + (1.0f - SMOOTHING_ALPHA) * raw[i];
            }
        }
        return new DepthMap(smoothedOutput.clone(), outputWidth, outputHeight,
                inputWidth, inputHeight, frame.getWidth(), frame.getHeight(), scale, padX, padY, outputNhwc);
    }

    private static String message(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    @Override public void close() { interpreter.close(); }

    static final class DepthMap {
        final float[] values;
        final int mapWidth, mapHeight, inputWidth, inputHeight, frameWidth, frameHeight;
        final float scale;
        final int padX, padY;
        final boolean nhwc;

        DepthMap(float[] values, int mapWidth, int mapHeight, int inputWidth, int inputHeight,
                 int frameWidth, int frameHeight, float scale, int padX, int padY, boolean nhwc) {
            this.values = values; this.mapWidth = mapWidth; this.mapHeight = mapHeight;
            this.inputWidth = inputWidth; this.inputHeight = inputHeight;
            this.frameWidth = frameWidth; this.frameHeight = frameHeight;
            this.scale = scale; this.padX = padX; this.padY = padY; this.nhwc = nhwc;
        }

        float sampleMedian(RectF bounds) {
            int x1 = clamp((int) bounds.left, 0, frameWidth - 1);
            int y1 = clamp((int) bounds.top, 0, frameHeight - 1);
            int x2 = clamp((int) Math.ceil(bounds.right), x1 + 1, frameWidth);
            int y2 = clamp((int) Math.ceil(bounds.bottom), y1 + 1, frameHeight);
            int dx = Math.max(0, (x2 - x1) / 6);
            int dy = Math.max(0, (y2 - y1) / 6);
            x1 += dx; x2 -= dx; y1 += dy; y2 -= dy;
            int mx1 = mapX(x1), mx2 = Math.max(mx1 + 1, mapX(x2));
            int my1 = mapY(y1), my2 = Math.max(my1 + 1, mapY(y2));
            mx1 = clamp(mx1, 0, mapWidth - 1); mx2 = clamp(mx2, mx1 + 1, mapWidth);
            my1 = clamp(my1, 0, mapHeight - 1); my2 = clamp(my2, my1 + 1, mapHeight);
            int step = Math.max(1, Math.max(mx2 - mx1, my2 - my1) / 24);
            float[] samples = new float[Math.max(1, ((mx2 - mx1 + step - 1) / step) * ((my2 - my1 + step - 1) / step))];
            int count = 0;
            for (int y = my1; y < my2; y += step) {
                for (int x = mx1; x < mx2; x += step) {
                    float value = get(y, x);
                    if (isFinite(value) && value > 0.02f && value < 1000.0f) {
                        if (count == samples.length) samples = Arrays.copyOf(samples, samples.length * 2);
                        samples[count++] = value;
                    }
                }
            }
            if (count == 0) return Float.POSITIVE_INFINITY;
            Arrays.sort(samples, 0, count);
            return samples[count / 2];
        }

        WallResult detectWall(float threshold) {
            int x1 = mapX(frameWidth * 35 / 100), x2 = mapX(frameWidth * 65 / 100);
            int y1 = mapY(frameHeight * 25 / 100), y2 = mapY(frameHeight * 85 / 100);
            x1 = clamp(x1, 0, mapWidth - 1); x2 = clamp(x2, x1 + 1, mapWidth);
            y1 = clamp(y1, 0, mapHeight - 1); y2 = clamp(y2, y1 + 1, mapHeight);
            int step = Math.max(1, Math.max(x2 - x1, y2 - y1) / 120);
            long close = 0, count = 0;
            double sum = 0;
            for (int y = y1; y < y2; y += step) {
                for (int x = x1; x < x2; x += step) {
                    float v = get(y, x);
                    if (isFinite(v) && v > 0.02f && v < 1000.0f) {
                        count++; sum += v; if (v <= threshold) close++;
                    }
                }
            }
            if (count == 0) return new WallResult(false, Float.POSITIVE_INFINITY, 0f);
            float mean = (float) (sum / count);
            float ratio = close / (float) count;
            return new WallResult(ratio >= 0.50f, mean, ratio);
        }

        private int mapX(float sourceX) {
            float modelX = sourceX * scale + padX;
            return Math.round(modelX * mapWidth / inputWidth);
        }
        private int mapY(float sourceY) {
            float modelY = sourceY * scale + padY;
            return Math.round(modelY * mapHeight / inputHeight);
        }
        private float get(int y, int x) { return values[y * mapWidth + x]; }
        private static boolean isFinite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
        private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    }

    static final class WallResult {
        final boolean rawWall;
        final float meanMeters;
        final float closeRatio;
        WallResult(boolean rawWall, float meanMeters, float closeRatio) {
            this.rawWall = rawWall; this.meanMeters = meanMeters; this.closeRatio = closeRatio;
        }
    }
}
