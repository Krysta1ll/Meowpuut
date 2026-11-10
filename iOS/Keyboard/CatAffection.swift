import Foundation

// Counts consecutive pets on 莫莫. A pause longer than `window` starts over,
// so an occasional touch never changes the script. After she switches it, a
// short rest keeps continued tapping from switching straight back.
struct CatAffection {
    static let petsToSwitch = 5
    static let window: TimeInterval = 1.6
    static let rest: TimeInterval = 1.2

    enum Outcome: Equatable {
        case pleased(Int)   // pets so far in this run, 1..<petsToSwitch
        case happy          // switch the script now
        case resting        // still enjoying the last switch
    }

    private(set) var pets = 0
    private var lastPet: Date?
    private var restUntil: Date?

    var remaining: Int { Self.petsToSwitch - pets }

    mutating func pet(at now: Date = Date()) -> Outcome {
        if let restUntil, now < restUntil { return .resting }
        restUntil = nil
        if let lastPet, now.timeIntervalSince(lastPet) > Self.window { pets = 0 }
        lastPet = now
        pets += 1
        guard pets >= Self.petsToSwitch else { return .pleased(pets) }
        pets = 0
        lastPet = nil
        restUntil = now.addingTimeInterval(Self.rest)
        return .happy
    }
}
