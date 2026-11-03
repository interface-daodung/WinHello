package com.example.winhello;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * Animated circular countdown ring for 30-second TOTP authentication.
 * Drains clockwise like Google Authenticator and shows remaining seconds in the center.
 */
public final class TotpCountdownRingView extends View {
    public interface OnCycleRolloverListener {
        void onRollover();
    }

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;
    private long lastStep = -1;
    private OnCycleRolloverListener rolloverListener;

    public TotpCountdownRingView(Context context) {
        super(context);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        float strokeWidth = 3f * density;

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(strokeWidth);
        trackPaint.setColor(Color.argb(45, 255, 255, 255));

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(strokeWidth);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(Color.rgb(56, 189, 248)); // Cyan

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        textPaint.setTextSize(10f * density);
        textPaint.setColor(Color.WHITE);
    }

    public void setOnRolloverListener(OnCycleRolloverListener listener) {
        this.rolloverListener = listener;
    }

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            long now = System.currentTimeMillis();
            long step = (now / 1000) / 30;
            if (lastStep != -1 && step != lastStep) {
                if (rolloverListener != null) {
                    rolloverListener.onRollover();
                }
            }
            lastStep = step;
            invalidate();
            handler.postDelayed(this, 200);
        }
    };

    public void start() {
        if (!isRunning) {
            isRunning = true;
            handler.post(tickRunnable);
        }
    }

    public void stop() {
        isRunning = false;
        handler.removeCallbacks(tickRunnable);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;

        float stroke = arcPaint.getStrokeWidth();
        float pad = stroke / 2f + 1f;
        oval.set(pad, pad, width - pad, height - pad);

        // Draw background track
        canvas.drawOval(oval, trackPaint);

        // Calculate 30-second progress
        long now = System.currentTimeMillis();
        long elapsedInCycle = now % 30_000L;
        long remainingMs = 30_000L - elapsedInCycle;
        float progress = (float) remainingMs / 30_000f; // 1.0 down to 0.0
        float sweepAngle = progress * 360f;

        int secondsLeft = (int) (remainingMs / 1000L) + 1;
        if (secondsLeft > 30) secondsLeft = 30;

        // Change color when time is running out (<= 5s)
        if (secondsLeft <= 5) {
            arcPaint.setColor(Color.rgb(239, 68, 68)); // Warning Red
            textPaint.setColor(Color.rgb(248, 113, 113));
        } else if (secondsLeft <= 10) {
            arcPaint.setColor(Color.rgb(245, 158, 11)); // Amber
            textPaint.setColor(Color.rgb(251, 191, 36));
        } else {
            arcPaint.setColor(Color.rgb(56, 189, 248)); // Cyan
            textPaint.setColor(Color.WHITE);
        }

        // Draw draining arc (starting from top: -90 deg)
        canvas.drawArc(oval, -90f, sweepAngle, false, arcPaint);

        // Draw remaining seconds in center
        String text = String.valueOf(secondsLeft);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = (height / 2f) - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, width / 2f, textY, textPaint);
    }
}
