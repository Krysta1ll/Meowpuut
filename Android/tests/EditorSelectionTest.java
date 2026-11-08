package app.meowpuut.android;

public final class EditorSelectionTest {
    public static void main(String[] args) {
        EditorSelection editor = new EditorSelection();
        editor.reset(3, 3);
        editor.composing("nihao");
        editor.committed("你好");
        editor.composing("x");
        // Queued notifications may arrive after later writes have succeeded.
        assert !editor.external(8, 8, 3, 8);
        assert !editor.external(5, 5, -1, -1);
        assert !editor.external(6, 6, 5, 6);
        assert !editor.external(6, 6, 5, 6);
        // External deletion also clears the composing range: do not resurrect it.
        assert editor.external(0, 0, -1, -1);
        editor.composing("wo");
        assert !editor.external(2, 2, 0, 2);
        // Moving/selecting elsewhere is external even if marked text remains.
        assert editor.external(0, 1, 0, 2);
        editor.finished();
        assert !editor.external(0, 1, -1, -1);
        editor.committed("🐈");
        assert !editor.external(2, 2, -1, -1); // Android selection is UTF-16
        editor.composing("好");
        assert !editor.external(3, 3, 2, 3);
        // A host can coalesce all writes to the final callback.
        editor.reset(0, 0);
        editor.composing("n"); editor.composing("ni"); editor.composing("nihao");
        assert !editor.external(5, 5, 0, 5);
        assert editor.external(5, 5, -1, -1); // host ended marked range without moving
        editor.reset(9, 9);
        assert editor.external(0, 0, -1, -1); // new editor has independent ownership
        editor.reset(-1, -1);
        editor.composing("n"); editor.composing("ni");
        assert !editor.external(11, 11, 10, 11);
        assert !editor.external(12, 12, 10, 12);
        editor.composing("nihao");
        assert !editor.external(15, 15, 10, 15);
        assert editor.external(8, 8, -1, -1);
        System.out.println("PASS: delayed/coalesced selection callbacks, external edits and UTF-16 ranges");
    }
}
