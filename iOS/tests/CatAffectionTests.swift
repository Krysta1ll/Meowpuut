import Foundation

@main
struct CatAffectionTests {
    static func main() {
        let start = Date(timeIntervalSinceReferenceDate: 0)
        var momo = CatAffection()
        for pet in 1..<CatAffection.petsToSwitch {
            precondition(momo.pet(at: start.addingTimeInterval(Double(pet) * 0.3)) == .pleased(pet))
        }
        precondition(momo.pet(at: start.addingTimeInterval(1.5)) == .happy)
        // Continued tapping right after the switch is only affection.
        precondition(momo.pet(at: start.addingTimeInterval(1.8)) == .resting)
        precondition(momo.pet(at: start.addingTimeInterval(2.6)) == .resting)
        precondition(momo.pet(at: start.addingTimeInterval(2.8)) == .pleased(1))

        // Occasional touches never accumulate into a switch.
        var occasional = CatAffection()
        for pet in 0..<20 {
            precondition(occasional.pet(at: start.addingTimeInterval(Double(pet) * 2)) == .pleased(1))
        }
        precondition(occasional.remaining == CatAffection.petsToSwitch - 1)
        print("PASS: 莫莫 switches script only after a run of consecutive pets")
    }
}
