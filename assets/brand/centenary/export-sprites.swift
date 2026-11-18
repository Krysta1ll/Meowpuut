#!/usr/bin/env swift
// Deterministic production export only: split the generated 2×2 sheets,
// preserve aspect and colours, fit each opaque subject into a 64 pt canvas.
// Usage: swift export-sprites.swift [asset-directory]
import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

let output = URL(fileURLWithPath: CommandLine.arguments.dropFirst().first ?? URL(fileURLWithPath: #filePath).deletingLastPathComponent().path)
let fm = FileManager.default
let poses = ["idle", "bow", "cheer", "doze"]
let pointSize = 64
let threshold: UInt8 = 16

func load(_ url: URL) throws -> CGImage {
    guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
        throw NSError(domain: "CentenaryExport", code: 1, userInfo: [NSLocalizedDescriptionKey: "Cannot load \(url.path)"])
    }
    return image
}

func context(_ width: Int, _ height: Int) -> CGContext {
    CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
              bytesPerRow: width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue)!
}

func bounds(_ image: CGImage) -> CGRect {
    let c = context(image.width, image.height)
    c.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
    let pixels = c.data!.assumingMemoryBound(to: UInt8.self)
    var left = image.width, top = image.height, right = -1, bottom = -1
    for y in 0..<image.height {
        for x in 0..<image.width where pixels[y * c.bytesPerRow + x * 4 + 3] >= threshold {
            left = min(left, x); top = min(top, y)
            right = max(right, x); bottom = max(bottom, y)
        }
    }
    precondition(right >= left && bottom >= top, "An empty sprite is not production artwork")
    return CGRect(x: left, y: top, width: right - left + 1, height: bottom - top + 1)
}

func save(_ image: CGImage, _ url: URL) throws {
    guard let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
        throw NSError(domain: "CentenaryExport", code: 2)
    }
    CGImageDestinationAddImage(dest, image, nil)
    guard CGImageDestinationFinalize(dest) else { throw NSError(domain: "CentenaryExport", code: 3) }
}

try fm.createDirectory(at: output.appendingPathComponent("sprites"), withIntermediateDirectories: true)
var metadata: [String: Any] = [:]
for cat in ["mimimo", "momo"] {
    let source = try load(output.appendingPathComponent("sources/\(cat)-poses.png"))
    precondition(source.width == 1254 && source.height == 1254, "Expected 1254 px square source sheet")
    for (index, pose) in poses.enumerated() {
        let cellRect = CGRect(x: index % 2 * 627, y: index / 2 * 627, width: 627, height: 627)
        let cell = source.cropping(to: cellRect)!
        let sourceBounds = bounds(cell)
        // Keep full alpha falloff around the subject, with no colour/alpha edit.
        let crop = sourceBounds.insetBy(dx: -3, dy: -3).intersection(CGRect(x: 0, y: 0, width: 627, height: 627))
        let subject = cell.cropping(to: crop)!
        let relative = sourceBounds.offsetBy(dx: -crop.minX, dy: -crop.minY)
        let scale = min(0.84 * Double(pointSize) / sourceBounds.width,
                        0.80 * Double(pointSize) / sourceBounds.height)
        let stem = "\(cat)_centenary_\(pose)"
        var measured: [Double] = []
        var files: [String: String] = [:]
        for density in 1...3 {
            let side = pointSize * density
            let s = scale * Double(density)
            let x = Double(side) / 2 - (relative.midX * s)
            let y = Double(side) * 0.90 - (relative.maxY * s)
            let target = context(side, side)
            target.interpolationQuality = .high
            target.draw(subject, in: CGRect(x: x, y: y, width: Double(subject.width) * s, height: Double(subject.height) * s))
            let result = target.makeImage()!
            let name = "\(stem)@\(density)x.png"
            try save(result, output.appendingPathComponent("sprites/\(name)"))
            files["\(density)x"] = "sprites/\(name)"
            if density == 3 {
                let b = bounds(result)
                measured = [b.minX, b.minY, b.maxX, b.maxY].map { Double($0) / Double(side) }
            }
        }
        metadata[stem] = ["size": [64, 64], "anchor": [0.5, 0.90], "bounds": measured,
                          "cut": [], "paws": [], "files": files,
                          "sourceCell": [Int(cellRect.minX), Int(cellRect.minY), 627, 627],
                          "sourceBounds": [sourceBounds.minX, sourceBounds.minY, sourceBounds.maxX, sourceBounds.maxY]]
        print("\(stem): \(measured)")
    }
}
let manifest: [String: Any] = ["schema": 1, "skin": "centenary", "alphaBoundsThreshold": Int(threshold),
                             "canvasPoints": [64, 64], "anchor": [0.5, 0.90], "cut": [], "paws": [],
                             "sprites": metadata]
let json = try JSONSerialization.data(withJSONObject: manifest, options: [.prettyPrinted, .sortedKeys])
try json.write(to: output.appendingPathComponent("sprites.json"), options: .atomic)
