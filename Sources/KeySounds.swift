import AppKit
import AVFoundation
import Combine

/// Plays the built-in typing sounds (docs/SOUNDS.md) on the Mac. The files are
/// `Contents/Resources/key-sounds/<set>/<role>-<variant>.wav`, the same ones the
/// iOS and Android keyboards use.
final class KeySoundPlayer {
    enum Role: String {
        case key, space, delete, pick
        case returnKey = "return"
    }

    static let shared = KeySoundPlayer()

    private var players: [String: [AVAudioPlayer]] = [:]
    private var loaded: KeySound?
    private var index = 0
    private var watch: AnyCancellable?
    private var idleRelease: DispatchWorkItem?

    private init() {
        watch = Preferences.shared.$keySound.sink { [weak self] sound in
            guard let self else { return }
            if !sound.isCustom { self.releasePlayers() }
        }
    }

    private func releasePlayers() {
        idleRelease?.cancel()
        idleRelease = nil
        for group in players.values { for player in group { player.stop() } }
        players.removeAll()
        loaded = nil
    }

    private func scheduleIdleRelease() {
        idleRelease?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.releasePlayers() }
        idleRelease = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 45, execute: work)
    }

    /// The folder of a set inside the app.
    static func folder(_ sound: KeySound, in bundle: Bundle = .main) -> URL? {
        bundle.resourceURL?.appendingPathComponent("key-sounds/\(sound.rawValue)")
    }

    private func load(_ sound: KeySound) {
        guard sound != loaded else { return }
        for group in players.values { for player in group { player.stop() } }
        players.removeAll()
        loaded = sound
        guard sound.isCustom, let root = Self.folder(sound),
              let files = try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) else { return }
        for file in files.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) where file.pathExtension == "wav" {
            guard let role = file.deletingPathExtension().lastPathComponent.split(separator: "-").first,
                  let player = try? AVAudioPlayer(contentsOf: file) else { continue }
            player.prepareToPlay()
            players[String(role), default: []].append(player)
        }
    }

    /// Plays the sound for a key, if a set is chosen. `keyCode` places 五聲's note.
    func play(_ role: Role, keyCode: UInt16? = nil) {
        let preferences = Preferences.shared
        guard preferences.keySound.isCustom, preferences.keySoundVolume > 0 else { return }
        load(preferences.keySound)
        let sample = role == .key && preferences.keySound == .penta
            ? "key\(Self.pentaNote(keyCode))"
            : role.rawValue
        guard let choices = players[sample], !choices.isEmpty else { return }
        let player = choices[index % choices.count]
        index = (index &+ 1) & Int.max
        player.volume = Float(preferences.keySoundVolume) / 100
        player.currentTime = 0
        player.play()
        scheduleIdleRelease()
    }

    /// Plays a key press. Space, Return or a number that commits a shown
    /// candidate sounds as a pick.
    func play(_ role: Role, keyCode: UInt16, hadCandidates: Bool, committed: Bool) {
        let picks = role == .space || role == .returnKey || (18...29).contains(keyCode)
        play(hadCandidates && committed && picks ? .pick : role, keyCode: keyCode)
    }

    /// The sound a key press makes, or nil for keys that do not type (arrows,
    /// Tab, Escape, function keys) and for shortcuts with Command or Control.
    static func role(for event: NSEvent) -> Role? {
        guard event.type == .keyDown, !event.isARepeat,
              event.modifierFlags.intersection([.command, .control]).isEmpty else { return nil }
        switch event.keyCode {
        case 49: return .space
        case 51, 117: return .delete
        case 36, 76: return .returnKey
        case 48, 53, 115, 116, 119, 121, 123, 124, 125, 126: return nil
        default:
            guard let characters = event.charactersIgnoringModifiers, let scalar = characters.unicodeScalars.first,
                  scalar.value >= 32, scalar.value != 127, !(0xF700...0xF8FF).contains(scalar.value) else { return nil }
            return .key
        }
    }

    /// 五聲 picks its note from where the key sits on a QWERTY keyboard: the key's
    /// centre in key widths from the left edge (the second row 0.5 further, the
    /// third 1), times 6/10, at most 5. Other keys take the middle of the row,
    /// as `scripts/key_sounds.py` does.
    static func pentaNote(_ keyCode: UInt16?) -> Int {
        let rows: [[UInt16]] = [[12, 13, 14, 15, 17, 16, 32, 34, 31, 35], [0, 1, 2, 3, 5, 4, 38, 40, 37], [6, 7, 8, 9, 11, 45, 46]]
        var x = 5.0
        if let keyCode {
            for (row, codes) in rows.enumerated() {
                if let column = codes.firstIndex(of: keyCode) { x = Double(column) + 0.5 + Double(row) * 0.5 }
            }
        }
        return min(5, Int(x * 6 / 10))
    }
}
