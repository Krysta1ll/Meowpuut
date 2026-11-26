//! 字景 in the companion (FINAL.md §2): the Rust 字景 engine's pictures, handed to the page as raw pixels.
//!
//! The page asks, this module draws, nothing is kept but the objects the engine itself wants kept (a `Scroll` with
//! the pictures of the places it drew lately, a `Board` with its land). Four families of command:
//!
//! * **The scroll** (行旅): [`scroll_open`] lays the scroll out for a window and answers with its geometry and where
//!   each day is; [`scroll_tile`] draws a stretch of it; [`scroll_roller`] one of the two rollers; [`scroll_share`]
//!   the picture of 截一段.
//! * **The lead** (引首): [`lead_info`] (the counts the pictures are made from) and [`lead_picture`].
//! * **The home keyboard** (字景 skin): [`board_open`], [`board_frame`].
//! * **The first run**: [`guide_picture`].
//!
//! Every picture comes back as one binary reply, not JSON: a header of sixteen little-endian `u32` words (the first
//! two are the width and the height in device pixels, the rest depend on the command), then straight RGBA, row by
//! row, ready for `ImageData`. Nothing is base64.

use meowpuut_glyph::board::{self, Board};
use meowpuut_glyph::guide;
use meowpuut_glyph::journey::{self, Journey};
use meowpuut_glyph::lead::{self, CatStat};
use meowpuut_glyph::page::Tokens;
use meowpuut_glyph::raster::Canvas;
use meowpuut_glyph::scenes::ALL;
use meowpuut_glyph::pools::{PixelHanzi, Pools};
use meowpuut_glyph::scroll::{Day, Lens, Scroll, Share, Side, Skin, View};
use meowpuut_glyph::strip::{self, CellIn, Strip};
use meowpuut_glyph::Key;
use meowpuut_pets::codec::PetState;
use meowpuut_pets::Pet;
use serde::{Deserialize, Serialize};
use std::sync::{Mutex, MutexGuard};
use std::time::Instant;
use tauri::ipc::Response;

/// Words in the header of every picture reply.
pub const HEADER_WORDS: usize = 16;

/// What the engine keeps between calls.
#[derive(Default)]
pub struct Glyph {
    scroll: Mutex<Option<Scroll>>,
    board: Mutex<Option<BoardSlot>>,
}

struct BoardSlot {
    board: Board,
    hold: bool,
    composing: bool,
    /// The board has to be drawn again whatever the keys did (opened, or its look changed).
    dirty: bool,
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

/// The ledger's days as the scroll's [`Day`]s (the place is the ledger's, `None` for a day recorded before places).
#[must_use]
pub fn days_of(state: &PetState) -> Vec<Day> {
    state
        .book
        .marks()
        .map(|m| Day { day: m.day, size: m.size, who: m.who, part: m.part, place: m.place_index(), fest: None })
        .collect()
}

/// Reads `#RRGGBB` as an opaque ARGB colour.
fn argb(hex: &str) -> Option<u32> {
    let h = hex.strip_prefix('#')?;
    (h.len() == 6).then(|| u32::from_str_radix(h, 16).ok()).flatten().map(|v| 0xFF00_0000 | v)
}

/// A paper skin's tokens as the page has them: `[paper, paper2, ink, mute, red]` as `#RRGGBB`.
fn tokens_of(hex: &[String]) -> Option<Tokens> {
    let [paper, paper2, ink, mute, red] = hex else { return None };
    Some(Tokens { paper: argb(paper)?, paper2: argb(paper2)?, ink: argb(ink)?, mute: argb(mute)?, red: argb(red)? })
}

fn pack_canvas(canvas: &Canvas, words: &[u32]) -> Response {
    raw(canvas.width, canvas.height, words, canvas.to_rgba8())
}

/// A reply with a header and no pixels (nothing to draw).
fn pack_empty(words: &[u32]) -> Response {
    let mut out = Vec::with_capacity(HEADER_WORDS * 4);
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());
    for i in 0..HEADER_WORDS - 2 {
        out.extend_from_slice(&words.get(i).copied().unwrap_or(0).to_le_bytes());
    }
    Response::new(out)
}

// ---- the scroll ------------------------------------------------------------------------------------------------------

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScrollAsk {
    pub lens: String,
    /// The window's width and the scroll's height, in the engine's points.
    pub width: f64,
    pub height: f64,
    pub dark: bool,
    /// A paper skin's `[paper, paper2, ink, mute, red]` as `#RRGGBB`; none: 字景.
    pub paper: Option<Vec<String>>,
    pub today: u32,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DaySpan {
    pub day: u32,
    pub size: u8,
    pub who: u8,
    pub place: Option<u8>,
    /// Where the day is along the scroll (left, right); none for a rolled-up day.
    pub span: Option<(f64, f64)>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScrollView {
    pub total: f64,
    pub lead: (f64, f64),
    pub painting: (f64, f64),
    pub rolled: Option<(f64, f64)>,
    pub days_at: (f64, f64),
    pub tail: (f64, f64),
    pub top: f64,
    pub height: f64,
    pub col: f64,
    pub rolled_days: usize,
    pub days: Vec<DaySpan>,
}

fn lens_of(name: &str) -> Lens {
    match name {
        "month" => Lens::Month,
        "day" => Lens::Day,
        _ => Lens::Year,
    }
}

/// The view of a scroll as the page asked for it.
fn view_of(ask: &ScrollAsk) -> View {
    let skin = ask.paper.as_deref().and_then(tokens_of).map_or(Skin::Glyph, Skin::Paper);
    View { dark: ask.dark, skin, ..View::new(lens_of(&ask.lens), ask.width, ask.height) }
}

pub fn open_scroll(slot: &mut Option<Scroll>, state: &PetState, ask: &ScrollAsk) -> ScrollView {
    let scroll = Scroll::new(&days_of(state), ask.today, view_of(ask));
    let geo = scroll.geometry();
    let days = scroll
        .days()
        .iter()
        .enumerate()
        .map(|(i, d)| DaySpan { day: d.day, size: d.size, who: d.who, place: d.place, span: scroll.day_span(i) })
        .collect();
    let view = ScrollView {
        total: geo.total,
        lead: geo.lead,
        painting: geo.painting,
        rolled: geo.rolled,
        days_at: geo.days,
        tail: geo.tail,
        top: geo.top,
        height: geo.height,
        col: geo.col,
        rolled_days: scroll.rolled_days(),
        days,
    };
    *slot = Some(scroll);
    view
}

#[tauri::command]
pub fn scroll_open(app: tauri::State<'_, crate::AppState>, ask: ScrollAsk) -> Result<ScrollView, String> {
    let reading = app.reading_for_glyph()?;
    Ok(open_scroll(&mut lock(&app.glyph.scroll), &reading.state, &ask))
}

/// A stretch of the scroll at `k`: header words `[x]`, the stretch's left edge in points as asked.
#[tauri::command]
pub async fn scroll_tile(app: tauri::State<'_, crate::AppState>, x: i32, w: u32, k: u32) -> Result<Response, String> {
    let t = Instant::now();
    let mut guard = lock(&app.glyph.scroll);
    let Some(scroll) = guard.as_mut() else { return Err("no scroll".to_owned()) };
    let (width, height, rgba) = scroll.tile_rgba(x, w, k);
    drop(guard);
    log_tile("tile", x, t);
    Ok(raw(width, height, &[], rgba))
}

/// The roller of one side (`left` / `right`), `wound` points of the scroll wound on it; empty when it is not there.
#[tauri::command]
pub async fn scroll_roller(app: tauri::State<'_, crate::AppState>, right: bool, wound: f64, k: u32) -> Result<Response, String> {
    let guard = lock(&app.glyph.scroll);
    let Some(scroll) = guard.as_ref() else { return Err("no scroll".to_owned()) };
    Ok(match scroll.roller(if right { Side::Right } else { Side::Left }, wound, k) {
        Some(cv) => pack_canvas(&cv, &[]),
        None => pack_empty(&[]),
    })
}

fn raw(width: u32, height: u32, words: &[u32], rgba: Vec<u8>) -> Response {
    let mut out = Vec::with_capacity(HEADER_WORDS * 4 + rgba.len());
    out.extend_from_slice(&width.to_le_bytes());
    out.extend_from_slice(&height.to_le_bytes());
    for i in 0..HEADER_WORDS - 2 {
        out.extend_from_slice(&words.get(i).copied().unwrap_or(0).to_le_bytes());
    }
    out.extend_from_slice(&rgba);
    Response::new(out)
}

/// 截一段: days `from..=to` as one picture. Header words: `[has cats, cats x, cats y, cats h, has title, title x,
/// title y]` (the floats as their bits); the clay cats and the brand title are for the page to lay on a paper skin.
#[tauri::command]
pub async fn scroll_share(app: tauri::State<'_, crate::AppState>, from: usize, to: usize, k: u32) -> Result<Response, String> {
    let guard = lock(&app.glyph.scroll);
    let Some(scroll) = guard.as_ref() else { return Err("no scroll".to_owned()) };
    let Share { canvas, cats, title } = scroll.share(from, to, k);
    let f = |v: f64| (v as f32).to_bits();
    let (c, t) = (cats.unwrap_or_default(), title.unwrap_or_default());
    let words = [u32::from(cats.is_some()), f(c.0), f(c.1), f(c.2), u32::from(title.is_some()), f(t.0), f(t.1)];
    Ok(pack_canvas(&canvas, &words))
}

/// The name of the last place of a stretch of days (截一段's location for Steam, FINAL.md §6.4); empty when none of
/// its days has a known place.
#[tauri::command]
pub fn scroll_last_place(app: tauri::State<'_, crate::AppState>, from: usize, to: usize) -> String {
    let guard = lock(&app.glyph.scroll);
    let Some(scroll) = guard.as_ref() else { return String::new() };
    last_place_name(scroll.days(), from, to).to_owned()
}

/// The name of the last known place among days `from..=to` (either order).
#[must_use]
pub fn last_place_name(days: &[Day], from: usize, to: usize) -> &'static str {
    let (a, b) = (from.min(to), from.max(to).min(days.len().saturating_sub(1)));
    days.get(a..=b)
        .and_then(|run| run.iter().rev().find_map(|d| d.place))
        .and_then(|p| ALL.get(usize::from(p)))
        .map_or("", |s| s.name)
}

fn log_tile(what: &str, x: i32, since: Instant) {
    if std::env::var_os("MEOWPUUT_TILE_LOG").is_some() {
        eprintln!("glyph: {what} x={x} {:.1} ms", since.elapsed().as_secs_f64() * 1000.0);
    }
}

// ---- the lead -------------------------------------------------------------------------------------------------------------

/// The numbers the lead's pictures and words are made from.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LeadInfo {
    pub characters: u64,
    /// The places come to (the one the cats are in included).
    pub stop: usize,
    /// How many times each of the twelve places was reached on the journey.
    pub visits: [u32; 12],
    pub places_met: usize,
    pub names: Vec<&'static str>,
    pub here: usize,
    /// Each cat's level (the height of its mountain) and its pets (the tally): `Snapshot::progress` and the contacts.
    pub mimimo: CatPair,
    pub momo: CatPair,
}

#[derive(Serialize)]
pub struct CatPair {
    pub level: u32,
    pub pets: u64,
}

#[must_use]
pub fn lead_info_of(state: &PetState) -> LeadInfo {
    let characters = state.snapshot.committed_graphemes;
    let mut journey = Journey::new();
    let places = journey::places(journey::distance_of(i64::try_from(characters).unwrap_or(i64::MAX)), &mut journey);
    let cat = |pet: Pet, pets: u64| CatPair { level: u32::from(state.snapshot.progress(pet).level), pets };
    LeadInfo {
        characters,
        stop: places.stop,
        visits: places.visits,
        places_met: places.visits.iter().filter(|&&v| v > 0).count(),
        names: ALL.iter().map(|s| s.name).collect(),
        here: places.here,
        mimimo: cat(Pet::Mimimo, state.snapshot.mimimo.pets),
        momo: cat(Pet::Momo, state.snapshot.momo.pets),
    }
}

#[tauri::command]
pub fn lead_info(app: tauri::State<'_, crate::AppState>) -> Result<LeadInfo, String> {
    Ok(lead_info_of(&app.reading_for_glyph()?.state))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LeadAsk {
    /// `place`, `parts`, `bands`, `cats`.
    pub kind: String,
    pub w: u32,
    pub h: u32,
    pub index: usize,
    pub dark: bool,
    pub k: u32,
}

#[tauri::command]
pub async fn lead_picture(app: tauri::State<'_, crate::AppState>, ask: LeadAsk) -> Result<Response, String> {
    let state = app.reading_for_glyph()?.state;
    let k = ask.k.clamp(1, 8);
    let cv = match ask.kind.as_str() {
        "place" => {
            let info = lead_info_of(&state);
            let index = ask.index.min(ALL.len() - 1);
            lead::place(index, info.visits[index] > 0, ask.w, ask.h, ask.dark, k)
        }
        "parts" => lead::parts(&days_of(&state), ask.w, ask.dark, k),
        "bands" => lead::bands(&days_of(&state), ask.w, ask.dark, k),
        "cats" => {
            let info = lead_info_of(&state);
            lead::cats(CatStat { level: info.mimimo.level, pets: info.mimimo.pets }, CatStat { level: info.momo.level, pets: info.momo.pets }, ask.w, ask.dark, k)
        }
        other => return Err(format!("no lead picture {other}")),
    };
    Ok(pack_canvas(&cv, &[]))
}

// ---- the home keyboard ----------------------------------------------------------------------------------------------------

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BoardAsk {
    pub windows: bool,
    pub zhuyin: bool,
    pub pitch: f64,
    pub dark: bool,
    /// The place the cats are in (an index of the twelve).
    pub place: usize,
    /// The cats are out on the desktop.
    pub out: bool,
    /// The system's reduced motion.
    pub still: bool,
}

#[derive(Serialize)]
pub struct BoardSize {
    pub w: f64,
    pub h: f64,
}

fn os_of(windows: bool) -> board::Os {
    if windows {
        board::Os::Windows
    } else {
        board::Os::Mac
    }
}

#[tauri::command]
pub fn board_open(app: tauri::State<'_, crate::AppState>, ask: BoardAsk) -> BoardSize {
    let mut board = Board::new(os_of(ask.windows), if ask.zhuyin { board::Ime::Zhuyin } else { board::Ime::Pinyin }, ask.pitch);
    board.set_place(ask.place);
    board.set_theme(ask.dark, 2, ask.still, true);
    board.set_out(ask.out);
    let (w, h) = board.size();
    *lock(&app.glyph.board) = Some(BoardSlot { board, hold: false, composing: false, dirty: true });
    BoardSize { w, h }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BoardFrame {
    /// Keys that went down (`true`) or up (`false`), by `KeyboardEvent.code`, in order.
    pub events: Vec<(String, bool)>,
    /// ⌘ (Ctrl on Windows) is held: the map.
    pub hold: bool,
    pub composing: bool,
    /// Take one tick of the clock (twelve a second while the board is busy).
    pub step: bool,
    /// The window lost its focus: every key is up.
    pub release: bool,
    /// Draw even if nothing changed.
    pub force: bool,
    pub k: u32,
}

/// Header words: `[busy, drawn]`. Without pixels (width 0) when nothing changed.
#[tauri::command]
pub async fn board_frame(app: tauri::State<'_, crate::AppState>, frame: BoardFrame) -> Result<Response, String> {
    let t = Instant::now();
    let mut guard = lock(&app.glyph.board);
    let Some(slot) = guard.as_mut() else { return Err("no board".to_owned()) };
    let mut changed = std::mem::take(&mut slot.dirty) || frame.force;
    if frame.release {
        slot.board.release_all();
        changed = true;
    }
    if frame.hold != slot.hold {
        slot.hold = frame.hold;
        slot.board.set_hold(frame.hold);
        changed = true;
    }
    if frame.composing != slot.composing {
        slot.composing = frame.composing;
        slot.board.set_composing(frame.composing);
        changed = true;
    }
    for (code, down) in &frame.events {
        if *down {
            changed |= slot.board.key_down(code);
        } else {
            slot.board.key_up(code);
            changed = true;
        }
    }
    if frame.step {
        changed |= slot.board.step();
    }
    let busy = u32::from(slot.board.busy());
    if !changed {
        return Ok(pack_empty(&[busy, 0]));
    }
    let mut canvas = slot.board.canvas(frame.k.clamp(1, 8));
    slot.board.draw(&mut canvas);
    drop(guard);
    log_tile("board", 0, t);
    Ok(pack_canvas(&canvas, &[busy, 1]))
}

/// A key of the board by its code, for the clicks: `(x, y, w, h)` in points.
#[tauri::command]
pub fn board_keys(app: tauri::State<'_, crate::AppState>) -> Vec<(String, f64, f64, f64, f64)> {
    lock(&app.glyph.board)
        .as_ref()
        .map(|s| s.board.keys().iter().map(|k| (k.code.to_owned(), k.x, k.y, k.w, k.h)).collect())
        .unwrap_or_default()
}

/// Where the cats are on the journey: the place of the latest day with one known, else the place the characters have
/// come to. Used for the home page's place name and the keyboard's land.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Here {
    pub place: usize,
    pub name: &'static str,
    pub stop: usize,
}

#[must_use]
pub fn here_of(state: &PetState) -> Here {
    let info = lead_info_of(state);
    let place = state.book.marks().filter_map(|m| m.place_index()).last().map_or(info.here, usize::from).min(ALL.len() - 1);
    Here { place, name: ALL[place].name, stop: info.stop }
}

#[tauri::command]
pub fn glyph_here(app: tauri::State<'_, crate::AppState>) -> Result<Here, String> {
    Ok(here_of(&app.reading_for_glyph()?.state))
}

// ---- the closet's candidate card ------------------------------------------------------------------------------------------

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StripAsk {
    pub dark: bool,
    /// The place the cats are in (an index of the twelve).
    pub place: usize,
    pub k: u32,
    pub still: bool,
    /// What the page measured for each candidate at 12 px, in points (`number`, `text`, `paw`; the paw is for Japanese).
    pub cells: Vec<StripCellIn>,
    pub selected: usize,
}

#[derive(Deserialize)]
pub struct StripCellIn {
    pub number: f64,
    pub text: f64,
    pub paw: f64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StripCell {
    pub number_x: f64,
    pub text_x: f64,
    pub baseline: f64,
    pub number_color: u32,
    pub text_color: u32,
    pub halo: Option<u32>,
    pub selected: bool,
}

#[derive(Serialize)]
pub struct StripLayout {
    pub w: f64,
    pub h: f64,
    pub cells: Vec<StripCell>,
}

/// The strip as the PC's candidate window is: the place, the sun behind the selected word, the cats on the ridge, settled
/// (the engine's own tick, sixty-six of them, as the prototype's shots run). The words are the page's, over the picture.
fn strip_of(ask: &StripAsk) -> Strip {
    let mut s = Strip::new(Pools::new(PixelHanzi::Removed));
    s.set_theme(ask.dark, 2, ask.still, false);
    s.set_scale(f64::from(ask.k.clamp(1, 8)));
    s.set_place(Some(ask.place.min(ALL.len() - 1)));
    let cells: Vec<CellIn> = ask.cells.iter().map(|c| CellIn { number: c.number, text: c.text, paw: c.paw }).collect();
    s.set_cells(&cells);
    s.select(ask.selected);
    for _ in 0..66 {
        s.step();
    }
    s.key(Key::Other);
    for _ in 0..6 {
        s.step();
    }
    s
}

/// Where each word goes (the pens, the baseline, the colours); the page sets its text there.
#[tauri::command]
pub fn strip_layout(ask: StripAsk) -> StripLayout {
    let s = strip_of(&ask);
    let (w, h) = s.size();
    let cells = s
        .cells()
        .iter()
        .map(|c| StripCell { number_x: c.number_x, text_x: c.text_x, baseline: c.baseline, number_color: c.number_color, text_color: c.text_color, halo: c.halo, selected: c.selected })
        .collect();
    StripLayout { w, h, cells }
}

/// The strip's picture (all three layers) at `k`.
#[tauri::command]
pub async fn strip_picture(ask: StripAsk) -> Response {
    let mut s = strip_of(&ask);
    let mut canvas = s.canvas();
    s.draw(strip::ALL_LAYERS, &mut canvas);
    pack_canvas(&canvas, &[])
}

// ---- the skin's colours, and photos in type ---------------------------------------------------------------------------------

/// 字景's paper tokens as `#RRGGBB`, the engine's own (`page::Tokens::glyph`), so the page and the picture are one paper.
#[derive(Serialize)]
pub struct GlyphTokens {
    pub paper: String,
    pub paper2: String,
    pub ink: String,
    pub mute: String,
    pub red: String,
}

#[must_use]
pub fn tokens_hex(dark: bool) -> GlyphTokens {
    let t = Tokens::glyph(dark);
    let hex = |c: u32| format!("#{:06X}", c & 0x00FF_FFFF);
    GlyphTokens { paper: hex(t.paper), paper2: hex(t.paper2), ink: hex(t.ink), mute: hex(t.mute), red: hex(t.red) }
}

#[tauri::command]
pub fn glyph_tokens() -> [GlyphTokens; 2] {
    [tokens_hex(false), tokens_hex(true)]
}

/// 生活照 in 字景: the photograph (`w × h` straight RGBA, the raw body, decoded by the page) set in type
/// (`page::photo_type`) at `out_w × out_h` points. Headers: `w`, `h`, `out-w`, `out-h`, `k`, `dark` (`1`/`0`).
#[tauri::command]
pub async fn photo_type(request: tauri::ipc::Request<'_>) -> Result<Response, String> {
    let tauri::ipc::InvokeBody::Raw(rgba) = request.body() else { return Err("expected the photograph's pixels".to_owned()) };
    let num = |name: &str| request.headers().get(name).and_then(|v| v.to_str().ok()).and_then(|v| v.parse::<u32>().ok()).ok_or_else(|| format!("no {name}"));
    let canvas = meowpuut_glyph::page::photo_type(rgba, num("w")?, num("h")?, num("out-w")?, num("out-h")?, num("k")?.clamp(1, 8), num("dark")? == 1);
    Ok(pack_canvas(&canvas, &[]))
}

// ---- the first run --------------------------------------------------------------------------------------------------------

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GuideAsk {
    /// 0–6: the seven steps.
    pub step: usize,
    pub windows: bool,
    pub dark: bool,
    pub zhuyin: bool,
    /// Step 2: the keys pressed so far (`KeyboardEvent.code`).
    pub pressed: Vec<String>,
    pub k: u32,
}

/// A step's picture. Header words: `[boards]` then, for each board, four float bits `x y w h` in points.
#[tauri::command]
pub async fn guide_picture(ask: GuideAsk) -> Result<Response, String> {
    let step = *guide::Step::ALL.get(ask.step).ok_or("no such step")?;
    let os = if ask.windows { guide::Os::Windows } else { guide::Os::Mac };
    // The engine wants 'static key codes: the board owns them.
    let keys = Board::new(os_of(ask.windows), board::Ime::Pinyin, 44.0);
    let pressed = ask.pressed.iter().filter_map(|c| keys.keys().iter().map(|k| k.code).find(|k| k == c)).collect();
    let options = guide::Options { pressed, zhuyin: ask.zhuyin };
    let picture = guide::render_with(step, os, ask.dark, &guide::Copy::of(step, os), &options, ask.k.clamp(1, 8));
    let mut words = vec![picture.boards.len().min(3) as u32];
    for b in picture.boards.iter().take(3) {
        words.extend([b.0, b.1, b.2, b.3].map(|v| (v as f32).to_bits()));
    }
    Ok(pack_canvas(&picture.canvas, &words))
}

#[cfg(test)]
mod tests {
    use super::*;
    use meowpuut_pets::codec::Delta;

    fn state() -> PetState {
        let mut s = PetState { epoch: 1, ..PetState::default() };
        for (i, day) in (20_300u32..20_330).enumerate() {
            let place = if i < 5 { 0 } else { (i / 5) as u8 };
            s.apply_delta(&Delta { epoch: 1, graphemes: 400, day, day_graphemes: 400, who: 3, part: 0b10, place, ..Delta::default() });
        }
        s.snapshot.committed_graphemes = 60_000;
        s
    }

    #[allow(non_snake_case)]
    fn StripCell_in(number: f64, text: f64) -> StripCellIn {
        StripCellIn { number, text, paw: 0.0 }
    }

    fn ask(lens: &str, paper: bool) -> ScrollAsk {
        ScrollAsk {
            lens: lens.to_owned(),
            width: 1280.0,
            height: 600.0,
            dark: false,
            paper: paper.then(|| ["#FAF2E6", "#EFE0CE", "#3A3334", "#6B5F60", "#B82E1F"].map(str::to_owned).to_vec()),
            today: 20_329,
        }
    }

    #[test]
    fn ledger_places_reach_the_scroll_and_days_before_them_are_rolled_up() {
        let mut slot = None;
        let view = open_scroll(&mut slot, &state(), &ask("year", false));
        assert_eq!(view.days.len(), 30);
        assert_eq!(view.days[0].place, None, "place 0 is 'none' in the delta: the day is rolled up");
        assert_eq!(view.days[29].place, Some(4), "the ledger stores index + 1");
        assert_eq!(view.rolled_days, 5);
        assert!(view.total > 1280.0 || view.total > view.painting.0 + view.painting.1 - 1.0);
        assert!(view.days[10].span.is_some() && view.days[0].span.is_none());
        assert_eq!(view.top, 0.0, "字景 is the whole page");
        let paper = open_scroll(&mut slot, &state(), &ask("year", true));
        assert!(paper.top > 0.0 && paper.height < 600.0, "a paper skin's plate stands in from the edges");
    }

    #[test]
    fn a_tile_is_straight_rgba_of_the_size_asked() {
        let mut slot = None;
        let _ = open_scroll(&mut slot, &state(), &ask("year", false));
        let scroll = slot.as_mut().unwrap();
        let g = scroll.geometry();
        let (w, h, rgba) = scroll.tile_rgba(g.painting.0 as i32, 256, 2);
        assert_eq!((w, h), (512, g.height as u32 * 2));
        assert_eq!(rgba.len(), (w * h * 4) as usize);
        assert!(rgba.chunks(4).any(|p| p[3] == 255), "the painting is opaque where it is");
    }

    #[test]
    fn colours_parse_only_whole_hex() {
        assert_eq!(argb("#FAF2E6"), Some(0xFFFA_F2E6));
        assert_eq!(argb("FAF2E6"), None);
        assert_eq!(argb("#FAF2E"), None);
        assert!(tokens_of(&["#000000".to_owned()]).is_none());
    }

    #[test]
    fn the_skin_tokens_are_the_pictures_colours_as_hex() {
        let [light, dark] = [tokens_hex(false), tokens_hex(true)];
        assert_eq!((light.paper.as_str(), light.red.as_str()), ("#ECE8DC", "#C4402A"));
        assert_eq!((dark.paper.as_str(), dark.ink.as_str()), ("#151413", "#E9E4D6"));
    }

    #[test]
    fn a_photograph_in_type_is_a_picture_of_the_size_asked_and_bad_input_is_paper() {
        let rgba: Vec<u8> = (0..32 * 24).flat_map(|i| [(i % 32 * 8) as u8, 90, 160, 255]).collect();
        let c = meowpuut_glyph::page::photo_type(&rgba, 32, 24, 120, 80, 2, false);
        assert_eq!((c.width, c.height), (240, 160));
        let paper = meowpuut_glyph::theme::Colors::of(false).paper;
        assert!(c.pixels.iter().any(|&p| p != paper), "a photograph leaves marks");
        let blank = meowpuut_glyph::page::photo_type(&[1, 2, 3], 32, 24, 120, 80, 2, false);
        assert!(blank.pixels.iter().all(|&p| p == paper));
    }

    #[test]
    fn the_candidate_strip_lays_out_the_words_it_was_given() {
        let ask = StripAsk { dark: false, place: 4, k: 2, still: false, cells: vec![StripCell_in(6.0, 24.0), StripCell_in(6.0, 24.0)], selected: 0 };
        let layout = strip_layout(ask);
        assert_eq!(layout.cells.len(), 2);
        assert!(layout.w >= strip::MIN_WIDTH && (layout.h - strip::HEIGHT).abs() < 1e-9);
        assert!(layout.cells[0].selected && layout.cells[1].text_x > layout.cells[0].text_x);
    }

    #[test]
    fn the_last_place_of_a_stretch_names_the_share_for_steam() {
        let days = [
            Day { day: 1, place: Some(0), ..Day::default() },
            Day { day: 2, place: Some(8), ..Day::default() },
            Day { day: 3, place: None, ..Day::default() },
        ];
        assert_eq!(last_place_name(&days, 0, 1), ALL[8].name);
        assert_eq!(last_place_name(&days, 2, 0), ALL[8].name, "the last known one, either order");
        assert_eq!(last_place_name(&days, 2, 2), "", "no known place: no location");
        assert_eq!(last_place_name(&[], 0, 3), "");
    }

    #[test]
    fn the_lead_counts_come_from_the_journey_and_the_cats_rule() {
        let info = lead_info_of(&state());
        assert_eq!(info.characters, 60_000);
        assert!(info.stop > 100, "60,000 characters is a long road: {}", info.stop);
        assert_eq!(info.visits.iter().map(|&v| v as usize).sum::<usize>(), info.stop);
        let fresh = lead_info_of(&PetState::default());
        assert_eq!((fresh.stop, fresh.places_met, fresh.mimimo.level), (1, 1, 0));
        // 200 characters or 3 contacts make a level, as on the phones.
        assert_eq!(info.mimimo.level, 100, "60,000 characters is 300 levels, capped at the top");
    }

    #[test]
    fn here_is_the_latest_known_place_or_where_the_characters_got_to() {
        let h = here_of(&state());
        assert_eq!(h.place, 4);
        assert_eq!(h.name, ALL[4].name);
        let fresh = here_of(&PetState::default());
        assert_eq!(fresh.name, ALL[meowpuut_glyph::journey::start().unwrap_or(0)].name);
    }

    #[test]
    fn the_board_lights_a_key_and_a_frame_is_pixels() {
        let mut board = Board::new(board::Os::Mac, board::Ime::Pinyin, 44.0);
        let mut cv = board.canvas(2);
        board.draw(&mut cv);
        let before = cv.pixels.clone();
        assert!(board.key_down("KeyM"));
        let mut cv2 = board.canvas(2);
        board.draw(&mut cv2);
        assert_ne!(before, cv2.pixels, "a held key shows");
        assert!(!board.key_down("NoSuchKey"));
    }

    #[test]
    fn every_guide_step_draws_and_its_buttons_lie_inside_the_picture() {
        for (i, step) in guide::Step::ALL.iter().enumerate() {
            for windows in [false, true] {
                let os = if windows { guide::Os::Windows } else { guide::Os::Mac };
                let p = guide::render(*step, os, false, 1);
                assert_eq!((p.canvas.width, p.canvas.height), (guide::WIDTH, guide::HEIGHT), "step {i}");
                for b in &p.boards {
                    assert!(b.0 >= 0.0 && b.1 >= 0.0 && b.0 + b.2 <= f64::from(guide::WIDTH) && b.1 + b.3 <= f64::from(guide::HEIGHT), "step {i}: {b:?}");
                }
            }
        }
    }
}
