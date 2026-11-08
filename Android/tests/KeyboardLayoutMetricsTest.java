package app.meowpuut.android;

public final class KeyboardLayoutMetricsTest {
    public static void main(String[] args) {
        int count = 0;
        // Available height is window bounds minus system bars/cutouts, not a
        // hard-coded device size. Include multi-window, portrait and landscape.
        for (int window : new int[]{240, 320, 360, 390, 480, 600, 800, 1024}) {
            for (int insets : new int[]{0, 24, 48, 80}) {
                for (int rows : new int[]{3, 4}) {
                    int previous = 0;
                    for (int preference = 0; preference <= 2; preference++) {
                        KeyboardLayoutMetrics m = new KeyboardLayoutMetrics(window - insets, rows, preference);
                        assert m.rowHeight >= 44 && m.bottomHeight >= 44;
                        assert m.keyFaceHeight() + 5 == m.rowHeight;
                        assert m.bottomFaceHeight() + 5 == m.bottomHeight;
                        // Expanding candidates replaces the rows without jumping height.
                        assert m.expandedListHeight() + 44 == rows * m.rowHeight;
                        assert m.expandedListHeight() >= 88;
                        assert m.contentHeight() >= previous;
                        previous = m.contentHeight();
                        int minimum = 54 + (rows + 1) * 44;
                        if (window - insets >= minimum) assert m.contentHeight() <= window - insets;
                        count++;
                    }
                }
            }
        }
        KeyboardLayoutMetrics portrait = new KeyboardLayoutMetrics(800, 3, 0);
        assert portrait.contentHeight() == 268; // no old 312dp minimum / blank band
        KeyboardLayoutMetrics tall = new KeyboardLayoutMetrics(800, 3, 2);
        assert tall.contentHeight() == 316;
        KeyboardLayoutMetrics landscape = new KeyboardLayoutMetrics(336, 3, 2);
        assert landscape.contentHeight() == 230; // preferred height yields to available room
        KeyboardLayoutMetrics zhuyin = new KeyboardLayoutMetrics(336, 4, 2);
        assert zhuyin.contentHeight() == 274;
        // Bad preferences cannot produce giant or negative buttons.
        assert new KeyboardLayoutMetrics(800, 3, -10).contentHeight() == portrait.contentHeight();
        assert new KeyboardLayoutMetrics(800, 3, 100).contentHeight() == tall.contentHeight();
        System.out.println("PASS: " + count + " keyboard geometries, expansion, insets and preference limits");
    }
}
