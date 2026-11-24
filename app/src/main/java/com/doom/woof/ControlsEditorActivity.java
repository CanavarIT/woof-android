package com.doom.woof;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Touch controls editor: drag buttons to move them, tap one to select it and hide or show it,
 * and adjust size, opacity and look sensitivity. Settings are stored in {@link ControlsConfig}.
 */
public class ControlsEditorActivity extends AppCompatActivity {

    private ControlsConfig cfg;
    private TouchControlsView view;
    private TextView selectionText;
    private boolean hasSelection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = new ControlsConfig(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF1B1B1B);

        view = new TouchControlsView(this);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(action(R.string.editor_options, v -> showOptions()));
        actions.addView(action(R.string.editor_reset, v -> {
            cfg.resetAll();
            view.setEditMode(true, this::onSelection);
        }));
        actions.addView(action(R.string.editor_done, v -> finish()));
        bar.addView(actions);

        selectionText = new TextView(this);
        selectionText.setTextColor(0xFFFFFFFF);
        selectionText.setTextSize(13);
        selectionText.setAlpha(0.7f);
        selectionText.setGravity(Gravity.CENTER);
        selectionText.setText(R.string.editor_hint);
        int pad = dp(10);
        selectionText.setPadding(pad, dp(8), pad, dp(8));
        selectionText.setOnClickListener(v -> {
            if (hasSelection) {
                view.toggleSelectedVisibility();
            }
        });
        bar.addView(selectionText);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(6);
        root.addView(bar, lp);

        setContentView(root);
        view.setEditMode(true, this::onSelection);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void onSelection(String label, boolean visible) {
        hasSelection = label != null;
        if (label == null) {
            selectionText.setText(R.string.editor_hint);
            selectionText.setAlpha(0.7f);
        } else {
            selectionText.setText(getString(
                    visible ? R.string.editor_hide : R.string.editor_show, label));
            selectionText.setAlpha(1f);
        }
    }

    private TextView action(int textRes, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(textRes);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setStroke(dp(1), 0x66FFFFFF);
        bg.setCornerRadius(dp(6));
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), 0, dp(3), 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        return t;
    }

    // ------------------------------------------------------------------ options dialog

    private void showOptions() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(12), dp(24), dp(4));

        addSlider(box, R.string.opt_size, ControlsConfig.SCALE_MIN, ControlsConfig.SCALE_MAX,
                cfg.scale(), v -> {
                    cfg.setScale(v);
                    view.refreshConfig();
                });
        addSlider(box, R.string.opt_opacity, ControlsConfig.ALPHA_MIN, ControlsConfig.ALPHA_MAX,
                cfg.alpha(), v -> {
                    cfg.setAlpha(v);
                    view.refreshConfig();
                });
        addSlider(box, R.string.opt_look, ControlsConfig.LOOK_MIN, ControlsConfig.LOOK_MAX,
                cfg.look(), cfg::setLook);

        new AlertDialog.Builder(this)
                .setTitle(R.string.editor_options)
                .setView(box)
                .setPositiveButton(R.string.editor_done, null)
                .show();
    }

    private interface FloatConsumer {
        void accept(float v);
    }

    private void addSlider(LinearLayout parent, int labelRes, final float min, final float max,
                           float value, final FloatConsumer onChange) {
        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextSize(13);
        label.setPadding(0, dp(12), 0, 0);
        parent.addView(label);

        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(Math.round((value - min) / (max - min) * 100f));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) {
                    onChange.accept(min + (max - min) * progress / 100f);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        parent.addView(bar);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
