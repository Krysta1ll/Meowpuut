//! The Steam friends status line (FINAL.md §11.2, §11.4): what the companion sends while 「讓 Steam 好友看到我在做什麼」
//! is on, and when it stops.
//!
//! Every 15 seconds ([`TICK_MS`]) the sharing loop asks the desk what you are doing now ([`DeskNow`]: a category, full
//! screen, and the app's display name only when 「顯示 App 名稱」 is on), reads the song now playing only when
//! 「顯示正在聽的歌」 is on ([`NowPlaying`]), and hands both with the switches and the time limit to the presence builder
//! of `meowpuut-activity` ([`decide`]). It calls Steam only when the line changes ([`Sharer`]): `SetRichPresence` with
//! all four keys (an empty value deletes a key set earlier), or `ClearRichPresence`. When sharing is turned off, the
//! record switch is turned off, or the limit ends, the line is cleared; on quit it is always cleared.
//!
//! Nothing here stores anything: the display name and the song pass through memory on their way to Steam.

use crate::companion::{Share, ShareLimit};
use crate::clock::Now;
use crate::steam::SteamApi;
use meowpuut_activity::presence::{self, Language, Presence, ShareUntil, Switches};
use meowpuut_activity::tracker::{Activity, Current};
use meowpuut_activity::{Category, CATEGORIES};
use serde::Serialize;

/// How often the loop asks the desk and the player.
pub const TICK_MS: u64 = 15_000;
/// `MP_ACTIVITY_CURRENT_BYTES`.
pub const CURRENT_BYTES: usize = 272;

/// "What am I doing now", from the desk process. `None`: the desk did not answer (not running, or it refused this
/// process's signature), which shares nothing.
pub trait DeskNow: Send {
    fn current(&mut self, want_app: bool) -> Option<Current>;
}

/// The song now playing, if any.
pub trait NowPlaying: Send {
    fn track(&mut self) -> Option<String>;
}

/// Reads an `MPActivityCurrent` (272 bytes, little endian: state, category, flags, reserved, `app[256]` NUL-terminated
/// UTF-8) as the desk wrote it. A wrong length, state or category is `None`; a name that is not UTF-8 is dropped.
pub fn parse_current(bytes: &[u8]) -> Option<Current> {
    if bytes.len() != CURRENT_BYTES {
        return None;
    }
    let word = |at: usize| u32::from_le_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]]);
    let (state, category, flags) = (word(0), word(4), word(8));
    let activity = match state {
        0 => Activity::Off,
        1 => Activity::Paused,
        2 => Activity::Idle,
        3 => Activity::Hidden,
        4 => Activity::Active(Category::from_index(u8::try_from(category).ok()?)?),
        _ => return None,
    };
    let text = &bytes[16..];
    let end = text.iter().position(|&b| b == 0).unwrap_or(text.len());
    let app = std::str::from_utf8(&text[..end]).ok().filter(|s| !s.is_empty()).map(str::to_owned);
    let active = matches!(activity, Activity::Active(_));
    Some(Current { activity, app: app.filter(|_| active), fullscreen: flags & 2 != 0 })
}

/// The share limit as the presence builder takes it, at `now`.
pub fn until(share: &Share, now: Now) -> ShareUntil {
    match share.limit {
        ShareLimit::UntilOff => ShareUntil::TurnedOff,
        ShareLimit::Today => ShareUntil::Day(share.day),
        ShareLimit::Hour => {
            let left = u64::try_from(share.ends_at_ms.saturating_sub(now.wall_ms)).unwrap_or(0);
            ShareUntil::Mono(now.mono_ms.saturating_add(left))
        }
    }
}

/// True when sharing is on and its limit has passed (the caller turns the switch off).
pub fn expired(share: &Share, now: Now) -> bool {
    share.on && until(share, now).is_over(now.clock())
}

/// Sharing can run at all: the switch is on, recording is on (it needs the desk's category), and the limit holds.
pub fn wanted(share: &Share, recording: bool, now: Now) -> bool {
    share.on && recording && !expired(share, now)
}

/// Windows' `ActivityShareAppName`: the desk may read the foreground app's display name exactly while sharing runs
/// (on, recording on, limit not over) with 「顯示 App 名稱」 on; any other moment (sharing off, the limit over, recording
/// off, the app gone) it must not.
pub fn app_name_gate(share: &Share, recording: bool, now: Now) -> bool {
    share.show_app && wanted(share, recording, now)
}

pub fn switches(share: &Share, app_names: bool) -> Switches {
    Switches { share: share.on, show_app: share.show_app && app_names, show_track: share.show_track }
}

/// The line for this moment. `app_names`: this platform's desk can give display names (both platforms; Windows' desk reads the name while its gate is on).
pub fn decide(share: &Share, recording: bool, app_names: bool, current: Option<&Current>, track: Option<&str>, now: Now) -> Presence {
    let Some(current) = current.filter(|_| recording) else { return Presence::Clear };
    presence::build(current, switches(share, app_names), track, until(share, now), now.clock())
}

/// Turns presence decisions into Steam calls, only when the line changes.
pub struct Sharer<D: DeskNow, P: NowPlaying> {
    pub desk: D,
    pub player: P,
    /// Whether this platform's desk gives app names at all.
    pub app_names: bool,
    last: Option<Presence>,
}

/// What one tick did.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Tick {
    /// Sharing goes on; the line may have changed.
    Sharing(Presence),
    /// Sharing ended now (limit, switch, recording): the line was cleared.
    Ended { expired: bool },
}

impl<D: DeskNow, P: NowPlaying> Sharer<D, P> {
    pub fn new(desk: D, player: P, app_names: bool) -> Self {
        Self { desk, player, app_names, last: None }
    }

    pub fn tick(&mut self, steam: &mut dyn SteamApi, share: &Share, recording: bool, now: Now) -> Tick {
        if !wanted(share, recording, now) {
            self.stop(steam);
            return Tick::Ended { expired: expired(share, now) };
        }
        let current = self.desk.current(share.show_app && self.app_names);
        let track = if share.show_track { self.player.track() } else { None };
        let line = decide(share, recording, self.app_names, current.as_ref(), track.as_deref(), now);
        self.send(steam, &line);
        Tick::Sharing(line)
    }

    /// Clears the line (always calls Steam, so a quit or a stop never leaves one behind).
    pub fn stop(&mut self, steam: &mut dyn SteamApi) {
        steam.clear_presence();
        self.last = Some(Presence::Clear);
    }

    fn send(&mut self, steam: &mut dyn SteamApi, line: &Presence) {
        if self.last.as_ref() == Some(line) {
            return;
        }
        match line {
            Presence::Clear => steam.clear_presence(),
            Presence::Set(rich) => {
                for (key, value) in rich.pairs() {
                    steam.set_presence(key, value);
                }
            }
        }
        self.last = Some(line.clone());
    }
}

// ---- the explanation page's preview ------------------------------------------------------------------------------

/// One line friends would see.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct PreviewLine {
    /// `active`, `fullscreen`, `idle`.
    pub case: &'static str,
    /// The line as Steam shows it, or empty when nothing is shown.
    pub text: String,
}

/// The status line text: the token's template in `language` with the values put in.
pub fn render(line: &Presence, language: Language) -> String {
    match line {
        Presence::Clear => String::new(),
        Presence::Set(rich) => rich.token.template(language).replace("%app%", &rich.app).replace("%track%", &rich.track),
    }
}

/// Exactly what friends would see with these switches, built by the same presence builder the loop uses, for three
/// moments: coding in a named app with a song playing, a full-screen app, and away (with the song).
pub fn preview(share: &Share, recording: bool, app_names: bool, language: Language) -> Vec<PreviewLine> {
    // Short enough that the 20-character line keeps both whole (longer ones are cut with …, as they would be).
    const SAMPLE_APP: &str = "VS Code";
    const SAMPLE_TRACK: &str = "月光";
    let share = Share { limit: ShareLimit::UntilOff, ..share.clone() };
    let now = Now { mono_ms: 1, wall_ms: 0, day: 1, minute: 600 };
    let cases = [
        ("active", Current { activity: Activity::Active(Category::Code), app: Some(SAMPLE_APP.into()), fullscreen: false }),
        ("fullscreen", Current { activity: Activity::Active(Category::Media), app: Some(SAMPLE_APP.into()), fullscreen: true }),
        ("idle", Current { activity: Activity::Idle, app: None, fullscreen: false }),
    ];
    cases
        .into_iter()
        .map(|(case, current)| {
            let line = decide(&share, recording, app_names, Some(&current), Some(SAMPLE_TRACK), now);
            PreviewLine { case, text: render(&line, language) }
        })
        .collect()
}

/// Category codes in order, for the pages (they hold the names).
pub fn category_codes() -> [&'static str; CATEGORIES] {
    Category::ALL.map(Category::code)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::steam::tests::FakeSteam;
    use std::collections::VecDeque;

    struct Desk(VecDeque<Option<Current>>, Vec<bool>);
    impl DeskNow for Desk {
        fn current(&mut self, want_app: bool) -> Option<Current> {
            self.1.push(want_app);
            self.0.pop_front().flatten()
        }
    }
    struct Player(Option<String>, u32);
    impl NowPlaying for Player {
        fn track(&mut self) -> Option<String> {
            self.1 += 1;
            self.0.clone()
        }
    }

    const NOW: Now = Now { mono_ms: 5_000, wall_ms: 1_800_000_000_000, day: 20_833, minute: 900 };

    fn active(category: Category, app: &str, fullscreen: bool) -> Current {
        Current { activity: Activity::Active(category), app: Some(app.to_owned()), fullscreen }
    }

    fn on(limit: ShareLimit) -> Share {
        Share { on: true, show_app: true, show_track: true, limit, ends_at_ms: NOW.wall_ms + 3_600_000, day: NOW.day }
    }

    fn bytes(state: u32, category: u32, flags: u32, app: &str) -> Vec<u8> {
        let mut b = vec![0u8; CURRENT_BYTES];
        b[0..4].copy_from_slice(&state.to_le_bytes());
        b[4..8].copy_from_slice(&category.to_le_bytes());
        b[8..12].copy_from_slice(&flags.to_le_bytes());
        b[16..16 + app.len()].copy_from_slice(app.as_bytes());
        b
    }

    #[test]
    fn reads_the_desks_272_bytes() {
        assert_eq!(parse_current(&bytes(4, 1, 0, "Visual Studio Code")), Some(active(Category::Code, "Visual Studio Code", false)));
        assert_eq!(parse_current(&bytes(4, 5, 2, "")).unwrap(), Current { activity: Activity::Active(Category::Media), app: None, fullscreen: true });
        // A name only while active; off is category 255.
        assert_eq!(parse_current(&bytes(2, 255, 0, "Leftover")).unwrap(), Current { activity: Activity::Idle, app: None, fullscreen: false });
        assert_eq!(parse_current(&bytes(0, 255, 0, "")).unwrap().activity, Activity::Off);
        assert_eq!(parse_current(&bytes(3, 255, 0, "")).unwrap().activity, Activity::Hidden);
        // Malformed: wrong length, unknown state or category.
        assert_eq!(parse_current(&[0u8; 100]), None);
        assert_eq!(parse_current(&bytes(9, 0, 0, "")), None);
        assert_eq!(parse_current(&bytes(4, 10, 0, "")), None);
        // A name that is not UTF-8 is dropped; the category still counts.
        let mut bad = bytes(4, 0, 0, "ab");
        bad[16] = 0xFF;
        assert_eq!(parse_current(&bad), Some(Current { activity: Activity::Active(Category::Write), app: None, fullscreen: false }));
        // No NUL at all: the whole field.
        let mut full = bytes(4, 0, 0, "");
        full[16..].fill(b'x');
        assert_eq!(parse_current(&full).unwrap().app.unwrap().len(), 256);
    }

    #[test]
    fn the_app_name_gate_is_on_only_while_sharing_runs_with_the_name_switch() {
        let all = on(ShareLimit::UntilOff);
        assert!(app_name_gate(&all, true, NOW));
        assert!(!app_name_gate(&Share { show_app: false, ..all.clone() }, true, NOW), "name switch off");
        assert!(!app_name_gate(&Share { on: false, ..all.clone() }, true, NOW), "sharing off");
        assert!(!app_name_gate(&all, false, NOW), "recording off");
        let over = Share { limit: ShareLimit::Hour, ends_at_ms: NOW.wall_ms - 1, ..all.clone() };
        assert!(!app_name_gate(&over, true, NOW), "the limit ran out");
        assert!(app_name_gate(&Share { limit: ShareLimit::Hour, ..all }, true, NOW), "an hour not yet over");
    }

    #[test]
    fn switches_decide_what_friends_see() {
        let now = active(Category::Code, "VS Code", false);
        let line = |share: &Share, app_names: bool| render(&decide(share, true, app_names, Some(&now), Some("Lemon"), NOW), Language::TChinese);
        let all = on(ShareLimit::UntilOff);
        assert_eq!(line(&all, true), "喵撲・在寫程式（VS Code）♪ Lemon");
        assert_eq!(line(&Share { show_app: false, ..all.clone() }, true), "喵撲・在寫程式 ♪ Lemon");
        assert_eq!(line(&Share { show_track: false, ..all.clone() }, true), "喵撲・在寫程式（VS Code）");
        // A desk that cannot give names: the switch cannot show one.
        assert_eq!(line(&all, false), "喵撲・在寫程式 ♪ Lemon");
        // Off, or recording off, or the desk not answering: nothing.
        assert_eq!(decide(&Share { on: false, ..all.clone() }, true, true, Some(&now), None, NOW), Presence::Clear);
        assert_eq!(decide(&all, false, true, Some(&now), None, NOW), Presence::Clear);
        assert_eq!(decide(&all, true, true, None, None, NOW), Presence::Clear);
    }

    #[test]
    fn full_screen_and_games_are_only_focus() {
        let all = on(ShareLimit::UntilOff);
        for current in [active(Category::Write, "Pages", true), active(Category::Game, "Some Game", false)] {
            let p = decide(&all, true, true, Some(&current), Some("Lemon"), NOW);
            assert_eq!(render(&p, Language::TChinese), "喵撲・專心中");
            let Presence::Set(rich) = p else { panic!() };
            assert_eq!(rich.pairs(), [("steam_display", "#Status_focus"), ("kind", "focus"), ("app", ""), ("track", "")]);
        }
    }

    #[test]
    fn the_limit_ends_sharing() {
        let hour = on(ShareLimit::Hour);
        assert!(wanted(&hour, true, NOW));
        let later = Now { wall_ms: hour.ends_at_ms, mono_ms: NOW.mono_ms + 3_600_000, ..NOW };
        assert!(expired(&hour, later) && !wanted(&hour, true, later));
        let just_before = Now { wall_ms: hour.ends_at_ms - 1, ..NOW };
        assert!(!expired(&hour, just_before));
        let today = on(ShareLimit::Today);
        assert!(!expired(&today, Now { minute: 1_439, ..NOW }));
        assert!(expired(&today, Now { day: NOW.day + 1, minute: 0, ..NOW }), "「今天」 ends at midnight");
        let forever = on(ShareLimit::UntilOff);
        assert!(!expired(&forever, Now { day: NOW.day + 400, wall_ms: i64::MAX, ..NOW }));
        assert!(!expired(&Share::default(), later), "off is not expired");
        assert!(!wanted(&forever, false, NOW), "sharing needs recording");
    }

    #[test]
    fn the_sharer_calls_steam_only_when_the_line_changes_and_clears_at_the_end() {
        let code = Some(active(Category::Code, "VS Code", false));
        let desk = Desk(VecDeque::from([code.clone(), code.clone(), Some(active(Category::Web, "Safari", true))]), Vec::new());
        let mut sharer = Sharer::new(desk, Player(Some("Lemon".into()), 0), true);
        let mut steam = FakeSteam::online();
        let share = on(ShareLimit::Hour);
        assert!(matches!(sharer.tick(&mut steam, &share, true, NOW), Tick::Sharing(Presence::Set(_))));
        assert_eq!(steam.calls, ["set steam_display=#Status_code_app_track", "set kind=code", "set app=VS Code", "set track=Lemon"]);
        steam.calls.clear();
        sharer.tick(&mut steam, &share, true, NOW);
        assert!(steam.calls.is_empty(), "the same line is not sent again");
        // Full screen: the stale app and track keys are deleted with empty values.
        sharer.tick(&mut steam, &share, true, NOW);
        assert_eq!(steam.calls, ["set steam_display=#Status_focus", "set kind=focus", "set app=", "set track="]);
        assert!(!steam.presence.contains_key("app"));
        assert_eq!(sharer.desk.1, [true, true, true], "the name is asked for only because 顯示 App 名稱 is on");
        // The hour is over: cleared, and reported as expired.
        steam.calls.clear();
        let later = Now { wall_ms: share.ends_at_ms + 1, ..NOW };
        assert_eq!(sharer.tick(&mut steam, &share, true, later), Tick::Ended { expired: true });
        assert_eq!(steam.calls, ["clear"]);
        assert!(steam.presence.is_empty());
    }

    #[test]
    fn the_song_and_the_name_are_read_only_when_their_switches_are_on() {
        let desk = Desk(VecDeque::from([Some(active(Category::Write, "Pages", false))]), Vec::new());
        let mut sharer = Sharer::new(desk, Player(Some("Lemon".into()), 0), true);
        let mut steam = FakeSteam::online();
        let share = Share { show_app: false, show_track: false, ..on(ShareLimit::UntilOff) };
        sharer.tick(&mut steam, &share, true, NOW);
        assert_eq!(sharer.player.1, 0, "the player is never asked");
        assert_eq!(sharer.desk.1, [false], "the desk is asked without the name");
        assert_eq!(steam.presence.get("steam_display").map(String::as_str), Some("#Status_write"));
        // Turning sharing off clears at once.
        steam.calls.clear();
        assert_eq!(sharer.tick(&mut steam, &Share { on: false, ..share }, true, NOW), Tick::Ended { expired: false });
        assert_eq!(steam.calls, ["clear"]);
    }

    #[test]
    fn the_preview_is_the_builders_line() {
        let all = on(ShareLimit::UntilOff);
        let p = preview(&all, true, true, Language::TChinese);
        assert_eq!(p[0].text, "喵撲・在寫程式（VS Code）♪ 月光");
        assert_eq!(p[1].text, "喵撲・專心中");
        assert_eq!(p[2].text, "喵撲・陪著發呆 ♪ 月光");
        let plain = preview(&Share { show_app: false, show_track: false, ..all.clone() }, true, true, Language::SChinese);
        assert_eq!(plain.iter().map(|l| l.text.as_str()).collect::<Vec<_>>(), ["喵扑・在写代码", "喵扑・专注中", "喵扑・陪着发呆"]);
        assert!(preview(&Share::default(), true, true, Language::TChinese).iter().all(|l| l.text.is_empty()), "off: nothing");
        for line in preview(&all, true, true, Language::English) {
            assert!(presence::columns(&line.text) <= presence::LINE_COLUMNS, "{}", line.text);
        }
    }
}
