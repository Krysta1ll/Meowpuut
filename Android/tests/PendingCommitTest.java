package app.meowpuut.android;

public final class PendingCommitTest {
    public static void main(String[] args) {
        PendingCommit pending = new PendingCommit();
        StringBuilder editor = new StringBuilder();
        PendingCommit.Destination accept = text -> { editor.append(text); return true; };
        pending.append("我喜欢");
        assert !pending.retry(null); // temporarily detached connection
        assert !pending.retry(text -> false); // host rejects insertion
        assert !pending.isEmpty();
        assert editor.length() == 0;
        assert pending.retry(accept);
        assert pending.isEmpty();
        assert pending.retry(accept); // another refresh never duplicates the prefix
        assert editor.toString().equals("我喜欢");
        pending.append("寿司");
        pending.append("🍣");
        assert !pending.retry(text -> false);
        assert pending.retry(accept);
        assert editor.toString().equals("我喜欢寿司🍣");
        pending.append("已选前段");
        pending.pause(); // cursor moved after a rejected commit
        assert pending.isPaused();
        assert !pending.retry(accept); // never send over the newly selected range
        assert editor.toString().equals("我喜欢寿司🍣");
        pending.resume(); // explicit retry at the current cursor
        assert pending.retry(accept);
        assert editor.toString().equals("我喜欢寿司🍣已选前段");
        pending.append("old editor");
        pending.pause();
        pending.clear(); // a new editor must never receive another editor's text
        assert pending.retry(accept);
        assert !pending.isPaused();
        assert editor.toString().equals("我喜欢寿司🍣已选前段");
        System.out.println("Pending commit retry, order, Unicode and editor isolation passed");
    }
}
