//! The sharing mode's runtime (FINAL.md §7.1, §11.2): the loop thread, the tray / menu-bar icon, and staying alive
//! without a window.
//!
//! Normally this process exists only while its window is open. While 「讓 Steam 好友看到我在做什麼」 is on (with
//! recording on, Steam connected and the limit not reached), a loop thread runs [`crate::share::Sharer`] every 15
//! seconds and a small icon appears in the menu bar / notification area with 「打開喵撲」 and 「停止分享」. Closing the
//! window then leaves the process running without one (on macOS without a Dock icon either) until sharing ends: the
//! switch is turned off, the limit runs out, or 「停止分享」. Then the line is cleared and, with no window, the process
//! quits. Quitting always clears the line.

use crate::share::{Sharer, Tick, TICK_MS};
use crate::{clock, desk_now, now_playing, AppState};
use std::sync::mpsc::{self, RecvTimeoutError, Sender};
use std::sync::Mutex;
use std::thread::JoinHandle;
use std::time::Duration;
use tauri::menu::{MenuBuilder, MenuItemBuilder};
use tauri::tray::TrayIconBuilder;
use tauri::{AppHandle, Emitter, Manager};

const TRAY_ID: &str = "share";
/// Sent to the page when sharing stopped by itself (limit) or from the tray.
pub const CHANGED_EVENT: &str = "share-changed";

enum Msg {
    Wake,
    Stop,
}

struct Running {
    wake: Sender<Msg>,
    thread: JoinHandle<()>,
}

#[derive(Default)]
pub struct Controller {
    running: Mutex<Option<Running>>,
}

impl Controller {
    pub fn active(&self) -> bool {
        self.running.lock().unwrap_or_else(|e| e.into_inner()).as_ref().is_some_and(|r| !r.thread.is_finished())
    }

    /// Starts, wakes or stops the loop to match the switches. Call after every change of them, and on open.
    pub fn sync(&self, app: &AppHandle) {
        let state = app.state::<AppState>();
        let want = state.share_wanted();
        let mut running = self.running.lock().unwrap_or_else(|e| e.into_inner());
        if running.as_ref().is_some_and(|r| r.thread.is_finished()) {
            if let Some(done) = running.take() {
                let _ = done.thread.join();
            }
        }
        match (want, running.is_some()) {
            (true, false) => *running = Some(start(app.clone())),
            (true, true) => {
                if let Some(r) = running.as_ref() {
                    let _ = r.wake.send(Msg::Wake);
                }
            }
            (false, true) => {
                if let Some(r) = running.take() {
                    let _ = r.wake.send(Msg::Stop);
                    let _ = r.thread.join();
                }
            }
            (false, false) => {}
        }
        drop(running);
        state.sync_app_name_gate();
        tray(app, want);
    }

    /// Quitting: stop the loop (it clears the line) and clear once more in any case.
    pub fn shutdown(&self, app: &AppHandle) {
        if let Some(r) = self.running.lock().unwrap_or_else(|e| e.into_inner()).take() {
            let _ = r.wake.send(Msg::Stop);
            let _ = r.thread.join();
        }
        let state = app.state::<AppState>();
        state.close_app_name_gate();
        let mut steam = state.steam();
        if steam.connected() {
            steam.clear_presence();
            steam.pump();
        }
    }
}

fn start(app: AppHandle) -> Running {
    let (wake, inbox) = mpsc::channel();
    let thread = std::thread::Builder::new()
        .name("meowpuut-share".into())
        .spawn(move || {
            let platform = app.state::<AppState>().platform;
            let mut sharer = Sharer::new(desk_now::reader(), now_playing::reader(), desk_now::app_names_available(platform));
            loop {
                let state = app.state::<AppState>();
                state.sync_app_name_gate(); // a limit that just ran out closes it before the loop ends
                let (share, recording) = state.share_inputs();
                let tick = {
                    let mut steam = state.steam();
                    let tick = sharer.tick(&mut **steam, &share, recording, clock::now());
                    steam.pump();
                    tick
                };
                if let Tick::Ended { expired } = tick {
                    if expired {
                        let _ = state.companion.modify(|c| c.share.on = false);
                    }
                    ended(&app);
                    return;
                }
                match inbox.recv_timeout(Duration::from_millis(TICK_MS)) {
                    Ok(Msg::Wake) | Err(RecvTimeoutError::Timeout) => {}
                    Ok(Msg::Stop) | Err(RecvTimeoutError::Disconnected) => {
                        let mut steam = state.steam();
                        sharer.stop(&mut **steam);
                        steam.pump();
                        return;
                    }
                }
            }
        })
        .expect("spawn the sharing thread");
    Running { wake, thread }
}

/// The loop ended by itself: tell the page, drop the icon, and quit when there is no window.
fn ended(app: &AppHandle) {
    let _ = app.emit(CHANGED_EVENT, ());
    let handle = app.clone();
    let _ = app.run_on_main_thread(move || {
        handle.state::<AppState>().sharing.sync(&handle);
        if handle.webview_windows().is_empty() {
            handle.exit(0);
        }
    });
}

/// 「停止分享」 from the tray or the page.
pub fn stop(app: &AppHandle) {
    let state = app.state::<AppState>();
    let _ = state.companion.modify(|c| c.share.on = false);
    state.sharing.sync(app);
    let _ = app.emit(CHANGED_EVENT, ());
    if app.webview_windows().is_empty() {
        app.exit(0);
    }
}

/// Opens the window again (from the tray): a new open, so achievements are settled again.
pub fn open_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.set_focus();
        return;
    }
    #[cfg(target_os = "macos")]
    let _ = app.set_activation_policy(tauri::ActivationPolicy::Regular);
    if let Some(config) = app.config().app.windows.first() {
        if let Ok(builder) = tauri::WebviewWindowBuilder::from_config(app, config) {
            let _ = builder.build();
        }
    }
    crate::settle_on_open(app.clone());
}

/// The last window closed while sharing: keep running without one (and on macOS without a Dock icon).
pub fn go_windowless(app: &AppHandle) {
    #[cfg(target_os = "macos")]
    let _ = app.set_activation_policy(tauri::ActivationPolicy::Accessory);
    #[cfg(not(target_os = "macos"))]
    let _ = app;
}

fn tray(app: &AppHandle, show: bool) {
    if !show {
        let _ = app.remove_tray_by_id(TRAY_ID);
        return;
    }
    if app.tray_by_id(TRAY_ID).is_some() {
        return;
    }
    let hans = crate::locale::system_script() == crate::locale::Script::Hans;
    let t = |hant: &'static str, hans_text: &'static str| if hans { hans_text } else { hant };
    let menu = (|| {
        let note = MenuItemBuilder::with_id("note", t("Steam 好友看得到你在做什麼", "Steam 好友看得到你在做什么")).enabled(false).build(app)?;
        let open = MenuItemBuilder::with_id("open", t("打開喵撲", "打开喵扑")).build(app)?;
        let stop = MenuItemBuilder::with_id("stop", t("停止分享", "停止分享")).build(app)?;
        MenuBuilder::new(app).item(&note).separator().item(&open).item(&stop).build()
    })();
    let Ok(menu) = menu else { return };
    let builder = TrayIconBuilder::with_id(TRAY_ID)
        .icon(icon())
        .icon_as_template(true)
        .tooltip(t("喵撲・分享中", "喵扑・分享中"))
        .menu(&menu)
        .show_menu_on_left_click(true)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => open_window(app),
            // Off the main thread: stopping waits for a tick that may be in progress.
            "stop" => {
                let app = app.clone();
                std::thread::spawn(move || stop(&app));
            }
            _ => {}
        });
    let _ = builder.build(app);
}

/// The monochrome pixel cat (two ears, two eyes; FINAL.md §6.1 「單色處用 A 的像素小圖」), 16 × 16 drawn at 2×. A
/// template image on macOS, so the menu bar tints it.
fn icon() -> tauri::image::Image<'static> {
    const ART: [&str; 16] = [
        "................",
        "..#..........#..",
        "..##........##..",
        "..#.#......#.#..",
        "..#..######..#..",
        ".#............#.",
        ".#............#.",
        ".#..##....##..#.",
        ".#..##....##..#.",
        ".#............#.",
        ".#.....##.....#.",
        "..#..........#..",
        "...##......##...",
        ".....######.....",
        "................",
        "................",
    ];
    const SCALE: usize = 2;
    let size = 16 * SCALE;
    let mut rgba = vec![0u8; size * size * 4];
    for (y, row) in ART.iter().enumerate() {
        for (x, cell) in row.bytes().enumerate() {
            if cell != b'#' {
                continue;
            }
            for dy in 0..SCALE {
                for dx in 0..SCALE {
                    let at = ((y * SCALE + dy) * size + x * SCALE + dx) * 4;
                    rgba[at..at + 4].copy_from_slice(&[0, 0, 0, 255]);
                }
            }
        }
    }
    tauri::image::Image::new_owned(rgba, size as u32, size as u32)
}
