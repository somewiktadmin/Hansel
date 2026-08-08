/*
 * Hansel - GPS breadcrumb logger v0.988
 * Copyright (C) 2026 GrimmsTales
 * GNU General Public License v3 - https://www.gnu.org/licenses/gpl-3.0.html
 */
package com.hansel.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * Self-contained speedometer overlay for Hansel's mapview.
 *
 * This class knows nothing about GPS, LocationService, or the map itself -
 * it is fed a speed value via setSpeed() and draws a plain MPH readout.
 * The intent is that this class (plus its own tiny prefs file) can be
 * lifted into a standalone app later with minimal change - only the
 * container differs (this FrameLayout child today, a WindowManager
 * overlay later).
 *
 * v1 scope: MPH only, no on/off setting, tap toggles black/white theme,
 * long-press-drag repositions (clamped to stay within the mapview,
 * up to 90% off-screen at the edges), confined to the mapview only.
 */
public class SpeedometerView extends View {

    private static final String PREFS_NAME = "speedometer_prefs";
    private static final String KEY_X_FRAC = "x_frac";
    private static final String KEY_Y_FRAC = "y_frac";
    private static final String KEY_DARK = "dark_mode";

    private static final float RESET_MARGIN_PX = 50f;
    private static final float MAX_OFFSCREEN_FRACTION = 0.9f;
    //private static final int VIEW_SIZE_PX = 180;
    private static final float SIZE_FRACTION_OF_WIDTH = 0.15f;
    private static final int FALLBACK_SIZE_PX = 180; // only used if parent width isn't known yet

    private final SharedPreferences prefs;

    private boolean darkMode;
    private float xFrac = -1f; // -1 = not yet positioned
    private float yFrac = -1f;
    private float speedMph = 0f;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gestureDetector;

    private boolean dragging = false;
    private float dragStartRawX, dragStartRawY;
    private float dragStartLeft, dragStartTop;

    private static final float CORNER_RADIUS_PX = 20f;
    private static final float BORDER_WIDTH_PX = 4f;

    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SpeedometerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        darkMode = prefs.getBoolean(KEY_DARK, true);
        xFrac = prefs.getFloat(KEY_X_FRAC, -1f);
        yFrac = prefs.getFloat(KEY_Y_FRAC, -1f);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                darkMode = !darkMode;
                prefs.edit().putBoolean(KEY_DARK, darkMode).apply();
                applyTheme();
                invalidate();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                dragging = true;
                dragStartRawX = e.getRawX();
                dragStartRawY = e.getRawY();
                dragStartLeft = getX();
                dragStartTop = getY();
            }
        });

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(BORDER_WIDTH_PX);
        applyTheme();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);

        if (dragging) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - dragStartRawX;
                    float dy = event.getRawY() - dragStartRawY;
                    moveTo(dragStartLeft + dx, dragStartTop + dy);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    savePosition();
                    return true;
            }
        }
        return true;
    }

    /** Called from MainActivity.updateGpsInfoOverlay() on every GPS fix. */
    public void setSpeed(float mph) {
        this.speedMph = mph;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(this::applySavedOrDefaultPosition);
    }

    private void applySavedOrDefaultPosition() {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent == null || parent.getWidth() == 0 || parent.getHeight() == 0) return;

        if (xFrac < 0 || yFrac < 0) {
            float left = RESET_MARGIN_PX;
            float top = parent.getHeight() - getHeight() - RESET_MARGIN_PX;
            moveTo(left, top);
            savePosition();
        } else {
            moveTo(xFrac * parent.getWidth(), yFrac * parent.getHeight());
        }
    }

    private void moveTo(float left, float top) {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent == null) return;

        int pw = parent.getWidth();
        int ph = parent.getHeight();
        int w = getWidth();
        int h = getHeight();
        if (pw == 0 || ph == 0 || w == 0 || h == 0) return;

        float minLeft = -MAX_OFFSCREEN_FRACTION * w;
        float maxLeft = pw - (1 - MAX_OFFSCREEN_FRACTION) * w;
        float minTop  = -MAX_OFFSCREEN_FRACTION * h;
        float maxTop  = ph - (1 - MAX_OFFSCREEN_FRACTION) * h;

        left = Math.max(minLeft, Math.min(maxLeft, left));
        top  = Math.max(minTop, Math.min(maxTop, top));

        setX(left);
        setY(top);

        xFrac = left / pw;
        yFrac = top / ph;
    }

    private void savePosition() {
        prefs.edit()
                .putFloat(KEY_X_FRAC, xFrac)
                .putFloat(KEY_Y_FRAC, yFrac)
                .apply();
    }

    private void applyTheme() {
        if (darkMode) {
            bgPaint.setColor(Color.BLACK);
            textPaint.setColor(Color.WHITE);
            borderPaint.setColor(Color.WHITE);
        } else {
            bgPaint.setColor(Color.WHITE);
            textPaint.setColor(Color.BLACK);
            borderPaint.setColor(Color.BLACK);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        canvas.drawRoundRect(0, 0, w, h, CORNER_RADIUS_PX, CORNER_RADIUS_PX, bgPaint);

        float half = BORDER_WIDTH_PX / 2f;
        canvas.drawRoundRect(half, half, w - half, h - half,
                CORNER_RADIUS_PX, CORNER_RADIUS_PX, borderPaint);

        textPaint.setTextSize(h * 0.4f);
        canvas.drawText(String.valueOf(Math.round(speedMph)), w / 2f, h * 0.55f, textPaint);
        textPaint.setTextSize(h * 0.14f);
        canvas.drawText("MPH", w / 2f, h * 0.78f, textPaint);
    }

    /** handle screen rotation elegantly */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int parentWidth = MeasureSpec.getSize(widthMeasureSpec);
        int size = parentWidth > 0
                ? Math.round(parentWidth * SIZE_FRACTION_OF_WIDTH)
                : FALLBACK_SIZE_PX;
        setMeasuredDimension(size, size);
    }

}
