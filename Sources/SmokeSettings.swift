import Foundation

/// The settings file (Sources/SettingsStore.swift). Every check uses its own temporary folder.
func runSettingsSmoke(_ h: inout SmokeHarness) {
    var made: [URL] = []
    func fresh() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Meowpuut-Settings-\(UUID().uuidString)")
        made.append(url); return url
    }
    defer { for url in made { try? FileManager.default.removeItem(at: url) } }
    func file(_ directory: URL) -> URL { directory.appendingPathComponent(SettingsStore.fileName) }
    func replaceFile(_ directory: URL, _ settings: [String: Any]) {
        // What another process does: a temporary file renamed over the file.
        let data = try? JSONSerialization.data(withJSONObject: ["schema": 1, "settings": settings])
        let temporary = directory.appendingPathComponent("other.tmp")
        try? data?.write(to: temporary)
        _ = rename(temporary.path, file(directory).path)
    }
    func spin(_ seconds: TimeInterval, until done: () -> Bool) {
        let end = Date().addingTimeInterval(seconds)
        while !done() && Date() < end { RunLoop.current.run(until: Date().addingTimeInterval(0.02)) }
    }

    // Migration: the old keys are copied once; the file then wins; an unknown key stays behind.
    let a = fresh()
    let legacy: [String: Any] = ["simplified": true, "inputMode": "pinyin", "keySoundVolume": 40, "festivalsSkipped": ["spring"],
                                 "candidatePins": ["ni": "你"], "mixJapanese": false, "unrelated": "x"]
    let keys = ["simplified", "inputMode", "keySoundVolume", "festivalsSkipped", "candidatePins", "mixJapanese", "catGlassSkin"]
    let first = SettingsStore(directory: a, legacy: legacy, keys: keys, watch: false)
    h.check(FileManager.default.fileExists(atPath: file(a).path), "設定檔在首次啟動時建立")
    h.check(first.bool(forKey: "simplified") && first.string(forKey: "inputMode") == "pinyin" && first.object(forKey: "keySoundVolume") as? Int == 40
            && first.stringArray(forKey: "festivalsSkipped") == ["spring"] && first.object(forKey: "mixJapanese") as? Bool == false,
            "舊設定的每個鍵搬進設定檔")
    h.check(first.object(forKey: "unrelated") == nil && first.object(forKey: "catGlassSkin") == nil, "未知的鍵不搬、沒有的鍵不憑空出現")
    let before = try? Data(contentsOf: file(a))
    let second = SettingsStore(directory: a, legacy: ["simplified": false, "inputMode": "zhuyin"], keys: keys, watch: false)
    h.check(second.bool(forKey: "simplified") && second.string(forKey: "inputMode") == "pinyin" && (try? Data(contentsOf: file(a))) == before,
            "搬移只做一次：檔案存在後不再從舊設定複製")

    // A change is held, then written once; a key another process wrote is not overwritten by an unrelated change.
    first.set("light", forKey: "candidateTone")
    h.check(SettingsStore.read(file(a))?["candidateTone"] == nil, "改動在延遲前不寫檔")
    first.set("light", forKey: "candidateTone")
    replaceFile(a, ["simplified": true, "inputMode": "pinyin", "glassStrength": "vivid", "candidatePins": ["ni": "你"]])
    first.flush()
    let merged = SettingsStore.read(file(a)) ?? [:]
    h.check(merged["candidateTone"] as? String == "light" && merged["glassStrength"] as? String == "vivid", "寫檔只改本程序動過的鍵，保留別的程序寫的鍵")
    h.check(!FileManager.default.fileExists(atPath: a.appendingPathComponent("other.tmp").path)
            && ((try? FileManager.default.contentsOfDirectory(atPath: a.path)) ?? []).allSatisfy { !$0.contains(".tmp") }, "寫檔不留暫存檔")

    // Another process changes the file: the directory watch, the notification and the explicit check each take it in.
    let b = fresh()
    let store = SettingsStore(directory: b, delay: 0.05)
    var signals = 0
    store.onExternalChange = { signals += 1 }
    store.set("tap", forKey: "keySound"); store.flush()
    h.check(signals == 0 && !store.refreshIfChanged(), "自己寫的檔不會當成外部變更")
    replaceFile(b, ["keySound": "tap", "simplified": true])
    spin(3) { signals > 0 }
    h.check(signals == 1 && store.bool(forKey: "simplified"), "另一個程序寫入設定檔後，目錄監看讓輸入法重讀")
    replaceFile(b, ["keySound": "tap", "simplified": false, "catPlacement": "x"])
    DistributedNotificationCenter.default().postNotificationName(SettingsStore.changedNotification, object: nil, userInfo: nil, deliverImmediately: true)
    spin(3) { !store.bool(forKey: "simplified") }
    h.check(!store.bool(forKey: "simplified") && store.string(forKey: "catPlacement") == "x", "收到不帶內容的通知後重讀")
    store.set("a", forKey: "catPlacement")
    replaceFile(b, ["keySound": "tap", "keySoundVolume": 10])
    store.refreshIfChanged(); store.flush()
    h.check(store.object(forKey: "keySoundVolume") as? Int == 10 && SettingsStore.read(file(b))?["catPlacement"] as? String == "a",
            "尚未寫出的本機改動在重讀時保留")

    // Corrupt, wrong shape and missing files: defaults, no crash, and the values in memory stay.
    let c = fresh()
    try? FileManager.default.createDirectory(at: c, withIntermediateDirectories: true)
    for garbage in ["{not json", "[1,2]", "{\"settings\": 3}", ""] {
        try? Data(garbage.utf8).write(to: file(c))
        let broken = SettingsStore(directory: c, legacy: ["simplified": true], keys: ["simplified"], watch: false)
        h.check(broken.allKeys.isEmpty && !broken.bool(forKey: "simplified") && broken.string(forKey: "inputMode") == nil, "壞檔「\(garbage)」讀作預設值")
    }
    let live = SettingsStore(directory: c, watch: false)
    live.set("pinyin", forKey: "inputMode"); live.flush()
    try? Data("{oops".utf8).write(to: file(c))
    h.check(!live.refreshIfChanged() && live.string(forKey: "inputMode") == "pinyin", "執行中檔案損壞時保留記憶中的設定")
    try? FileManager.default.removeItem(at: file(c))
    h.check(!live.refreshIfChanged() && live.string(forKey: "inputMode") == "pinyin", "執行中檔案消失時保留記憶中的設定")
    live.set("zhuyin", forKey: "inputMode"); live.flush()
    h.check(SettingsStore.read(file(c))?["inputMode"] as? String == "zhuyin", "檔案消失或損壞後下一次寫入會重建")

    // The real Preferences read through the file: a value written by another process reaches the published setting.
    let preferences = Preferences.shared
    preferences.flushSettings()
    var current = SettingsStore.read(file(Preferences.directory)) ?? [:]
    h.check(current["simplified"] != nil && current["inputMode"] != nil, "Preferences 的設定存在設定檔裡")
    var notified = false
    let token = NotificationCenter.default.addObserver(forName: Preferences.externalChange, object: nil, queue: nil) { _ in notified = true }
    let was = preferences.simplified
    current["simplified"] = !was
    replaceFile(Preferences.directory, current)
    spin(3) { notified }
    h.check(notified && preferences.simplified == !was, "外部改檔後 Preferences 的設定跟著變")
    NotificationCenter.default.removeObserver(token)
    preferences.simplified = was
    preferences.flushSettings()

    // Nothing typed is in the file: only known setting names, and the only dictionary is the pins.
    preferences.pins = ["ni": "你"]
    preferences.flushSettings()
    let all = SettingsStore.read(file(Preferences.directory)) ?? [:]
    let known: Set<String> = ["simplified", "inputMode", "modulesEnabled", "rememberChoices", "catGlassSkin", "catPlacement", "candidateTone",
        "candidateTextSize", "candidateWindowSize", "glassStrength", "effectsLevel", "keySound", "keySoundVolume", "candidatePalette", "glassShape",
        "festivalOutfits", "festivalsSkipped", "keyboardBackend", "candidatePins", "mixJapanese"]
    h.check(Set(all.keys).isSubset(of: known), "設定檔只有已知的設定名稱")
    h.check(all.filter { $0.value is [String: Any] }.map(\.key) == ["candidatePins"], "設定檔裡唯一的字典是使用者釘選的字")
    let text = (try? String(contentsOf: file(Preferences.directory), encoding: .utf8)) ?? ""
    h.check(!text.contains("你好"), "設定檔不含輸入過的文字")
    preferences.pins = [:]
    preferences.flushSettings()
}
