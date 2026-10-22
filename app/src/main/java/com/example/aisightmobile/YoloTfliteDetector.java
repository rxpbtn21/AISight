package com.example.aisightmobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.Image;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;

import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Runs the custom AISight YOLO26n model exported to float32 TFLite. */
final class YoloTfliteDetector implements AutoCloseable {
    static final String[] LABELS = {
            "stop_sign", "e-tricycle", "car", "person", "e-bike", "e-trike", "tricycle",
            "van", "bench", "bike", "bus", "cat", "crosswalk", "dog", "manhole",
            "hazard_sign", "jeepney", "motorcycle", "door", "pit_hole", "puddle", "ramps",
            "stairs", "tree", "truck", "uncovered_manhole", "utility_pole"
    };

    private static final int MODEL_SIZE = 640;
    private static final int MAX_RESULTS = 20;
    private static final float NMS_IOU = 0.45f;

    private final Interpreter interpreter;
    private final int inputWidth;
    private final int inputHeight;
    private final boolean inputNhwc;
    private final int[] outputShape;
    private final ByteBuffer inputBuffer;
    private final float[][][] outputBuffer;
    private final int[] inputPixels;

    YoloTfliteDetector(@NonNull Context context) throws IOException {
        File modelFile = new File(context.getFilesDir(), "aisight.tflite");
        if (!modelFile.exists() || modelFile.length() == 0) {
            try (InputStream source = context.getAssets().open("aisight.tflite");
                 FileOutputStream destination = new FileOutputStream(modelFile)) {
                byte[] chunk = new byte[64 * 1024];
                int count;
                while ((count = source.read(chunk)) != -1) destination.write(chunk, 0, count);
            }
        }

        try (FileInputStream stream = new FileInputStream(modelFile);
             FileChannel channel = stream.getChannel()) {
            ByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            Interpreter.Options options = new Interpreter.Options().setNumThreads(4);
            interpreter = new Interpreter(mapped, options);
        } catch (RuntimeException ex) {
            throw new IOException("Could not load AISight TFLite model: " + ex.getMessage(), ex);
        }

        int[] inputShape = interpreter.getInputTensor(0).shape();
        if (inputShape.length != 4) {
            interpreter.close();
            throw new IOException("Expected a rank-4 image model input; got " + shapeText(inputShape));
        }
        inputNhwc = inputShape[3] == 3;
        if (inputNhwc) {
            inputHeight = inputShape[1];
            inputWidth = inputShape[2];
        } else if (inputShape[1] == 3) {
            inputHeight = inputShape[2];
            inputWidth = inputShape[3];
        } else {
            interpreter.close();
            throw new IOException("Could not identify RGB input layout: " + shapeText(inputShape));
        }
        outputShape = interpreter.getOutputTensor(0).shape();
        if (inputWidth <= 0 || inputHeight <= 0 || outputShape.length != 3 || outputShape[0] != 1
                || Math.min(outputShape[1], outputShape[2]) < LABELS.length + 4) {
            interpreter.close();
            throw new IOException("Unsupported model tensor layout. Input=" + shapeText(inputShape)
                    + ", output=" + shapeText(outputShape));
        }
        inputBuffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * 4)
                .order(ByteOrder.nativeOrder());
        outputBuffer = new float[outputShape[0]][outputShape[1]][outputShape[2]];
        inputPixels = new int[inputWidth * inputHeight];
    }

    List<Detection> detect(@NonNull ImageProxy proxy, float confidenceThreshold) throws IOException {
        Bitmap upright = imageProxyToUprightBitmap(proxy);
        try {
            return detectBitmap(upright, confidenceThreshold);
        } finally {
            upright.recycle();
        }
    }

    /** Caller owns the returned upright bitmap and must recycle it. */
    static Bitmap imageProxyToUprightBitmap(ImageProxy proxy) throws IOException {
        Bitmap raw = imageProxyToBitmap(proxy);
        Bitmap upright = rotateBitmap(raw, proxy.getImageInfo().getRotationDegrees());
        if (upright != raw) raw.recycle();
        return upright;
    }

    List<Detection> detectBitmap(@NonNull Bitmap bitmap, float threshold) {
        float scale = Math.min(inputWidth / (float) bitmap.getWidth(), inputHeight / (float) bitmap.getHeight());
        int resizedWidth = Math.max(1, Math.min(inputWidth, Math.round(bitmap.getWidth() * scale)));
        int resizedHeight = Math.max(1, Math.min(inputHeight, Math.round(bitmap.getHeight() * scale)));
        float scaleX = resizedWidth / (float) bitmap.getWidth();
        float scaleY = resizedHeight / (float) bitmap.getHeight();
        int padX = (inputWidth - resizedWidth) / 2;
        int padY = (inputHeight - resizedHeight) / 2;

        Bitmap resized = Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, true);
        Bitmap letterboxed = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(letterboxed);
        canvas.drawColor(Color.rgb(114, 114, 114));
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(resized, padX, padY, paint);
        if (resized != bitmap) resized.recycle();

        letterboxed.getPixels(inputPixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);
        letterboxed.recycle();

        // Reuse a direct buffer instead of allocating hundreds of thousands of tiny
        // Java arrays for float[1][640][640][3] on every camera frame.
        inputBuffer.clear();
        if (inputNhwc) {
            for (int px : inputPixels) {
                inputBuffer.putFloat(((px >> 16) & 0xff) / 255.0f);
                inputBuffer.putFloat(((px >> 8) & 0xff) / 255.0f);
                inputBuffer.putFloat((px & 0xff) / 255.0f);
            }
        } else {
            for (int shift : new int[]{16, 8, 0}) {
                for (int px : inputPixels) inputBuffer.putFloat(((px >> shift) & 0xff) / 255.0f);
            }
        }
        inputBuffer.rewind();

        java.util.Map<Integer, Object> outputs = new java.util.HashMap<>();
        outputs.put(0, outputBuffer);
        interpreter.runForMultipleInputsOutputs(new Object[]{inputBuffer}, outputs);
        float[][][] output = outputBuffer;

        boolean channelFirst = outputShape[1] <= 128 && outputShape[2] > outputShape[1];
        int features = channelFirst ? outputShape[1] : outputShape[2];
        int candidates = channelFirst ? outputShape[2] : outputShape[1];
        int classOffset;
        boolean hasObjectness;
        if (features == LABELS.length + 5) {
            classOffset = 5;
            hasObjectness = true;
        } else if (features >= LABELS.length + 4) {
            classOffset = 4;
            hasObjectness = false;
        } else {
            throw new IllegalStateException("Unexpected YOLO output: " + shapeText(outputShape)
                    + " for " + LABELS.length + " classes");
        }

        List<Detection> proposals = new ArrayList<>();
        for (int n = 0; n < candidates; n++) {
            float best = 0f;
            int bestClass = -1;
            float objectness = hasObjectness ? value(output, channelFirst, 4, n) : 1f;
            for (int c = 0; c < LABELS.length; c++) {
                float score = value(output, channelFirst, classOffset + c, n) * objectness;
                if (score > best) {
                    best = score;
                    bestClass = c;
                }
            }
            if (bestClass < 0 || best < threshold) continue;

            float cx = value(output, channelFirst, 0, n);
            float cy = value(output, channelFirst, 1, n);
            float bw = value(output, channelFirst, 2, n);
            float bh = value(output, channelFirst, 3, n);
            // Some exporters emit normalized coordinates; others emit 640-space pixels.
            if (Math.max(Math.max(Math.abs(cx), Math.abs(cy)), Math.max(Math.abs(bw), Math.abs(bh))) <= 1.5f) {
                cx *= inputWidth; cy *= inputHeight; bw *= inputWidth; bh *= inputHeight;
            }
            float left = ((cx - bw / 2f) - padX) / scaleX;
            float top = ((cy - bh / 2f) - padY) / scaleY;
            float right = ((cx + bw / 2f) - padX) / scaleX;
            float bottom = ((cy + bh / 2f) - padY) / scaleY;
            left = clamp(left, 0, bitmap.getWidth());
            top = clamp(top, 0, bitmap.getHeight());
            right = clamp(right, 0, bitmap.getWidth());
            bottom = clamp(bottom, 0, bitmap.getHeight());
            if (right - left < 2 || bottom - top < 2) continue;
            proposals.add(new Detection(LABELS[bestClass], best, bestClass,
                    new RectF(left, top, right, bottom)));
        }

        Collections.sort(proposals, (a, b) -> Float.compare(b.confidence, a.confidence));
        List<Detection> kept = new ArrayList<>();
        for (Detection candidate : proposals) {
            boolean suppressed = false;
            for (Detection prior : kept) {
                if (prior.classId == candidate.classId && iou(prior.bounds, candidate.bounds) > NMS_IOU) {
                    suppressed = true;
                    break;
                }
            }
            if (!suppressed) kept.add(candidate);
            if (kept.size() >= MAX_RESULTS) break;
        }
        return kept;
    }

    private static float value(float[][][] out, boolean channelFirst, int feature, int candidate) {
        return channelFirst ? out[0][feature][candidate] : out[0][candidate][feature];
    }

    private static float iou(RectF a, RectF b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        float intersection = Math.max(0, right - left) * Math.max(0, bottom - top);
        float union = a.width() * a.height() + b.width() * b.height() - intersection;
        return union <= 0 ? 0 : intersection / union;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    static Bitmap rotateBitmap(Bitmap source, int degrees) {
        if (degrees == 0) return source;
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    static Bitmap imageProxyToBitmap(ImageProxy proxy) throws IOException {
        Image image = proxy.getImage();
        if (image == null) throw new IOException("Camera frame is empty");
        ImageProxy.PlaneProxy[] planes = proxy.getPlanes();
        if (planes.length < 3) throw new IOException("Expected YUV camera image");
        ByteBuffer yBuffer = planes[0].getBuffer().duplicate();
        ByteBuffer uBuffer = planes[1].getBuffer().duplicate();
        ByteBuffer vBuffer = planes[2].getBuffer().duplicate();
        int width = proxy.getWidth();
        int height = proxy.getHeight();
        int yRow = planes[0].getRowStride(), yPixel = planes[0].getPixelStride();
        int uRow = planes[1].getRowStride(), uPixel = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride(), vPixel = planes[2].getPixelStride();
        int yPosition = yBuffer.position(), uPosition = uBuffer.position(), vPosition = vBuffer.position();
        int[] colors = new int[width * height];
        for (int y = 0; y < height; y++) {
            int yBase = y * yRow;
            int uBase = (y >> 1) * uRow;
            int vBase = (y >> 1) * vRow;
            for (int x = 0; x < width; x++) {
                int yy = yBuffer.get(yPosition + yBase + x * yPixel) & 0xff;
                int uu = (uBuffer.get(uPosition + uBase + (x >> 1) * uPixel) & 0xff) - 128;
                int vv = (vBuffer.get(vPosition + vBase + (x >> 1) * vPixel) & 0xff) - 128;
                int c = Math.max(0, yy - 16);
                int r = clampInt((298 * c + 409 * vv + 128) >> 8);
                int g = clampInt((298 * c - 100 * uu - 208 * vv + 128) >> 8);
                int b = clampInt((298 * c + 516 * uu + 128) >> 8);
                colors[y * width + x] = Color.rgb(r, g, b);
            }
        }
        return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888);
    }

    private static int clampInt(int value) { return Math.max(0, Math.min(255, value)); }

    private static String shapeText(int[] shape) {
        return java.util.Arrays.toString(shape);
    }

    @Override public void close() { interpreter.close(); }

    static final class Detection {
        final String label;
        final float confidence;
        final int classId;
        final RectF bounds;
        Detection(String label, float confidence, int classId, RectF bounds) {
            this.label = label;
            this.confidence = confidence;
            this.classId = classId;
            this.bounds = bounds;
        }
    }
}
