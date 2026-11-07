package app.meowpuut.android;

import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.PopupMenu;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class MeowpuutKeyboard extends InputMethodService {
    static { System.loadLibrary("meowpuut"); }

    private static final int PINYIN = 0;
    private static final int ZHUYIN = 1;
    private static final int JAPANESE = 2;
    private static final long JAPANESE_SESSION = -1;
    private static final int PAGE_SIZE = 7;
    private static final String[] MODE_NAMES = {"拼音", "注音", "日語"};
    private static final String[] QWERTY = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    private static final String[] ZHUYIN_KEYS = {"1234567890-", "qwertyuiop", "asdfghjkl;", "zxcvbnm,./"};
    private static final String[] ZHUYIN_LABELS = {"ㄅㄉˇˋㄓˊ˙ㄚㄞㄢㄦ", "ㄆㄊㄍㄐㄔㄗㄧㄛㄟㄣ",
            "ㄇㄋㄎㄑㄕㄘㄨㄜㄠㄤ", "ㄈㄌㄏㄒㄖㄙㄩㄝㄡㄥ"};
    private static final String[] SYMBOLS = {"1234567890", "-/:;()$&@", ".,?!'\"", "[]{}#%_="};
    private static final String[] MORE_SYMBOLS = {"1234567890", "^*+|\\~`<>", "€£¥•…", ".,?!'\""};

    private static native long open(AssetManager assets, boolean pinyin);
    private static native void close(long handle);
    private static native void reset(long handle, boolean simplified);
    private static native boolean key(long handle, int value);
    private static native void choose(long handle, int index);
    private static native byte[] takeCommit(long handle);
    private static native byte[] preedit(long handle);
    private static native String raw(long handle);
    private static native int count(long handle);
    private static native int page(long handle);
    private static native int sentenceCount(long handle);
    private static native int literalTail(long handle);
    private static native int consumed(long handle, int index);
    private static native byte[] exactPinyin(long handle, String reading);
    private static native void setScript(long handle, boolean simplified);
    private static native byte[] candidate(long handle, int index);

    private final long[] sessions = new long[2];
    private final boolean[] attempted = new boolean[2];
    private final SharedPreferences.OnSharedPreferenceChangeListener appearanceListener = (settings, key) -> {
        if (this.root == null || key == null) return;
        switch (key) {
            case "palette": case "tone": case "strength": case "skin": case "keyHeight":
                rebuild();
                break;
        }
    };
    private SharedPreferences preferences;
    private Japanese japanese;
    private int mode;
    private boolean simplified;
    private boolean directOnly;
    private boolean numbers;
    private int symbols;
    private boolean expanded;
    private boolean chinese = true;
    private boolean dark;
    private int typingHeightBonus;
    private int accent;
    private int strength;
    private KeyboardPalette palette;
    private boolean shifted;
    private boolean capsLock;
    private long lastShift;
    private LinearLayout root;
    private LinearLayout candidates;
    private HorizontalScrollView scroll;
    private TextView error;
    private boolean marked;
    private String returnTitle = "換行";
    private Button expand;
    private final List<PinyinChoice> pinyinChoices = new ArrayList<>();
    private int pinyinPage;
    private String rankedInput;
    private boolean rankedSimplified;
    private JapaneseSegment segment;
    private int pinnedStart = -1;
    private String pinnedReading = "";
    private String pinnedChoice = "";

    private static final class PinyinChoice {
        final String text;
        final int engineIndex;
        final int rawTail;
        final String hint;

        PinyinChoice(String text, int engineIndex, int rawTail, String hint) {
            this.text = text;
            this.engineIndex = engineIndex;
            this.rawTail = rawTail;
            this.hint = hint;
        }
    }

    private static final class JapaneseSegment {
        final int start;
        final String reading;
        final String before;
        final String after;
        final int rawTail;
        final List<String> choices;

        JapaneseSegment(int start, String reading, String before, String after,
                        int rawTail, List<String> choices) {
            this.start = start;
            this.reading = reading;
            this.before = before;
            this.after = after;
            this.rawTail = rawTail;
            this.choices = choices;
        }

        String sentence(String choice) { return before + choice + after; }
    }
    private Button space;
    private Button confirm;

    @Override public void onCreate() {
        super.onCreate();
        preferences = getSharedPreferences("keyboard", MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener(appearanceListener);
        japanese = new Japanese(getAssets());
        mode = preferences.getInt("mode", PINYIN);
        simplified = preferences.getBoolean("simplified", true);
    }

    @Override public View onCreateInputView() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(android.view.Gravity.CENTER_VERTICAL);
        root.setMinimumHeight(dp(312));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                int bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                view.setPadding(dp(5), dp(5), dp(5), bottom);
            }
            return insets;
        });
        rebuild();
        return root;
    }

    @Override public void onStartInput(EditorInfo editor, boolean restarting) {
        super.onStartInput(editor, restarting);
        marked = false;
        clear();
        mode = preferences.getInt("mode", PINYIN);
        simplified = preferences.getBoolean("simplified", true);
        clear();
        int action = (editor.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
                ? EditorInfo.IME_ACTION_NONE : editor.imeOptions & EditorInfo.IME_MASK_ACTION;
        switch (action) {
            case EditorInfo.IME_ACTION_SEARCH: returnTitle = "搜尋"; break;
            case EditorInfo.IME_ACTION_SEND: returnTitle = "傳送"; break;
            case EditorInfo.IME_ACTION_GO: returnTitle = "前往"; break;
            case EditorInfo.IME_ACTION_DONE: returnTitle = "完成"; break;
            case EditorInfo.IME_ACTION_NEXT: returnTitle = "下一步"; break;
            default: returnTitle = "換行";
        }
        int type = editor.inputType & InputType.TYPE_MASK_CLASS;
        int variation = editor.inputType & InputType.TYPE_MASK_VARIATION;
        numbers = type == InputType.TYPE_CLASS_NUMBER || type == InputType.TYPE_CLASS_PHONE
                || type == InputType.TYPE_CLASS_DATETIME;
        directOnly = type != InputType.TYPE_CLASS_TEXT
                || variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_URI;
        symbols = numbers ? 1 : 0;
        expanded = false;
        shifted = false;
        capsLock = false;
        lastShift = 0;
        if (root != null) rebuild();
    }

    @Override public void onUpdateSelection(int oldStart, int oldEnd, int newStart, int newEnd,
                                            int composingStart, int composingEnd) {
        super.onUpdateSelection(oldStart, oldEnd, newStart, newEnd, composingStart, composingEnd);
        if (marked && composingEnd >= 0 && (newStart != composingEnd || newEnd != composingEnd)) {
            InputConnection connection = getCurrentInputConnection();
            if (connection != null) connection.finishComposingText();
            marked = false;
            expanded = false;
            clear();
            if (root != null) rebuild();
        }
    }

    @Override public void onFinishInputView(boolean finishingInput) {
        if (!finishingInput && marked) finishComposition();
        clear();
        marked = false;
        super.onFinishInputView(finishingInput);
    }

    @Override public void onFinishInput() {
        marked = false;
        clear();
        super.onFinishInput();
    }

    @Override public void onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(appearanceListener);
        for (long handle : sessions) if (handle != 0) close(handle);
        super.onDestroy();
    }

    private boolean engineKey(long handle, int value) {
        return handle == JAPANESE_SESSION ? japanese.key(value) : key(handle, value);
    }

    private void engineChoose(long handle, int index) {
        if (handle == JAPANESE_SESSION) japanese.choose(index);
        else choose(handle, index);
    }

    private String engineTakeCommit(long handle) {
        return handle == JAPANESE_SESSION ? japanese.takeCommit() : new String(takeCommit(handle), StandardCharsets.UTF_8);
    }

    private String enginePreedit(long handle) {
        return handle == JAPANESE_SESSION ? japanese.preedit : new String(preedit(handle), StandardCharsets.UTF_8);
    }

    private String engineRaw(long handle) {
        return handle == JAPANESE_SESSION ? japanese.raw : raw(handle);
    }

    private int engineCount(long handle) {
        return handle == JAPANESE_SESSION ? japanese.count() : count(handle);
    }

    private int enginePage(long handle) {
        return handle == JAPANESE_SESSION ? japanese.page : page(handle);
    }

    private String engineCandidate(long handle, int index) {
        return handle == JAPANESE_SESSION ? japanese.candidate(index) : new String(candidate(handle, index), StandardCharsets.UTF_8);
    }

    private long active() {
        return directOnly || !chinese ? 0 : mode == JAPANESE ? JAPANESE_SESSION : sessions[mode];
    }

    private long load() {
        if (directOnly || !chinese || mode == JAPANESE) return active();
        if (!attempted[mode]) {
            attempted[mode] = true;
            sessions[mode] = open(getAssets(), mode == PINYIN);
            clear();
        }
        return active();
    }

    private void clear() {
        pinnedStart = -1;
        rankedInput = null;
        pinyinPage = 0;
        long handle = active();
        if (handle == JAPANESE_SESSION) japanese.clear();
        else if (handle != 0) reset(handle, simplified);
        refreshCandidates();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        switch (label) {
            case "⌫": button.setContentDescription("退格"); break;
            case "↵": button.setContentDescription("確認或換行"); break;
            case "⇧": case "⇪": button.setContentDescription("切換大小寫"); break;
            case "🌐︎": button.setContentDescription("選擇鍵盤"); break;
            case "‹": button.setContentDescription("上一頁候選"); break;
            case "›": button.setContentDescription("下一頁候選"); break;
        }
        button.setAllCaps(false);
        button.setSingleLine();
        button.setTextSize(label.length() > 1 ? 14 : 20);
        button.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        button.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
                new int[]{dark ? 0xff8b858a : 0xff777276, dark ? Color.WHITE : 0xff202023}));
        button.setElevation(0);
        button.setStateListAnimator(null);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            action.run();
        });
        return button;
    }

    private GradientDrawable surface(int light, int shade, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(dark ? shade : light);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private Button keyButton(LinearLayout row, String label, int width, int height,
                             boolean utility, Runnable action) {
        Button cap = button(label, action);
        boolean primary = label.equals("換行");
        int capColor = palette.cap(dark, utility);
        if (primary) capColor = tint(capColor, accent, dark ? .17f : .22f);
        MatteSurface face = new MatteSurface(
                tint(capColor, Color.WHITE, dark ? .055f : .12f),
                tint(capColor, Color.BLACK, dark ? .06f : .025f),
                dp(8), dp(1) * .65f, dark, false);
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf((accent & 0xffffff) | 0x32000000), face,
                surface(Color.WHITE, Color.WHITE, 8));
        cap.setBackground(new InsetDrawable(ripple, dp(2), 0, dp(2), dp(5)));
        cap.setPadding(dp(2), 0, dp(2), dp(5));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(
                width == 0 ? 0 : dp(width), dp(height + 5), width == 0 ? 1 : 0);
        row.addView(cap, size);
        return cap;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        root.addView(row);
        return row;
    }

    private void rebuild() {
        Configuration configuration = getResources().getConfiguration();
        typingHeightBonus = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                ? 0 : preferences.getInt("keyHeight", 0) * 6;
        int tone = preferences.getInt("tone", 0);
        dark = tone == 2 || (tone == 0 && (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        palette = KeyboardPalette.at(preferences.getInt("palette", 0));
        accent = palette.accent(dark);
        strength = preferences.getInt("strength", 1);
        root.removeAllViews();
        space = null;
        confirm = null;
        root.setPadding(dp(5), dp(5), dp(5), root.getPaddingBottom());
        int board = palette.board(dark);
        root.setBackground(new MatteSurface(tint(board, Color.WHITE, dark ? .045f : .26f),
                tint(board, Color.BLACK, dark ? .20f : .07f), dp(19), dp(1) * .65f, dark, true));

        error = new TextView(this);
        error.setText("無法載入詞庫");
        error.setTextColor(dark ? 0xffffb4ab : 0xffa52b20);
        error.setVisibility(View.GONE);
        root.addView(error);
        LinearLayout strip = row();
        strip.setGravity(Gravity.CENTER_VERTICAL);
        boolean cats = preferences.getInt("skin", 0) == 0;
        strip.setBackground(new CandidateGlass(getResources().getDisplayMetrics().density,
                dark, cats, palette, strength));
        strip.setPadding(dp(cats ? 35 : 4), 0, dp(cats ? 34 : 4), 0);
        LinearLayout.LayoutParams stripSize = (LinearLayout.LayoutParams) strip.getLayoutParams();
        stripSize.height = dp(44);
        stripSize.bottomMargin = dp(5);

        scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setHorizontalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(8));
        candidates = new LinearLayout(this);
        scroll.addView(candidates);
        strip.addView(scroll, new LinearLayout.LayoutParams(0, dp(44), 1));
        expand = button(expanded ? "⌃" : "⌄", () -> {
            expanded = !expanded;
            rebuild();
        });
        expand.setContentDescription(expanded ? "收合候選詞" : "展開候選詞");
        expand.setBackgroundColor(Color.TRANSPARENT);
        strip.addView(expand, new LinearLayout.LayoutParams(dp(44), dp(44)));

        boolean zhuyin = !directOnly && chinese && mode == ZHUYIN && symbols == 0;
        boolean latin = (directOnly || !chinese) && symbols == 0;
        if (expanded) {
            updatePinyinChoices(active());
            addExpandedCandidates();
        } else {
            String[] keys = symbols == 2 ? MORE_SYMBOLS : symbols == 1 ? SYMBOLS : zhuyin ? ZHUYIN_KEYS : QWERTY;
            int height = (symbols > 0 || zhuyin ? 46 : 50) + typingHeightBonus;
            for (int r = 0; r < keys.length; r++) {
                LinearLayout row = row();
                boolean last = r == keys.length - 1;
                if ((symbols == 0 && !zhuyin && r == 1) || (zhuyin && (r == 1 || r == 2))) {
                    row.setPadding(dp(18), 0, dp(18), 0);
                }
                if (last && !zhuyin && symbols == 0) keyButton(row, capsLock ? "⇪" : "⇧", 44, height, true, this::shift);
                if (last && symbols > 0 && !numbers) {
                    keyButton(row, symbols == 1 ? "#+=" : "123", 44, height, true, () -> {
                        symbols = 3 - symbols;
                        rebuild();
                    });
                }
                for (int i = 0; i < keys[r].length(); i++) {
                    char value = keys[r].charAt(i);
                    String label = zhuyin ? ZHUYIN_LABELS[r].substring(i, i + 1) : String.valueOf(value);
                    if (latin && shifted) label = label.toUpperCase(Locale.ROOT);
                    String output = symbols > 0 && chinese && !directOnly && mode != JAPANESE ? chineseSymbol(value) : label;
                    if (symbols > 0) label = output;
                    keyButton(row, label, 0, height, false, () -> {
                        if (latin || symbols > 0) {
                            if (symbols > 0) finishComposition();
                            commit(output);
                            if (latin && shifted && !capsLock) {
                                shifted = false;
                                rebuild();
                            }
                        } else type(value);
                    });
                }
                if (last) deleteButton(row, height);
            }
        }

        int bottomHeight = 44 + typingHeightBonus;
        LinearLayout bottom = row();
        keyButton(bottom, "🌐︎", 44, bottomHeight, true,
                () -> getSystemService(InputMethodManager.class).showInputMethodPicker());
        if (!directOnly) {
            Button language = keyButton(bottom, chinese ? "ABC" : mode == PINYIN ? "拼" : mode == ZHUYIN ? "ㄅ" : "あ",
                    44, bottomHeight, true, () -> {
                flushRaw();
                chinese = !chinese;
                shifted = false;
                rebuild();
            });
            language.setContentDescription(chinese ? "切換英文，長按選擇輸入方式" : "切換中文或日語，長按選擇輸入方式");
            language.setOnLongClickListener(v -> {
                showInputModes(v);
                return true;
            });
        }
        if (!numbers) {
            keyButton(bottom, symbols == 0 ? "符號" : mode == ZHUYIN && chinese && !directOnly
                    ? "注音" : "字母", 44, bottomHeight, true, () -> {
                symbols = symbols == 0 ? 1 : 0;
                rebuild();
            });
        }
        space = keyButton(bottom, "空格", 0, bottomHeight, false, () -> {
            if (zhuyin || (expanded && mode == ZHUYIN) || (chinese && !directOnly && mode == JAPANESE)) type(' ');
            else if (mode == PINYIN) {
                if (!finishCandidate()) commit(" ");
            } else {
                flushRaw();
                commit(" ");
            }
        });
        enableSpaceCursor();
        if (expanded) deleteButton(bottom, bottomHeight);
        confirm = keyButton(bottom, "換行", 72, bottomHeight, true, this::enter);
        refreshCandidates();
    }

    private void showInputModes(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        for (int index = 0; index < MODE_NAMES.length; index++) {
            menu.getMenu().add(0, index, index, MODE_NAMES[index]);
        }
        menu.getMenu().setGroupCheckable(0, true, true);
        menu.getMenu().findItem(mode).setChecked(true);
        int scriptItem = MODE_NAMES.length;
        menu.getMenu().add(1, scriptItem, scriptItem, simplified ? "切換為繁體" : "切換為简体");
        menu.setOnMenuItemClickListener(item -> {
            int selected = item.getItemId();
            if (selected == scriptItem) {
                simplified = !simplified;
                preferences.edit().putBoolean("simplified", simplified).apply();
                long handle = active();
                if (handle > 0) setScript(handle, simplified);
                rankedInput = null;
                rebuild();
                return true;
            }
            if (selected != mode || !chinese) {
                flushRaw();
                mode = selected;
                chinese = true;
                symbols = 0;
                preferences.edit().putInt("mode", mode).apply();
                rebuild();
            }
            return true;
        });
        menu.show();
    }

    private void shift() {
        long now = android.os.SystemClock.uptimeMillis();
        if (shifted && !capsLock && now - lastShift <= ViewConfiguration.getDoubleTapTimeout()) {
            capsLock = true;
        } else {
            shifted = !shifted;
            capsLock = false;
        }
        lastShift = now;
        rebuild();
    }

    private void deleteButton(LinearLayout row, int height) {
        Button delete = keyButton(row, "⌫", 44, height, true, this::backspace);
        Runnable repeat = new Runnable() {
            @Override public void run() {
                if (!delete.isPressed() || !delete.isAttachedToWindow()) return;
                backspace();
                delete.postDelayed(this, 75);
            }
        };
        delete.setOnLongClickListener(v -> {
            repeat.run();
            return true;
        });
    }

    private void enableSpaceCursor() {
        Button bar = space;
        bar.setOnTouchListener(new View.OnTouchListener() {
            private float position;
            private boolean tracking;
            private final Runnable activate = () -> {
                if (!bar.isPressed() || !bar.isAttachedToWindow()) return;
                tracking = true;
                finishComposition();
                bar.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            };

            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        position = event.getX();
                        bar.setPressed(true);
                        bar.postDelayed(activate, ViewConfiguration.getLongPressTimeout());
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (tracking) {
                            float delta = event.getX() - position;
                            int steps = (int) (delta / dp(10));
                            position += steps * dp(10);
                            int key = steps > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
                            for (int i = 0; i < Math.abs(steps); i++) sendDownUpKeyEvents(key);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        bar.removeCallbacks(activate);
                        bar.setPressed(false);
                        if (!tracking && event.getActionMasked() == MotionEvent.ACTION_UP) bar.performClick();
                        tracking = false;
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private boolean isPinyin(long handle) {
        return mode == PINYIN && handle != 0;
    }

    private void updatePinyinChoices(long handle) {
        if (!isPinyin(handle)) return;
        String input = engineRaw(handle);
        if (input.equals(rankedInput) && simplified == rankedSimplified) return;
        if (rankedInput != null && !input.equals(rankedInput)) pinyinPage = 0;
        rankedInput = input;
        rankedSimplified = simplified;
        pinyinChoices.clear();
        segment = findJapaneseSegment(handle, input);
        int full = sentenceCount(handle);
        List<String> japaneseAlternatives = new ArrayList<>();
        boolean chineseFirst = segment != null && pinnedStart < 0 && full > 0 && literalTail(handle) == 0;
        if (chineseFirst) appendChineseSentences(handle, 1);
        if (segment != null) {
            String first = pinnedStart >= 0 ? pinnedChoice : segment.choices.get(0);
            addPinyinChoice(segment.sentence(first), -1, segment.rawTail, "");
            for (String option : segment.choices) {
                if (!option.equals(first)) japaneseAlternatives.add(segment.sentence(option));
            }
            if (chineseFirst && full > 1) {
                addPinyinChoice(engineCandidate(handle, 1), 1, 0, "");
            }
        } else {
            appendChineseSentences(handle, full);
        }
        List<Integer> corrections = new ArrayList<>();
        for (int i = full, total = engineCount(handle); i < total; i++) {
            int length = consumed(handle, i);
            if (pinnedStart >= 0 && length < input.length() && length > pinnedStart) continue;
            String text = engineCandidate(handle, i);
            if (length < input.length()) {
                addPinyinChoice(text, i, 0, input.substring(0, length));
            } else if (text.equals(input)) {
                addPinyinChoice(text, i, input.length(), "");
            } else {
                corrections.add(i);
            }
        }
        appendAlternatingChoices(handle, input, japaneseAlternatives, corrections);
        pinyinPage = Math.min(pinyinPage, Math.max(0, (pinyinChoices.size() - 1) / PAGE_SIZE));
    }

    private void appendChineseSentences(long handle, int count) {
        for (int i = 0; i < count; i++) {
            addPinyinChoice(engineCandidate(handle, i), i, i == 0 ? literalTail(handle) : 0, "");
        }
    }

    private void appendAlternatingChoices(long handle, String input, List<String> japaneseAlternatives,
                                          List<Integer> corrections) {
        Random order = new Random(input.hashCode());
        Collections.shuffle(japaneseAlternatives, order);
        boolean japaneseFirst = order.nextBoolean();
        int japaneseIndex = 0;
        int correctionIndex = 0;
        while (japaneseIndex < japaneseAlternatives.size() || correctionIndex < corrections.size()) {
            if (japaneseIndex < japaneseAlternatives.size()
                    && (japaneseFirst || correctionIndex >= corrections.size())) {
                addPinyinChoice(japaneseAlternatives.get(japaneseIndex++), -1, segment.rawTail, "");
            } else {
                int index = corrections.get(correctionIndex++);
                addPinyinChoice(engineCandidate(handle, index), index, 0, "");
            }
            japaneseFirst = !japaneseFirst;
        }
    }

    private void addPinyinChoice(String text, int index, int rawTail, String hint) {
        for (PinyinChoice existing : pinyinChoices) {
            if (existing.text.equals(text) && existing.hint.equals(hint)) return;
        }
        pinyinChoices.add(new PinyinChoice(text, index, rawTail, hint));
    }

    private JapaneseSegment findJapaneseSegment(long handle, String input) {
        if (pinnedStart >= 0 && input.startsWith(pinnedReading, pinnedStart)) {
            return japaneseSegment(handle, input, pinnedStart, pinnedReading, true);
        }
        pinnedStart = -1;
        // Limit scans to recent input and dictionary-sized readings.
        for (int end = input.length(); end >= Math.max(4, input.length() - 16); end--) {
            for (int start = Math.max(0, end - 16); start <= end - 4; start++) {
                String reading = input.substring(start, end);
                if (!japanese.hasWord(reading)) continue;
                JapaneseSegment match = japaneseSegment(handle, input, start, reading, false);
                if (match != null) return match;
            }
        }
        return null;
    }

    private JapaneseSegment japaneseSegment(long handle, String input, int start, String reading, boolean pinned) {
        String head = input.substring(0, start);
        String tail = input.substring(start + reading.length());
        String before = exactText(handle, head);
        String after = exactText(handle, tail);
        if (!pinned && ((!head.isEmpty() && before.isEmpty()) || (!tail.isEmpty() && after.isEmpty()))) {
            return null;
        }
        if (before.isEmpty()) before = head;
        return new JapaneseSegment(start, reading, before, after.isEmpty() ? tail : after,
                after.isEmpty() ? tail.length() : 0, japanese.segmentChoices(reading));
    }

    private String exactText(long handle, String input) {
        if (input.isEmpty()) return "";
        byte[] bytes = exactPinyin(handle, input);
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    private int displayedCount(long handle) {
        return isPinyin(handle) ? pinyinChoices.size() : handle == 0 ? 0 : engineCount(handle);
    }

    private int displayedPage(long handle) {
        return isPinyin(handle) ? pinyinPage : handle == 0 ? 0 : enginePage(handle);
    }

    private CharSequence displayedCandidate(long handle, int index) {
        if (!isPinyin(handle)) return engineCandidate(handle, index);
        PinyinChoice choice = pinyinChoices.get(index);
        SpannableStringBuilder label = new SpannableStringBuilder(choice.text);
        if (choice.rawTail > 0) {
            int start = label.length() - choice.rawTail;
            label.setSpan(new TypefaceSpan("monospace"), start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.setSpan(new ForegroundColorSpan(dark ? 0xffd8c1a3 : 0xff765229),
                    start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (!choice.hint.isEmpty()) {
            int start = label.length();
            label.append("  ").append(choice.hint);
            label.setSpan(new RelativeSizeSpan(.69f), start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.setSpan(new ForegroundColorSpan(dark ? 0xffd7cdd6 : 0xff64535d),
                    start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return label;
    }

    private boolean isJapaneseCandidate(long handle, int index) {
        return handle == JAPANESE_SESSION || (isPinyin(handle) && pinyinChoices.get(index).engineIndex < 0);
    }

    private void setCandidateLabel(Button button, long handle, int index) {
        CharSequence candidate = displayedCandidate(handle, index);
        boolean mixed = isPinyin(handle) && pinyinChoices.get(index).engineIndex < 0;
        if (isJapaneseCandidate(handle, index)) {
            SpannableStringBuilder label = new SpannableStringBuilder(candidate);
            int end = label.length();
            label.append("  🐾");
            label.setSpan(new RelativeSizeSpan(.62f), end, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            button.setText(label);
            button.setContentDescription(candidate + "，含日語");
        } else {
            button.setText(candidate);
            button.setContentDescription(null);
        }
        button.setOnLongClickListener(mixed ? view -> {
            openJapaneseChoices(view);
            return true;
        } : null);
        button.setLongClickable(mixed);
    }

    private void openJapaneseChoices(View anchor) {
        long handle = active();
        JapaneseSegment current = segment;
        if (!isPinyin(handle) || current == null) return;
        PopupMenu menu = new PopupMenu(this, anchor);
        String selected = pinnedStart >= 0 ? pinnedChoice : current.choices.get(0);
        for (int i = 0; i < current.choices.size(); i++) {
            menu.getMenu().add(0, i, i, current.choices.get(i));
        }
        menu.getMenu().setGroupCheckable(0, true, true);
        menu.getMenu().findItem(current.choices.indexOf(selected)).setChecked(true);
        menu.setOnMenuItemClickListener(item -> {
            if (active() != handle || segment != current) return false;
            pinJapaneseChoice(handle, current, current.choices.get(item.getItemId()));
            return true;
        });
        menu.show();
    }

    private void pinJapaneseChoice(long handle, JapaneseSegment current, String choice) {
        pinnedStart = current.start;
        pinnedReading = current.reading;
        pinnedChoice = choice;
        rankedInput = null;
        pinyinPage = 0;
        publishCommit(handle);
    }

    private void addExpandedCandidates() {
        long handle = active();
        int total = displayedCount(handle);
        int current = displayedPage(handle);
        LinearLayout navigation = row();
        navigation.setGravity(android.view.Gravity.CENTER_VERTICAL);
        navigation.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        keyButton(navigation, "‹", 44, 44, true, () -> turnPage(handle, 0xff55))
                .setEnabled(current > 0);
        TextView pageLabel = new TextView(this);
        pageLabel.setText((current + 1) + " / " + Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE));
        pageLabel.setTextColor(dark ? Color.WHITE : Color.BLACK);
        pageLabel.setGravity(android.view.Gravity.CENTER);
        pageLabel.setTextSize(13);
        navigation.addView(pageLabel, new LinearLayout.LayoutParams(dp(56), dp(44)));
        keyButton(navigation, "›", 44, 44, true, () -> turnPage(handle, 0xff56))
                .setEnabled((current + 1) * PAGE_SIZE < total);
        ScrollView list = new ScrollView(this);
        list.setVerticalFadingEdgeEnabled(true);
        list.setFadingEdgeLength(dp(10));
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        int start = current * PAGE_SIZE;
        int end = Math.min(total, start + PAGE_SIZE);
        if (current == 0 && start < end) {
            LinearLayout sentence = new LinearLayout(this);
            grid.addView(sentence);
            Button first = keyButton(sentence, "", 0, 44, false,
                    () -> select(handle, 0));
            setCandidateLabel(first, handle, 0);
            first.setTextSize(15);
            first.setSingleLine(false);
            first.setMaxLines(Integer.MAX_VALUE);
            first.setEllipsize(null);
            first.setPadding(dp(12), dp(8), dp(12), dp(13));
            first.setMinHeight(dp(49));
            first.getLayoutParams().height = LinearLayout.LayoutParams.WRAP_CONTENT;
            first.setSelected(true);
            start++;
        }
        for (int i = start; i < end; i += 2) {
            LinearLayout line = new LinearLayout(this);
            grid.addView(line);
            for (int j = i; j < Math.min(i + 2, end); j++) {
                int index = j;
                Button option = keyButton(line, "", 0, 44, false,
                        () -> select(handle, index));
                setCandidateLabel(option, handle, index);
                option.setTextSize(15);
                option.setSingleLine(false);
                option.setMaxLines(2);
                option.setEllipsize(android.text.TextUtils.TruncateAt.END);
                option.setPadding(dp(6), 0, dp(6), 0);
            }
        }
        list.addView(grid);
        root.addView(list, new LinearLayout.LayoutParams(-1, dp(147)));
    }

    private void refreshCandidates() {
        if (candidates == null || expand == null || space == null || confirm == null) return;
        candidates.removeAllViews();
        long handle = active();
        error.setVisibility(!directOnly && chinese && mode != JAPANESE && attempted[mode] && handle == 0
                ? View.VISIBLE : View.GONE);
        updatePinyinChoices(handle);
        int total = displayedCount(handle);
        int start = displayedPage(handle) * PAGE_SIZE;
        boolean japaneseShown = false;
        for (int i = start; i < Math.min(start + PAGE_SIZE, total); i++) {
            boolean japaneseCandidate = isJapaneseCandidate(handle, i);
            if (japaneseCandidate && japaneseShown) continue;
            addCandidate(handle, i, i == start);
            japaneseShown |= japaneseCandidate;
        }
        expand.setEnabled(total > 0 || expanded);
        String input = handle == 0 ? "" : engineRaw(handle);
        String spaceTitle = "空格";
        if (mode == ZHUYIN && !input.isEmpty() && " 6347".indexOf(input.charAt(input.length() - 1)) < 0) {
            spaceTitle = "一聲";
        }
        space.setText(spaceTitle);
        space.setContentDescription(input.isEmpty() || spaceTitle.equals("一聲") ? spaceTitle : "選詞");
        space.setTextSize(15);
        confirm.setText(input.isEmpty() ? returnTitle : "✓");
        confirm.setContentDescription(input.isEmpty() ? returnTitle : "確認首選");
        confirm.setTextSize(input.isEmpty() ? 15 : 19);
        scroll.scrollTo(0, 0);
    }

    private void addCandidate(long handle, int index, boolean highlighted) {
        Button option = button("", () -> select(handle, index));
        setCandidateLabel(option, handle, index);
        option.setTextSize(18);
        option.setMinWidth(dp(44));
        option.setSelected(highlighted);
        option.setPadding(dp(9), 0, dp(9), 0);
        if (highlighted) {
            int color = (accent & 0xffffff) | (dark ? 0x38000000 : 0x24000000);
            option.setBackground(new InsetDrawable(surface(color, color, 18), dp(2), dp(7), dp(2), dp(7)));
            option.setPadding(dp(9), 0, dp(9), 0);
        } else option.setBackgroundColor(Color.TRANSPARENT);
        candidates.addView(option, new LinearLayout.LayoutParams(-2, dp(36)));
    }

    private void turnPage(long handle, int direction) {
        if (handle == 0 || active() != handle) return;
        if (isPinyin(handle)) {
            pinyinPage += direction == 0xff55 ? -1 : 1;
            rebuild();
        } else if (engineKey(handle, direction)) rebuild();
    }

    private void select(long handle, int index) {
        if (active() != handle) return;
        if (isPinyin(handle)) {
            PinyinChoice choice = pinyinChoices.get(index);
            if (choice.engineIndex < 0) {
                MixedSelection selection = MixedSelection.split(choice.text, choice.rawTail);
                InputConnection connection = getCurrentInputConnection();
                if (connection == null || !connection.commitText(selection.converted, 1)) return;
                marked = false;
                expanded = false;
                clear();
                for (int i = 0; i < selection.remaining.length(); i++) {
                    if (!engineKey(handle, selection.remaining.charAt(i))) {
                        // If replay unexpectedly fails, preserve the complete raw tail in order.
                        String rawTail = engineRaw(handle) + selection.remaining.substring(i);
                        clear();
                        connection.commitText(rawTail, 1);
                        rebuild();
                        return;
                    }
                }
                publishCommit(handle);
                return;
            }
            if (pinnedStart >= 0) pinnedStart -= consumed(handle, choice.engineIndex);
            index = choice.engineIndex;
        }
        engineChoose(handle, index);
        publishCommit(handle);
    }

    private void type(char value) {
        long handle = load();
        if (handle != 0 && engineKey(handle, value)) publishCommit(handle);
        else if (mode != ZHUYIN || value == ' ') {
            flushRaw();
            commit(String.valueOf(value));
        }
    }

    private void publishCommit(long handle) {
        String text = engineTakeCommit(handle);
        updatePinyinChoices(handle);
        CharSequence remaining = pinnedStart >= 0 && isPinyin(handle) && !pinyinChoices.isEmpty()
                ? displayedCandidate(handle, 0) : enginePreedit(handle);
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) {
            connection.beginBatchEdit();
            try {
                if (!text.isEmpty()) {
                    connection.commitText(text, 1);
                    marked = false;
                }
                if (remaining.length() > 0) {
                    marked = connection.setComposingText(remaining, 1);
                } else if (marked) {
                    connection.commitText("", 1);
                    marked = false;
                }
            } finally {
                connection.endBatchEdit();
            }
        }
        updateComposition(handle);
    }

    private void updateComposition(long handle) {
        if (expanded) {
            expanded = displayedCount(handle) > 0;
            rebuild();
        } else refreshCandidates();
    }

    private void flushRaw() {
        long handle = active();
        if (handle != 0) {
            String text = pinnedStart >= 0 && isPinyin(handle) && !pinyinChoices.isEmpty()
                    ? pinyinChoices.get(0).text : engineRaw(handle);
            if (!text.isEmpty()) commit(text);
        }
        expanded = false;
        clear();
    }

    private void commit(String text) {
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) connection.commitText(text, 1);
        marked = false;
    }

    private void backspace() {
        long handle = active();
        if (handle != 0 && engineKey(handle, 0xff08)) publishCommit(handle);
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
    }

    private void finishComposition() {
        while (finishCandidate()) {
            // A candidate can consume only part of the reading.
        }
    }

    static int tint(int base, int accent, float amount) {
        return Color.rgb(Math.round(Color.red(base) * (1 - amount) + Color.red(accent) * amount),
                Math.round(Color.green(base) * (1 - amount) + Color.green(accent) * amount),
                Math.round(Color.blue(base) * (1 - amount) + Color.blue(accent) * amount));
    }

    private boolean finishCandidate() {
        long handle = active();
        if (handle == 0) return false;
        if (isPinyin(handle)) {
            updatePinyinChoices(handle);
            if (pinyinChoices.isEmpty()) return false;
            select(handle, 0);
            return true;
        }
        if (!engineKey(handle, 0xff0d)) return false;
        publishCommit(handle);
        return true;
    }

    private void enter() {
        if (!finishCandidate() && !sendDefaultEditorAction(true)) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
        }
    }

    private static String chineseSymbol(char symbol) {
        String ascii = ",.?;:!()[]{}\\\"";
        int index = ascii.indexOf(symbol);
        return index < 0 ? String.valueOf(symbol) : "，。？；：！（）「」『』、“".substring(index, index + 1);
    }
}
