import UIKit
import SwiftUI

struct KeyboardPanel: View {
    @ObservedObject var engine: Engine
    @ObservedObject var context: KeyboardContext
    @ObservedObject var preferences = Preferences.shared
    var key: (Int32, Int32) -> Void
    var insertSymbol: (String) -> Void
    var choose: (Int) -> Void
    var erase: () -> Void
    var beginCursor: () -> Void
    var moveCursor: (Int) -> Void
    var switchScript: () -> Bool
    var keyboardController: () -> UIInputViewController?
    @State var expanded = false
    @State var capitalization = CapitalizationState()
    @State var symbolsPage = 0
    @State var availableHeight: CGFloat = 312
    @State var availableWidth: CGFloat = 320
    @State var candidatePage: Int? = 0
    @Environment(\.colorScheme) var systemScheme
    @Environment(\.colorSchemeContrast) var contrast
    @Environment(\.accessibilityReduceMotion) var reduceMotion
    @Environment(\.accessibilityReduceTransparency) var reduceTransparency
    @Environment(\.dynamicTypeSize) var dynamicTypeSize
    var scheme: ColorScheme {
        switch preferences.candidateTone {
        case .system: systemScheme
        case .light: .light
        case .dark: .dark
        }
    }
    var accent: Color { preferences.candidatePalette.color(dark: scheme == .dark) }
    let zhuyin: [[(String, String)]] = [
        [("ㄅ","1"),("ㄉ","2"),("ˇ","3"),("ˋ","4"),("ㄓ","5"),("ˊ","6"),("˙","7"),("ㄚ","8"),("ㄞ","9"),("ㄢ","0"),("ㄦ","-")],
        [("ㄆ","q"),("ㄊ","w"),("ㄍ","e"),("ㄐ","r"),("ㄔ","t"),("ㄗ","y"),("ㄧ","u"),("ㄛ","i"),("ㄟ","o"),("ㄣ","p")],
        [("ㄇ","a"),("ㄋ","s"),("ㄎ","d"),("ㄑ","f"),("ㄕ","g"),("ㄘ","h"),("ㄨ","j"),("ㄜ","k"),("ㄠ","l"),("ㄤ",";")],
        [("ㄈ","z"),("ㄌ","x"),("ㄏ","c"),("ㄒ","v"),("ㄖ","b"),("ㄙ","n"),("ㄩ","m"),("ㄝ",","),("ㄡ","."),("ㄥ","/")]
    ]
    var body: some View {
      GeometryReader { geometry in
        let barHeight = computeCandidateBarHeight(width: geometry.size.width)
        let keyPitch = max(0, geometry.size.width - 10) / 10
        let sideKeyWidth = max(0, keyPitch * 1.5 - 4)
        VStack(spacing: 5) {
                HStack(spacing: 0) {
                  GeometryReader { candidateArea in
                    let hasCandidates = !engine.snapshot.candidates.isEmpty || engine.snapshot.compactJapanese != nil
                    let initial = hasCandidates ? candidatePages(width: candidateArea.size.width) : []
                    let pageWidth = max(1, candidateArea.size.width - (initial.count > 1 ? 12 : 0))
                    let pages = initial.count > 1 ? candidatePages(width: pageWidth) : initial
                    HStack(spacing: 0) {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 0) {
                                ForEach(pages.indices, id: \.self) { page in
                                    HStack(spacing: 4) {
                                        ForEach(pages[page], id: \.index) { item in
                                            candidateButton(item.index)
                                                .frame(width: item.width)
                                                .clipped()
                                        }
                                        Spacer(minLength: 0)
                                    }
                                    .frame(width: pageWidth, height: barHeight, alignment: .leading)
                                    .id(page)
                                }
                            }.scrollTargetLayout()
                        }
                        .frame(width: pageWidth, height: barHeight)
                        .scrollTargetBehavior(.paging)
                        .scrollPosition(id: $candidatePage)
                        if pages.count > 1 {
                            Image(systemName: (candidatePage ?? 0) < pages.count - 1 ? "chevron.right" : "chevron.left")
                                .font(.system(size: 9, weight: .semibold)).foregroundStyle(.secondary)
                                .frame(width: 12, height: barHeight).allowsHitTesting(false).accessibilityHidden(true)
                            }
                        }
                  }.frame(height: barHeight)
                    .overlay {
                        if engine.snapshot.candidates.isEmpty, engine.snapshot.compactJapanese == nil, !expanded {
                            idlePredictionOrPunctuationOverlay
                        }
                    }
                    Button {
                        KeyboardHaptics.keyPress()
                        expanded.toggle()
                        if expanded { engine.browse(phrases: false) }
                        else if engine.browsing { engine.refresh() }
                    } label: { Image(systemName: expanded ? "chevron.up" : "chevron.down").frame(width: 44, height: 44) }
                        .disabled(engine.snapshot.candidates.isEmpty && !expanded).accessibilityLabel(expanded ? t("收合候選詞", "收起候选词") : t("展開候選詞", "展开候选词"))
                }
                .padding(.horizontal, preferences.catGlassSkin ? 0 : max(0, (barHeight - 44) / 2))
                .frame(height: barHeight)
                .modifier(KeyboardCandidateGlass(cats: preferences.catGlassSkin, dark: scheme == .dark,
                                                  strength: preferences.glassStrength, tint: accent,
                                                  appearanceRevision: context.appearanceRevision,
                                                  composing: !engine.snapshot.preedit.isEmpty,
                                                  simplified: preferences.simplified,
                                                  chineseActive: !engine.english && engine.inputMode != .japanese,
                                                  switchScript: switchScript))
                .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: expanded)
                if expanded { expandedCandidates.transition(.opacity) }
                else if symbolsPage > 0 || engine.english || engine.inputMode != .zhuyin {
                    ForEach(englishRows, id: \.self) { row in
                      GlassEffectContainer(spacing: 0) {
                        HStack(spacing: 0) {
                            if row == "zxcvbnm" { keycap(capitalization.mode == .locked ? "⇪" : "⇧", width: sideKeyWidth, role: capitalization.mode != .lowercase && engine.english ? .accentUtility : .utility, rowKey: true) { capitalization.tapShift() } }
                            if symbolsPage > 0 && row == englishRows.last { keycap(symbolsPage == 1 ? "#+=" : "123", rowKey: true) { symbolsPage = symbolsPage == 1 ? 2 : 1 } }
                            ForEach(Array(row), id: \.self) { character in
                                let text = symbolsPage > 0
                                    ? KeyboardSymbols.text(character, chinese: !engine.english && engine.inputMode != .japanese)
                                    : (engine.english && capitalization.mode != .lowercase ? String(character).uppercased() : String(character))
                                keycap(text, rowKey: true) {
                                    if symbolsPage > 0 {
                                        insertSymbol(text)
                                        if KeyboardSymbols.shouldAutoReturnToLetters(after: text) { symbolsPage = 0 }
                                    } else {
                                        let output = capitalization.consumeLetter(english: engine.english) ? String(character).uppercased() : String(character)
                                        key(Int32(output.unicodeScalars.first!.value), 0)
                                    }
                                }
                            }
                            if row == englishRows.last { deleteKey(height: rowHeight, rowKey: true, width: row == "zxcvbnm" ? sideKeyWidth : nil) }
                        }
                        .padding(.horizontal, symbolsPage == 0 && row == "asdfghjkl" ? keyPitch / 2 : 0)
                      }
                    }
                } else {
                    ForEach(zhuyin.indices, id: \.self) { row in
                      GlassEffectContainer(spacing: 0) {
                        HStack(spacing: 0) {
                            ForEach(zhuyin[row], id: \.1) { symbol, raw in keycap(symbol, rowKey: true) { key(Int32(raw.utf8.first!), 0) } }
                            if row == zhuyin.count - 1 { deleteKey(height: rowHeight, rowKey: true) }
                        }
                        .padding(.horizontal, (row == 1 || row == 2) ? max(0, geometry.size.width - 10) / 22 : 0)
                      }
                    }
                }
            GlassEffectContainer(spacing: 0) {
              HStack(spacing: 4) {
                if keyboardController()?.needsInputModeSwitchKey == true { InputModeButton(controller: keyboardController, label: t("切換鍵盤", "切换键盘")).frame(width: 44, height: 44) }
                keycap(engine.english ? (engine.inputMode == .pinyin ? "拼" : engine.inputMode == .japanese ? "あ" : "ㄅ") : "ABC", width: 44, role: .utility) { key(Engine.toggleEnglishKey, 0) }
                keycap(symbolsPage == 0 ? t("符號", "符号") : (!engine.english && engine.inputMode == .zhuyin ? "注音" : "字母"), width: 44, role: .utility) {
                    symbolsPage = symbolsPage == 0 ? 1 : 0
                }
                SpaceCursorButton(title: spaceTitle, text: t, onTap: {
                    if symbolsPage > 0 { symbolsPage = 0 }
                    key(32, 0)
                }, onBeginCursor: beginCursor,
                                  onMove: moveCursor, tintColor: scheme == .dark ? .white : .black)
                    .frame(maxWidth: .infinity)
                    .frame(height: 44)
                    .background(keySurface(.character), in: RoundedRectangle(cornerRadius: 8))
                    .overlay(RoundedRectangle(cornerRadius: 8).fill(accent.opacity(reduceTransparency ? 0.02 : 0.065)).allowsHitTesting(false))
                    .glassEffect(keyGlass, in: .rect(cornerRadius: 8))
                if expanded { deleteKey() }
                keycap(engine.snapshot.preedit.isEmpty ? returnTitle : t("確認", "确认"),
                       width: 72,
                       role: engine.snapshot.preedit.isEmpty && context.isActionReturnKey ? .accentUtility : .utility) { key(0xff0d, 0) }
              }
            }
            if !engine.error.isEmpty { Text(engine.error).font(.caption).lineLimit(1).foregroundStyle(.red).frame(height: KeyboardLayoutMetrics.errorHeight) }
        }.animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: expanded)
            .padding(.horizontal, 5).padding(.vertical, 5)
            .frame(width: geometry.size.width, height: geometry.size.height, alignment: .top)
            .background {
                if preferences.catGlassSkin { catGlassBackdrop } else {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(LinearGradient(colors: scheme == .dark
                        ? [Color(white: 0.18), Color(white: 0.11)]
                        : [Color(white: 0.91), Color(white: 0.81)],
                        startPoint: .topLeading, endPoint: .bottomTrailing))
                    .overlay {
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .fill(LinearGradient(colors: [accent.opacity(reduceTransparency ? 0.025 : 0.10), .clear],
                                                 startPoint: .topLeading, endPoint: .bottomTrailing))
                    }
                    .glassEffect(.regular.tint(accent.opacity(0.04)), in: .rect(cornerRadius: 18))
                }
            }.tint(accent)
            .environment(\.colorScheme, scheme)
            .onChange(of: engine.snapshot.preedit) { _, text in if text.isEmpty { expanded = false } }
            .onChange(of: engine.snapshot.input) { _, text in
                // Editing while expanded refreshes the engine and leaves browse mode.
                // Rebuild the filtered pool for the remaining composition.
                if expanded, !text.isEmpty, !engine.browsing {
                    engine.browse(phrases: engine.phrasesOnly)
                }
            }
            .onChange(of: engine.snapshot.candidates) { _, _ in candidatePage = 0 }
            .onAppear {
                KeyboardHaptics.prepare()
                availableHeight = geometry.size.height
                availableWidth = geometry.size.width
                capitalization.applyAutoCapitalization(context.shouldAutoCapitalize)
                (keyboardController() as? KeyboardViewController)?.setVisibleRowCount(visibleRowCount)
                (keyboardController() as? KeyboardViewController)?.setCandidateBarHeight(barHeight)
            }
            .onChange(of: geometry.size.height) { _, height in availableHeight = height }
            .onChange(of: geometry.size.width) { _, width in availableWidth = width }
            .onChange(of: visibleRowCount) { _, count in
                (keyboardController() as? KeyboardViewController)?.setVisibleRowCount(count)
            }
            .onChange(of: barHeight) { _, height in
                (keyboardController() as? KeyboardViewController)?.setCandidateBarHeight(height)
            }
            .onChange(of: context.shouldAutoCapitalize) { _, shouldCap in
                capitalization.applyAutoCapitalization(shouldCap)
            }
            .onChange(of: engine.english) { _, _ in
                capitalization.applyAutoCapitalization(context.shouldAutoCapitalize)
            }
      }.coordinateSpace(name: KeyPreviewGeometry.coordinateSpace)
    }
    // A clean pearl-and-obsidian Liquid Glass surface with soft specular light
    // that lets the glass candidate bar and mochi cats stand out clearly.
    var catGlassBackdrop: some View {
        let dark = scheme == .dark
        return RoundedRectangle(cornerRadius: 18, style: .continuous)
            .fill(LinearGradient(colors: dark
                ? [Color(red: 0.16, green: 0.16, blue: 0.18), Color(red: 0.10, green: 0.10, blue: 0.12)]
                : [Color(red: 0.91, green: 0.90, blue: 0.93), Color(red: 0.84, green: 0.83, blue: 0.87)],
                startPoint: .topLeading, endPoint: .bottomTrailing))
            .overlay {
                if !reduceTransparency {
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .fill(LinearGradient(colors: [
                            Color.white.opacity(dark ? 0.06 : 0.28),
                            accent.opacity(dark ? 0.08 : 0.07),
                            .clear
                        ], startPoint: .topLeading, endPoint: .bottomTrailing))
                }
            }
            .glassEffect(.regular.tint(accent.opacity(0.04)), in: .rect(cornerRadius: 18))
            .allowsHitTesting(false)
    }
    var englishRows: [String] {
        switch symbolsPage {
        case 1: return ["1234567890", "-/:;()$&@", ".,?!'\"", "[]{}#%_="]
        case 2: return ["1234567890", "^*+|\\~`<>", "€£¥•…", ".,?!'\""]
        default: return ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
        }
    }
    var spaceTitle: String {
        let choose = t("選詞", "选词")
        if engine.snapshot.preedit.isEmpty { return "空格" }
        if engine.snapshot.preedit.hasPrefix("/") { return choose }
        if engine.inputMode == .pinyin { return engine.snapshot.candidates.isEmpty ? "原文＋空格" : choose }
        if engine.inputMode == .japanese { return choose }
        if let last = engine.snapshot.input.last, " 6347".contains(last) { return choose }
        return t("一聲", "一声")
    }
    // Labels follow the script being typed, so 莫莫's switch changes them too.
    var t: InterfaceText { InterfaceText(simplified: preferences.simplified) }
    var returnTitle: String {
        switch context.returnKey {
        case .search, .google, .yahoo: t("搜尋", "搜索")
        case .send: t("傳送", "发送")
        case .go: "前往"
        case .done: "完成"
        case .next: "下一步"
        case .join: "加入"
        case .route: t("路線", "路线")
        case .emergencyCall: t("緊急呼叫", "紧急呼叫")
        case .continue: t("繼續", "继续")
        default: t("換行", "换行")
        }
    }
    var idleStatus: String {
        if engine.english { return "English" }
        switch engine.inputMode {
        case .japanese: return "日本語"
        case .pinyin: return preferences.simplified ? "拼音 · 简体" : "拼音 · 繁體"
        case .zhuyin: return preferences.simplified ? "注音 · 简体" : "注音 · 繁體"
        }
    }
    enum KeyRole { case character, utility, accentUtility }
    var layout: KeyboardLayoutMetrics {
        return KeyboardLayoutMetrics(availableHeight: availableHeight,
                                     rowCount: visibleRowCount,
                                     expanded: expanded, showsError: !engine.error.isEmpty,
                                     candidateBarHeight: candidateBarHeight)
    }
    var visibleRowCount: Int {
        symbolsPage > 0 || engine.english || engine.inputMode != .zhuyin ? englishRows.count : zhuyin.count
    }
    var rowHeight: CGFloat {
        layout.rowHeight
    }
    func deleteKey(height: CGFloat = 44, rowKey: Bool = false, width: CGFloat? = nil) -> some View {
        let inset: CGFloat = rowKey ? 2 : 0
        return RepeatDeleteButton(action: erase, tintColor: scheme == .dark ? .white : .black, label: t("刪除", "删除"))
            .frame(maxWidth: rowKey && width == nil ? .infinity : nil)
            .frame(width: width.map { $0 + 2 * inset } ?? (rowKey ? nil : 44), height: height)
            .background {
                RoundedRectangle(cornerRadius: 8)
                    .fill(keySurface(.utility))
                    .overlay(RoundedRectangle(cornerRadius: 8).fill(accent.opacity(0.045)))
                    .padding(.horizontal, inset)
                    .padding(.vertical, rowKey ? 2 : 0)
                    .allowsHitTesting(false)
            }
            // Apply glass to the whole key so it cannot cover the UIKit image.
            .glassEffect(keyGlass, in: .rect(cornerRadius: 8))
    }
    var keyGlass: Glass {
        guard !reduceTransparency else { return .identity }
        switch preferences.glassStrength {
        case .soft: return .regular.tint(accent.opacity(0.025)).interactive()
        case .balanced: return .clear.tint(accent.opacity(0.04)).interactive()
        case .vivid: return .clear.tint(accent.opacity(0.075)).interactive()
        }
    }
    func keySurface(_ role: KeyRole) -> Color {
        if role == .accentUtility { return accent.opacity(scheme == .dark ? 0.55 : 0.78) }
        if scheme == .dark { return Color(white: role == .character ? 0.27 : 0.20) }
        return Color(white: role == .character ? 0.98 : 0.76)
    }
    func keycap(_ text: String, width: CGFloat? = nil, role: KeyRole = .character, rowKey: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(.system(size: text.count > 1 ? 15 : 21, weight: role == .accentUtility ? .medium : .regular)).lineLimit(1).minimumScaleFactor(0.8)
                .foregroundStyle(role == .accentUtility ? Color.white : Color.primary)
                .frame(maxWidth: width == nil ? .infinity : nil)
                .frame(height: rowKey ? max(0, rowHeight - 4) : 44)
                .frame(width: width)
                .background(keySurface(role), in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).fill(accent.opacity(reduceTransparency ? 0.02 : 0.065)).allowsHitTesting(false))
                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(.white.opacity(scheme == .dark ? 0.06 : 0.48), lineWidth: 0.5))
                .shadow(color: .black.opacity(scheme == .dark ? 0.16 : 0.10), radius: 0.5, y: 1)
                .glassEffect(keyGlass, in: .rect(cornerRadius: 8))
                .frame(height: rowKey ? rowHeight : 44)
                .padding(.horizontal, rowKey ? 2 : 0)
                .contentShape(Rectangle())
        }.buttonStyle(KeyboardCharacterPreviewStyle(text: text, keyboardWidth: availableWidth,
                                                    tint: accent, enabled: rowKey && role == .character))
            .accessibilityLabel(text)
            .accessibilityAddTraits(.isKeyboardKey)
    }
}
