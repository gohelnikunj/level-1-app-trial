package com.fnoema.chart;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.time.format.DateTimeFormatter;
import java.util.*;

final class ChartView extends View {
    static final int CLOSE = Color.rgb(111, 168, 248);
    static final int EMA = Color.rgb(86, 225, 174);
    private static final int MUTED = Color.rgb(164, 179, 201);
    private static final int GRID = Color.rgb(45, 60, 83);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.ENGLISH)
            .withZone(Series.INDIA);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Series.Point> points = Collections.emptyList();

    ChartView(Context context) {
        super(context);
        setContentDescription("Line chart of closing price and EMA 9 in rupees");
    }

    void update(List<Series.Point> values) {
        points = values.subList(Math.max(0, values.size() - 80), values.size());
        invalidate();
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(19, 30, 48));
        float left = dp(14), right = getWidth() - dp(84), top = dp(22), bottom = getHeight() - dp(47);
        if (right <= left || bottom <= top) return;
        paint.setColor(MUTED);
        paint.setTextSize(dp(11));
        if (points.isEmpty()) {
            canvas.drawText("Waiting for candle data…", left, dp(35), paint);
            return;
        }
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (Series.Point point : points) {
            low = Math.min(low, point.close());
            high = Math.max(high, point.close());
            if (Double.isFinite(point.ema())) {
                low = Math.min(low, point.ema());
                high = Math.max(high, point.ema());
            }
        }
        double pad = Math.max(0.01, (high - low) * 0.08);
        low -= pad;
        high += pad;
        for (int row = 0; row <= 4; row++) {
            float y = top + (bottom - top) * row / 4;
            paint.setColor(GRID);
            paint.setStrokeWidth(dp(1));
            canvas.drawLine(left, y, right, y, paint);
            paint.setColor(MUTED);
            canvas.drawText(String.format(Locale.ENGLISH, "%.2f", high - (high - low) * row / 4),
                    right + dp(8), y + dp(4), paint);
        }
        line(canvas, false, left, right, top, bottom, low, high);
        line(canvas, true, left, right, top, bottom, low, high);
        paint.setColor(MUTED);
        String start = TIME.format(points.get(0).time());
        String end = TIME.format(points.get(points.size() - 1).time());
        canvas.drawText(start, left, bottom + dp(20), paint);
        if (points.size() > 1 && right - left > paint.measureText(start + end) + dp(8)) {
            canvas.drawText(end, right - paint.measureText(end), bottom + dp(20), paint);
        }
        canvas.drawText("IST", right + dp(8), bottom + dp(20), paint);
    }

    private void line(Canvas canvas, boolean ema, float left, float right, float top, float bottom,
                      double low, double high) {
        Path path = new Path();
        boolean first = true;
        float x = 0, y = 0;
        for (int index = 0; index < points.size(); index++) {
            Series.Point point = points.get(index);
            double value = ema ? point.ema() : point.close();
            if (!Double.isFinite(value)) continue;
            x = left + (right - left) * index / Math.max(1, points.size() - 1);
            y = bottom - (float) ((value - low) / (high - low)) * (bottom - top);
            if (first) path.moveTo(x, y);
            else path.lineTo(x, y);
            first = false;
        }
        if (first) return;
        paint.setColor(ema ? EMA : CLOSE);
        paint.setStrokeWidth(dp(ema ? 2.4f : 1.5f));
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(x, y, dp(3.2f), paint);
    }
}
