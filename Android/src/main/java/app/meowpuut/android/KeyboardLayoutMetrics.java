package app.meowpuut.android;

/** Vertical dimensions in dp, including each key's existing 5dp visual gap. */
final class KeyboardLayoutMetrics {
    static final int TOP = 5;
    static final int CANDIDATES = 44;
    static final int STRIP_GAP = 5;
    static final int KEY_GAP = 5;
    static final int MIN_TARGET = 44;
    final int rowHeight;
    final int bottomHeight;
    final int keyRows;

    KeyboardLayoutMetrics(int availableHeight, int keyRows, int preference) {
        if (keyRows != 3 && keyRows != 4) throw new IllegalArgumentException("keyboard row count");
        this.keyRows = keyRows;
        int bonus = Math.max(0, Math.min(2, preference)) * 6;
        int preferredRow = (keyRows == 4 ? 51 : 55) + bonus;
        int preferredBottom = 49 + bonus;
        int overhead = TOP + CANDIDATES + STRIP_GAP;
        int minimum = overhead + (keyRows + 1) * MIN_TARGET;
        // Preserve editing room where possible. Below the geometric minimum,
        // retain usable keys rather than silently making them too small. Extremely
        // short hosts still need more room or the system fullscreen editor.
        int budget = Math.max(minimum, Math.min(availableHeight - 96,
                Math.round(Math.max(0, availableHeight) * .55f)));
        rowHeight = Math.min(preferredRow, Math.max(MIN_TARGET,
                (budget - overhead - MIN_TARGET) / keyRows));
        bottomHeight = Math.min(preferredBottom,
                Math.max(MIN_TARGET, budget - overhead - keyRows * rowHeight));
    }

    int keyFaceHeight() { return rowHeight - KEY_GAP; }
    int bottomFaceHeight() { return bottomHeight - KEY_GAP; }
    int expandedListHeight() { return keyRows * rowHeight - MIN_TARGET; }
    int contentHeight() { return TOP + CANDIDATES + STRIP_GAP + keyRows * rowHeight + bottomHeight; }
}
