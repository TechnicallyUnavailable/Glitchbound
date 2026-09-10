package com.technicallyunavailable.measure;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;
import java.util.Collections;
import java.util.List;

public final class MeasurementOverlayView extends View {
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<MeasureRenderer.ScreenPoint> points = Collections.emptyList();
    private List<MeasureRenderer.ScreenSegment> segments = Collections.emptyList();

    public MeasurementOverlayView(Context context) {
        super(context);
        setWillNotDraw(false);

        linePaint.setColor(Color.rgb(255, 138, 0));
        linePaint.setStrokeWidth(dp(3f));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        pointPaint.setColor(Color.rgb(255, 138, 0));
        pointPaint.setStyle(Paint.Style.FILL);

        pointTextPaint.setColor(Color.BLACK);
        pointTextPaint.setTextSize(dp(12f));
        pointTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        pointTextPaint.setTextAlign(Paint.Align.CENTER);

        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(dp(14f));
        labelPaint.setTypeface(Typeface.DEFAULT_BOLD);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        labelBgPaint.setColor(0xD9000000);
        labelBgPaint.setStyle(Paint.Style.FILL);

        reticlePaint.setColor(Color.WHITE);
        reticlePaint.setStrokeWidth(dp(2f));
        reticlePaint.setStyle(Paint.Style.STROKE);
    }

    public void setMeasurementGeometry(List<MeasureRenderer.ScreenPoint> newPoints,
                                       List<MeasureRenderer.ScreenSegment> newSegments) {
        points = newPoints == null ? Collections.emptyList() : newPoints;
        segments = newSegments == null ? Collections.emptyList() : newSegments;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        for (MeasureRenderer.ScreenSegment segment : segments) {
            if (!segment.visible) continue;
            canvas.drawLine(segment.x1, segment.y1, segment.x2, segment.y2, linePaint);
            if (segment.label != null && !segment.label.isEmpty()) {
                float mx = (segment.x1 + segment.x2) * 0.5f;
                float my = (segment.y1 + segment.y2) * 0.5f;
                float width = labelPaint.measureText(segment.label) + dp(18f);
                float height = dp(28f);
                canvas.drawRoundRect(mx - width / 2f, my - height / 2f,
                        mx + width / 2f, my + height / 2f, dp(8f), dp(8f), labelBgPaint);
                canvas.drawText(segment.label, mx, my + dp(5f), labelPaint);
            }
        }

        for (MeasureRenderer.ScreenPoint point : points) {
            if (!point.visible) continue;
            float radius = dp(12f);
            canvas.drawCircle(point.x, point.y, radius, pointPaint);
            canvas.drawText(String.valueOf(point.index + 1), point.x, point.y + dp(4f), pointTextPaint);
        }

        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f;
        float r = dp(15f);
        canvas.drawCircle(cx, cy, r, reticlePaint);
        canvas.drawLine(cx - r - dp(8f), cy, cx - r + dp(2f), cy, reticlePaint);
        canvas.drawLine(cx + r - dp(2f), cy, cx + r + dp(8f), cy, reticlePaint);
        canvas.drawLine(cx, cy - r - dp(8f), cx, cy - r + dp(2f), reticlePaint);
        canvas.drawLine(cx, cy + r - dp(2f), cx, cy + r + dp(8f), reticlePaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
