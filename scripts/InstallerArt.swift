import AppKit
import ImageIO
import UniformTypeIdentifiers

let destination = URL(fileURLWithPath: CommandLine.arguments[1])
let width = 760, height = 500
let context = CGContext(data: nil, width: width * 2, height: height * 2, bitsPerComponent: 8, bytesPerRow: 0,
                        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
context.scaleBy(x: 2, y: 2)
context.translateBy(x: 0, y: CGFloat(height)); context.scaleBy(x: 1, y: -1)
NSGraphicsContext.current = NSGraphicsContext(cgContext: context, flipped: true)
func color(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat) -> NSColor { NSColor(srgbRed: r, green: g, blue: b, alpha: 1) }
func polygon(_ points: [CGPoint], _ fill: NSColor) {
    let p = NSBezierPath(); p.move(to: points[0]); for point in points.dropFirst() { p.line(to: point) }; p.close(); fill.setFill(); p.fill()
}
func text(_ value: String, _ x: CGFloat, _ y: CGFloat, _ size: CGFloat, _ weight: NSFont.Weight = .regular, _ ink: NSColor = .labelColor) {
    (value as NSString).draw(at: NSPoint(x: x, y: y), withAttributes: [.font: NSFont.systemFont(ofSize: size, weight: weight), .foregroundColor: ink])
}
color(0.96, 0.94, 0.90).setFill(); NSRect(x: 0, y: 0, width: width, height: height).fill()
let ink = color(0.18, 0.19, 0.18), muted = color(0.48, 0.43, 0.39)
text("喵撲", 48, 34, 36, .semibold, ink)
text("Meowpuut", 142, 49, 17, .medium, muted)
text("把小貓接回家。", 48, 91, 19, .regular, ink)
text("首次安裝：拖出紙箱裡的喵撲，放進右邊的「輸入法」。", 48, 123, 13, .regular, muted)
// Folded kraft paper. The draggable application icon sits inside the open box.
polygon([.init(x: 110,y: 244),.init(x: 210,y: 206),.init(x: 310,y: 244),.init(x: 210,y: 282)],color(0.58,0.43,0.31))
polygon([.init(x: 110,y: 244),.init(x: 210,y: 282),.init(x: 210,y: 352),.init(x: 110,y: 310)],color(0.76,0.59,0.43))
polygon([.init(x: 210,y: 282),.init(x: 310,y: 244),.init(x: 310,y: 310),.init(x: 210,y: 352)],color(0.85,0.69,0.51))
polygon([.init(x: 110,y: 244),.init(x: 210,y: 282),.init(x: 180,y: 300),.init(x: 78,y: 259)],color(0.88,0.74,0.57))
polygon([.init(x: 210,y: 282),.init(x: 310,y: 244),.init(x: 339,y: 261),.init(x: 240,y: 300)],color(0.94,0.82,0.66))
text("mimo", 135, 305, 13, .medium, color(0.43,0.31,0.23))
let arrow = NSBezierPath(); arrow.move(to: .init(x: 368, y: 231)); arrow.curve(to: .init(x: 455,y: 231), controlPoint1: .init(x: 394,y: 218), controlPoint2: .init(x: 426,y: 218)); arrow.line(to: .init(x: 447,y: 220)); arrow.move(to: .init(x: 455,y: 231)); arrow.line(to: .init(x: 442,y: 235)); arrow.lineWidth = 1.6; arrow.lineCapStyle = .round; muted.setStroke(); arrow.stroke()
text("新的家", 517, 310, 12, .regular, muted)
text("更新時若喵撲正在使用，請看「安裝說明」。", 48, 374, 14, .medium, ink)
text("完成後儲存工作，登出再登入。", 48, 402, 14, .regular, ink)
text("系統設定 → 鍵盤 → 輸入來源 → 加入 Meowpuut", 48, 429, 12, .regular, muted)
text("macOS 27+ · Apple silicon", 48, 462, 11, .regular, muted)
let data = NSMutableData()
let encoder = CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(encoder, context.makeImage()!, [kCGImagePropertyDPIWidth:144,kCGImagePropertyDPIHeight:144] as CFDictionary)
CGImageDestinationFinalize(encoder)
try (data as Data).write(to: destination)
