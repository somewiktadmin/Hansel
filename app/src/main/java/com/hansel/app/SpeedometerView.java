/*
 * Hansel - GPS breadcrumb logger v0.988
 * Copyright (C) 2026 GrimmsTales
 * GNU General Public License v3 - https://www.gnu.org/licenses/gpl-3.0.html
 */
package com.hansel.app;

import android.app.AlertDialog;
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
import android.widget.Toast;

/**
 * Self-contained speedometer overlay for Hansel's mapview.
 *
 * This class knows nothing about GPS, LocationService, or the map itself -
 * it is fed a speed value via setSpeed() and draws a plain speed readout.
 * The intent is that this class (plus its own tiny prefs file) can be
 * lifted into a standalone app later with minimal change - only the
 * container differs (this FrameLayout child today, a WindowManager
 * overlay later).
 *
 * Gestures:
 *   - single tap:  toggle black/white theme (unchanged from v1; now uses
 *     onSingleTapConfirmed() rather than onSingleTapUp() so it can coexist
 *     with double-tap detection - this adds Android's normal ~300ms
 *     double-tap-confirmation delay, which was explicitly accepted as a
 *     tradeoff rather than using a corner-zone or two-finger gesture)
 *   - long-press + drag: reposition (unchanged from v1), no-op if locked
 *   - double tap: opens the [Move] [Resize] [Settings] menu
 *
 * v2 scope: Move/Resize/Settings menu (native dialogs), resize presets
 * (Full / 2/3 / 1/5 / Free with a drag handle), lock-position setting,
 * MPH/KPH toggle. Settings dialog also exposes the theme toggle as an
 * explicit choice, alongside the tap-to-toggle shortcut - the shortcut
 * is left fully intact.
 */
public class SpeedometerView extends View {

    private static final String PREFS_NAME = "speedometer_prefs";
    private static final String KEY_X_FRAC = "x_frac";
    private static final String KEY_Y_FRAC = "y_frac";
    private static final String KEY_DARK = "dark_mode";
    private static final String KEY_LOCKED = "locked";
    private static final String KEY_USE_KPH = "use_kph";
    private static final String KEY_SIZE_MODE = "size_mode";
    private static final String KEY_FREE_W_FRAC = "free_w_frac";
    private static final String KEY_FREE_H_FRAC = "free_h_frac";

    private static final float RESET_MARGIN_PX = 50f;
    private static final float MAX_OFFSCREEN_FRACTION = 0.9f;
    private static final float SIZE_FRACTION_OF_WIDTH = 0.15f;
    private static final int FALLBACK_SIZE_PX = 180; // only used if parent width isn't known yet

    /** Resize presets. FREE uses freeWFrac/freeHFrac instead of a fixed fraction. */
    private enum SizeMode { FULL, TWO_THIRDS, ONE_FIFTH, FREE }

    private static final float FULL_SIZE_FRAC = 0.96f;
    private static final float FULL_INSET_FRAC = 0.02f; // top-left inset, both axes
    private static final float TWO_THIRDS_FRAC = 0.667f;
    private static final float ONE_FIFTH_FRAC = SIZE_FRACTION_OF_WIDTH; // matches existing v1 default

    /** Background alpha (0-255). Text/border stay fully opaque regardless. */
    private static final int BACKGROUND_ALPHA = 64; // ~75% transparent

    private static final float MIN_FREE_FRAC = 0.08f;
    private static final float HANDLE_RADIUS_PX = 28f;
    private static final float KM_PER_MILE = 1.60934f;

    private final SharedPreferences prefs;

    private boolean darkMode;
    private boolean locked;
    private boolean useKph;
    private SizeMode sizeMode;
    private float freeWFrac;
    private float freeHFrac;

    private float xFrac = -1f; // -1 = not yet positioned
    private float yFrac = -1f;
    private float speedMph = 0f;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gestureDetector;

    private boolean dragging = false;
    private float dragStartRawX, dragStartRawY;
    private float dragStartLeft, dragStartTop;

    /** Armed by the Move menu item: next touch-drag moves the view without needing long-press. */
    private boolean moveArmed = false;

    /** True while the free-resize corner handle is being dragged. */
    private boolean resizingFree = false;
    private float resizeStartRawX, resizeStartRawY;
    private float resizeStartWFrac, resizeStartHFrac;

    private static final float CORNER_RADIUS_PX = 20f;
    private static final float BORDER_WIDTH_PX = 4f;

    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SpeedometerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        darkMode = prefs.getBoolean(KEY_DARK, true);
        locked = prefs.getBoolean(KEY_LOCKED, false);
        useKph = prefs.getBoolean(KEY_USE_KPH, false);
        sizeMode = SizeMode.values()[prefs.getInt(KEY_SIZE_MODE, SizeMode.ONE_FIFTH.ordinal())];
        freeWFrac = prefs.getFloat(KEY_FREE_W_FRAC, SIZE_FRACTION_OF_WIDTH);
        freeHFrac = prefs.getFloat(KEY_FREE_H_FRAC, SIZE_FRACTION_OF_WIDTH);
        xFrac = prefs.getFloat(KEY_X_FRAC, -1f);
        yFrac = prefs.getFloat(KEY_Y_FRAC, -1f);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                darkMode = !darkMode;
                prefs.edit().putBoolean(KEY_DARK, darkMode).apply();
                applyTheme();
                invalidate();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                showMainMenu();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (locked) return;
                dragging = true;
                dragStartRawX = e.getRawX();
                dragStartRawY = e.getRawY();
                dragStartLeft = getX();
                dragStartTop = getY();
            }
        });

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(BORDER_WIDTH_PX);
        handlePaint.setStyle(Paint.Style.FILL);
        applyTheme();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);

        if (resizingFree) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    resizeStartRawX = event.getRawX();
                    resizeStartRawY = event.getRawY();
                    resizeStartWFrac = freeWFrac;
                    resizeStartHFrac = freeHFrac;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    updateFreeResize(event.getRawX(), event.getRawY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    resizingFree = false;
                    saveFreeSize();
                    return true;
            }
            return true;
        }

        if (moveArmed) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragStartRawX = event.getRawX();
                    dragStartRawY = event.getRawY();
                    dragStartLeft = getX();
                    dragStartTop = getY();
                    dragging = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) {
                        float dx = event.getRawX() - dragStartRawX;
                        float dy = event.getRawY() - dragStartRawY;
                        moveTo(dragStartLeft + dx, dragStartTop + dy);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    moveArmed = false;
                    savePosition();
                    return true;
            }
        }

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

    // ============
    // Menu
    // ============

    private void showMainMenu() {
        new AlertDialog.Builder(getContext())
                .setTitle("Speedometer")
                .setItems(new CharSequence[]{"Move", "Resize", "Settings"}, (dialog, which) -> {
                    switch (which) {
                        case 0: startMove(); break;
                        case 1: showResizeMenu(); break;
                        case 2: showSettingsMenu(); break;
                    }
                })
                .show();
    }

    private void startMove() {
        if (locked) {
            Toast.makeText(getContext(), "Position is locked - unlock in Settings first", Toast.LENGTH_SHORT).show();
            return;
        }
        moveArmed = true;
        Toast.makeText(getContext(), "Drag the speedometer to move it", Toast.LENGTH_SHORT).show();
    }

    private void showResizeMenu() {
        new AlertDialog.Builder(getContext())
                .setTitle("Resize")
                .setItems(new CharSequence[]{"Full", "2/3", "1/5 (default)", "Free"}, (dialog, which) -> {
                    switch (which) {
                        case 0: applySizeMode(SizeMode.FULL); break;
                        case 1: applySizeMode(SizeMode.TWO_THIRDS); break;
                        case 2: applySizeMode(SizeMode.ONE_FIFTH); break;
                        case 3: startFreeResize(); break;
                    }
                })
                .show();
    }

    private void showSettingsMenu() {
        String themeLabel = darkMode ? "Theme: white-on-black (tap to switch)" : "Theme: black-on-white (tap to switch)";
        String unitLabel = useKph ? "Units: KPH (tap to switch)" : "Units: MPH (tap to switch)";
        String lockLabel = locked ? "Unlock position" : "Lock position";

        new AlertDialog.Builder(getContext())
                .setTitle("Speedometer settings")
                .setItems(new CharSequence[]{themeLabel, unitLabel, lockLabel}, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            darkMode = !darkMode;
                            prefs.edit().putBoolean(KEY_DARK, darkMode).apply();
                            applyTheme();
                            invalidate();
                            break;
                        case 1:
                            useKph = !useKph;
                            prefs.edit().putBoolean(KEY_USE_KPH, useKph).apply();
                            invalidate();
                            break;
                        case 2:
                            locked = !locked;
                            prefs.edit().putBoolean(KEY_LOCKED, locked).apply();
                            Toast.makeText(getContext(), locked ? "Position locked" : "Position unlocked", Toast.LENGTH_SHORT).show();
                            break;
                    }
                })
                .show();
    }

    // ============
    // Resize
    // ============

    private void applySizeMode(SizeMode mode) {
        sizeMode = mode;
        prefs.edit().putInt(KEY_SIZE_MODE, sizeMode.ordinal()).apply();
        requestLayout();
        invalidate();
    }

    private void startFreeResize() {
        sizeMode = SizeMode.FREE;
        prefs.edit().putInt(KEY_SIZE_MODE, sizeMode.ordinal()).apply();
        resizingFree = true;
        Toast.makeText(getContext(), "Drag the corner handle to resize, release when done", Toast.LENGTH_LONG).show();
        requestLayout();
        invalidate();
    }

    private void updateFreeResize(float rawX, float rawY) {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent == null || parent.getWidth() == 0 || parent.getHeight() == 0) return;

        float dx = rawX - resizeStartRawX;
        float dy = rawY - resizeStartRawY;

        float newWFrac = resizeStartWFrac + dx / parent.getWidth();
        float newHFrac = resizeStartHFrac + dy / parent.getHeight();

        freeWFrac = Math.max(MIN_FREE_FRAC, Math.min(1f, newWFrac));
        freeHFrac = Math.max(MIN_FREE_FRAC, Math.min(1f, newHFrac));

        requestLayout();
        invalidate();
    }

    private void saveFreeSize() {
        prefs.edit()
                .putFloat(KEY_FREE_W_FRAC, freeWFrac)
                .putFloat(KEY_FREE_H_FRAC, freeHFrac)
                .apply();
    }

    // ============
    // Position
    // ============

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(this::applySavedOrDefaultPosition);
    }

    private void applySavedOrDefaultPosition() {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent == null || parent.getWidth() == 0 || parent.getHeight() == 0) return;

        if (sizeMode == SizeMode.FULL) {
            // Computed fresh every time, not persisted - selecting Full
            // always snaps back here regardless of any prior drag.
            moveTo(parent.getWidth() * FULL_INSET_FRAC, parent.getHeight() * FULL_INSET_FRAC);
            return;
        }
        if (sizeMode == SizeMode.TWO_THIRDS) {
            float left = (parent.getWidth() - getWidth()) / 2f;
            float top = (parent.getHeight() - getHeight()) / 2f;
            moveTo(left, top);
            return;
        }

        // ONE_FIFTH and FREE: normal persisted/draggable position.
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

        if (sizeMode == SizeMode.ONE_FIFTH || sizeMode == SizeMode.FREE) {
            xFrac = left / pw;
            yFrac = top / ph;
        }
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
            handlePaint.setColor(Color.WHITE);
        } else {
            bgPaint.setColor(Color.WHITE);
            textPaint.setColor(Color.BLACK);
            borderPaint.setColor(Color.BLACK);
            handlePaint.setColor(Color.BLACK);
        }
        // setColor() above resets alpha to opaque - apply transparency after.
        bgPaint.setAlpha(BACKGROUND_ALPHA);
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

        float displaySpeed = useKph ? speedMph * KM_PER_MILE : speedMph;
        String unitLabel = useKph ? "KPH" : "MPH";

        textPaint.setTextSize(h * 0.4f);
        canvas.drawText(String.valueOf(Math.round(displaySpeed)), w / 2f, h * 0.55f, textPaint);
        textPaint.setTextSize(h * 0.14f);
        canvas.drawText(unitLabel, w / 2f, h * 0.78f, textPaint);

        if (resizingFree) {
            canvas.drawCircle(w, h, HANDLE_RADIUS_PX, handlePaint);
        }
    }

    /** handle screen rotation and resize-mode changes elegantly */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int parentWidth = MeasureSpec.getSize(widthMeasureSpec);
        int parentHeight = MeasureSpec.getSize(heightMeasureSpec);

        int w, h;
        if (parentWidth <= 0 || parentHeight <= 0) {
            w = h = FALLBACK_SIZE_PX;
        } else {
            switch (sizeMode) {
                case FULL:
                    w = Math.round(parentWidth * FULL_SIZE_FRAC);
                    h = Math.round(parentHeight * FULL_SIZE_FRAC);
                    break;
                case TWO_THIRDS:
                    w = Math.round(parentWidth * TWO_THIRDS_FRAC);
                    h = Math.round(parentHeight * TWO_THIRDS_FRAC);
                    break;
                case FREE:
                    w = Math.round(parentWidth * freeWFrac);
                    h = Math.round(parentHeight * freeHFrac);
                    break;
                case ONE_FIFTH:
                default:
                    w = Math.round(parentWidth * ONE_FIFTH_FRAC);
                    h = Math.round(parentHeight * ONE_FIFTH_FRAC);
                    break;
            }
        }
        setMeasuredDimension(w, h);
    }

}
