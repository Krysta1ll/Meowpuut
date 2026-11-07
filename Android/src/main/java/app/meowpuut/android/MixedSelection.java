package app.meowpuut.android;

/** Separates the displayed raw suffix from the converted mixed-language text. */
final class MixedSelection {
    final String converted;
    final String remaining;

    private MixedSelection(String converted, String remaining) {
        this.converted = converted;
        this.remaining = remaining;
    }

    static MixedSelection split(String text, int rawTail) {
        if (rawTail < 0 || rawTail > text.length()) throw new IllegalArgumentException("invalid raw tail");
        int end = text.length() - rawTail;
        return new MixedSelection(text.substring(0, end), text.substring(end));
    }
}
