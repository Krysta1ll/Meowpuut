package app.meowpuut.android;

import java.util.List;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/** Presentation ordering only; original candidate identities stay intact. */
final class PinyinCandidatePolicy {
    private PinyinCandidatePolicy() {}

    // Explicit English words, aligned with the shared Apple-side evidence.
    // No length-only guess: a valid exact Chinese or recorded Japanese word wins.
    private static final Set<String> ENGLISH = new HashSet<>(Arrays.asList(
            "hello", "world", "this", "that", "these", "those", "please", "thanks", "thank",
            "your", "with", "without", "learning", "learned", "beautiful", "important", "amazing",
            "wonderful", "emoji", "emojis", "email", "emails", "emoticon", "emoticons", "markdown", "screenshot",
            "screenshots", "configuration", "compatibility", "using", "testing", "search", "music",
            "download", "update", "install", "settings", "keyboard", "input", "method", "candidate",
            "candidates", "development", "developer", "programming", "program", "application",
            "applications", "information", "international", "internationalization", "performance",
            "optimization", "computer", "computers", "macos", "windows", "iphone", "internet",
            "website", "browser", "code", "swift", "python", "javascript", "typescript", "function",
            "return", "const", "import", "class", "string", "github", "codex", "openai", "meowpuut",
            "good", "morning", "night", "today", "tomorrow", "really", "working", "work", "works",
            "example", "examples", "english", "sentence", "sentences", "word", "words"));

    static boolean isKnownEnglish(String raw) { return ENGLISH.contains(raw); }

    static <T> void preferKnownEnglish(String raw, boolean exactChinese, boolean japanese,
                                       List<T> choices, Function<T, String> text) {
        if (exactChinese || japanese || !isKnownEnglish(raw)) return;
        for (int i = 0; i < choices.size(); i++) {
            if (raw.equals(text.apply(choices.get(i)))) {
                if (i != 0) choices.add(0, choices.remove(i));
                return;
            }
        }
    }

    static <T> void preferChineseForInitial(String raw, int literalTail, List<T> choices,
                                             Function<T, String> text) {
        if (raw.length() != 1 || raw.charAt(0) < 'a' || raw.charAt(0) > 'z'
                || literalTail != 1 || choices.size() < 2
                || !raw.equals(text.apply(choices.get(0)))) return;
        int lastChinese = 0;
        for (int i = 1; i < choices.size() && lastChinese < 3; i++) {
            String candidate = text.apply(choices.get(i));
            if (candidate.isEmpty() || Character.UnicodeScript.of(candidate.codePointAt(0)) != Character.UnicodeScript.HAN)
                break;
            lastChinese = i;
        }
        if (lastChinese > 0) choices.add(lastChinese, choices.remove(0));
    }
}
