import Foundation

// Keep selected segments and the remaining reading in one marked range until
// the composition ends. This avoids unmarking and immediately remarking text
// in the host's separate process, where the latter can replace the selection.
struct CompositionDelivery {
    enum Action: Equatable {
        case mark(String)
        case unmark
        case insert(String)
    }

    private(set) var hasMarkedText = false
    private var selectedPrefix = ""
    /// The last string this keyboard put before the caret: the marked text,
    /// or the composition it finished.
    private(set) var shown = ""

    private mutating func note(_ text: String) {
        if !text.isEmpty { shown = text }
    }

    /// Whether the text before the caret still ends with the marked text. The
    /// host may cut the context short, so a context that is only its tail
    /// counts too. An earlier string does not: after a tap between two words
    /// the text before the caret can end with an older commit. A report that
    /// arrives late, right after a key, is left to the controller's wait.
    func agrees(withContextBefore before: String) -> Bool {
        guard !before.isEmpty, !shown.isEmpty else { return false }
        return before.hasSuffix(shown) || shown.hasSuffix(before)
    }

    mutating func actions(commit: String, preedit: String) -> [Action] {
        selectedPrefix += commit
        if !preedit.isEmpty {
            hasMarkedText = true
            note(selectedPrefix + preedit)
            return [.mark(selectedPrefix + preedit)]
        }
        let completed = selectedPrefix
        selectedPrefix = ""
        note(completed)
        if hasMarkedText {
            hasMarkedText = false
            return [.mark(completed), .unmark]
        }
        return completed.isEmpty ? [] : [.insert(completed)]
    }

    mutating func reset() {
        selectedPrefix = ""
        hasMarkedText = false
        shown = ""
    }
}

/// The reading as the host shows it, split into syllables the way Apple's
/// keyboards do: pinyin with U+2006 ("ni hao"), zhuyin with U+2004
/// ("ㄋㄧˇ ㄏㄠˇ"). Only the marked text changes; the engine keeps the raw keys.
enum SyllableBreaks {
    static func pinyin(_ reading: String) -> String {
        // NIHAO and iPhone stay as typed, as on Apple's keyboard.
        guard reading.dropFirst().allSatisfy({ !$0.isUppercase }) else { return reading }
        var out = "", run = "", afterSyllable = false
        func flush() -> Bool {
            defer { run = "" }
            guard !run.isEmpty else { return true }
            guard let lengths = split(run.lowercased()) else { return false }
            var rest = Substring(run)
            for length in lengths {
                if afterSyllable { out += "\u{2006}" }
                out += rest.prefix(length); rest = rest.dropFirst(length)
                afterSyllable = true
            }
            return true
        }
        for ch in reading {
            if ch.isASCII && ch.isLetter { run.append(ch); continue }
            guard flush() else { return reading }
            out.append(ch)
            // A tone digit ends its syllable (ni3 hao); an apostrophe is the person's own break.
            afterSyllable = ch.isNumber
        }
        return flush() ? out : reading
    }

    /// Syllable lengths covering the letters: whole syllables, the last one may
    /// be unfinished (ni h). Fewest syllables; on a tie the earlier syllable is
    /// the longer one (fang an, not fan gan). nil when the letters are not pinyin.
    private static func split(_ letters: String) -> [Int]? {
        let chars = Array(letters)
        let n = chars.count
        var best = [Double](repeating: .infinity, count: n + 1), from = [Int](repeating: -1, count: n + 1)
        best[0] = 0
        for end in 1...n {
            for start in stride(from: end - 1, through: max(0, end - 6), by: -1) where best[start] < .infinity {
                let piece = String(chars[start..<end])
                let cost: Double = syllables.contains(piece) ? 1 : (end == n && prefixes.contains(piece) ? 1.5 : .infinity)
                if best[start] + cost < best[end] { best[end] = best[start] + cost; from[end] = start }
            }
        }
        guard best[n] < .infinity else { return nil }
        var lengths: [Int] = [], end = n
        while end > 0 { lengths.insert(end - from[end], at: 0); end = from[end] }
        return lengths
    }

    static func zhuyin(_ reading: String) -> String {
        let tones: Set<Character> = ["ˉ", "ˊ", "ˇ", "ˋ", "˙"]
        func kind(_ ch: Character) -> Int {  // 1 initial, 2 medial, 3 final, 4 tone, 0 other
            if tones.contains(ch) { return 4 }
            guard let v = ch.unicodeScalars.first?.value else { return 0 }
            switch v {
            case 0x3105...0x3119: return 1
            case 0x3127...0x3129: return 2
            case 0x311A...0x3126: return 3
            default: return 0
            }
        }
        var out = ""
        var previous = 0
        for ch in reading {
            let k = kind(ch)
            if (previous == 4 && k != 0) || (k == 1 && previous != 0) || (k == 2 && previous == 3) { out += "\u{2004}" }
            out.append(ch)
            previous = k
        }
        return out
    }

    private static let syllables: Set<String> = Set("""
        a ai an ang ao e ei en eng er o ou yi ya yao yan yang ye yin ying yo yong you yu yue yuan yun wu wa wai wan wang wei wen weng wo \
        ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu \
        ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu fa fan fang fei fen feng fo fou fu \
        da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo \
        ta tai tan tang tao te tei teng ti tian tiao tie ting tong tou tu tuan tui tun tuo \
        na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nuan nun nuo nv nve nue \
        la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lun luo lv lve lue \
        ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo \
        ka kai kan kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun kuo \
        ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo \
        ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun \
        xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun \
        zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo \
        cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo \
        sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo \
        ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo za zai zan zang zao ze zei zen zeng zi zong zou zu zuan zui zun zuo \
        ca cai can cang cao ce cen ceng ci cong cou cu cuan cui cun cuo sa sai san sang sao se sen seng si song sou su suan sui sun suo
        """.split(separator: " ").map(String.init))
    private static let prefixes: Set<String> = Set(syllables.flatMap { s in (1..<s.count).map { String(s.prefix($0)) } })
}
