import SwiftUI
import UIKit
import ImageIO
import Photos

/// One life photo of 米米莫 and 莫莫, bundled in the Photos folder
/// (scripts/import-photos.py adds them).
struct CatPhoto: Decodable, Identifiable, Hashable {
    var file: String
    var width: Int
    var height: Int
    var cats: [String]
    var month: String?
    var caption: String?

    var id: String { file }
    var aspect: CGFloat { height > 0 ? CGFloat(width) / CGFloat(height) : 1 }
    var url: URL? { PhotoLibrary.directory?.appendingPathComponent(file) }

    func title(_ t: InterfaceText) -> String {
        if let caption, !caption.isEmpty { return caption }
        let mimimo = cats.contains("mimimo"), momo = cats.contains("momo")
        if mimimo && momo { return t("米米莫和莫莫", "米米莫和莫莫") }
        if mimimo { return "米米莫" }
        if momo { return "莫莫" }
        return t("貓", "猫")
    }

    /// What VoiceOver says for the photo: its title and month.
    func spokenTitle(_ t: InterfaceText) -> String {
        title(t) + (monthText.map { "，" + $0 } ?? "")
    }

    /// "2026 年 1 月", from the photo's own date when it had one.
    var monthText: String? {
        guard let month, month.count == 7, let year = Int(month.prefix(4)), let number = Int(month.suffix(2)) else { return nil }
        return "\(year) 年 \(number) 月"
    }
}

enum PhotoLibrary {
    private struct Manifest: Decodable { var version: Int; var photos: [CatPhoto] }

    static var directory: URL? { Bundle.main.url(forResource: "Photos", withExtension: nil) }

    static let photos: [CatPhoto] = {
        guard let url = directory?.appendingPathComponent("photos.json"),
              let data = try? Data(contentsOf: url),
              let manifest = try? JSONDecoder().decode(Manifest.self, from: data) else { return [] }
        return manifest.photos.filter { $0.url.map { FileManager.default.fileExists(atPath: $0.path) } ?? false }
    }()

    private static let cache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.totalCostLimit = 96 * 1024 * 1024
        return cache
    }()

    /// A decoded image no larger than `pixels` on its long side. ImageIO
    /// decodes straight to that size, so a grid never holds full photos.
    static func image(_ photo: CatPhoto, pixels: Int) async -> UIImage? {
        let key = "\(photo.file)|\(pixels)" as NSString
        if let hit = cache.object(forKey: key) { return hit }
        guard let url = photo.url else { return nil }
        let image = await Task.detached(priority: .userInitiated) { () -> UIImage? in
            guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceShouldCacheImmediately: true,
                kCGImageSourceThumbnailMaxPixelSize: pixels
            ]
            guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
            return UIImage(cgImage: cgImage)
        }.value
        if let image {
            cache.setObject(image, forKey: key, cost: Int(image.size.width * image.size.height * 4))
        }
        return image
    }

    enum SaveResult {
        case saved, denied, failed

        /// The notice shown and spoken after saving.
        func message(_ t: InterfaceText) -> String {
            switch self {
            case .saved: return t("已存到照片", "已存到照片")
            case .denied: return t("沒有加入照片的權限，可在「設定」開啟", "没有添加照片的权限，可在“设置”打开")
            case .failed: return t("沒有存成功，請再試一次", "没有存成功，请再试一次")
            }
        }
    }

    /// Adds the full-size photo to the user's library (add-only access).
    static func save(_ photo: CatPhoto) async -> SaveResult {
        guard let url = photo.url else { return .failed }
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { return .denied }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                PHAssetCreationRequest.forAsset().addResource(with: .photo, fileURL: url, options: nil)
            }
            return .saved
        } catch {
            return .failed
        }
    }
}

/// Loads one photo at the size it is drawn, with a soft placeholder. The
/// photo fills `size` and is cropped, so its longer side is usually larger
/// than the frame: that side, in pixels, is what gets decoded.
struct PhotoImage: View {
    let photo: CatPhoto
    var size: CGSize
    @Environment(\.displayScale) private var displayScale
    @State private var image: UIImage?

    static func pixels(aspect: CGFloat, size: CGSize, scale: CGFloat) -> Int {
        guard size.width > 0, size.height > 0, aspect > 0 else { return 0 }
        let side = max(max(size.width, size.height * aspect), max(size.height, size.width / aspect))
        return Int((side * scale).rounded(.up))
    }

    private var pixels: Int { Self.pixels(aspect: photo.aspect, size: size, scale: displayScale) }

    var body: some View {
        ZStack {
            Rectangle().fill(Color(red: 0.90, green: 0.87, blue: 0.83).opacity(0.6))
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .transition(.opacity)
            }
        }
        .task(id: "\(photo.file)|\(pixels)") {
            guard pixels > 0 else { return }
            let loaded = await PhotoLibrary.image(photo, pixels: pixels)
            withAnimation(.easeOut(duration: 0.2)) { image = loaded }
        }
    }
}
