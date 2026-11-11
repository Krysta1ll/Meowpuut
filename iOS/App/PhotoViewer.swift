import SwiftUI
import UIKit

/// Pinch and double-tap zoom with the same physics as Photos. The image is
/// fitted to the view's size, and fitted again whenever that size or the
/// image changes (rotation, a resized window on iPad).
final class ZoomScrollView: UIScrollView, UIScrollViewDelegate {
    private let imageView = UIImageView()
    private var fittedBounds = CGSize.zero
    private var fittedImage = CGSize.zero

    var image: UIImage? {
        get { imageView.image }
        set {
            guard imageView.image !== newValue else { return }
            imageView.image = newValue
            setNeedsLayout()
        }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        delegate = self
        minimumZoomScale = 1
        maximumZoomScale = 4
        showsVerticalScrollIndicator = false
        showsHorizontalScrollIndicator = false
        contentInsetAdjustmentBehavior = .never
        backgroundColor = .clear
        imageView.contentMode = .scaleAspectFit
        imageView.isAccessibilityElement = false
        addSubview(imageView)
        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(doubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        addGestureRecognizer(doubleTap)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        // This runs on every scroll and zoom step; refit only for a new size.
        let size = bounds.size, imageSize = imageView.image?.size ?? .zero
        guard size.width > 0, size.height > 0, imageSize.width > 0, imageSize.height > 0,
              size != fittedBounds || imageSize != fittedImage else { return }
        fittedBounds = size
        fittedImage = imageSize
        // The frame set below only means something without a zoom transform.
        zoomScale = 1
        let scale = min(size.width / imageSize.width, size.height / imageSize.height)
        let fitted = CGSize(width: imageSize.width * scale, height: imageSize.height * scale)
        imageView.frame = CGRect(origin: .zero, size: fitted)
        contentSize = fitted
        centre()
        contentOffset = CGPoint(x: -contentInset.left, y: -contentInset.top)
    }

    private func centre() {
        let size = imageView.frame.size
        let insetX = max(0, (bounds.width - size.width) / 2)
        let insetY = max(0, (bounds.height - size.height) / 2)
        contentInset = UIEdgeInsets(top: insetY, left: insetX, bottom: insetY, right: insetX)
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }
    func scrollViewDidZoom(_ scrollView: UIScrollView) { centre() }

    @objc private func doubleTap(_ gesture: UITapGestureRecognizer) {
        if zoomScale > 1.01 {
            setZoomScale(1, animated: true)
        } else {
            let point = gesture.location(in: imageView)
            let scale: CGFloat = 2.5
            let size = CGSize(width: bounds.width / scale, height: bounds.height / scale)
            zoom(to: CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2,
                            width: size.width, height: size.height), animated: true)
        }
    }
}

struct ZoomableImage: UIViewRepresentable {
    let image: UIImage?

    func makeUIView(context: Context) -> ZoomScrollView { ZoomScrollView() }

    func updateUIView(_ scrollView: ZoomScrollView, context: Context) {
        scrollView.image = image
    }
}

private struct ViewerPage: View {
    let photo: CatPhoto
    @State private var image: UIImage?

    var body: some View {
        ZoomableImage(image: image)
            .task(id: photo.file) { image = await PhotoLibrary.image(photo, pixels: 2048) }
            .accessibilityElement()
            .accessibilityLabel(photo.spokenTitle(InterfaceText.system))
            .accessibilityAddTraits(.isImage)
    }
}

struct PhotoViewer: View {
    let photos: [CatPhoto]
    @State var index: Int
    @Environment(\.dismiss) private var dismiss
    @State private var chromeHidden = false
    @State private var notice: String?
    @State private var saving = false
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.accessibilitySwitchControlEnabled) private var switchControl
    private let t = InterfaceText.system

    /// A tap on the photo hides the buttons. With VoiceOver or Switch Control
    /// they stay, since a tap there is how those activate the photo.
    private var showsChrome: Bool { !chromeHidden || voiceOver || switchControl }

    private var current: CatPhoto? { photos.indices.contains(index) ? photos[index] : nil }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            TabView(selection: $index) {
                ForEach(Array(photos.enumerated()), id: \.element.id) { offset, photo in
                    ViewerPage(photo: photo).tag(offset)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .ignoresSafeArea()
            .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { chromeHidden.toggle() } }

            if showsChrome {
                // Keeps the white title readable where the photo, not the
                // black bars, reaches the bottom (iPad, iPhone landscape).
                LinearGradient(colors: [.clear, .black.opacity(0.45)], startPoint: .top, endPoint: .bottom)
                    .frame(height: 160)
                    .frame(maxHeight: .infinity, alignment: .bottom)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
                    .transition(.opacity)
                VStack(spacing: 0) {
                    HStack {
                        Button { dismiss() } label: {
                            Image(systemName: "xmark")
                                .font(.system(size: 16, weight: .semibold))
                                .frame(width: 44, height: 44)
                        }
                        .glassEffect(.regular.interactive(), in: .circle)
                        .accessibilityLabel(t("關閉", "关闭"))
                        Spacer()
                        Text("\(index + 1) / \(photos.count)")
                            .font(.subheadline.monospacedDigit().weight(.medium))
                            .padding(.horizontal, 14).frame(height: 36)
                            .glassEffect(.regular, in: .capsule)
                            .accessibilityLabel(t("第 \(index + 1) 張，共 \(photos.count) 張", "第 \(index + 1) 张，共 \(photos.count) 张"))
                    }
                    .padding(.horizontal, 16)
                    Spacer()
                    if let photo = current {
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(photo.title(t)).font(.headline)
                                if let month = photo.monthText {
                                    Text(month).font(.caption).foregroundStyle(.white.opacity(0.75))
                                }
                            }
                            Spacer(minLength: 8)
                            Button {
                                Task { await save(photo) }
                            } label: {
                                Label(t("存到照片", "存到照片"), systemImage: "square.and.arrow.down")
                                    .font(.subheadline.weight(.semibold))
                                    .padding(.horizontal, 16).frame(height: 44)
                            }
                            .glassEffect(.regular.interactive(), in: .capsule)
                            .disabled(saving)
                            if let url = photo.url {
                                ShareLink(item: url, preview: SharePreview(photo.title(t))) {
                                    Image(systemName: "square.and.arrow.up")
                                        .font(.system(size: 17, weight: .semibold))
                                        .frame(width: 44, height: 44)
                                }
                                .glassEffect(.regular.interactive(), in: .circle)
                                .accessibilityLabel(t("分享", "分享"))
                            }
                        }
                        .padding(.horizontal, 18)
                        .padding(.bottom, 6)
                    }
                }
                .foregroundStyle(.white)
                .transition(.opacity)
            }

            if let notice {
                Text(notice)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 18).frame(height: 40)
                    .glassEffect(.regular, in: .capsule)
                    .transition(.scale(scale: 0.9).combined(with: .opacity))
                    // Already spoken as an announcement when it appears.
                    .accessibilityHidden(true)
            }
        }
        .statusBarHidden(!showsChrome)
        .preferredColorScheme(.dark)
        // The two-finger scrub closes the viewer.
        .accessibilityAction(.escape) { dismiss() }
        .task(id: notice) {
            guard notice != nil else { return }
            try? await Task.sleep(for: .seconds(1.8))
            withAnimation { notice = nil }
        }
    }

    private func save(_ photo: CatPhoto) async {
        saving = true
        let result = await PhotoLibrary.save(photo)
        saving = false
        let text = result.message(t)
        UIAccessibility.post(notification: .announcement, argument: text)
        withAnimation(.spring(duration: 0.3)) { notice = text }
    }
}
