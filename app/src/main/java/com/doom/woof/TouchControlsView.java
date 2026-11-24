package com.doom.woof;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import org.libsdl.app.SDLActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Minimal touch controls (Woof's default key bindings). Layout, size, opacity and look
 * sensitivity come from {@link ControlsConfig} and can be edited in {@link ControlsEditorActivity}.
 *
 * In game:
 *   left side   - floating joystick (outline only, visible while touched):
 *                 up/down = forward/back, left/right = strafe;
 *                 partial deflection = walk, full deflection = run;
 *   right side  - drag to turn;
 *   FIRE, USE, "<" ">" (weapon), ESC, MAP buttons.
 *
 * In menus, title screens, intermissions and demos the buttons are hidden and plain touch mode applies:
 * a touch is a mouse click at that point, a second finger is a right click (back).
 * Android Back = Escape.
 */
public class TouchControlsView extends View {

    private static final float LOOK_SENSITIVITY = 1.4f;
    private static final int ROLE_BUTTON = 1, ROLE_STICK = 2, ROLE_LOOK = 3;
    private static final int POLL_MS = 100;
    // Woof polls input once per tic (~28 ms). If a key goes down and up within one tic
    // the game never sees it, so keys are held at least this long.
    private static final int MIN_KEY_HOLD_MS = 90;

    private static final int BUTTON_PRIMARY = 1;     // MotionEvent.BUTTON_PRIMARY
    private static final int BUTTON_SECONDARY = 2;   // MotionEvent.BUTTON_SECONDARY

    private static final int ACTION_WEAPON_PREV = -1, ACTION_WEAPON_NEXT = -2;

    private static final class Btn {
        final String id;
        final String label;
        final int keyCode;      // >0 key code; negative values are special actions
        final boolean round;
        final RectF rect = new RectF();
        boolean down;
        long downAt;
        Runnable pendingUp;     // delayed release of a too-short tap
        Btn(String label, int keyCode, boolean round) {
            this.id = label;
            this.label = label;
            this.keyCode = keyCode;
            this.round = round;
        }
    }

    private static final class Pointer {
        int role;
        Btn btn;
        float lastX, lastY;
    }

    /** Reports the selected button while the layout is being edited. */
    public interface EditListener {
        void onSelectionChanged(String label, boolean visible);
    }

    private final float dp;
    private final ControlsConfig cfg;
    private boolean editMode = false;
    private EditListener editListener;
    private Btn selected;
    private float grabDx, grabDy;
    private final android.graphics.DashPathEffect dash;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Btn> buttons = new ArrayList<>();
    private final Btn fire, use, esc, map, prev, next;

    private final SparseArray<Pointer> pointers = new SparseArray<>();
    private final Set<Integer> heldKeys = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Joystick
    private int stickId = -1;
    private float stickCx, stickCy, stickX, stickY;

    // Game state from native code
    private boolean touchMode = false;
    private boolean autorun = true;
    private boolean nativeOk = true;
    private boolean awaitingAnswer = false;   // on-screen Y/N question
    private final Btn yes = new Btn("Y", KeyEvent.KEYCODE_Y, false);
    private final Btn no = new Btn("N", KeyEvent.KEYCODE_N, false);

    // Touch mode (mouse)
    private int mouseId = -1;
    private int secondaryId = -1;
    private boolean mousePressed = false;   // left button already sent to the game
    private boolean pressScheduled = false;
    private float mouseX, mouseY;
    private static final int CLICK_DELAY_MS = 70;   // the cursor must reach the item before the click
    private static final int CLICK_HOLD_MS = 40;

    private final Runnable pressRunnable = new Runnable() {
        @Override
        public void run() {
            pressScheduled = false;
            if (!mousePressed) {
                mousePressed = true;
                SDLActivity.onNativeMouse(BUTTON_PRIMARY, MotionEvent.ACTION_DOWN, mouseX, mouseY, false);
            }
        }
    };

    private final Runnable releaseRunnable = new Runnable() {
        @Override
        public void run() {
            if (mousePressed) {
                mousePressed = false;
                SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, mouseX, mouseY, false);
            }
        }
    };

    private static native int nativeUiState();

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            updateUiState();
            handler.postDelayed(this, POLL_MS);
        }
    };

    public TouchControlsView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;
        cfg = new ControlsConfig(context);
        dash = new android.graphics.DashPathEffect(new float[] { 6 * dp, 4 * dp }, 0);
        setFocusable(false);
        setFocusableInTouchMode(false);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * dp);
        fill.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xAAFFFFFF);
        text.setTextSize(10 * dp);

        fire = add(new Btn("FIRE", KeyEvent.KEYCODE_CTRL_RIGHT, true));
        use = add(new Btn("USE", KeyEvent.KEYCODE_SPACE, true));
        esc = add(new Btn("ESC", KeyEvent.KEYCODE_ESCAPE, false));
        map = add(new Btn("MAP", KeyEvent.KEYCODE_TAB, false));
        prev = add(new Btn("<", ACTION_WEAPON_PREV, false));
        next = add(new Btn(">", ACTION_WEAPON_NEXT, false));
    }

    private Btn add(Btn b) {
        buttons.add(b);
        return b;
    }

    // ------------------------------------------------------------------ state

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!editMode) {
            handler.post(poll);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(poll);
        releaseAll();
        super.onDetachedFromWindow();
    }

    private void updateUiState() {
        if (!nativeOk) {
            return;
        }
        int s;
        try {
            s = nativeUiState();
        } catch (UnsatisfiedLinkError e) {
            nativeOk = false;
            return;
        }
        boolean newTouch = (s & 1) != 0;
        autorun = (s & 2) != 0;
        boolean newAsk = (s & 4) != 0;
        if (newAsk != awaitingAnswer) {
            awaitingAnswer = newAsk;
            invalidate();
        }
        if (newTouch != touchMode) {
            touchMode = newTouch;
            releaseAll();
            invalidate();
        }
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        layoutButtons();
    }

    /** Places every button from the saved config, or from the default layout. */
    private void layoutButtons() {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float k = cfg.scale();
        float m = 10 * dp;
        float bw = 34 * dp;
        float bh = 24 * dp;
        float gap = 5 * dp;
        float fr = 34 * dp;
        float ur = 22 * dp;

        for (Btn b : buttons) {
            float cx, cy;
            if (cfg.hasPosition(b.id)) {
                cx = cfg.x(b.id) * w;
                cy = cfg.y(b.id) * h;
            } else if (b == esc) {
                cx = m + bw / 2;
                cy = m + bh / 2;
            } else if (b == fire) {
                cx = w - m - fr;
                cy = h - m - fr;
            } else if (b == use) {
                cx = w - m - 2 * fr - gap - ur;
                cy = h - m - ur;
            } else {
                // top-right row: MAP, then ">" and "<" to its left
                int i = b == map ? 0 : b == next ? 1 : 2;
                cx = w - m - bw / 2 - i * (bw + gap);
                cy = m + bh / 2;
            }
            float hw = (b.round ? (b == fire ? fr : ur) : bw / 2) * k;
            float hh = (b.round ? (b == fire ? fr : ur) : bh / 2) * k;
            b.rect.set(cx - hw, cy - hh, cx + hw, cy + hh);
        }

        float yn = 44 * dp;
        float cx = w / 2f;
        float by = h - m - yn;
        no.rect.set(cx + gap * 2, by, cx + gap * 2 + yn, by + yn);
        yes.rect.set(cx - gap * 2 - yn, by, cx - gap * 2, by + yn);
    }

    private float stickRadius() {
        return 44 * dp * cfg.scale();
    }

    private boolean shown(Btn b) {
        return editMode || cfg.visible(b.id);
    }

    /** Applies the user's opacity setting to an ARGB colour. */
    private int ac(int argb) {
        int a = Math.round(((argb >>> 24) & 0xFF) * cfg.alpha());
        return (a << 24) | (argb & 0xFFFFFF);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        if (editMode) {
            drawEditor(c);
            return;
        }
        if (touchMode) {
            if (awaitingAnswer) {
                drawKey(c, yes);
                drawKey(c, no);
            }
            return;
        }
        for (Btn b : buttons) {
            if (!shown(b)) {
                continue;
            }
            stroke.setColor(ac(b.down ? 0xEEFFFFFF : 0x66FFFFFF));
            fill.setColor(ac(b.down ? 0x77FFFFFF : 0x00000000));
            drawShape(c, b);
            text.setColor(ac(0xAAFFFFFF));
            c.drawText(b.label, b.rect.centerX(), b.rect.centerY() + 3.5f * dp, text);
        }
        if (stickId != -1) {
            // Outlines only, no fill.
            stroke.setColor(ac(0x55FFFFFF));
            c.drawCircle(stickCx, stickCy, stickRadius(), stroke);
            c.drawCircle(stickCx + stickX, stickCy + stickY, 15 * dp * cfg.scale(), stroke);
        }
    }

    private void drawShape(Canvas c, Btn b) {
        if (b.round) {
            float r = b.rect.width() / 2f;
            c.drawCircle(b.rect.centerX(), b.rect.centerY(), r, fill);
            c.drawCircle(b.rect.centerX(), b.rect.centerY(), r, stroke);
        } else {
            float r = 6 * dp;
            c.drawRoundRect(b.rect, r, r, fill);
            c.drawRoundRect(b.rect, r, r, stroke);
        }
    }

    private void drawEditor(Canvas c) {
        // Joystick hint on the left half.
        stroke.setColor(ac(0x33FFFFFF));
        stroke.setPathEffect(dash);
        float r = stickRadius();
        c.drawCircle(getWidth() * 0.2f, getHeight() * 0.62f, r, stroke);
        c.drawCircle(getWidth() * 0.2f, getHeight() * 0.62f, 15 * dp * cfg.scale(), stroke);
        stroke.setPathEffect(null);

        for (Btn b : buttons) {
            boolean vis = cfg.visible(b.id);
            boolean sel = b == selected;
            stroke.setColor(sel ? 0xFFE58744 : (vis ? 0xCCFFFFFF : 0x55FFFFFF));
            stroke.setStrokeWidth((sel ? 2.5f : 1.5f) * dp);
            if (!vis) {
                stroke.setPathEffect(dash);
            }
            fill.setColor(sel ? 0x33E58744 : 0x00000000);
            drawShape(c, b);
            stroke.setPathEffect(null);
            text.setColor(vis ? 0xCCFFFFFF : 0x66FFFFFF);
            c.drawText(b.label, b.rect.centerX(), b.rect.centerY() + 3.5f * dp, text);
        }
        stroke.setStrokeWidth(1.5f * dp);
    }

    // ------------------------------------------------------------------ editor API

    /** Turns the view into a layout editor: nothing is sent to the game. */
    public void setEditMode(boolean on, EditListener listener) {
        editMode = on;
        editListener = listener;
        selected = null;
        notifySelection();
        handler.removeCallbacks(poll);
        layoutButtons();
        invalidate();
    }

    /** Re-reads size/opacity/position settings after they changed. */
    public void refreshConfig() {
        layoutButtons();
        invalidate();
    }

    public void toggleSelectedVisibility() {
        if (selected != null) {
            cfg.setVisible(selected.id, !cfg.visible(selected.id));
            notifySelection();
            invalidate();
        }
    }

    private void notifySelection() {
        if (editListener != null) {
            editListener.onSelectionChanged(selected != null ? selected.label : null,
                    selected != null && cfg.visible(selected.id));
        }
    }

    private boolean onEditTouch(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                selected = null;
                float best = Float.MAX_VALUE;
                for (Btn b : buttons) {
                    float d = (float) Math.hypot(x - b.rect.centerX(), y - b.rect.centerY());
                    float reach = Math.max(b.rect.width(), b.rect.height()) / 2f + 12 * dp;
                    if (d <= reach && d < best) {
                        best = d;
                        selected = b;
                    }
                }
                if (selected != null) {
                    grabDx = selected.rect.centerX() - x;
                    grabDy = selected.rect.centerY() - y;
                }
                notifySelection();
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (selected != null) {
                    float hw = selected.rect.width() / 2f;
                    float hh = selected.rect.height() / 2f;
                    float cx = Math.max(hw, Math.min(getWidth() - hw, x + grabDx));
                    float cy = Math.max(hh, Math.min(getHeight() - hh, y + grabDy));
                    selected.rect.set(cx - hw, cy - hh, cx + hw, cy + hh);
                }
                break;
            case MotionEvent.ACTION_UP:
                if (selected != null) {
                    cfg.setPosition(selected.id, selected.rect.centerX() / getWidth(),
                            selected.rect.centerY() / getHeight());
                }
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    private void drawKey(Canvas c, Btn b) {
        float r = 6 * dp;
        stroke.setColor(b.down ? 0xEEFFFFFF : 0x99FFFFFF);
        fill.setColor(b.down ? 0x77FFFFFF : 0x44000000);
        text.setColor(0xCCFFFFFF);
        c.drawRoundRect(b.rect, r, r, fill);
        c.drawRoundRect(b.rect, r, r, stroke);
        text.setTextSize(15 * dp);
        c.drawText(b.label, b.rect.centerX(), b.rect.centerY() + 5.5f * dp, text);
        text.setTextSize(10 * dp);
    }

    /** While a Y/N question is up, only the two buttons react; taps elsewhere are ignored. */
    private boolean onAnswerTouch(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = e.getX(idx);
            float y = e.getY(idx);
            float slop = 8 * dp;
            for (final Btn b : new Btn[] { yes, no }) {
                if (x >= b.rect.left - slop && x <= b.rect.right + slop
                        && y >= b.rect.top - slop && y <= b.rect.bottom + slop) {
                    b.down = true;
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    keyDown(b.keyCode);
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            b.down = false;
                            keyUp(b.keyCode);
                            invalidate();
                        }
                    }, MIN_KEY_HOLD_MS);
                    invalidate();
                    break;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ touches

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (editMode) {
            return onEditTouch(e);
        }
        updateUiState();
        if (touchMode && awaitingAnswer) {
            return onAnswerTouch(e);
        }
        return touchMode ? onMouseTouch(e) : onGameTouch(e);
    }

    // ---- touch mode: a touch is the mouse

    private boolean onMouseTouch(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        int id = e.getPointerId(idx);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mouseId = id;
                mouseX = e.getX(idx);
                mouseY = e.getY(idx);
                // Move the cursor first: the menu selects an item on mouse motion, so a click
                // in the same frame would hit the previously selected item (two taps needed).
                SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, mouseX, mouseY, false);
                pressScheduled = true;
                handler.removeCallbacks(releaseRunnable);
                handler.postDelayed(pressRunnable, CLICK_DELAY_MS);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (mouseId != -1 && secondaryId == -1) {
                    // Second finger: right click (back).
                    secondaryId = id;
                    // Cancel the left click, only the right one remains.
                    handler.removeCallbacks(pressRunnable);
                    pressScheduled = false;
                    SDLActivity.onNativeMouse(BUTTON_SECONDARY,
                            MotionEvent.ACTION_DOWN, e.getX(idx), e.getY(idx), false);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    if (e.getPointerId(i) == mouseId) {
                        mouseX = e.getX(i);
                        mouseY = e.getY(i);
                        SDLActivity.onNativeMouse(mousePressed ? BUTTON_PRIMARY : 0,
                                MotionEvent.ACTION_MOVE, mouseX, mouseY, false);
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (id == secondaryId) {
                    secondaryId = -1;
                    SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP,
                            e.getX(idx), e.getY(idx), false);
                } else if (id == mouseId) {
                    mouseId = -1;
                    scheduleRelease();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (secondaryId != -1) {
                    SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP,
                            e.getX(idx), e.getY(idx), false);
                }
                if (mouseId != -1) {
                    scheduleRelease();
                }
                mouseId = -1;
                secondaryId = -1;
                break;
            default:
                break;
        }
        return true;
    }

    // ---- game mode: buttons, joystick, turning

    private boolean onGameTouch(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                pointerDown(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    pointerMove(e.getPointerId(i), e.getX(i), e.getY(i));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                pointerUp(e.getPointerId(idx));
                break;
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    /**
     * Which button is under the finger. Round buttons (FIRE, USE) have a hit area 1.6x
     * larger than drawn so a thumb lands on the first try; where areas overlap,
     * the button whose centre is closer wins.
     */
    private Btn hit(float x, float y) {
        Btn best = null;
        float bestScore = Float.MAX_VALUE;
        for (Btn b : buttons) {
            if (!shown(b)) {
                continue;
            }
            float score;
            if (b.round) {
                float r = b.rect.width() / 2f;
                float d = (float) Math.hypot(x - b.rect.centerX(), y - b.rect.centerY());
                if (d > r * 1.6f) {
                    continue;
                }
                score = d / r;
            } else {
                float slop = 8 * dp;
                if (x < b.rect.left - slop || x > b.rect.right + slop
                        || y < b.rect.top - slop || y > b.rect.bottom + slop) {
                    continue;
                }
                score = 0.5f;
            }
            if (score < bestScore) {
                bestScore = score;
                best = b;
            }
        }
        return best;
    }

    private void pointerDown(int id, float x, float y) {
        Pointer p = new Pointer();
        p.lastX = x;
        p.lastY = y;
        pointers.put(id, p);

        Btn b = hit(x, y);
        if (b != null) {
            p.role = ROLE_BUTTON;
            p.btn = b;
            b.down = true;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (b.keyCode == ACTION_WEAPON_NEXT) {
                weaponWheel(1f);
            } else if (b.keyCode == ACTION_WEAPON_PREV) {
                weaponWheel(-1f);
            } else {
                b.downAt = SystemClock.uptimeMillis();
                if (b.pendingUp != null) {
                    // Key is still down from the previous short tap - just keep holding it.
                    handler.removeCallbacks(b.pendingUp);
                    b.pendingUp = null;
                } else {
                    keyDown(b.keyCode);
                }
            }
            return;
        }
        if (x < getWidth() * 0.45f && stickId == -1) {
            p.role = ROLE_STICK;
            stickId = id;
            stickCx = x;
            stickCy = y;
            stickX = stickY = 0;
        } else {
            p.role = ROLE_LOOK;
        }
    }

    private void pointerMove(int id, float x, float y) {
        Pointer p = pointers.get(id);
        if (p == null) {
            return;
        }
        if (p.role == ROLE_STICK) {
            updateStick(x, y);
        } else if (p.role == ROLE_LOOK || (p.role == ROLE_BUTTON && p.btn == fire)) {
            // The finger holding FIRE can turn too: aim and shoot with one finger.
            float dx = (x - p.lastX) * LOOK_SENSITIVITY * cfg.look();
            if (dx != 0f) {
                // Relative mouse motion (horizontal only).
                SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, dx, 0f, true);
            }
        }
        p.lastX = x;
        p.lastY = y;
    }

    private void pointerUp(int id) {
        Pointer p = pointers.get(id);
        if (p == null) {
            return;
        }
        if (p.role == ROLE_BUTTON && p.btn != null) {
            p.btn.down = false;
            if (p.btn.keyCode > 0) {
                releaseButtonKey(p.btn);
            }
        } else if (p.role == ROLE_STICK) {
            releaseStick();
        }
        pointers.remove(id);
    }

    private void releaseAll() {
        for (int i = 0; i < pointers.size(); i++) {
            Pointer p = pointers.valueAt(i);
            if (p.role == ROLE_BUTTON && p.btn != null) {
                p.btn.down = false;
            }
        }
        pointers.clear();
        for (Btn b : buttons) {
            if (b.pendingUp != null) {
                handler.removeCallbacks(b.pendingUp);
                b.pendingUp = null;
            }
        }
        releaseStick();
        for (Integer k : new ArrayList<>(heldKeys)) {
            keyUp(k);
        }
        if (secondaryId != -1) {
            SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, 0f, 0f, false);
        }
        handler.removeCallbacks(pressRunnable);
        handler.removeCallbacks(releaseRunnable);
        pressScheduled = false;
        if (mousePressed) {
            mousePressed = false;
            SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, mouseX, mouseY, false);
        }
        mouseId = -1;
        secondaryId = -1;
    }

    /** Releases the left button; if the press has not been sent yet, let it go out first. */
    private void scheduleRelease() {
        handler.removeCallbacks(releaseRunnable);
        handler.postDelayed(releaseRunnable,
                pressScheduled ? CLICK_DELAY_MS + CLICK_HOLD_MS : 0);
    }

    private void releaseButtonKey(final Btn b) {
        long held = SystemClock.uptimeMillis() - b.downAt;
        if (held >= MIN_KEY_HOLD_MS) {
            keyUp(b.keyCode);
            return;
        }
        b.pendingUp = new Runnable() {
            @Override
            public void run() {
                b.pendingUp = null;
                keyUp(b.keyCode);
            }
        };
        handler.postDelayed(b.pendingUp, MIN_KEY_HOLD_MS - held);
    }

    private void weaponWheel(float dir) {
        // Mouse wheel: up = next weapon, down = previous (Woof's default bindings).
        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_SCROLL, 0f, dir, false);
    }

    // ------------------------------------------------------------------ joystick

    private void updateStick(float x, float y) {
        float r = stickRadius();
        float dx = x - stickCx;
        float dy = y - stickCy;
        float len = (float) Math.hypot(dx, dy);
        if (len > r) {
            dx = dx / len * r;
            dy = dy / len * r;
            len = r;
        }
        stickX = dx;
        stickY = dy;

        float dead = 0.25f * r;
        boolean up = dy < -dead;
        boolean down = dy > dead;
        boolean left = dx < -dead;
        boolean right = dx > dead;
        setKey(KeyEvent.KEYCODE_DPAD_UP, up);
        setKey(KeyEvent.KEYCODE_DPAD_DOWN, down);
        setKey(KeyEvent.KEYCODE_A, left);
        setKey(KeyEvent.KEYCODE_D, right);

        // Partial deflection walks, full deflection runs.
        // In Woof speed = autorun XOR Shift, so Shift depends on autorun.
        boolean moving = up || down || left || right;
        boolean wantRun = len > 0.85f * r;
        setKey(KeyEvent.KEYCODE_SHIFT_RIGHT, moving && (wantRun != autorun));
    }

    private void releaseStick() {
        stickId = -1;
        stickX = stickY = 0;
        setKey(KeyEvent.KEYCODE_DPAD_UP, false);
        setKey(KeyEvent.KEYCODE_DPAD_DOWN, false);
        setKey(KeyEvent.KEYCODE_A, false);
        setKey(KeyEvent.KEYCODE_D, false);
        setKey(KeyEvent.KEYCODE_SHIFT_RIGHT, false);
    }

    // ------------------------------------------------------------------ keys

    private void setKey(int keyCode, boolean pressed) {
        boolean now = heldKeys.contains(keyCode);
        if (pressed && !now) {
            keyDown(keyCode);
        } else if (!pressed && now) {
            keyUp(keyCode);
        }
    }

    private void keyDown(int keyCode) {
        heldKeys.add(keyCode);
        SDLActivity.onNativeKeyDown(keyCode);
    }

    private void keyUp(int keyCode) {
        heldKeys.remove(keyCode);
        SDLActivity.onNativeKeyUp(keyCode);
    }
}
