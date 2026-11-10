import UIKit
import SwiftUI

extension KeyboardPanel {
    func candidateText(_ index: Int) -> String {
        if engine.snapshot.candidates.indices.contains(index) { return engine.snapshot.candidates[index] }
        return engine.snapshot.compactJapanese?.text ?? ""
    }
    func isJapaneseCandidate(_ index: Int) -> Bool {
        index >= engine.snapshot.candidates.count || engine.snapshot.japaneseCandidates.contains(candidateText(index))
    }
    func candidateWidth(_ index: Int) -> CGFloat {
        let text = candidateText(index) as NSString
        let font = UIFont.preferredFont(forTextStyle: .title3)
        let marker: CGFloat = isJapaneseCandidate(index) ? 13 : 0
        return max(44, ceil(text.size(withAttributes: [.font: font]).width) + 18 + marker + numberWidth(index))
    }
    // The compact strip numbers its candidates, "1 你好  2 妳好 …".
    func numberWidth(_ index: Int) -> CGFloat {
        guard !expanded else { return 0 }
        let font = UIFont.monospacedDigitSystemFont(ofSize: UIFont.preferredFont(forTextStyle: .footnote).pointSize, weight: .regular)
        return ceil(("\(index + 1)" as NSString).size(withAttributes: [.font: font]).width) + 5
    }
    func candidatePages(width: CGFloat) -> [[KeyboardLayoutMetrics.CandidateItem]] {
        var widths = engine.snapshot.candidates.indices.map(candidateWidth)
        if expanded {
            return KeyboardLayoutMetrics.candidatePages(widths: widths, availableWidth: width).map { page in
                page.map { KeyboardLayoutMetrics.CandidateItem(index: $0, width: min(width, widths[$0])) }
            }
        }
        let japanese = Set(engine.snapshot.candidates.indices.filter {
            engine.snapshot.japaneseCandidates.contains(engine.snapshot.candidates[$0])
        })
        var japaneseIndices = japanese
        if engine.snapshot.compactJapanese != nil {
            japaneseIndices.insert(widths.count)
            widths.append(candidateWidth(widths.count))
        }
        return KeyboardLayoutMetrics.compactCandidatePages(widths: widths, japaneseIndices: japaneseIndices, availableWidth: width)
    }
    var candidateBarHeight: CGFloat {
        computeCandidateBarHeight(width: availableWidth)
    }
    func computeCandidateBarHeight(width keyboardWidth: CGFloat) -> CGFloat {
        let minimum = preferences.catGlassSkin ? KeyboardCandidateGlass.catBarHeight : KeyboardLayoutMetrics.candidateHeight
        if expanded || (engine.snapshot.candidates.isEmpty && engine.snapshot.compactJapanese == nil) {
            return minimum
        }
        // Short candidates (<= 6 characters) never wrap vertically in the bar,
        // avoiding expensive per-keystroke CoreText boundingRect passes.
        let hasLongCandidate = engine.snapshot.candidates.contains { $0.count > 6 }
            || (engine.snapshot.compactJapanese?.text.count ?? 0) > 6
        if !hasLongCandidate {
            return minimum
        }
        let catInsets: CGFloat = preferences.catGlassSkin ? KeyboardCandidateGlass.horizontalInsets : 8
        let width = max(44, keyboardWidth - 10 - catInsets - 44 - 12)
        let font = UIFont.preferredFont(forTextStyle: .title3)
        let heights = candidatePages(width: width).flatMap { $0 }.map { item -> CGFloat in
            let index = item.index
            let text = candidateText(index) as NSString
            let marker: CGFloat = (isJapaneseCandidate(index) ? 13 : 0) + numberWidth(index)
            let bounds = text.boundingRect(with: CGSize(width: max(1, item.width - 18 - marker), height: .greatestFiniteMagnitude),
                                           options: [.usesLineFragmentOrigin, .usesFontLeading],
                                           attributes: [.font: font], context: nil)
            return ceil(bounds.height) + 14
        }
        let screenHeight = keyboardController()?.view.window?.windowScene?.screen.bounds.height ?? UIScreen.main.bounds.height
        return KeyboardLayoutMetrics.candidateBarHeight(contentHeight: max(minimum, heights.max() ?? 44),
                                                        keyboardWidth: keyboardWidth, screenHeight: screenHeight,
                                                        rowCount: visibleRowCount)
    }
    func selectCandidate(_ index: Int) {
        KeyboardHaptics.keyPress()
        choose(index)
        expanded = false
    }
    func selectPrediction(_ index: Int) {
        guard let text = engine.choosePrediction(index) else { return }
        KeyboardHaptics.keyPress()
        insertSymbol(text)
    }
    var idlePredictionOrPunctuationOverlay: some View {
        HStack(spacing: 4) {
            if !engine.predictions.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(Array(engine.predictions.enumerated()), id: \.offset) { index, word in
                            Button { selectPrediction(index) } label: {
                                Text(word)
                                    .font(.title3)
                                    .padding(.horizontal, 10)
                                    .frame(minWidth: 38, minHeight: 34)
                                    .background(
                                        Capsule().fill(accent.opacity(scheme == .dark ? 0.16 : 0.11))
                                    )
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(CandidatePressStyle(tint: accent, dark: scheme == .dark))
                            .accessibilityLabel(t("聯想詞：", "联想词：") + word)
                        }
                    }
                    .padding(.horizontal, 6)
                }
            } else {
                if !engine.english && engine.inputMode != .japanese {
                    HStack(spacing: 2) {
                        ForEach(["，", "。", "？", "！", "、"], id: \.self) { mark in
                            Button {
                                KeyboardHaptics.keyPress()
                                insertSymbol(mark)
                            } label: {
                                Text(mark)
                                    .font(.system(size: 17, weight: .medium))
                                    .foregroundStyle(.secondary)
                                    .frame(width: 32, height: 36)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(CandidatePressStyle(tint: accent, dark: scheme == .dark))
                            .accessibilityLabel(t("標點：", "标点：") + mark)
                        }
                    }
                    .padding(.leading, 4)
                }
                Spacer(minLength: 4)
                Text(idleStatus)
                    .font(.footnote.weight(.medium)).tracking(1)
                    .foregroundStyle(.secondary).opacity(0.75)
                    .contentTransition(.numericText())
                    .animation(reduceMotion ? nil : .snappy, value: idleStatus)
                    .padding(.trailing, 8)
                    .allowsHitTesting(false)
                    .accessibilityLabel(t("目前輸入：", "当前输入：") + idleStatus)
                if engine.english || engine.inputMode == .japanese {
                    Spacer(minLength: 4)
                }
            }
        }
    }
    var selectionGlass: Glass {
        switch preferences.glassStrength {
        case .soft: .regular.tint(accent.opacity(0.10))
        case .balanced: .clear.tint(accent.opacity(0.16))
        case .vivid: .clear.tint(accent.opacity(0.20))
        }
    }
    var candidateSelection: some View {
        let accessible = reduceTransparency || contrast == .increased
        let opacity: Double = switch preferences.glassStrength {
        case .soft: 0.34
        case .balanced: 0.29
        case .vivid: 0.24
        }
        return ZStack {
            if preferences.catGlassSkin && !accessible {
                // A tinted Liquid Glass bubble floating inside the glass bar.
                Capsule().fill(accent.opacity(0.16))
                    .glassEffect(.regular.tint(accent.opacity(0.42)), in: .capsule)
            } else if accessible {
                // A solid base keeps text legible when translucent material is disabled.
                Capsule().fill(scheme == .dark ? Color(white: 0.20) : Color(white: 0.94))
                Capsule().fill(accent.opacity(scheme == .dark ? 0.38 : 0.25))
            } else {
                // The system glass is isolated on an empty capsule behind the
                // label; candidate text remains outside its foreground effect.
                Capsule().fill(.clear)
                    .glassEffect(selectionGlass, in: .capsule)
                Capsule().fill(LinearGradient(stops: [
                    .init(color: accent.opacity(opacity * 0.55), location: 0),
                    .init(color: accent.opacity(opacity * 0.28), location: 0.55),
                    .init(color: accent.opacity(opacity * 0.16), location: 1)
                ], startPoint: .topLeading, endPoint: .bottomTrailing))
                Capsule().inset(by: 1).strokeBorder(LinearGradient(colors: [
                    .white.opacity(scheme == .dark ? 0.26 : 0.42), .clear,
                    .white.opacity(scheme == .dark ? 0.17 : 0.25)
                ], startPoint: .top, endPoint: .bottom), lineWidth: 0.65)
            }
            Capsule().strokeBorder(LinearGradient(colors: [.white.opacity(scheme == .dark ? 0.42 : 0.66),
                                                           .white.opacity(scheme == .dark ? 0.10 : 0.18),
                                                           .black.opacity(scheme == .dark ? 0.18 : 0.12)],
                                                  startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 0.7)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
    func candidateButton(_ index: Int) -> some View {
        Button { selectCandidate(index) } label: {
            ScrollView(.vertical, showsIndicators: false) {
                candidateLabel(candidateText(index), japanese: isJapaneseCandidate(index), number: index + 1).font(.title3)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 9).padding(.vertical, 5)
            }
                .scrollEdgeEffectHidden(true, for: .all)
                .frame(minWidth: 44, minHeight: 34, maxHeight: candidateBarHeight - 10)
                // The cat silhouette starts 8pt down across the middle of the
                // bar. Keep the capsule within that contour rather than let
                // the outer clip flatten its top edge; the 44pt touch area is
                // unchanged.
                .background { if index == engine.snapshot.highlighted { candidateSelection.padding(.vertical, 3).offset(y: preferences.catGlassSkin ? 0 : 1.5) } }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }.buttonStyle(CandidatePressStyle(tint: accent, dark: scheme == .dark))
            .accessibilityAddTraits(index == engine.snapshot.highlighted ? .isSelected : [])
    }
    var expandedCandidates: some View {
        VStack(spacing: 0) {
            HStack(spacing: 2) {
                expandedFilter("全部", phrases: false)
                expandedFilter(t("詞組", "词组"), phrases: true)
                Spacer(minLength: 4)
                Button { key(0xff55, 0) } label: {
                    Image(systemName: "chevron.left").frame(width: 44, height: 44)
                }
                .disabled(engine.snapshot.page == 0)
                .accessibilityLabel(t("上一頁", "上一页"))
                Text("\(engine.snapshot.page + 1) / \(max(1, (engine.browseCount + 6) / 7))")
                    .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    .fixedSize().accessibilityIdentifier("expanded.page")
                Button { key(0xff56, 0) } label: {
                    Image(systemName: "chevron.right").frame(width: 44, height: 44)
                }
                .disabled(engine.snapshot.lastPage)
                .accessibilityLabel(t("下一頁", "下一页"))
            }
            .font(.subheadline.weight(.medium))
            .buttonStyle(.plain)
            .padding(.horizontal, 4)

            Rectangle().fill(.primary.opacity(scheme == .dark ? 0.10 : 0.08))
                .frame(height: 0.5).padding(.horizontal, 12)

            GeometryReader { area in
                // Show whole rows at every keyboard height; scrolling reveals the rest.
                let rows = max(1, floor((area.size.height + 2) / 46))
                let listHeight = min(area.size.height, rows * 46 - 2)
                ScrollView {
                    LazyVStack(spacing: 2) {
                        ForEach(expandedCandidateRows(width: area.size.width), id: \.self) { row in
                            HStack(spacing: 2) {
                                ForEach(row, id: \.self) { index in
                                    let text = engine.snapshot.candidates[index]
                                    Button { selectCandidate(index) } label: {
                                        candidateLabel(text)
                                            .font(.system(size: 18))
                                            .fixedSize(horizontal: false, vertical: true)
                                            .padding(.horizontal, 12).padding(.vertical, 8)
                                            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                                            .contentShape(Rectangle())
                                    }
                                    .buttonStyle(CandidatePressStyle(tint: accent, dark: scheme == .dark))
                                    .accessibilityAddTraits(index == engine.snapshot.highlighted ? .isSelected : [])
                                    .background {
                                        if index == engine.snapshot.highlighted {
                                            candidateSelection.padding(.vertical, 3)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .padding(.horizontal, 5)
                }
                .scrollIndicators(.visible)
                .scrollEdgeEffectHidden(true, for: .all)
                // A new reading, filter or page starts at its first candidate.
                .id("\(engine.snapshot.input)|\(engine.phrasesOnly)|\(engine.snapshot.page)")
                .accessibilityIdentifier("expanded.candidates")
                .frame(height: listHeight, alignment: .top)
            }
            .overlay {
                if engine.browsing && engine.browseCount == 0 {
                    Text(engine.phrasesOnly ? t("暫無詞組", "暂无词组") : t("暫無候選", "暂无候选"))
                        .font(.subheadline).foregroundStyle(.secondary)
                        .allowsHitTesting(false)
                }
            }

        }
        .background {
            RoundedRectangle(cornerRadius: 15, style: .continuous)
                .fill(scheme == .dark ? Color.white.opacity(0.045) : Color.white.opacity(0.22))
                .overlay {
                    RoundedRectangle(cornerRadius: 15, style: .continuous)
                        .strokeBorder(.white.opacity(scheme == .dark ? 0.12 : 0.38), lineWidth: 0.6)
                }
                .allowsHitTesting(false)
        }
        .frame(height: layout.expandedHeight)
    }
    // Give long candidates their own row while keeping short ones compact.
    func expandedCandidateRows(width: CGFloat) -> [[Int]] {
        let columnWidth = max(0, (width - 12) / 2)
        let font = UIFont.systemFont(ofSize: 18)
        var rows: [[Int]] = []
        var pending: Int?
        for (index, text) in engine.snapshot.candidates.enumerated() {
            let marker: CGFloat = engine.snapshot.japaneseCandidates.contains(text) ? 13 : 0
            let needed = (text as NSString).size(withAttributes: [.font: font]).width + 24 + marker
            if needed > columnWidth {
                if let short = pending { rows.append([short]); pending = nil }
                rows.append([index])
            } else if let short = pending {
                rows.append([short, index]); pending = nil
            } else {
                pending = index
            }
        }
        if let short = pending { rows.append([short]) }
        return rows
    }
    func expandedFilter(_ title: String, phrases: Bool) -> some View {
        Button { engine.browse(phrases: phrases) } label: {
            Text(title)
                .foregroundStyle(engine.phrasesOnly == phrases ? Color.primary : Color.secondary)
                .frame(minWidth: 48, minHeight: 44)
                .background {
                    if engine.phrasesOnly == phrases {
                        Capsule().fill(accent.opacity(scheme == .dark ? 0.24 : 0.17))
                            .padding(.vertical, 7)
                    }
                }
                .contentShape(Rectangle())
        }
        .accessibilityAddTraits(engine.phrasesOnly == phrases ? .isSelected : [])
    }
    func candidateLabel(_ text: String, japanese: Bool? = nil, number: Int? = nil) -> some View {
        HStack(spacing: 4) {
            if let number {
                Text("\(number)").font(.footnote.monospacedDigit()).foregroundStyle(.secondary)
                    .padding(.trailing, 1).accessibilityHidden(true)
            }
            Text(text)
            if japanese ?? engine.snapshot.japaneseCandidates.contains(text) {
                Image(systemName: "pawprint.fill").font(.system(size: 9)).rotationEffect(.degrees(-20))
                    .foregroundStyle(.secondary).accessibilityLabel(t("日語候選", "日语候选"))
            }
        }
    }
}
