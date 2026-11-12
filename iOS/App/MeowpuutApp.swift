import SwiftUI
import UIKit

@main
struct MeowpuutApp: App {
    var body: some Scene {
        WindowGroup { HomeView() }
    }
}

private struct ViewerTarget: Identifiable {
    let index: Int
    var id: Int { index }
}

struct HomeView: View {
    @State private var text = ""
    @State private var showsSettings = false
    @State private var viewer: ViewerTarget?
    @State private var settings = SharedKeyboardSettings()
    @State private var savedNotice: String?
    @State private var viewportHeight: CGFloat = 800
    @Environment(\.colorScheme) private var colorScheme
    @FocusState private var typing: Bool
    private let photos = PhotoLibrary.photos
    // Follows the system language: Simplified for zh-Hans, otherwise Traditional.
    private let t = InterfaceText.system

    /// The newest photos lead in the carousel; the rest follow in the grid.
    private var featured: Int { min(5, photos.count) }
    private var dark: Bool { colorScheme == .dark }
    private var paper: Color { dark ? Color(red: 0.075, green: 0.071, blue: 0.078) : Brand.paper }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 30) {
                    header
                    if !photos.isEmpty {
                        VStack(alignment: .leading, spacing: 14) {
                            HStack(alignment: .firstTextBaseline) {
                                Text(t("生活照", "生活照")).font(.title3.weight(.semibold))
                                Text(t("\(photos.count) 張", "\(photos.count) 张")).font(.subheadline).foregroundStyle(.secondary)
                                Spacer()
                            }
                            carousel
                            if photos.count > featured { grid }
                        }
                    }
                    trial
                    keyboardCard
                    footer
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 32)
                .frame(maxWidth: 680)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { viewportHeight = $0 }
            .background(paper.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(t("鍵盤設定", "键盘设置"), systemImage: "keyboard") { showsSettings = true }
                }
            }
            .sheet(isPresented: $showsSettings) { KeyboardSettingsView(settings: $settings) }
            .fullScreenCover(item: $viewer) { target in PhotoViewer(photos: photos, index: target.index) }
            .overlay(alignment: .bottom) {
                if let savedNotice {
                    Text(savedNotice)
                        .font(.subheadline.weight(.medium))
                        .padding(.horizontal, 18).frame(height: 40)
                        .controlGlass(in: Capsule())
                        .padding(.bottom, 24)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .task(id: savedNotice) {
                guard savedNotice != nil else { return }
                try? await Task.sleep(for: .seconds(1.8))
                withAnimation { savedNotice = nil }
            }
            .tint(Brand.accent)
            .onAppear {
                settings = SharedKeyboardSettings.read() ?? SharedKeyboardSettings()
                #if DEBUG
                // Screenshots: -openSettings YES, -openViewer <index>.
                // -savePhoto <index> saves that photo, to check the save path.
                let args = UserDefaults.standard
                if args.bool(forKey: "openSettings") { showsSettings = true }
                if let index = args.object(forKey: "openViewer") as? String, let value = Int(index) { viewer = ViewerTarget(index: value) }
                if let index = args.object(forKey: "savePhoto") as? String, let value = Int(index), photos.indices.contains(value) {
                    save(photos[value])
                }
                #endif
            }
        }
    }

    // MARK: Header

    private var header: some View {
        HStack(alignment: .center, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Meowpuut")
                    .font(.system(size: 34, weight: .semibold, design: .serif))
                    .italic()
                    .tracking(0.5)
                Text(t("喵撲 · 米米莫和莫莫", "喵扑 · 米米莫和莫莫"))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            BrandMark(size: 52)
                .shadow(color: .black.opacity(0.08), radius: 6, y: 2)
        }
        .padding(.top, 8)
    }

    // MARK: Photos

    private var carousel: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(spacing: 14) {
                ForEach(Array(photos.prefix(featured).enumerated()), id: \.element.id) { index, photo in
                    Button { viewer = ViewerTarget(index: index) } label: {
                        GeometryReader { proxy in
                            PhotoImage(photo: photo, size: proxy.size)
                                .frame(width: proxy.size.width, height: proxy.size.height)
                                .clipped()
                        }
                        .aspectRatio(4 / 5, contentMode: .fit)
                        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                        .overlay(alignment: .bottomLeading) { caption(photo).padding(14) }
                        .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).stroke(.white.opacity(dark ? 0.08 : 0.5), lineWidth: 1))
                        .shadow(color: .black.opacity(dark ? 0.4 : 0.12), radius: 14, y: 8)
                    }
                    .buttonStyle(.plain)
                    // In iPhone landscape the height limits the card, so the
                    // whole 4:5 card and its caption fit on screen.
                    .containerRelativeFrame(.horizontal) { width, _ in min(width - 56, 520, viewportHeight * 0.8 * 4 / 5) }
                    .scrollTransition(.interactive, axis: .horizontal) { content, phase in
                        content
                            .scaleEffect(1 - abs(phase.value) * 0.06)
                            .opacity(1 - abs(phase.value) * 0.25)
                    }
                    .contextMenu { photoMenu(photo) }
                    .accessibilityLabel(photo.spokenTitle(t))
                    .accessibilityHint(t("打開大圖", "打开大图"))
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.viewAligned)
        .contentMargins(.horizontal, 28, for: .scrollContent)
        // Lets the card shadows show in full and, on iPad, the next card run
        // on to the screen edge instead of stopping at the 680 pt column.
        .scrollClipDisabled()
        .padding(.horizontal, -20)
        .padding(.vertical, 4)
    }

    private func caption(_ photo: CatPhoto) -> some View {
        HStack(spacing: 6) {
            Text(photo.title(t)).font(.subheadline.weight(.semibold))
            if let month = photo.monthText {
                Text(month).font(.caption).opacity(0.8)
            }
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 12).frame(height: 32)
        .background(Capsule().fill(.black.opacity(0.18)))
        .controlGlass(in: Capsule())
    }

    private var grid: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Two columns, each photo at its own proportions; every photo
            // goes to the shorter column.
            let columns = masonry()
            HStack(alignment: .top, spacing: 10) {
                ForEach(0..<2, id: \.self) { column in
                    LazyVStack(spacing: 10) {
                        ForEach(columns[column], id: \.self) { index in
                            let photo = photos[index]
                            Button { viewer = ViewerTarget(index: index) } label: {
                                GeometryReader { proxy in
                                    PhotoImage(photo: photo, size: proxy.size)
                                        .frame(width: proxy.size.width, height: proxy.size.height)
                                        .clipped()
                                }
                                .aspectRatio(max(0.6, min(1.5, photo.aspect)), contentMode: .fit)
                                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                            }
                            .buttonStyle(.plain)
                            .contextMenu { photoMenu(photo) }
                            .accessibilityLabel(photo.spokenTitle(t))
                            .accessibilityHint(t("打開大圖", "打开大图"))
                        }
                    }
                }
            }
        }
    }

    private func masonry() -> [[Int]] {
        var columns: [[Int]] = [[], []]
        var heights: [CGFloat] = [0, 0]
        for (index, photo) in photos.enumerated() where index >= featured {
            let column = heights[0] <= heights[1] ? 0 : 1
            columns[column].append(index)
            heights[column] += 1 / max(0.6, min(1.5, photo.aspect))
        }
        return columns
    }

    @ViewBuilder
    private func photoMenu(_ photo: CatPhoto) -> some View {
        Button(t("存到照片", "存到照片"), systemImage: "square.and.arrow.down") { save(photo) }
        if let url = photo.url {
            ShareLink(item: url, preview: SharePreview(photo.title(t))) {
                Label(t("分享", "分享"), systemImage: "square.and.arrow.up")
            }
        }
    }

    private func save(_ photo: CatPhoto) {
        Task {
            let text = await PhotoLibrary.save(photo).message(t)
            UIAccessibility.post(notification: .announcement, argument: text)
            withAnimation(.spring(duration: 0.3)) { savedNotice = text }
        }
    }

    // MARK: Typing and keyboard

    private var trial: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(t("試打", "试打")).font(.title3.weight(.semibold))
            TextField(t("切換到喵撲鍵盤，在這裡打字", "切换到喵扑键盘，在这里打字"), text: $text, axis: .vertical)
                .font(.title3)
                .lineLimit(4...8)
                .focused($typing)
                .padding(16)
                .background(RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(dark ? Color(white: 0.12) : Color.white.opacity(0.85)))
                .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Color.primary.opacity(0.06)))
                .accessibilityLabel(t("試打", "试打"))
        }
    }

    private var keyboardCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(t("鍵盤", "键盘")).font(.title3.weight(.semibold))
                Spacer()
                Button(t("設定", "设置")) { showsSettings = true }
                    .font(.subheadline.weight(.medium))
            }
            CandidateBarPreview(
                shape: settings.glassShape.flatMap(GlassShape.init(rawValue:)) ?? .capsule,
                dark: settings.candidateTone == "dark" || (settings.candidateTone != "light" && dark),
                palette: settings.candidatePalette.flatMap(CandidatePalette.init(rawValue:)) ?? .rose,
                strength: settings.glassStrength.flatMap(GlassStrength.init(rawValue:)) ?? .balanced,
                cats: settings.catGlassSkin ?? true,
                placement: settings.catPlacement ?? CatSchedule.daily,
                simplified: settings.simplified ?? false)
            Text(t("加入方式：設定 → 一般 → 鍵盤 → 鍵盤 → 新增鍵盤 → 喵撲。不需要開啟「完整取用」。",
                   "添加方式：设置 → 通用 → 键盘 → 键盘 → 添加新键盘 → 喵扑。不需要打开“完全访问”。"))
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(t("輸入、排序和選詞記錄都留在這台裝置上。", "输入、排序和选词记录都留在这台设备上。"))
            // MARKETING_VERSION in project.yml.
            if let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String {
                Text(version)
            }
        }
        .font(.caption)
        .foregroundStyle(.secondary)
    }
}
