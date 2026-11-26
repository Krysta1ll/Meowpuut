import Foundation

/// The word-count ledger (Sources/PetLedger.swift). Every check uses its own temporary folder.
func runLedgerSmoke(_ h: inout SmokeHarness) {
    func folder() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("Meowpuut-Ledger-\(UUID().uuidString)")
    }
    let file = { (directory: URL) in directory.appendingPathComponent(PetLedger.fileName) }
    var made: [URL] = []
    func fresh() -> URL { let url = folder(); made.append(url); return url }
    defer { for url in made { try? FileManager.default.removeItem(at: url) } }

    // Counting graphemes, the file after a flush, and no write before the batch interval.
    let a = fresh()
    let ledger = PetLedger(directory: a)
    ledger.committed("你好"); ledger.committed("😊👍🏽"); ledger.committed("👨‍👩‍👧")
    h.check(!FileManager.default.fileExists(atPath: file(a).path), "打字紀錄在批次間隔前不寫檔")
    ledger.flush(wait: true)
    h.check(ledger.totalCharacters == 5, "打字紀錄按字形計數（組合表情算一個）")
    let reread = PetLedger(directory: a)
    h.check(reread.totalCharacters == 5 && reread.dayCount == 1, "打字紀錄重新載入後仍是 5 字、1 天")
    let bytes = (try? Data(contentsOf: file(a))) ?? Data()
    h.check(bytes.count == Int(MP_PETS_STATE_BYTES) && bytes.range(of: Data("你好".utf8)) == nil, "打字紀錄檔長度固定且不含文字")
    // The day's place, written with the flush: 5 characters in, the cats are still at the sea (place 1).
    h.check(reread.dayPlaces == [UInt8(mp_glyph_place_of_characters(5))] && mp_glyph_place_of_characters(5) == 1
            && bytes.count > 2 && bytes[2] == 2, "打字紀錄記下當天走到的地方（還在海邊）")
    let far = fresh()
    let walker = PetLedger(directory: far)
    walker.committed(String(repeating: "喵", count: 2600)); walker.flush(wait: true); walker.flush(wait: true)
    h.check(PetLedger(directory: far).dayPlaces == [UInt8(mp_glyph_place_of_characters(2600))], "走得遠的一天記下那天走到的地方")

    // Two processes' increments add.
    let b = fresh()
    let first = PetLedger(directory: b), second = PetLedger(directory: b)
    first.committed("你好嗎"); second.committed("再見")
    first.flush(wait: true); second.flush(wait: true)
    h.check(PetLedger(directory: b).totalCharacters == 5, "兩個程序的字數相加不遺失")

    // Clearing: the old numbers do not come back from a process that counted before.
    let c = fresh()
    let one = PetLedger(directory: c)
    one.committed("你好嗎"); one.flush(wait: true)
    let other = PetLedger(directory: c)
    other.committed("再見"); one.clear()
    h.check(one.totalCharacters == 0 && PetLedger(directory: c).totalCharacters == 0, "清除後紀錄歸零")
    other.flush(wait: true)
    h.check(PetLedger(directory: c).totalCharacters == 0 && other.totalCharacters == 0, "清除前計的數不會在之後的寫入中回來")
    let cleared = (try? Data(contentsOf: file(c))) ?? Data()
    h.check(PetLedger(directory: c).dayPlaces.isEmpty && cleared.count > 2 && cleared[2] == 1, "清除後也不留走到的地方")
}

/// The candidate glass and 喵撲貓窩's seats message (Sources/DeskPulse.swift): Mimimo leaves the glass only while
/// she is out on the desk in 乙; without 喵撲貓窩, or under --smoke, both cats stay.
func runDeskSeatsSmoke(_ h: inout SmokeHarness) {
    h.check(!DeskPulseSender.shared.enabled && GlassSeats.shared.desk == nil, "檢查時不連貓窩，候選玻璃上兩隻貓都在")
    var heard: [DeskSeats] = []
    let inbox = DeskSeatInbox { heard.append($0) }
    inbox.seats(DeskSeats(mimimo: true).data)
    inbox.seats(Data([0x9C, 0xB2, 1, 9]))
    inbox.seats(Data("米米莫".utf8))
    h.check(heard == [DeskSeats(mimimo: true)], "貓窩的座位訊息只收固定 4 位元組")
    let stage = Desk.stage(width: 360, shape: .capsule, chevron: true)
    guard let places = CatArrangement.named("ends")?.place(on: stage) else {
        h.check(false, "兩端排列放得下")
        return
    }
    let out = DeskSeats(mimimo: true)
    let seated = GlassSeats.seated(places, seats: out, typingSeat: nil)
    h.check(seated.map(\.cat) == [.momo] && seated.first == places.first { $0.cat == .momo }, "米米莫在桌面上時，候選玻璃只畫莫莫，莫莫位置不變")
    h.check(GlassSeats.seated(places, seats: out, typingSeat: "glass").count == 2, "打字時陪走關（甲）：兩隻都在候選玻璃上")
    h.check(GlassSeats.seated(places, seats: nil, typingSeat: nil).count == 2, "沒有貓窩：兩隻都在候選玻璃上")
    h.check(GlassSeats.seated(places, seats: DeskSeats(momo: true), typingSeat: nil).count == 2, "米米莫回到候選玻璃：兩隻都在")
}
