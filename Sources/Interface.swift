import SwiftUI
import AppKit

let accent = Brand.accent

// One layout calculation drives visibility and text widths, so reserving a Japanese
// match cannot make the rendered row exceed the same budget used to select it.
struct CollapsedCandidateLayout {
    let indices: [Int]
    let textWidths: [Int: CGFloat]
    let truncated: Bool

    init(_ snapshot: Snapshot, budget: CGFloat? = nil) {
        let font = NSFont.systemFont(ofSize: 14)
        let widths = snapshot.candidates.map { ceil(($0 as NSString).size(withAttributes: [.font: font]).width) + (snapshot.japaneseCandidates.contains($0) ? 14 : 0) }
        guard !widths.isEmpty else { indices = []; textWidths = [:]; truncated = false; return }
        let confidence = Set(snapshot.confidentCandidates)
        let trusted = snapshot.candidates.prefix(7).filter { confidence.contains($0) }.count
        let reservedJapanese = snapshot.candidates.firstIndex { snapshot.japaneseCandidates.contains($0) }
            ?? snapshot.requiredCandidate.flatMap { snapshot.candidates.firstIndex(of: $0) }
        let baseBudget = max(110, budget ?? (trusted >= 5 ? 320 : trusted >= 3 ? 280 : 230))
        let japanese = Set(snapshot.japaneseCandidates)
        let chineseIndices = snapshot.candidates.indices.filter { !japanese.contains(snapshot.candidates[$0]) }
        var selected: [Int] = []
        func total(_ ids: [Int]) -> CGFloat {
            14 + CGFloat(ids.count * 34 + max(0, ids.count - 1) * 4) + ids.reduce(0) { $0 + widths[$1] }
        }
        // Allocate the Chinese row first; Japanese gets an additional slot and width.
        for index in chineseIndices {
            if !selected.isEmpty && (selected.count >= (trusted >= 5 ? 6 : 5) || total(selected + [index]) > baseBudget) { break }
            selected.append(index)
        }
        var available = baseBudget
        if let index = reservedJapanese, (snapshot.page == 0 || !snapshot.japaneseCandidates.isEmpty), !selected.contains(index) {
            selected.append(index)
            if budget == nil { available += min(120, widths[index] + 38) }
        }
        if selected.isEmpty { selected = [0] }
        selected = selected.filter { !japanese.contains(snapshot.candidates[$0]) }.sorted() + selected.filter { japanese.contains(snapshot.candidates[$0]) }
        let textBudget = max(0, available - 14 - CGFloat(selected.count * 34 + max(0, selected.count - 1) * 4))
        var remaining = textBudget
        var allocation: [Int: CGFloat] = [:]
        let shortestFirst = selected.sorted { widths[$0] < widths[$1] }
        for (offset, index) in shortestFirst.enumerated() {
            let share = floor(remaining / CGFloat(shortestFirst.count - offset))
            let width = min(widths[index], share)
            allocation[index] = width
            remaining -= width
        }
        indices = selected
        textWidths = allocation
        truncated = selected.contains { (allocation[$0] ?? 0) < widths[$0] }
    }
}

func collapsedCandidateCount(_ candidates: [String]) -> Int {
    CollapsedCandidateLayout(Snapshot(candidates: candidates)).indices.count
}

func collapsedCandidateIndices(_ snapshot: Snapshot) -> [Int] {
    CollapsedCandidateLayout(snapshot).indices
}

// Rows preserve candidate order and shortcuts; a long phrase receives a full row.
func expandedCandidateWidth(_ candidates: [String]) -> CGFloat {
    let font = NSFont.systemFont(ofSize: 14)
    let widths = candidates.prefix(7).map { ceil(($0 as NSString).size(withAttributes: [.font: font]).width) + 40 }
    let longest = widths.max() ?? 60
    if longest <= 85 { return max(170, min(246, longest * CGFloat(min(3, widths.count)) + CGFloat(max(0, min(3, widths.count) - 1) * 4))) }
    if longest <= 120 { return max(180, longest * 2 + 4) }
    return min(300, max(180, longest))
}

func expandedCandidateRows(_ candidates: [String]) -> [[Int]] {
    let font = NSFont.systemFont(ofSize: 14)
    let widths = candidates.prefix(7).map { ceil(($0 as NSString).size(withAttributes: [.font: font]).width) + 40 }
    var rows: [[Int]] = []
    var row: [Int] = []
    for index in widths.indices {
        let proposed = row + [index]
        let cell = (expandedCandidateWidth(candidates) - CGFloat(proposed.count - 1) * 4) / CGFloat(proposed.count)
        if !row.isEmpty && (proposed.count > 3 || proposed.contains(where: { widths[$0] > cell })) {
            rows.append(row); row = []
        }
        row.append(index)
    }
    if !row.isEmpty { rows.append(row) }
    return rows
}

func expandedCandidateRowHeight(_ candidates: [String], row: [Int]) -> CGFloat {
    let textWidth = max(1, (expandedCandidateWidth(candidates) - CGFloat(row.count - 1) * 4) / CGFloat(row.count) - 40)
    let font = NSFont.systemFont(ofSize: 14)
    return max(28, (row.map { index in
        ceil((candidates[index] as NSString).boundingRect(with: NSSize(width: textWidth, height: 10000),
            options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: [.font: font]).height)
    }.max() ?? 0) + 10)
}

func expandedCandidateHeight(_ candidates: [String]) -> CGFloat {
    let rows = expandedCandidateRows(candidates)
    return min(220, rows.reduce(CGFloat(0)) { $0 + expandedCandidateRowHeight(candidates, row: $1) }
        + CGFloat(max(0, rows.count - 1) * 3))
}

func expandedCandidateMove(_ candidates: [String], from index: Int, key: Int32) -> Int {
    guard !candidates.isEmpty else { return 0 }
    let rows = expandedCandidateRows(candidates)
    guard let row = rows.firstIndex(where: { $0.contains(index) }), let column = rows[row].firstIndex(of: index) else { return 0 }
    if key == 0xff51 || key == 0xff53 { return min(candidates.count - 1, max(0, index + (key == 0xff51 ? -1 : 1))) }
    let nextRow = min(rows.count - 1, max(0, row + (key == 0xff52 ? -1 : 1)))
    return rows[nextRow][min(column, rows[nextRow].count - 1)]
}

struct CandidateStrip: View {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var selection
    @ObservedObject var engine: Engine
    var onChoose: (Int) -> Void
    var onPage: (Int32) -> Void = { _ in }
    var compact = false
    var expanded = false
    var body: some View {
        if compact {
            if engine.snapshot.candidates.isEmpty {
                Text(engine.snapshot.preedit)
                    .font(.system(size: 14)).lineLimit(1).truncationMode(.head)
                    .frame(minWidth: 56, maxWidth: 210, alignment: .leading)
                    .padding(.horizontal, 7).padding(.vertical, 4)
                    .frame(minHeight: 33)
                    .help(engine.snapshot.preedit)
                    .accessibilityLabel("輸入中：" + engine.snapshot.preedit)
            } else if expanded {
                expandedGrid
            } else {
                collapsedRow
            }
        } else {
            content.padding(16)
                .background(colorScheme == .dark ? Color(nsColor: .windowBackgroundColor) : Brand.paper,
                            in: RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(.primary.opacity(0.08)))
        }
    }
    @ViewBuilder private func japaneseMark(_ text: String) -> some View {
        if engine.snapshot.japaneseCandidates.contains(text) {
            Image(systemName: "pawprint.fill").font(.system(size: 8))
                .rotationEffect(.degrees(-20))
                .foregroundStyle(.secondary).frame(width: 10)
                .accessibilityLabel("日語候選")
        }
    }
    private var collapsedRow: some View {
        let layout = CollapsedCandidateLayout(engine.snapshot)
        let indices = layout.indices
        let widths = layout.textWidths
        return HStack(spacing: 4) {
            ForEach(indices, id: \.self) { index in
                let candidate = engine.snapshot.candidates[index]
                Button { onChoose(index) } label: {
                    HStack(spacing: 4) {
                        Text(engine.candidateShortcut(index)).font(.system(size: 10, weight: .medium, design: .monospaced))
                            .frame(width: 18)
                            .foregroundStyle(.secondary)
                        Text(candidate).font(.system(size: 14)).lineLimit(1)
                            .truncationMode(.tail)
                            .frame(width: max(0, (widths[index] ?? 0) - (engine.snapshot.japaneseCandidates.contains(candidate) ? 14 : 0)), alignment: .leading)
                        japaneseMark(candidate)
                    }
                    .padding(.horizontal, 6)
                    .padding(.vertical, 4)
                    .background {
                        if index == engine.snapshot.highlighted {
                            Capsule().fill(accent.opacity(0.28)).matchedGeometryEffect(id: "selection", in: selection)
                        }
                    }
                }
                .buttonStyle(.plain)
                .help(candidate)
                .accessibilityAddTraits(index == engine.snapshot.highlighted ? .isSelected : [])
            }
        }
        .padding(.horizontal, 7)
        .padding(.vertical, 4)
        .frame(minHeight: 33)
        .fixedSize()
        .animation(reduceMotion ? nil : .easeOut(duration: 0.16), value: engine.snapshot.highlighted)
    }

    private var expandedGrid: some View {
        let rows = expandedCandidateRows(engine.snapshot.layoutCandidates)
        let width = expandedCandidateWidth(engine.snapshot.layoutCandidates)
        return VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 6) {
                Text(engine.snapshot.preedit).font(.system(size: 11)).lineLimit(1).help(engine.snapshot.preedit)
                Spacer(minLength: 4)
                Text(engine.modeLabel).font(.system(size: 10)).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 26)
            .frame(height: 19)
            ScrollViewReader { proxy in
                ScrollView(.vertical) {
                    VStack(alignment: .leading, spacing: 3) {
            ForEach(rows.indices, id: \.self) { row in
                HStack(spacing: 4) {
                    ForEach(rows[row], id: \.self) { index in
                        let cellWidth = (width - CGFloat(rows[row].count - 1) * 4) / CGFloat(rows[row].count)
                        let markWidth: CGFloat = engine.snapshot.japaneseCandidates.contains(engine.snapshot.candidates[index]) ? 15 : 0
                        Button { onChoose(index) } label: {
                            HStack(alignment: .firstTextBaseline, spacing: 5) {
                                Text(engine.candidateShortcut(index)).font(.system(size: 10, design: .monospaced))
                                    .foregroundStyle(.secondary).frame(width: 18)
                                Text(engine.snapshot.candidates[index]).font(.system(size: 14))
                                    .frame(width: max(1, cellWidth - 35 - markWidth), alignment: .leading)
                                    .fixedSize(horizontal: false, vertical: true)
                                japaneseMark(engine.snapshot.candidates[index])
                            }
                            .padding(.horizontal, 6).padding(.vertical, 4)
                            .frame(width: cellWidth, height: expandedCandidateRowHeight(engine.snapshot.layoutCandidates, row: rows[row]), alignment: .leading)
                            .contentShape(RoundedRectangle(cornerRadius: 8))
                            .background {
                                if index == engine.snapshot.highlighted {
                                    RoundedRectangle(cornerRadius: 8).fill(accent.opacity(0.28))
                                }
                            }
                        }
                        .buttonStyle(.plain).help(engine.snapshot.candidates[index]).id(index)
                        .accessibilityAddTraits(index == engine.snapshot.highlighted ? .isSelected : [])
                    }
                }
            }
                    }
                }
                .frame(width: width, height: expandedCandidateHeight(engine.snapshot.layoutCandidates))
                .id(engine.snapshot.page)
                .onChange(of: engine.snapshot.highlighted) { _, index in proxy.scrollTo(index) }
                .onChange(of: engine.snapshot.page) { _, _ in proxy.scrollTo(0, anchor: .top) }
            }
            if engine.snapshot.page > 0 || !engine.snapshot.lastPage {
                HStack(spacing: 8) {
                    Spacer()
                    pageButton(0xff55, symbol: "chevron.left", label: "上一頁")
                        .disabled(engine.snapshot.page == 0)
                    Text("\(engine.snapshot.page + 1)").font(.system(size: 10, design: .monospaced)).foregroundStyle(.secondary)
                    pageButton(0xff56, symbol: "chevron.right", label: "下一頁")
                        .disabled(engine.snapshot.lastPage)
                    Spacer()
                }
            }
        }
        .padding(7).frame(width: width + 14)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.16), value: engine.snapshot.highlighted)
    }

    private func pageButton(_ key: Int32, symbol: String, label: String) -> some View {
        Button { onPage(key) } label: {
            HStack(spacing: 3) {
                Image(systemName: symbol)
                Text(label)
            }
            .font(.system(size: 10, weight: .medium))
            .padding(.horizontal, 5).frame(height: 24)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .help(label + (key == 0xff55 ? "（⌥← / Page Up）" : "（⌥→ / Page Down）"))
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: compact ? 4 : 12) {
            HStack {
                if !compact { BrandMark(size: 27) }
                Text(engine.snapshot.preedit.isEmpty ? "Meowpuut" : engine.snapshot.preedit)
                    .font(.system(size: compact ? 13 : 16, weight: .medium))
                    .lineLimit(compact ? 1 : nil)
                Spacer()
                Text(engine.modeLabel).font(.caption).foregroundStyle(.secondary)
                if compact {
                    if engine.snapshot.page > 0 {
                        pageButton(0xff55, symbol: "chevron.left", label: "上一頁")
                    }
                    if !engine.snapshot.lastPage {
                        pageButton(0xff56, symbol: "chevron.right", label: "下一頁")
                    }
                }
            }
            if !engine.snapshot.candidates.isEmpty || engine.browsing {
                VStack(spacing: compact ? 1 : 3) {
                    ForEach(Array(engine.snapshot.candidates.prefix(7).enumerated()), id: \.offset) { index, candidate in
                        HStack(spacing: 0) {
                            Button { onChoose(index) } label: {
                                HStack(spacing: compact ? 5 : 12) {
                                    Text(engine.candidateShortcut(index)).font(.system(size: 11, weight: .medium, design: .monospaced)).opacity(0.5).frame(width: compact ? 21 : 26)
                                    Text(candidate).font(.system(size: compact ? 14 : 18)).lineLimit(2).multilineTextAlignment(.leading)
                                    japaneseMark(candidate)
                                    Spacer(minLength: 0)
                                }.padding(.horizontal, compact ? 5 : 10).padding(.vertical, compact ? 2 : 8)
                                    .background {
                                        if index == engine.snapshot.highlighted {
                                            RoundedRectangle(cornerRadius: 9).fill(accent.opacity(0.28))
                                                .matchedGeometryEffect(id: "selection", in: selection)
                                        }
                                    }
                                    .animation(reduceMotion ? nil : .easeOut(duration: 0.16), value: engine.snapshot.highlighted)
                            }.buttonStyle(.plain).help(candidate)
                                .accessibilityAddTraits(index == engine.snapshot.highlighted ? .isSelected : [])

                        }
                    }
                }
                if !compact {
                    HStack {
                        Button(engine.browsing && !engine.phrasesOnly ? "全部 ✓" : "更多") { engine.browse(phrases: false) }.disabled(engine.snapshot.input.isEmpty)
                        Button(engine.phrasesOnly ? "词组 ✓" : "词组") { engine.browse(phrases: true) }.disabled(engine.snapshot.input.isEmpty)
                        if engine.browsing { Text("\(engine.browseCount == 256 ? "前 " : "")\(engine.browseCount) 项").font(.system(size: 11)).foregroundStyle(.secondary) }
                        Text("↑↓ 选词 · ↵ 确认").font(.system(size: 11)).foregroundStyle(.secondary)
                        Spacer()
                        HStack(spacing: 0) {
                            Button { onPage(0xff55) } label: {
                                Image(systemName: "chevron.left").frame(width: 30, height: 28)
                            }.disabled(engine.snapshot.page == 0).accessibilityLabel("上一頁")
                            Text("\(engine.snapshot.page + 1)").font(.system(size: 11, design: .monospaced))
                            Button { onPage(0xff56) } label: {
                                Image(systemName: "chevron.right").frame(width: 30, height: 28)
                            }.disabled(engine.snapshot.lastPage).accessibilityLabel("下一頁")
                        }.glassEffect(.regular, in: .capsule)
                    }.buttonStyle(.plain)
                }
            }
        }
    }
}

final class TypingView: NSTextView {
    var engine: Engine?
    override func keyDown(with event: NSEvent) {
        guard let engine, let (key, modifiers) = inputKey(event) else { super.keyDown(with: event); return }
        let handled = engine.key(key, modifiers: modifiers)
        let commit = engine.takeCommit()
        if !commit.isEmpty { insertText(commit, replacementRange: selectedRange()) }
        if !handled { super.keyDown(with: event) }
    }
}
struct TypingPad: NSViewRepresentable {
    var engine: Engine
    func makeNSView(context: Context) -> NSScrollView {
        let scroll = NSScrollView()
        let text = TypingView()
        text.engine = engine; text.font = .systemFont(ofSize: 23)
        text.textContainerInset = NSSize(width: 20, height: 20)
        text.isRichText = false; text.isAutomaticQuoteSubstitutionEnabled = false
        text.isVerticallyResizable = true; text.autoresizingMask = [.width]
        text.textContainer?.widthTracksTextView = true
        text.backgroundColor = .textBackgroundColor
        scroll.documentView = text; scroll.hasVerticalScroller = true
        scroll.drawsBackground = false
        return scroll
    }
    func updateNSView(_ view: NSScrollView, context: Context) {}
}

struct Studio: View {
    @Environment(\.colorScheme) private var colorScheme
    @StateObject var engine = Engine()
    @ObservedObject var preferences = Preferences.shared
    @ObservedObject var memory = ChoiceMemory.shared
    @State private var confirmingMemoryClear = false
    @State private var section = "试打"
    @State private var inputSourceStatus = ""
    var body: some View {
        NavigationSplitView {
            VStack(alignment: .leading, spacing: 24) {
                HStack(spacing: 10) {
                    BrandMark(size: 36)
                    Text("Meowpuut").font(.system(size: 22, weight: .black)).tracking(-0.9)
                }.padding(.top, 20)
                ForEach(["试打", "候选词", "扩展", "快捷键"], id: \.self) { item in
                    Button { section = item } label: {
                        Label(item, systemImage: item == "试打" ? "keyboard" : item == "候选词" ? "text.alignleft" : item == "扩展" ? "square.stack.3d.up" : "command")
                            .frame(maxWidth: .infinity, alignment: .leading).padding(12)
                            .background(section == item ? accent.opacity(0.12) : .clear, in: RoundedRectangle(cornerRadius: 10))
                    }.buttonStyle(.plain)
                }
                Spacer()
                Text("0.1 开发版").font(.caption2).foregroundStyle(.secondary)
            }.padding(20).navigationSplitViewColumnWidth(220)
        } detail: {
            ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text(section).font(.system(size: 28, weight: .semibold))
                if section == "试打" {
                    Picker("輸入方式", selection: Binding(get: { engine.inputMode }, set: { mode in
                        engine.finishComposition(); engine.selectMode(mode)
                    })) {
                        ForEach(InputMode.allCases) { Text($0.title).tag($0) }
                    }
                    Text("Meowpuut 自研核心").font(.caption).foregroundStyle(.secondary)
                    HStack {
                        Picker("输出字形", selection: $preferences.simplified) {
                            Text("繁體中文").tag(false); Text("简体中文").tag(true)
                        }.pickerStyle(.segmented).frame(width: 260)
                        Spacer()
                        Text(engine.english ? "ABC 英文" : engine.inputMode.title).font(.callout.weight(.medium)).foregroundStyle(accent)
                    }
                    TypingPad(engine: engine).frame(height: 140).clipShape(RoundedRectangle(cornerRadius: 16))
                        .overlay(RoundedRectangle(cornerRadius: 16).stroke(.primary.opacity(0.08)))
                    CandidateStrip(engine: engine, onChoose: { index in
                        engine.choose(index)
                        if let text = NSApp.keyWindow?.firstResponder as? TypingView { text.insertText(engine.takeCommit(), replacementRange: text.selectedRange()) }
                    }, onPage: { key in engine.key(key) })
                    Text("大千键盘 · 空格标一声 · Tab / 方向键选词 · 回车确认\nShift + Space 切换中英 · ⇧数字选词 · Option + Space 短语")
                        .font(.caption).foregroundStyle(.secondary).lineSpacing(6)
                    if !engine.error.isEmpty { Text(engine.error).foregroundStyle(.red) }
                } else if section == "候选词" {
                    Toggle("自動學習用詞", isOn: $preferences.rememberChoices)
                    HStack {
                        Text("已记住 \(memory.records.count) 組讀音").font(.caption).foregroundStyle(.secondary)
                        Spacer()
                        Button("清除选词记录") { confirmingMemoryClear = true }.disabled(memory.records.isEmpty && memory.error.isEmpty)
                    }
                    if !memory.error.isEmpty { Text(memory.error).foregroundStyle(.red) }
                    if !memory.records.isEmpty {
                        Text("选词记录").font(.headline)
                        LazyVStack(spacing: 8) {
                            ForEach(memory.records.keys.filter { !$0.hasPrefix("ctx|") }.sorted(), id: \.self) { key in
                                HStack {
                                    VStack(alignment: .leading, spacing: 5) {
                                        Text(memory.records[key]?.first?.text ?? "").font(.system(size: 18))
                                        Text(readingLabel(key)).font(.caption).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    Button("忘记") { memory.forget(key); engine.refresh() }
                                }.padding(14).background(.quaternary, in: RoundedRectangle(cornerRadius: 12))
                            }
                        }
                    }
                } else if section == "扩展" {
                    Toggle("启用本地短语模块", isOn: $preferences.modulesEnabled)
                    ForEach(preferences.modules) { module in
                        HStack {
                            Image(systemName: "text.bubble").foregroundStyle(accent).font(.title2)
                            VStack(alignment: .leading) { Text(module.name).font(.headline); Text("\(module.trigger) · \(module.phrases.count) 条短语").foregroundStyle(.secondary) }
                            Spacer(); Text("API v\(module.apiVersion)").font(.caption).foregroundStyle(.secondary)
                        }.padding(18).background(.quaternary, in: RoundedRectangle(cornerRadius: 12))
                    }
                    HStack {
                        Button("打开模块文件夹") { NSWorkspace.shared.open(preferences.moduleDirectory) }
                        Button("重新加载") { preferences.reload() }
                    }
                    if !preferences.moduleError.isEmpty { Text(preferences.moduleError).foregroundStyle(.red) }
                    Text("添加或编辑模块后，点重新加载。").font(.caption).foregroundStyle(.secondary)
                } else {
                    ForEach([("↑ / ↓ · Tab / ⇧Tab", "选择候选"), ("回车", "提交整句"), ("⇧回车", "按原文输出英文"), ("⇧1–7", "选择对应候选"), ("PageUp / PageDown", "翻页"), ("Shift + Space", "切换中英"), ("Escape", "退出筛选 / 取消组合"), ("Option + Space", "打开短语")], id: \.0) { key, action in
                        HStack { Text(key).font(.system(.body, design: .monospaced)); Spacer(); Text(action).foregroundStyle(.secondary) }.padding(.vertical, 6)
                    }
                    HStack {
                        Button("启用 Meowpuut") {
                            do { try SystemInput.enable(); inputSourceStatus = "已添加到输入法列表。" }
                            catch { inputSourceStatus = error.localizedDescription }
                        }
                        Button("打开键盘设置") { NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.Keyboard-Settings.extension")!) }
                    }
                    if !inputSourceStatus.isEmpty { Text(inputSourceStatus).font(.caption).foregroundStyle(.secondary) }
                }
                Spacer(minLength: 0)
                Button("第三方授權") {
                    if let url = Bundle.main.resourceURL?.appendingPathComponent("Licenses") { NSWorkspace.shared.open(url) }
                }.font(.caption).buttonStyle(.link)
            }.padding(36).padding(.top, 16).frame(maxWidth: .infinity, alignment: .topLeading)
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(colorScheme == .dark ? Color(nsColor: .windowBackgroundColor) : Brand.paper)
        }.tint(accent).frame(minWidth: 920, minHeight: 650)
        .onChange(of: preferences.simplified) { _, _ in engine.modeChanged() }
        .onChange(of: preferences.rememberChoices) { _, _ in engine.refresh() }
        .confirmationDialog("清除选词记录？", isPresented: $confirmingMemoryClear) {
            Button("清除记录", role: .destructive) { memory.clear(); engine.refresh() }
            Button("取消", role: .cancel) {}
        } message: { Text("固定首选会保留。") }
    }
}
