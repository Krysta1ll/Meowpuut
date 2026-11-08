package app.meowpuut.android;

public final class JapaneseOverflowTest {
    public static void main(String[] args) {
        // The host test supplies an empty dictionary and exercises real key handling.
        Japanese keyboard = new Japanese(null);
        StringBuilder delivered = new StringBuilder();
        for (int i = 0; i < 161; i++) {
            if (!keyboard.key('a')) throw new AssertionError("key rejected at " + i);
            String commit = keyboard.takeCommit();
            if (!commit.isEmpty() && commit.length() != 80) throw new AssertionError(commit);
            delivered.append(commit);
        }
        if (delivered.length() != 160 || !keyboard.raw.equals("a")) {
            throw new AssertionError("lost input at the limit");
        }
        if (!keyboard.key(0xff0d) || !keyboard.takeCommit().equals("あ") || !keyboard.raw.isEmpty()) {
            throw new AssertionError("could not select the remaining reading");
        }
        Japanese buffered = new Japanese(null);
        for (int i = 0; i < 161; i++) buffered.key('a');
        if (!buffered.key(0xff0d) || !buffered.takeCommit().equals("a".repeat(160) + "あ")) {
            throw new AssertionError("selection discarded undelivered raw input");
        }
        System.out.println("PASS: Japanese keeps raw input and accepts keys past repeated limits");
    }
}
