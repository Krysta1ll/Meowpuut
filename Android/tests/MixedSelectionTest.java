package app.meowpuut.android;

public final class MixedSelectionTest {
    private static void check(String text, int tail, String converted, String remaining) {
        MixedSelection result = MixedSelection.split(text, tail);
        if (!result.converted.equals(converted) || !result.remaining.equals(remaining)) {
            throw new AssertionError(text);
        }
    }

    public static void main(String[] args) {
        check("我好きx", 1, "我好き", "x");
        check("我好き你", 0, "我好き你", "");
        check("𠮷好きnihao", 5, "𠮷好き", "nihao");
        check("猫🐈x", 1, "猫🐈", "x");
        try {
            MixedSelection.split("我好き", 99);
            throw new AssertionError("invalid tail accepted");
        } catch (IllegalArgumentException expected) {
            // An invalid range must never silently discard text.
        }
        System.out.println("PASS: mixed selection preserves raw suffix and Unicode");
    }
}
