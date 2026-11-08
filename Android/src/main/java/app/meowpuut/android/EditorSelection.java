package app.meowpuut.android;

import java.util.ArrayDeque;

/** Matches delayed editor callbacks against accepted writes, not the latest key. */
final class EditorSelection {
    private static final class Range {
        final int start, end, markedStart, markedEnd;
        Range(int start, int end, int markedStart, int markedEnd) {
            this.start = start; this.end = end;
            this.markedStart = markedStart; this.markedEnd = markedEnd;
        }
        boolean matches(Range other) {
            if (start < 0 && end < 0) {
                if (other.start < 0 || other.start != other.end) return false;
                return markedStart == -2
                        ? other.markedStart >= 0 && other.markedEnd - other.markedStart == markedEnd
                            && other.end == other.markedEnd
                        : other.markedStart == -1 && other.markedEnd == -1;
            }
            return start == other.start && end == other.end
                    && markedStart == other.markedStart && markedEnd == other.markedEnd;
        }
    }
    private Range current = new Range(-1, -1, -1, -1);
    private final ArrayDeque<Range> expected = new ArrayDeque<>();

    void reset(int start, int end) {
        current = new Range(start, end, -1, -1);
        expected.clear();
    }
    void committed(String text) { replace(text.length(), false); }
    void composing(CharSequence text) { replace(text.length(), true); }
    void finished() {
        record(new Range(current.start, current.end, -1, -1));
    }
    private void replace(int length, boolean marked) {
        int start = current.markedStart >= 0 ? current.markedStart : Math.min(current.start, current.end);
        if (start < 0) {
            // Some hosts supply initialSelStart=-1. Match the first accepted
            // write by its marked length, then learn the actual cursor anchor.
            record(new Range(-1, -1, marked ? -2 : -1, marked ? length : -1));
            return;
        }
        record(new Range(start + length, start + length, marked ? start : -1, marked ? start + length : -1));
    }
    private void record(Range next) {
        current = next;
        expected.addLast(next);
        // Android normally batches these callbacks; cap history for broken hosts.
        if (expected.size() > 32) expected.removeFirst();
    }
    boolean external(int start, int end, int markedStart, int markedEnd) {
        Range actual = new Range(start, end, markedStart, markedEnd);
        for (Range next : expected) {
            if (next.matches(actual)) {
                // Intermediate callbacks must not roll back a newer accepted write.
                while (expected.removeFirst() != next) { }
                if (expected.isEmpty()) current = actual;
                return false;
            }
        }
        boolean changed = !actual.matches(current);
        current = actual;
        expected.clear();
        return changed;
    }
}
