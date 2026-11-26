//! 截一段's last step (FINAL.md §2.2, §6.4): the picture the user picked a stretch for, handed to Steam's screenshot
//! library (`ISteamScreenshots`, location set to the stretch's last place) or, without Steam, saved where the user
//! says. Only the 「截一段」 button reaches this, one picture per press; nothing is sent anywhere else and nothing
//! happens in the background.
//!
//! The page draws the picture (the engine's pixels, and under a paper skin the clay cats and the wordmark) and sends
//! it as a PNG; this module checks it is one, puts it in a folder of its own while Steam copies it, and calls Steam.

use crate::steam::SteamApi;
use serde::Serialize;
use std::path::{Path, PathBuf};

/// The largest picture taken (a 1400 × 380 point picture at k = 4 is about 1 MB; this is far past any).
pub const MAX_BYTES: usize = 32 << 20;

const PNG_SIGNATURE: [u8; 8] = [0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];

/// What became of the picture.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum Published {
    /// In Steam's screenshot library, under this location.
    Steam { location: String },
    /// Saved here.
    Saved { path: String },
    /// The user closed the save dialog.
    Cancelled,
    /// Neither worked.
    Failed { why: String },
}

/// Checks the bytes are a PNG of the size the page says (its IHDR) and returns the size.
pub fn check_png(bytes: &[u8]) -> Result<(u32, u32), String> {
    if bytes.len() > MAX_BYTES {
        return Err("picture too large".to_owned());
    }
    if bytes.len() < 33 || bytes[..8] != PNG_SIGNATURE || &bytes[12..16] != b"IHDR" {
        return Err("not a PNG".to_owned());
    }
    let be = |at: usize| u32::from_be_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]]);
    let (w, h) = (be(16), be(20));
    if w == 0 || h == 0 || u64::from(w) * u64::from(h) > 64 << 20 {
        return Err("odd picture size".to_owned());
    }
    Ok((w, h))
}

/// A file name that is safe on both systems: the page's name with the characters neither allows taken out.
#[must_use]
pub fn clean_name(name: &str) -> String {
    let kept: String = name.chars().filter(|c| !c.is_control() && !matches!(c, '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|')).take(80).collect();
    let kept = kept.trim().trim_end_matches('.').to_owned();
    let stem = kept.strip_suffix(".png").unwrap_or(&kept).trim().to_owned();
    format!("{}.png", if stem.is_empty() { "Meowpuut" } else { &stem })
}

/// The folder Steam copies the picture from; whatever an earlier press left in it goes first.
pub fn staging_dir() -> PathBuf {
    std::env::temp_dir().join("Meowpuut-share")
}

/// Hands the picture to Steam. `Ok(true)`: the screenshot library has it. `Ok(false)`: Steam is not there (or refused):
/// the caller saves the file where the user says instead.
pub fn to_steam(steam: &mut dyn SteamApi, dir: &Path, name: &str, png: &[u8], location: &str) -> Result<bool, String> {
    let (w, h) = check_png(png)?;
    if !steam.connected() {
        return Ok(false);
    }
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    if let Ok(old) = std::fs::read_dir(dir) {
        for entry in old.flatten() {
            let _ = std::fs::remove_file(entry.path());
        }
    }
    let path = dir.join(clean_name(name));
    std::fs::write(&path, png).map_err(|e| e.to_string())?;
    Ok(steam.screenshot(&path, w, h, location))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::steam::tests::FakeSteam;

    /// The smallest header-valid PNG prefix: signature, IHDR of `w` × `h`.
    fn png(w: u32, h: u32) -> Vec<u8> {
        let mut v = PNG_SIGNATURE.to_vec();
        v.extend_from_slice(&13u32.to_be_bytes());
        v.extend_from_slice(b"IHDR");
        v.extend_from_slice(&w.to_be_bytes());
        v.extend_from_slice(&h.to_be_bytes());
        v.extend_from_slice(&[8, 6, 0, 0, 0, 0, 0, 0, 0]);
        v
    }

    #[test]
    fn only_a_png_of_a_sane_size_is_taken() {
        assert_eq!(check_png(&png(2800, 760)), Ok((2800, 760)));
        assert!(check_png(b"GIF89a....................................").is_err());
        assert!(check_png(&png(0, 10)).is_err());
        assert!(check_png(&png(100_000, 100_000)).is_err());
        assert!(check_png(&[]).is_err());
    }

    #[test]
    fn file_names_lose_what_a_system_would_refuse() {
        assert_eq!(clean_name("行旅 2026-11-23.png"), "行旅 2026-11-23.png");
        assert_eq!(clean_name("a/b\\c:d*e?.png"), "abcde.png");
        assert_eq!(clean_name("  "), "Meowpuut.png");
        assert_eq!(clean_name("../../etc/passwd"), "....etcpasswd.png");
    }

    #[test]
    fn steam_gets_the_picture_once_with_the_last_place_and_the_file_stays_for_its_copy() {
        let dir = tempfile::tempdir().unwrap();
        let mut steam = FakeSteam::online();
        assert_eq!(to_steam(&mut steam, dir.path(), "x.png", &png(2800, 760), "火山"), Ok(true));
        assert_eq!(steam.calls, vec!["screenshot x.png 2800x760 at 火山"]);
        assert!(dir.path().join("x.png").exists(), "Steam copies it asynchronously");
        // The next press leaves only its own picture.
        assert_eq!(to_steam(&mut steam, dir.path(), "y.png", &png(1400, 380), ""), Ok(true));
        assert!(!dir.path().join("x.png").exists() && dir.path().join("y.png").exists());
    }

    #[test]
    fn without_steam_nothing_is_written_and_nothing_is_called() {
        let dir = tempfile::tempdir().unwrap();
        let mut offline = FakeSteam::default();
        assert_eq!(to_steam(&mut offline, dir.path(), "x.png", &png(10, 10), "海"), Ok(false));
        assert!(offline.calls.is_empty());
        assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 0);
        let mut none = crate::steam::NotLinked;
        assert_eq!(to_steam(&mut none, dir.path(), "x.png", &png(10, 10), "海"), Ok(false));
        // A picture that is not a PNG is refused before Steam is asked.
        let mut steam = FakeSteam::online();
        assert!(to_steam(&mut steam, dir.path(), "x.png", b"nope", "").is_err());
        assert!(steam.calls.is_empty());
    }
}
