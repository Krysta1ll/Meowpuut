package app.meowpuut.android;

/** Text removed from the engine remains owned here until the current editor accepts it. */
final class PendingCommit {
    interface Destination { boolean commit(String text); }
    private String text = "";
    private boolean paused;

    boolean isEmpty() { return text.isEmpty(); }
    void append(String value) { text += value; }
    void clear() { text = ""; paused = false; }
    boolean isPaused() { return paused; }
    void pause() { if (!text.isEmpty()) paused = true; }
    void resume() { paused = false; }

    boolean retry(Destination destination) {
        if (text.isEmpty()) return true;
        if (paused) return false;
        if (destination == null || !destination.commit(text)) return false;
        text = "";
        return true;
    }
}
