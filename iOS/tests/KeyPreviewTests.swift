import Foundation
import CoreGraphics

@main
struct KeyPreviewTests {
    static func main() {
        for width: CGFloat in [160, 320, 375, 440, 568, 834] {
            for key in [CGRect(x: 4, y: 54, width: 26, height: 40),
                        CGRect(x: width / 2 - 16, y: 105, width: 32, height: 44),
                        CGRect(x: width - 34, y: 54, width: 30, height: 40)] {
                let bubble = KeyPreviewGeometry.frame(for: key, keyboardWidth: width)
                precondition(bubble.minX >= 4 && bubble.maxX <= width - 4)
                precondition(bubble.minY >= 4 && bubble.maxY <= key.minY + 2)
                precondition(bubble.width >= key.width && bubble.height == 48)
            }
        }
        print("PASS: key previews stay above the key within the keyboard at both edges")
    }
}
