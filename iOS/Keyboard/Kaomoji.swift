import Foundation

/// The list behind the ^_^ key: the groups of Resources/Lexicon/kaomoji.tsv
/// and the kaomoji typed from the list most recently.
enum Kaomoji {
    struct Group: Equatable {
        var name: String
        var simplifiedName: String
        var items: [String]
    }

    /// Rows are `group, kaomoji, words`. A row without a kaomoji only carries
    /// words; the first one of a group may name it as Traditional|Simplified.
    static func parse(_ text: String) -> [Group] {
        var groups: [Group] = []
        for line in text.split(separator: "\n") where !line.hasPrefix("#") {
            let fields = line.split(separator: "\t", omittingEmptySubsequences: false)
            let names = fields[0].split(separator: "|").map(String.init)
            guard let name = names.first else { continue }
            if groups.last?.name != name { groups.append(Group(name: name, simplifiedName: names.last ?? name, items: [])) }
            if fields.count > 1, !fields[1].isEmpty { groups[groups.count - 1].items.append(String(fields[1])) }
        }
        return groups
    }

    static let groups: [Group] = Bundle.main.url(forResource: "kaomoji", withExtension: "tsv")
        .flatMap { try? String(contentsOf: $0, encoding: .utf8) }.map(parse) ?? []

    private static let groupOf: [String: Int] = {
        var result: [String: Int] = [:]
        for (index, group) in groups.enumerated() {
            for item in group.items where result[item] == nil { result[item] = index }
        }
        return result
    }()

    static func contains(_ text: String) -> Bool { groupOf[text] != nil }

    /// What VoiceOver says for a kaomoji: its group, since the characters
    /// themselves read as noise. Nil for anything that is not in the list.
    static func spokenName(_ text: String, simplified: Bool) -> String? {
        guard let group = groupOf[text].map({ groups[$0] }) else { return nil }
        return (simplified ? "颜文字：" + group.simplifiedName : "顏文字：" + group.name)
    }

    // Kept in the keyboard's own defaults: without Full Access nothing else is writable.
    private static let recentKey = "recentKaomoji"
    static let recentLimit = 24

    static var recent: [String] { UserDefaults.standard.stringArray(forKey: recentKey) ?? [] }

    /// A kaomoji typed from the list leads it next time. Nothing is recorded
    /// while 記住選詞 is off.
    static func used(_ text: String) {
        guard Preferences.shared.rememberChoices else { return }
        UserDefaults.standard.set(Array(([text] + recent.filter { $0 != text }).prefix(recentLimit)), forKey: recentKey)
    }

    static func forgetRecent() { UserDefaults.standard.removeObject(forKey: recentKey) }

    /// The candidate row after the ^_^ key: the recent ones, then the first of every group.
    static func row(recent: [String]) -> [String] {
        var seen = Set<String>()
        return (recent + groups.compactMap(\.items.first)).filter { seen.insert($0).inserted }
    }
}
