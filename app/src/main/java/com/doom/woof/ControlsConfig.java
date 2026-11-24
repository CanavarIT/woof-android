package com.doom.woof;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User-adjustable touch control settings, stored in SharedPreferences.
 * Written by {@link ControlsEditorActivity}, read by {@link TouchControlsView}.
 */
final class ControlsConfig {
    private static final String PREFS = "woof_controls";

    static final float SCALE_MIN = 0.6f, SCALE_MAX = 1.6f;
    static final float ALPHA_MIN = 0.2f, ALPHA_MAX = 1.0f;
    static final float LOOK_MIN = 0.4f, LOOK_MAX = 3.0f;

    private final SharedPreferences prefs;

    ControlsConfig(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Button size multiplier. */
    float scale() {
        return prefs.getFloat("scale", 1f);
    }

    void setScale(float v) {
        prefs.edit().putFloat("scale", v).apply();
    }

    /** Overall button opacity, 1 = default look. */
    float alpha() {
        return prefs.getFloat("alpha", 1f);
    }

    void setAlpha(float v) {
        prefs.edit().putFloat("alpha", v).apply();
    }

    /** Look (turn) sensitivity multiplier. */
    float look() {
        return prefs.getFloat("look", 1f);
    }

    void setLook(float v) {
        prefs.edit().putFloat("look", v).apply();
    }

    boolean visible(String id) {
        return prefs.getBoolean(id + ".visible", true);
    }

    void setVisible(String id, boolean visible) {
        prefs.edit().putBoolean(id + ".visible", visible).apply();
    }

    /** True if the user moved this button; position is stored as a fraction of the screen. */
    boolean hasPosition(String id) {
        return prefs.contains(id + ".x") && prefs.contains(id + ".y");
    }

    float x(String id) {
        return prefs.getFloat(id + ".x", 0f);
    }

    float y(String id) {
        return prefs.getFloat(id + ".y", 0f);
    }

    void setPosition(String id, float fx, float fy) {
        prefs.edit().putFloat(id + ".x", fx).putFloat(id + ".y", fy).apply();
    }

    void resetAll() {
        prefs.edit().clear().apply();
    }
}
