package com.example.aisightmobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Transparent overlay for visual verification; hidden completely in headless mode. */
final class DetectionOverlayView extends View {
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint depthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Item> items = new ArrayList<>();
    private int sourceWidth = 1;
    private int sourceHeight = 1;

    DetectionOverlayView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        boxPaint.setColor(Color.rgb(153, 255, 195));
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(3.0f);
        labelPaint.setColor(Color.BLACK);
        labelPaint.setTextSize(30f);
        labelPaint.setFakeBoldText(true);
        labelBg.setColor(Color.rgb(153, 255, 195));
        labelBg.setStyle(Paint.Style.FILL);
        depthPaint.setColor(Color.WHITE);
        depthPaint.setTextSize(25f);
        depthPaint.setShadowLayer(3f, 1f, 1f, Color.BLACK);
    }

    void update(List<Item> newItems, int frameWidth, int frameHeight) {
        items = new ArrayList<>(newItems);
        sourceWidth = Math.max(1, frameWidth);
        sourceHeight = Math.max(1, frameHeight);
        postInvalidateOnAnimation();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (items.isEmpty() || getWidth() == 0 || getHeight() == 0) return;
        float scale = Math.max(getWidth() / (float) sourceWidth, getHeight() / (float) sourceHeight);
        float offsetX = (getWidth() - sourceWidth * scale) / 2f;
        float offsetY = (getHeight() - sourceHeight * scale) / 2f;
        for (Item item : items) {
            RectF r = new RectF(item.bounds);
            r.left = r.left * scale + offsetX;
            r.right = r.right * scale + offsetX;
            r.top = r.top * scale + offsetY;
            r.bottom = r.bottom * scale + offsetY;
            canvas.drawRoundRect(r, 8f, 8f, boxPaint);
            String text = item.label + "  " + Math.round(item.confidence * 100) + "%";
            float textWidth = labelPaint.measureText(text);
            float top = Math.max(0, r.top - 38f);
            canvas.drawRoundRect(r.left, top, Math.min(getWidth(), r.left + textWidth + 18f), top + 36f,
                    5f, 5f, labelBg);
            canvas.drawText(text, r.left + 8f, top + 27f, labelPaint);
            if (item.detail != null && !item.detail.isEmpty()) {
                canvas.drawText(item.detail, r.left, Math.min(getHeight() - 2f, r.bottom + 27f), depthPaint);
            }
        }
    }

    static final class Item {
        final String label;
        final float confidence;
        final RectF bounds;
        final String detail;
        Item(String label, float confidence, RectF bounds, String detail) {
            this.label = label; this.confidence = confidence; this.bounds = new RectF(bounds); this.detail = detail;
        }
    }
}
