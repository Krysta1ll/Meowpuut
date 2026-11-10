import SwiftUI
import AppKit

struct CandidateSelection: View {
    var radius: CGFloat
    @Environment(\.colorScheme) private var scheme
    @ObservedObject private var preferences = Preferences.shared
    private var accent: Color { preferences.candidatePalette.color(dark: scheme == .dark) }
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast

    var body: some View {
        RoundedRectangle(cornerRadius: radius)
            .fill(LinearGradient(colors: [
                accent.opacity(reduceTransparency ? 0.65 : (scheme == .dark ? 0.36 : 0.29)),
                accent.opacity(reduceTransparency ? 0.65 : (scheme == .dark ? 0.20 : 0.15))
            ], startPoint: .topLeading, endPoint: .bottomTrailing))
            .overlay {
                RoundedRectangle(cornerRadius: radius).strokeBorder(
                    LinearGradient(colors: [.white.opacity(contrast == .increased ? 0.7 : 0.38), .white.opacity(0.04)],
                                   startPoint: .top, endPoint: .bottom), lineWidth: 0.6)
            }
            .offset(y: 1.5)
            .allowsHitTesting(false)
    }
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
    var maximumContentHeight: CGFloat = .greatestFiniteMagnitude
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
        if engine.snapshot.japaneseCandidates.contains(text) || engine.snapshot.compactJapanese?.text == text {
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
        let requiredHeight = collapsedCandidateHeight(engine.snapshot, layout: layout)
        let height = min(maximumContentHeight, requiredHeight)
        let textWidth: CGFloat = indices.reduce(0) { $0 + (widths[$1] ?? 0) }
        let spacingWidth = CGFloat(indices.count * 34 + max(0, indices.count - 1) * 4)
        let width: CGFloat = 14 + spacingWidth + textWidth
        return ScrollView(.vertical) {
        HStack(alignment: requiredHeight > 33 ? .top : .center, spacing: 4) {
            ForEach(Array(indices.enumerated()), id: \.element) { ordinal, index in
                let candidate = compactCandidateText(engine.snapshot, at: index) ?? ""
                let isJapanese = engine.snapshot.japaneseCandidates.contains(candidate) || engine.snapshot.compactJapanese?.text == candidate
                let selected = index == engine.snapshot.highlighted
                Button { onChoose(index) } label: {
                    HStack(alignment: requiredHeight > 33 ? .top : .center, spacing: 4) {
                        Text(engine.candidateShortcut(ordinal)).font(.system(size: 10, weight: .medium, design: .monospaced))
                            .frame(width: 18)
                            .foregroundStyle(.secondary)
                        Text(candidate).font(.system(size: 14)).lineLimit(selected && requiredHeight > 33 ? nil : 1)
                            .truncationMode(.tail)
                            .frame(width: max(0, (widths[index] ?? 0) - (isJapanese ? 14 : 0)), alignment: .leading)
                            .fixedSize(horizontal: false, vertical: selected && requiredHeight > 33)
                        japaneseMark(candidate)
                    }
                    .padding(.horizontal, 6)
                    .padding(.vertical, 4)
                    .background {
                        if selected {
                            CandidateSelection(radius: 20).matchedGeometryEffect(id: "selection", in: selection)
                        }
                    }
                }
                .buttonStyle(.plain)
                .help(candidate)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(.horizontal, 7)
        .padding(.vertical, 4)
        .frame(minHeight: 33)
        .fixedSize(horizontal: false, vertical: true)
        }
        .frame(width: width, height: height)
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
                                                CandidateSelection(radius: 8)
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
                .frame(width: width, height: min(maximumContentHeight, expandedCandidateHeight(engine.snapshot.layoutCandidates)))
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
                                            CandidateSelection(radius: 9)
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
                        Button(engine.phrasesOnly ? "詞組 ✓" : "詞組") { engine.browse(phrases: true) }.disabled(engine.snapshot.input.isEmpty)
                        if engine.browsing { Text("\(engine.browseCount == 256 ? "前 " : "")\(engine.browseCount) 項").font(.system(size: 11)).foregroundStyle(.secondary) }
                        Text("↑↓ 選詞 · ↵ 確認").font(.system(size: 11)).foregroundStyle(.secondary)
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
