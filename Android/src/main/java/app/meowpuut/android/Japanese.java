package app.meowpuut.android;

import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Japanese {
    private static final Map<String, String> KANA = kana();
    private final JSONObject words;
    private final List<String> choices = new ArrayList<>();
    String raw = "";
    String preedit = "";
    String commit = "";
    int page;

    Japanese(AssetManager assets) {
        JSONObject dictionary;
        try (InputStream stream = assets.open("native-japanese.json");
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            dictionary = new JSONObject(bytes.toString("UTF-8"));
        } catch (Exception error) {
            dictionary = new JSONObject();
        }
        words = dictionary;
    }

    void clear() {
        raw = preedit = commit = "";
        choices.clear();
        page = 0;
    }

    boolean key(int value) {
        if (value == 0xff08 && !raw.isEmpty()) {
            raw = raw.substring(0, raw.length() - 1);
        } else if ((value == 0xff0d || value == ' ') && !raw.isEmpty()) {
            if (choices.isEmpty()) {
                String text = raw;
                String previous = commit;
                clear();
                commit = previous + text;
            } else choose(page * 7);
            return true;
        } else if (value == 0xff55 || value == 0xff56) {
            page = Math.max(0, Math.min(Math.max(0, (choices.size() - 1) / 7),
                    page + (value == 0xff55 ? -1 : 1)));
            return !raw.isEmpty();
        } else if (value >= 'a' && value <= 'z' || value == '-' || value == '\'') {
            if (raw.length() >= 80) {
                // Preserve the original reading before accepting another key.
                commit += raw;
                raw = "";
            }
            raw += (char) value;
        } else return false;

        choices.clear();
        preedit = reading(raw);
        JSONArray entries = words.optJSONArray(raw);
        if (entries != null) for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry != null) add(entry.optString("text"));
        }
        if (!preedit.isEmpty()) {
            add(preedit);
            StringBuilder katakana = new StringBuilder();
            for (char kana : preedit.toCharArray()) {
                katakana.append(kana >= 'ぁ' && kana <= 'ゖ' ? (char) (kana + 0x60) : kana);
            }
            add(katakana.toString());
        }
        page = 0;
        return true;
    }

    private void add(String text) {
        if (!text.isEmpty() && !choices.contains(text)) choices.add(text);
    }

    int count() { return choices.size(); }
    String candidate(int index) { return choices.get(index); }

    void choose(int index) {
        if (index >= choices.size()) return;
        String selected = choices.get(index);
        String previous = commit;
        clear();
        commit = previous + selected;
    }

    String takeCommit() {
        String result = commit;
        commit = "";
        return result;
    }

    boolean hasWord(String raw) {
        return words.optJSONArray(raw) != null;
    }

    List<String> segmentChoices(String raw) {
        List<String> result = new ArrayList<>();
        JSONArray entries = words.optJSONArray(raw);
        if (entries == null || entries.optJSONObject(0) == null) return result;
        result.add(entries.optJSONObject(0).optString("text"));
        String kana = reading(raw);
        if (!result.contains(kana)) result.add(kana);
        StringBuilder katakana = new StringBuilder();
        for (char character : kana.toCharArray()) {
            katakana.append(character >= 'ぁ' && character <= 'ゖ' ? (char) (character + 0x60) : character);
        }
        if (!result.contains(katakana.toString())) result.add(katakana.toString());
        return result;
    }

    private static String reading(String input) {
        StringBuilder result = new StringBuilder();
        int position = 0;
        while (position < input.length()) {
            char key = input.charAt(position);
            char next = position + 1 < input.length() ? input.charAt(position + 1) : 0;
            if (key == '-' || key == '\'') {
                if (key == '-') result.append('ー');
                position++;
                continue;
            }
            if (key == 'n' && "aiueoy".indexOf(next) < 0) {
                result.append('ん');
                position += next == '\'' || (next == 'n' && position + 2 == input.length()) ? 2 : 1;
                continue;
            }
            if (key == next && "aiueon".indexOf(key) < 0) {
                result.append('っ');
                position++;
                continue;
            }
            int length = Math.min(4, input.length() - position);
            String token = KANA.get(input.substring(position, position + length));
            while (length > 1 && token == null) {
                length--;
                token = KANA.get(input.substring(position, position + length));
            }
            result.append(token == null ? String.valueOf(key) : token);
            position += length;
        }
        return result.toString();
    }

    private static Map<String, String> kana() {
        Map<String, String> table = new HashMap<>();
        String[] rows = {"a i u e o|あ い う え お", "ka ki ku ke ko|か き く け こ",
                "sa shi su se so|さ し す せ そ", "ta chi tsu te to|た ち つ て と",
                "na ni nu ne no|な に ぬ ね の", "ha hi fu he ho|は ひ ふ へ ほ",
                "ma mi mu me mo|ま み む め も", "ya yu yo|や ゆ よ", "ra ri ru re ro|ら り る れ ろ",
                "wa wo|わ を", "ga gi gu ge go|が ぎ ぐ げ ご", "za ji zu ze zo|ざ じ ず ぜ ぞ",
                "da di du de do|だ ぢ づ で ど", "ba bi bu be bo|ば び ぶ べ ぼ",
                "pa pi pu pe po|ぱ ぴ ぷ ぺ ぽ", "va vi vu ve vo|ゔぁ ゔぃ ゔ ゔぇ ゔぉ",
                "xa xi xu xe xo xya xyu xyo xtsu xwa|ぁ ぃ ぅ ぇ ぉ ゃ ゅ ょ っ ゎ",
                "fa fi fe fo wi we who she che thi dhi twu dwu fyu ye|ふぁ ふぃ ふぇ ふぉ うぃ うぇ うぉ しぇ ちぇ てぃ でぃ とぅ どぅ ふゅ いぇ"};
        for (String row : rows) {
            String[] pair = row.split("\\|");
            String[] keys = pair[0].split(" ");
            String[] values = pair[1].split(" ");
            for (int i = 0; i < keys.length; i++) table.put(keys[i], values[i]);
        }
        String[] prefixes = {"ky", "gy", "sh", "ch", "ny", "hy", "by", "py", "my", "ry", "j"};
        String bases = "きぎしちにひびぴみりじ";
        for (int i = 0; i < prefixes.length; i++) {
            for (int j = 0; j < 4; j++) {
                table.put(prefixes[i] + "auoe".charAt(j), "" + bases.charAt(i) + "ゃゅょぇ".charAt(j));
            }
        }
        String[] aliases = {"si shi", "ti chi", "tu tsu", "hu fu", "zi ji", "sya sha", "syu shu", "syo sho",
                "tya cha", "tyu chu", "tyo cho", "cya cha", "cyu chu", "cyo cho", "zya ja", "zyu ju",
                "zyo jo", "jya ja", "jyu ju", "jyo jo", "xtu xtsu"};
        for (String alias : aliases) {
            String[] parts = alias.split(" ");
            table.put(parts[0], table.get(parts[1]));
        }
        for (String key : new ArrayList<>(table.keySet())) if (key.startsWith("x")) {
            table.put("l" + key.substring(1), table.get(key));
        }
        return table;
    }
}
