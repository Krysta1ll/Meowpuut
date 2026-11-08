package app.meowpuut.android;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PinyinCandidatePolicyTest {
    private static final class Choice {
        final String text;
        final int engineIndex;
        Choice(String text, int engineIndex) { this.text = text; this.engineIndex = engineIndex; }
    }

    public static void main(String[] args) {
        List<Choice> choices = new ArrayList<>(Arrays.asList(new Choice("d", 0), new Choice("的", 1),
                new Choice("到", 2), new Choice("都", 3), new Choice("等", 4)));
        PinyinCandidatePolicy.preferChineseForInitial("d", 1, choices, choice -> choice.text);
        assert choices.get(0).text.equals("的") && choices.get(0).engineIndex == 1;
        assert choices.get(1).text.equals("到") && choices.get(2).text.equals("都");
        assert choices.get(3).text.equals("d") && choices.get(3).engineIndex == 0;

        List<String> rawOnly = new ArrayList<>(Arrays.asList("v"));
        PinyinCandidatePolicy.preferChineseForInitial("v", 1, rawOnly, x -> x);
        assert rawOnly.equals(Arrays.asList("v"));
        List<String> english = new ArrayList<>(Arrays.asList("emoji", "恶魔及"));
        PinyinCandidatePolicy.preferChineseForInitial("emoji", 5, english, x -> x);
        assert english.get(0).equals("emoji");
        List<String> partial = new ArrayList<>(Arrays.asList("你好x", "你好"));
        PinyinCandidatePolicy.preferChineseForInitial("nihaox", 1, partial, x -> x);
        assert partial.get(0).equals("你好x");
        List<String> fullySpelled = new ArrayList<>(Arrays.asList("啊", "a"));
        PinyinCandidatePolicy.preferChineseForInitial("a", 0, fullySpelled, x -> x);
        assert fullySpelled.get(0).equals("啊");
        List<Choice> englishChoices = new ArrayList<>(Arrays.asList(new Choice("恶魔及", 0),
                new Choice("恶魔即", 1), new Choice("emoji", 34)));
        PinyinCandidatePolicy.preferKnownEnglish("emoji", false, false, englishChoices, choice -> choice.text);
        assert englishChoices.get(0).text.equals("emoji") && englishChoices.get(0).engineIndex == 34;
        assert englishChoices.get(1).text.equals("恶魔及");
        for (String word : Arrays.asList("email", "emails")) {
            List<Choice> email = new ArrayList<>(Arrays.asList(new Choice("拼音候选", 0), new Choice(word, 29)));
            PinyinCandidatePolicy.preferKnownEnglish(word, false, false, email, choice -> choice.text);
            assert email.get(0).text.equals(word) && email.get(0).engineIndex == 29;
        }
        List<String> chinese = new ArrayList<>(Arrays.asList("中文", "word"));
        PinyinCandidatePolicy.preferKnownEnglish("word", true, false, chinese, x -> x);
        assert chinese.get(0).equals("中文");
        PinyinCandidatePolicy.preferKnownEnglish("word", false, true, chinese, x -> x);
        assert chinese.get(0).equals("中文");
        List<String> sushi = new ArrayList<>(Arrays.asList("苏轼", "sushi"));
        PinyinCandidatePolicy.preferKnownEnglish("sushi", false, false, sushi, x -> x);
        assert sushi.get(0).equals("苏轼");
        List<String> unknown = new ArrayList<>(Arrays.asList("你好", "nihao"));
        PinyinCandidatePolicy.preferKnownEnglish("nihao", false, false, unknown, x -> x);
        assert unknown.get(0).equals("你好");
        System.out.println("PASS: Chinese initials lead while raw letters and candidate identities remain selectable");
    }
}
