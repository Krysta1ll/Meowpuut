//! The song now playing, read only while 「顯示正在聽的歌」 is on (FINAL.md §11.2). The title passes through memory on
//! its way to the status line and is never stored.
//!
//! - macOS ([`mac::AppleScript`]): asks 「音樂」 and Spotify only, with AppleScript through `/usr/bin/osascript`, and
//!   only when that player is already running (it never launches one to ask). The first time, macOS asks whether 喵撲
//!   may control that app (Automation; `NSAppleEventsUsageDescription` in `Info.plist`). Not the MediaRemote framework
//!   and not the `/usr/bin/perl` workaround for macOS 15.4's MediaRemote restriction. 網易雲 and QQ 音樂 are not
//!   supported on the Mac.
//! - Windows ([`win::Smtc`]): the system's media session (`GlobalSystemMediaTransportControlsSessionManager`), the
//!   title of the current session while it is playing.

use crate::share::NowPlaying;

/// The platform's reader.
pub fn reader() -> Box<dyn NowPlaying> {
    #[cfg(target_os = "macos")]
    return Box::new(mac::AppleScript);
    #[cfg(windows)]
    return Box::new(win::Smtc::default());
    #[cfg(not(any(target_os = "macos", windows)))]
    Box::new(Silence)
}

impl NowPlaying for Box<dyn NowPlaying> {
    fn track(&mut self) -> Option<String> {
        (**self).track()
    }
}

pub struct Silence;

impl NowPlaying for Silence {
    fn track(&mut self) -> Option<String> {
        None
    }
}

/// What a player printed: one line, trimmed; nothing for an empty answer.
pub fn clean(output: &str) -> Option<String> {
    let line = output.lines().next().unwrap_or("").trim();
    (!line.is_empty()).then(|| line.to_owned())
}

#[cfg(target_os = "macos")]
pub mod mac {
    use super::clean;
    use crate::share::NowPlaying;
    use objc2_app_kit::NSRunningApplication;
    use objc2_foundation::NSString;
    use std::io::Read;
    use std::process::{Command, Stdio};
    use std::time::{Duration, Instant};

    /// Spotify first (it is the one people start on purpose), then 「音樂」.
    pub const PLAYERS: [(&str, &str); 2] = [
        ("com.spotify.client", "tell application id \"com.spotify.client\" to if player state is playing then return name of current track"),
        ("com.apple.Music", "tell application id \"com.apple.Music\" to if player state is playing then return name of current track"),
    ];
    /// The first run waits for the Automation question; after that an answer takes milliseconds.
    const TIMEOUT: Duration = Duration::from_secs(30);

    pub struct AppleScript;

    fn running(bundle: &str) -> bool {
        NSRunningApplication::runningApplicationsWithBundleIdentifier(&NSString::from_str(bundle)).count() > 0
    }

    fn run(script: &str) -> Option<String> {
        let mut child = Command::new("/usr/bin/osascript")
            .arg("-e")
            .arg(script)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .ok()?;
        let deadline = Instant::now() + TIMEOUT;
        loop {
            match child.try_wait() {
                Ok(Some(status)) if status.success() => break,
                Ok(None) if Instant::now() < deadline => std::thread::sleep(Duration::from_millis(50)),
                _ => {
                    let _ = child.kill();
                    let _ = child.wait();
                    return None;
                }
            }
        }
        let mut out = String::new();
        child.stdout.take()?.take(4096).read_to_string(&mut out).ok()?;
        clean(&out)
    }

    impl NowPlaying for AppleScript {
        fn track(&mut self) -> Option<String> {
            PLAYERS.iter().filter(|(bundle, _)| running(bundle)).find_map(|(_, script)| run(script))
        }
    }
}

#[cfg(windows)]
pub mod win {
    use super::clean;
    use crate::share::NowPlaying;
    use windows::Media::Control::{GlobalSystemMediaTransportControlsSessionManager as Manager, GlobalSystemMediaTransportControlsSessionPlaybackStatus as Status};
    use windows::Win32::System::WinRT::{RoInitialize, RO_INIT_MULTITHREADED};

    #[derive(Default)]
    pub struct Smtc {
        initialized: bool,
    }

    impl NowPlaying for Smtc {
        fn track(&mut self) -> Option<String> {
            if !self.initialized {
                // SAFETY: joins this (the sharing loop's) thread to the multithreaded apartment once; an error means it
                // already is in one, which is fine.
                let _ = unsafe { RoInitialize(RO_INIT_MULTITHREADED) };
                self.initialized = true;
            }
            let manager = Manager::RequestAsync().ok()?.join().ok()?;
            let session = manager.GetCurrentSession().ok()?;
            if session.GetPlaybackInfo().ok()?.PlaybackStatus().ok()? != Status::Playing {
                return None;
            }
            let properties = session.TryGetMediaPropertiesAsync().ok()?.join().ok()?;
            clean(&properties.Title().ok()?.to_string_lossy())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_players_answer_is_one_trimmed_line_or_nothing() {
        assert_eq!(clean("月光\n"), Some("月光".to_owned()));
        assert_eq!(clean("  \n"), None);
        assert_eq!(clean(""), None);
        assert_eq!(clean(" A \nB"), Some("A".to_owned()));
    }
}
