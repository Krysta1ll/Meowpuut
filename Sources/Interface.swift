import SwiftUI
import AppKit

let accent = Color(red: 0.40, green: 0.36, blue: 0.82)

struct CandidateStrip: View {
    @ObservedObject var engine: Engine
    var onChoose: (Int) -> Void
    var onPage: (Int32) -> Void = { _ in }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "cat.fill").foregroundStyle(accent)
                Text(engine.snapshot.preedit.isEmpty ? "Meowpuut" : engine.snapshot.preedit).font(.system(size: 16, weight: .medium))
                Spacer()
                Text(engine.english ? "ABC" : (Preferences.shared.simplified ? "简" : "繁")).font(.caption).foregroundStyle(.secondary)
            }
            if !engine.snapshot.candidates.isEmpty || engine.browsing {
                VStack(spacing: 3) {
                    ForEach(Array(engine.snapshot.candidates.prefix(7).enumerated()), id: \.offset) { index, candidate in
                        HStack(spacing: 0) {
                        Button { onChoose(index) } label: {
                            HStack(spacing: 12) {
                                Text("⇧\(index + 1)").font(.system(size: 11, weight: .medium, design: .monospaced)).opacity(0.5).frame(width: 26)
                                Text(candidate).font(.system(size: 18)).lineLimit(2).multilineTextAlignment(.leading)
                                Spacer(minLength: 0)
                            }.padding(.horizontal, 10).padding(.vertical, 8)
                                .background(index == engine.snapshot.highlighted ? accent.opacity(0.15) : Color.clear, in: RoundedRectangle(cornerRadius: 9))
                        }.buttonStyle(.plain).help(candidate)
                        if !engine.snapshot.input.isEmpty {
                            Button { engine.pin(index) } label: { Image(systemName: engine.isPinned(candidate) ? "pin.fill" : "pin").foregroundStyle(engine.isPinned(candidate) ? accent : .secondary) }
                                .buttonStyle(.plain).help(engine.isPinned(candidate) ? "取消固定首选" : "固定为这组注音的首选").padding(.trailing, 10)
                        }
                        }
                    }
                }
                HStack {
                    Button(engine.browsing && !engine.phrasesOnly ? "全部 ✓" : "更多") { engine.browse(phrases: false) }.disabled(engine.snapshot.input.isEmpty)
                    Button(engine.phrasesOnly ? "词组 ✓" : "词组") { engine.browse(phrases: true) }.disabled(engine.snapshot.input.isEmpty)
                    if engine.browsing { Text("\(engine.browseCount == 256 ? "前 " : "")\(engine.browseCount) 项").font(.system(size: 11)).foregroundStyle(.secondary) }
                    Text("↑↓ 选词 · ↵ 确认").font(.system(size: 11)).foregroundStyle(.secondary)
                    Spacer()
                    Button { onPage(0xff55) } label: { Image(systemName: "chevron.left") }.disabled(engine.snapshot.page == 0)
                    Text("\(engine.snapshot.page + 1)").font(.system(size: 11, design: .monospaced)).foregroundStyle(.secondary)
                    Button { onPage(0xff56) } label: { Image(systemName: "chevron.right") }.disabled(engine.snapshot.lastPage)
                }.buttonStyle(.plain)
            }
        }.padding(16).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(.primary.opacity(0.08)))
    }
}

final class TypingView: NSTextView {
    var engine: Engine?
    override func keyDown(with event: NSEvent) {
        guard let engine, let (key, modifiers) = rimeKey(event) else { super.keyDown(with: event); return }
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
    @StateObject var engine = Engine()
    @ObservedObject var preferences = Preferences.shared
    @State private var section = "试打"
    @State private var inputSourceStatus = ""
    var body: some View {
        NavigationSplitView {
            VStack(alignment: .leading, spacing: 24) {
                HStack(spacing: 10) {
                    Image(systemName: "cat.fill").font(.system(size: 26)).foregroundStyle(accent)
                    Text("Meowpuut").font(.system(size: 20, weight: .semibold, design: .rounded))
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
                    HStack {
                        Picker("输出字形", selection: $preferences.simplified) {
                            Text("繁體注音").tag(false); Text("简体注音").tag(true)
                        }.pickerStyle(.segmented).frame(width: 260)
                        Spacer()
                        Text(engine.english ? "ABC 英文" : "ㄅ 注音").font(.callout.weight(.medium)).foregroundStyle(accent)
                    }
                    TypingPad(engine: engine).frame(height: 140).clipShape(RoundedRectangle(cornerRadius: 16))
                        .overlay(RoundedRectangle(cornerRadius: 16).stroke(.primary.opacity(0.08)))
                    CandidateStrip(engine: engine, onChoose: { index in
                        engine.choose(index)
                        if let text = NSApp.keyWindow?.firstResponder as? TypingView { text.insertText(engine.takeCommit(), replacementRange: text.selectedRange()) }
                    }, onPage: { key in engine.key(key) })
                    Text("大千键盘 · 空格标一声 · Tab / 方向键选词 · 回车确认\nShift + Space 切换中英 · ⇧数字选词 · /hello 调用短语模块")
                        .font(.caption).foregroundStyle(.secondary).lineSpacing(6)
                    if !engine.error.isEmpty { Text(engine.error).foregroundStyle(.red) }
                } else if section == "候选词" {
                    Text("固定首选").font(.headline)
                    if preferences.pins.isEmpty { Text("在候选词旁点图钉，即可固定首选。").foregroundStyle(.secondary) }
                    ForEach(preferences.pins.keys.sorted(), id: \.self) { key in
                        HStack {
                            VStack(alignment: .leading, spacing: 5) {
                                Text(preferences.pins[key] ?? "").font(.system(size: 20))
                                Text(readingLabel(key)).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("取消固定") { preferences.pins.removeValue(forKey: key); engine.refresh() }
                        }.padding(14).background(.quaternary, in: RoundedRectangle(cornerRadius: 12))
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
                    ForEach([("↑ / ↓ · Tab / ⇧Tab", "选择候选"), ("回车", "提交整句"), ("⇧回车", "按原文输出英文"), ("⇧1–7", "选择对应候选"), ("PageUp / PageDown", "翻页"), ("Shift + Space", "切换中英"), ("Escape", "退出筛选 / 取消组合"), ("/hello", "常用短语")], id: \.0) { key, action in
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
            }.padding(36).padding(.top, 16).frame(maxWidth: .infinity, alignment: .topLeading)
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
        }.tint(accent).frame(minWidth: 920, minHeight: 650)
        .onChange(of: preferences.simplified) { _, _ in engine.modeChanged() }
    }
}
