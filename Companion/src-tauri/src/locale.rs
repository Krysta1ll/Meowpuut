//! Traditional or Simplified for the pages, from the system language, the way the Mac input method's own windows
//! decide (`InterfaceText.prefersSimplified` in Sources/InterfaceText.swift): the first Chinese entry in the user's
//! language list decides; anything else falls back to Traditional, the project's original script.
//! `MEOWPUUT_SCRIPT=hans|hant` overrides it (previews).

use serde::Serialize;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Script {
    Hant,
    Hans,
}

pub fn prefers_simplified<S: AsRef<str>>(languages: impl IntoIterator<Item = S>) -> bool {
    for language in languages {
        let tag = language.as_ref().to_ascii_lowercase().replace('_', "-");
        if tag != "zh" && !tag.starts_with("zh-") {
            continue;
        }
        if tag.contains("-hans") {
            return true;
        }
        if tag.contains("-hant") {
            return false;
        }
        return ["zh-cn", "zh-sg", "zh-my"].iter().any(|p| tag.starts_with(p));
    }
    false
}

pub fn system_script() -> Script {
    match std::env::var("MEOWPUUT_SCRIPT").ok().as_deref() {
        Some("hans") => return Script::Hans,
        Some("hant") => return Script::Hant,
        _ => {}
    }
    if prefers_simplified(sys_locale::get_locales()) {
        Script::Hans
    } else {
        Script::Hant
    }
}

#[cfg(test)]
mod tests {
    use super::prefers_simplified;

    #[test]
    fn the_first_chinese_language_decides() {
        assert!(prefers_simplified(["en-US", "zh-Hans-CN"]));
        assert!(prefers_simplified(["zh_CN"]));
        assert!(!prefers_simplified(["zh-Hant-TW", "zh-Hans"]));
        assert!(!prefers_simplified(["zh-TW"]));
        assert!(!prefers_simplified(["zh-HK"]));
        assert!(!prefers_simplified(["en-GB", "ja-JP"]));
        assert!(!prefers_simplified(Vec::<String>::new()));
    }
}
