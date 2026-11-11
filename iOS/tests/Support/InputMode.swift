// Stands in for the enum in Sources/InputBackend.swift, which cannot be
// compiled without the Rust engine. Only the key tests use it.
enum InputMode: String, CaseIterable {
    case zhuyin, pinyin, japanese
}
