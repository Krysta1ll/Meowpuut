import Foundation
import CoreGraphics
import ImageIO

// The cat pictures are imported by scripts/render_cats.py. This checks that
// what is in the asset catalog is what the layout places: every sprite of
// every arrangement at both scales, at the size the table says, cut on its
// anchor row with its paws below, and with enough pixels for the largest size
// it is drawn at.

private func expect(_ condition: Bool, _ message: @autoclosure () -> String, line: UInt = #line) {
    if !condition {
        print("FAIL: \(message()) (CatArtTests.swift:\(line))")
        exit(1)
    }
}

struct Bitmap {
    var width: Int
    var height: Int
    var alpha: [UInt8]

    init?(_ url: URL) {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }
        let columns = image.width, rows = image.height
        var pixels = [UInt8](repeating: 0, count: columns * rows * 4)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(data: buffer.baseAddress, width: columns, height: rows, bitsPerComponent: 8,
                                          bytesPerRow: columns * 4, space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: columns, height: rows))
            return true
        }
        guard drawn else { return nil }
        width = columns
        height = rows
        alpha = stride(from: 3, to: pixels.count, by: 4).map { pixels[$0] }
    }

    func solid(_ column: Int, _ row: Int) -> Bool {
        column >= 0 && column < width && row >= 0 && row < height && alpha[row * width + column] > 128
    }

    /// Opaque columns in a row.
    func count(row: Int, from: Int, to: Int) -> Int { (from..<to).filter { solid($0, row) }.count }
}

@main
struct CatArtTests {
    static let catalog = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("Keyboard/Cats.xcassets")

    static func load(_ name: String, scale: Int) -> Bitmap {
        let url = catalog.appendingPathComponent("\(name).imageset/\(name)@\(scale)x.png")
        guard let bitmap = Bitmap(url) else {
            print("FAIL: cannot read \(url.path)")
            exit(1)
        }
        return bitmap
    }

    static func main() {
        // The catalog holds the sprites in the table and nothing from the earlier art.
        let sets = ((try? FileManager.default.contentsOfDirectory(atPath: catalog.path)) ?? []).filter { $0.hasSuffix(".imageset") }
        expect(Set(sets) == Set(CatSprite.all.keys.map { $0 + ".imageset" }), "the catalog holds exactly the sprites in CatSpriteData: \(sets.sorted())")
        // Every pose an arrangement uses has its sprite.
        for arrangement in CatArrangement.all {
            for seat in arrangement.seats {
                expect(CatSprite.all["\(seat.cat.rawValue)_\(seat.pose.asset)"] != nil, "\(arrangement.id): \(seat.cat) \(seat.pose)")
            }
        }

        for (name, sprite) in CatSprite.all.sorted(by: { $0.key < $1.key }) {
            let large = load(name, scale: 3), small = load(name, scale: 2)
            expect(large.width == Int(sprite.size.width) * 3 && large.height == Int(sprite.size.height) * 3
                   && small.width == Int(sprite.size.width) * 2 && small.height == Int(sprite.size.height) * 2,
                   "\(name) is \(sprite.size) pt at 2x and 3x (has \(small.width)×\(small.height) and \(large.width)×\(large.height))")
            expect(large.alpha.contains { $0 > 250 } && large.alpha.contains { $0 == 0 }, "\(name) has a transparent background")
            // The opaque part is where the table says, with a clear margin all round (ears and tail are not cropped).
            var minX = large.width, maxX = 0, minY = large.height, maxY = 0
            for row in 0..<large.height {
                for column in 0..<large.width where large.alpha[row * large.width + column] > 24 {
                    minX = min(minX, column); maxX = max(maxX, column + 1); minY = min(minY, row); maxY = max(maxY, row + 1)
                }
            }
            let w = CGFloat(large.width), h = CGFloat(large.height)
            expect(abs(CGFloat(minX) / w - sprite.bounds.minX) < 0.006 && abs(CGFloat(maxX) / w - sprite.bounds.maxX) < 0.006
                   && abs(CGFloat(minY) / h - sprite.bounds.minY) < 0.006 && abs(CGFloat(maxY) / h - sprite.bounds.maxY) < 0.006,
                   "\(name): opaque bounds match the table")
            expect(minX > 0 && minY > 0 && maxX < large.width && maxY < large.height, "\(name) keeps a transparent margin")

            let row = Int((sprite.anchor.y * h).rounded())
            guard sprite.behindBar else {
                // A cat that holds the bar's end: its paw's tip is at the anchor.
                let x = Int((sprite.anchor.x * w).rounded())
                let reach = Int(0.07 * w)
                var touched = false
                for dy in -reach...reach { for dx in -reach...reach where large.solid(x + dx, row + dy) { touched = true } }
                expect(touched, "\(name): a paw at the anchor")
                continue
            }
            // The body is cut flat on the anchor row: wide just above it, and under it only the paws.
            let from = Int(sprite.cut.lowerBound * w), to = Int(sprite.cut.upperBound * w)
            let above = large.count(row: row - 3, from: from, to: to), below = large.count(row: row + 2, from: from, to: to)
            expect(above > Int(0.85 * CGFloat(to - from)), "\(name): the body reaches the anchor row across its cut (\(above) of \(to - from))")
            expect(below < Int(0.75 * CGFloat(to - from)) && below > 0, "\(name): only paws under the anchor row (\(below) of \(to - from))")
            expect(sprite.cut.contains(sprite.anchor.x), "\(name): the anchor is between the ends of the cut")
            expect(!sprite.paws.isEmpty, "\(name) has paws")
            for paw in sprite.paws {
                let x = Int(paw.x * w)
                expect(large.solid(x, row + 1) && !large.solid(x, row + Int(paw.depth * h) + 2),
                       "\(name): a paw at \(paw.x) hangs \(paw.depth) under the anchor row")
                expect(sprite.cut.contains(paw.x), "\(name): the paw is on the cut")
            }
            // Nothing else hangs under the row: every opaque column there belongs to a paw.
            for column in 0..<large.width where large.solid(column, row + 2) {
                let x = CGFloat(column) / w
                expect(sprite.paws.contains { abs(x - $0.x) <= $0.halfWidth + 0.01 }, "\(name): column \(column) under the anchor row is not a paw")
            }
        }

        // Sharp at the largest size each sprite is drawn: the widest phones at 3x, an iPad at 2x.
        for (idiom, width, scale) in [(KeyboardIdiom.phone, CGFloat(440), CGFloat(3)), (.phone, 466, 3), (.pad, 1366, 2)] {
            for shape in GlassShape.allCases {
                let layout = CandidateRegionLayout(width: width, idiom: idiom, rim: GlassEdge.band(shape, height: 1).top)
                for arrangement in CatArrangement.all {
                    for place in arrangement.place(on: layout.stage(shape: shape)) ?? [] {
                        let pixels = CGFloat(load(place.sprite.name, scale: Int(scale)).width)
                        expect(place.frame.width * scale <= pixels + 0.5,
                               "\(place.sprite.name) is drawn \(place.frame.width) pt wide at \(Int(scale))x on \(idiom) \(Int(width)): needs \(place.frame.width * scale) px, has \(pixels)")
                    }
                }
            }
        }
        print("PASS: \(CatSprite.all.count) sprites at 2x and 3x match the table: sizes, opaque bounds, the flat cut on the anchor row, paws below it, and enough pixels for every arrangement")
    }
}
