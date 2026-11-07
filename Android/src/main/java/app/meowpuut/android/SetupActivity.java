package app.meowpuut.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class SetupActivity extends Activity {
    private SharedPreferences preferences;
    private boolean dark;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("keyboard", MODE_PRIVATE);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        LinearLayout screen = column();
        screen.setBackgroundColor(dark ? 0xff181818 : 0xfffaf2e6);
        screen.setFocusableInTouchMode(true);
        screen.setPadding(dp(20), dp(12), dp(20), dp(12));
        FrameLayout bar = new FrameLayout(this);
        TextView name = text("Meowpuut", 17, true);
        name.setGravity(Gravity.CENTER);
        bar.addView(name, new FrameLayout.LayoutParams(-1, dp(44)));
        Button settings = new Button(this);
        settings.setText("鍵盤設定");
        settings.setAllCaps(false);
        settings.setBackgroundColor(Color.TRANSPARENT);
        settings.setTextColor(dark ? 0xffff917a : 0xffb82e1f);
        settings.setOnClickListener(view -> showSettings());
        bar.addView(settings, new FrameLayout.LayoutParams(-2, dp(44), Gravity.END));
        screen.addView(bar);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();
        scroll.addView(content);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout masthead = column();
        masthead.setGravity(Gravity.CENTER_HORIZONTAL);
        masthead.setPadding(0, dp(24), 0, dp(24));
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.meowpuut_mark);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        masthead.addView(mark, new LinearLayout.LayoutParams(dp(134), dp(134)));
        TextView wordmark = text("Meowpuut", 34, true);
        wordmark.setGravity(Gravity.CENTER);
        masthead.addView(wordmark);
        TextView phonetics = text("ㄅ ㄆ ㄇ ㄈ", 14, false);
        phonetics.setGravity(Gravity.CENTER);
        phonetics.setTextColor(dark ? 0xffff917a : 0xffb82e1f);
        masthead.addView(phonetics);
        View rule = new View(this);
        rule.setBackgroundColor(dark ? 0x88ff917a : 0x88b82e1f);
        LinearLayout.LayoutParams ruleSize = new LinearLayout.LayoutParams(dp(36), dp(2));
        ruleSize.topMargin = dp(12);
        ruleSize.gravity = Gravity.CENTER_HORIZONTAL;
        masthead.addView(rule, ruleSize);
        content.addView(masthead);

        content.addView(text("試打", 16, true));
        EditText sample = new EditText(this);
        sample.setHint("nihao");
        sample.setContentDescription("試打");
        sample.setTextSize(19);
        sample.setGravity(Gravity.TOP);
        sample.setMinHeight(dp(180));
        sample.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        sample.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable field = new GradientDrawable();
        field.setColor(dark ? 0xff282828 : Color.WHITE);
        field.setCornerRadius(dp(20));
        sample.setBackground(field);
        content.addView(sample);
        setContentView(screen);
    }

    private void showSettings() {
        LinearLayout form = column();
        form.setPadding(dp(16), 0, dp(16), dp(8));
        LinearLayout input = section(form, "輸入");
        LinearLayout appearance = section(form, "外觀");
        LinearLayout installation = section(form, "系統鍵盤");
        choice(input, "輸入方式", "mode", "拼音", "注音", "日本語 · ローマ字");
        choice(input, "中文", "simplified", "繁體", "简体");
        choice(input, "按鍵高度", "keyHeight", "標準", "加高", "特高");
        choice(appearance, "皮膚", "skin", "雙貓玻璃", "簡潔玻璃");
        choice(appearance, "色調", "tone", "跟隨系統", "淺色", "深色");
        choice(appearance, "玻璃強度", "strength", "柔和", "標準", "鮮明");
        paletteChoice(appearance);
        settingsRow(installation, "啟用喵撲",
                () -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        settingsRow(installation, "選擇預設鍵盤",
                () -> getSystemService(InputMethodManager.class).showInputMethodPicker());
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        new AlertDialog.Builder(this).setTitle("鍵盤設定").setView(scroll)
                .setPositiveButton("完成", null).show();
    }

    private void choice(LinearLayout form, String title, String key, String... values) {
        LinearLayout row = settingsRow(form, title, null);
        TextView value = text("", 14, false);
        value.setTextColor(dark ? 0xffc8b7af : 0xff695851);
        row.addView(value, 1);
        Runnable updateLabel = () -> {
            String selected = values[setting(key)];
            value.setText(selected);
            row.setContentDescription(title + "，" + selected);
        };
        updateLabel.run();
        row.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(values, setting(key), (dialog, index) -> {
                    SharedPreferences.Editor edit = preferences.edit();
                    if (key.equals("simplified")) edit.putBoolean(key, index == 1);
                    else edit.putInt(key, index);
                    edit.apply();
                    updateLabel.run();
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show());
    }

    private void paletteChoice(LinearLayout form) {
        LinearLayout row = settingsRow(form, "色系", null);
        TextView value = text("", 14, false);
        value.setTextColor(dark ? 0xffc8b7af : 0xff695851);
        row.addView(value, 1);
        Runnable update = () -> {
            String title = KeyboardPalette.at(preferences.getInt("palette", 0)).title;
            value.setText(title);
            row.setContentDescription("色系，" + title);
        };
        update.run();
        row.setOnClickListener(view -> showPalettes(update));
    }

    private void showPalettes(Runnable update) {
        LinearLayout choices = column();
        choices.setPadding(dp(16), dp(4), dp(16), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(choices);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("色系")
                .setView(scroll).setNegativeButton("取消", null).create();
        KeyboardPalette current = KeyboardPalette.at(preferences.getInt("palette", 0));
        int tone = preferences.getInt("tone", 0);
        boolean previewDark = tone == 2 || (tone == 0 && dark);
        for (KeyboardPalette palette : KeyboardPalette.values()) {
            LinearLayout row = settingsRow(choices, palette.title, () -> {
                preferences.edit().putInt("palette", palette.ordinal()).apply();
                update.run();
                dialog.dismiss();
            });
            row.removeViewAt(row.getChildCount() - 1);
            swatch(row, palette.board(previewDark));
            swatch(row, palette.cap(previewDark, false));
            swatch(row, palette.accent(previewDark));
            TextView check = text(palette == current ? "✓" : "", 17, false);
            check.setGravity(Gravity.CENTER);
            row.addView(check, new LinearLayout.LayoutParams(dp(24), -2));
            row.setContentDescription(palette.title + (palette == current ? "，已選取" : ""));
        }
        dialog.show();
    }

    private void swatch(LinearLayout row, int color) {
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(color);
        surface.setCornerRadius(dp(6));
        surface.setStroke(1, dark ? 0x30ffffff : 0x22000000);
        View swatch = new View(this);
        swatch.setBackground(surface);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(20), dp(20));
        size.leftMargin = dp(5);
        row.addView(swatch, size);
    }

    private int setting(String key) {
        return key.equals("simplified") ? (preferences.getBoolean(key, true) ? 1 : 0)
                : preferences.getInt(key, key.equals("strength") ? 1 : 0);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(dark ? 0xfff9f5f2 : 0xff211b17);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    private LinearLayout section(LinearLayout form, String title) {
        TextView heading = text(title, 13, true);
        heading.setTextColor(dark ? 0xffe8b4a6 : 0xff8c5145);
        heading.setPadding(dp(8), dp(13), 0, dp(6));
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        form.addView(heading);
        LinearLayout group = column();
        GradientDrawable background = new GradientDrawable();
        background.setColor(dark ? 0xff302a29 : 0xfffaf2ed);
        background.setCornerRadius(dp(16));
        group.setBackground(background);
        group.setClipToOutline(true);
        form.addView(group);
        return group;
    }

    private LinearLayout settingsRow(LinearLayout group, String label, Runnable action) {
        if (group.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(dark ? 0x28ffffff : 0x1f603b30);
            LinearLayout.LayoutParams rule = new LinearLayout.LayoutParams(-1, 1);
            rule.leftMargin = dp(14);
            group.addView(divider, rule);
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(8), dp(12), dp(8));
        row.setMinimumHeight(dp(50));
        row.setFocusable(true);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x278b5346), null, mask));
        row.addView(text(label, 15, false), new LinearLayout.LayoutParams(0, -2, 1));
        TextView disclosure = text("›", 21, false);
        disclosure.setPadding(dp(9), 0, 0, 0);
        disclosure.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(disclosure);
        if (action != null) row.setOnClickListener(v -> action.run());
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }
}
