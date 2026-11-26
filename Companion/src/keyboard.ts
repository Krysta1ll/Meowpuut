// 首頁的 hero: the keyboard under your hands (FINAL.md §2.1, the c-porch prototype). Drawn from the real layout:
// Mac (fn ⌃ ⌥ ⌘) or Windows (Ctrl ⊞ Alt), pinyin QWERTY legends or Zhuyin on the 大千 layout. A key lights while it
// is held, from this window's own key events only (no global hook: when the window does not have focus, nothing
// lights). Holding ⌘ / Ctrl turns the keyboard into the app's map; the cats hop off to leave it clear.
//
// TODO(native keys): FINAL.md §2.1 #2 wants the keys captured on the native side (NSEvent local monitor, Raw Input
// for the foreground window), because a WebView reports keyCode 229 while composing. `KeyboardEvent.code` is still
// set during composition in WebKit and WebView2, so the DOM events are enough for this first version.

import type { Platform, SkinKey } from "./api";
import { catPair } from "./art";
import { esc, t } from "./i18n";
import { canvasOf, hasEngine, kOf, paint, picture, pointCss, reducedMotion, word } from "./glyph";
import { invoke } from "@tauri-apps/api/core";
import { isDark } from "./skins";
import { store } from "./store";

type Layout = "pinyin" | "zhuyin";

const ROWS: Record<Platform, string[]> = {
  mac: [
    "Backquote Digit1 Digit2 Digit3 Digit4 Digit5 Digit6 Digit7 Digit8 Digit9 Digit0 Minus Equal Backspace:2",
    "Tab:1.5 KeyQ KeyW KeyE KeyR KeyT KeyY KeyU KeyI KeyO KeyP BracketLeft BracketRight Backslash:1.5",
    "CapsLock:1.75 KeyA KeyS KeyD KeyF KeyG KeyH KeyJ KeyK KeyL Semicolon Quote Enter:2.25",
    "ShiftLeft:2.25 KeyZ KeyX KeyC KeyV KeyB KeyN KeyM Comma Period Slash ShiftRight:2.75",
    "Fn ControlLeft AltLeft MetaLeft:1.25 Space:5.5 MetaRight:1.25 AltRight ArrowLeft ArrowUD ArrowRight",
  ],
  windows: [
    "Backquote Digit1 Digit2 Digit3 Digit4 Digit5 Digit6 Digit7 Digit8 Digit9 Digit0 Minus Equal Backspace:2",
    "Tab:1.5 KeyQ KeyW KeyE KeyR KeyT KeyY KeyU KeyI KeyO KeyP BracketLeft BracketRight Backslash:1.5",
    "CapsLock:1.75 KeyA KeyS KeyD KeyF KeyG KeyH KeyJ KeyK KeyL Semicolon Quote Enter:2.25",
    "ShiftLeft:2.25 KeyZ KeyX KeyC KeyV KeyB KeyN KeyM Comma Period Slash ShiftRight:2.75",
    "ControlLeft:1.25 Fn MetaLeft:1.25 AltLeft:1.25 Space:4.75 AltRight:1.25 ControlRight:1.25 ArrowLeft ArrowUD ArrowRight",
  ],
};

const PUNCT: Record<string, string> = {
  Backquote: "`", Minus: "-", Equal: "=", BracketLeft: "[", BracketRight: "]", Backslash: "\\", Semicolon: ";", Quote: "'", Comma: ",", Period: ".", Slash: "/",
};

// 注音 on the 大千 layout (the one printed on keyboards sold in Taiwan).
const DAQIAN: Record<string, string> = {
  Digit1: "ㄅ", Digit2: "ㄉ", Digit3: "ˇ", Digit4: "ˋ", Digit5: "ㄓ", Digit6: "ˊ", Digit7: "˙", Digit8: "ㄚ", Digit9: "ㄞ", Digit0: "ㄢ", Minus: "ㄦ",
  KeyQ: "ㄆ", KeyW: "ㄊ", KeyE: "ㄍ", KeyR: "ㄐ", KeyT: "ㄔ", KeyY: "ㄗ", KeyU: "ㄧ", KeyI: "ㄛ", KeyO: "ㄟ", KeyP: "ㄣ",
  KeyA: "ㄇ", KeyS: "ㄋ", KeyD: "ㄎ", KeyF: "ㄑ", KeyG: "ㄕ", KeyH: "ㄘ", KeyJ: "ㄨ", KeyK: "ㄜ", KeyL: "ㄠ", Semicolon: "ㄤ",
  KeyZ: "ㄈ", KeyX: "ㄌ", KeyC: "ㄏ", KeyV: "ㄒ", KeyB: "ㄖ", KeyN: "ㄙ", KeyM: "ㄩ", Comma: "ㄝ", Period: "ㄡ", Slash: "ㄥ",
};

const MAC_MODS: Record<string, [string, string]> = {
  Backspace: ["⌫", "delete"], Tab: ["⇥", "tab"], CapsLock: ["⇪", "caps lock"], Enter: ["↵", "return"],
  ShiftLeft: ["⇧", "shift"], ShiftRight: ["⇧", "shift"], ControlLeft: ["⌃", "control"], AltLeft: ["⌥", "option"], AltRight: ["⌥", "option"],
  MetaLeft: ["⌘", "command"], MetaRight: ["⌘", "command"],
};
const WIN_MODS: Record<string, string> = {
  Backspace: "Backspace", Tab: "Tab", CapsLock: "Caps Lock", Enter: "Enter", ShiftLeft: "Shift", ShiftRight: "Shift",
  ControlLeft: "Ctrl", ControlRight: "Ctrl", AltLeft: "Alt", AltRight: "Alt", Fn: "Fn",
};

/** What each key does in the app while ⌘ / Ctrl is held (short names: a 44 px key holds three characters). */
export const MAP: Record<string, [string, string]> = {
  Digit0: ["首頁", "首页"], Digit1: ["設定", "设置"], Digit2: ["衣櫥", "衣橱"], Digit3: ["行旅", "行旅"], Digit4: ["爪印本", "爪印本"],
  Digit5: ["客廳", "客厅"], Digit6: ["成就", "成就"], Digit7: ["生活照", "生活照"], KeyK: ["路牌", "路牌"], KeyT: ["試打", "试打"], KeyW: ["關窗", "关窗"],
};

export function layoutName(_os: Platform, layout: Layout): string {
  return `${t("美式", "美式")}・${layout === "zhuyin" ? t("注音（大千）", "注音（大千）") : t("全鍵拼音", "全键拼音")}`;
}

function legend(code: string, os: Platform, layout: Layout): string {
  const letter = code.startsWith("Key") ? code.slice(3).toLowerCase() : code.startsWith("Digit") ? code.slice(5) : PUNCT[code];
  if (letter !== undefined) {
    const zy = layout === "zhuyin" ? DAQIAN[code] : undefined;
    return zy ? `<b class="zy">${zy}</b><i class="small">${esc(letter)}</i>` : `<b class="ch">${esc(letter)}</b>`;
  }
  if (code === "Space") return "";
  if (code === "ArrowLeft") return `<b class="ar">◀</b>`;
  if (code === "ArrowRight") return `<b class="ar">▶</b>`;
  if (code === "ArrowUp") return `<b class="ar">▲</b>`;
  if (code === "ArrowDown") return `<b class="ar">▼</b>`;
  if (os === "mac") {
    if (code === "Fn") return `<span class="word solo">fn</span>`;
    const [sym, word] = MAC_MODS[code] ?? ["", code];
    return `<b class="sym">${sym}</b><span class="word">${word}</span>`;
  }
  if (code === "MetaLeft") return `<span class="win"><i></i><i></i><i></i><i></i></span>`;
  return `<span class="word solo">${WIN_MODS[code] ?? code}</span>`;
}

export interface HeroOptions {
  os: Platform;
  layout: Layout;
  skin: SkinKey;
  /** One key unit in px (FINAL.md §2.1: about 44, so the window fits a 1280 × 800 laptop). */
  u: number;
}

const PAD = 14;

/** 字景 (FINAL.md §2.1): the keyboard is one picture of the engine's, the land running on between the keys, 44 points a
 *  key. Drawn only inside the app; the paper skins keep their glass keyboard. */
export const glyphHero = (skin: SkinKey): boolean => skin === "glyph" && hasEngine;

export function heroHTML(o: HeroOptions): string {
  const { u } = o;
  if (glyphHero(o.skin)) return `<div class="hero glyph" data-glyph="1" data-os="${o.os}" data-layout="${o.layout}" data-u="${u}"><canvas class="board"></canvas></div>`;
  const keys: string[] = [];
  ROWS[o.os].forEach((row, r) => {
    let x = 0;
    for (const token of row.split(" ")) {
      const [code, width] = token.split(":");
      const w = Number(width ?? 1);
      const place = (c: string, top: number, h: number, extra = "") =>
        `<div class="key${extra}${MAP[c] ? " mapped" : ""}" data-code="${c}" style="left:${PAD + x * u + 3}px;top:${PAD + r * u + top}px;width:${w * u - 6}px;height:${h}px">` +
        `<div class="cap">${legend(c, o.os, o.layout)}${MAP[c] ? `<em class="map">${t(...MAP[c])}</em>` : ""}</div></div>`;
      const full = u - 9;
      if (code === "ArrowUD") {
        const half = Math.floor((full - 2) / 2);
        keys.push(place("ArrowUp", 4, half, " half"), place("ArrowDown", 4 + half + 2, full - half - 2, " half"));
      } else if (code === "ArrowLeft" || code === "ArrowRight") {
        const half = Math.floor((full - 2) / 2);
        keys.push(place(code, 4 + full - half, half, " half"));
      } else {
        const extra = (code === "Enter" ? " accent" : "") + (code === "Space" ? " space" : "");
        keys.push(place(code, 4, full, extra));
      }
      x += w;
    }
  });
  const W = 15 * u + 2 * PAD;
  const H = 5 * u + 2 * PAD - 2;
  const cats = catPair(o.skin);
  const catH = 56;
  return `
    <div class="hero" style="--u:${u}px;width:${W}px;height:${H + catH}px" data-os="${o.os}">
      <div class="hero-cats ${cats.kind}" style="top:0;height:${catH}px">
        <img class="cat l" src="${cats.left}" alt="${t("米米莫", "米米莫")}" style="left:${Math.round(u * 1.6)}px">
        <img class="cat r" src="${cats.right}" alt="${t("莫莫", "莫莫")}" style="right:${Math.round(u * 1.9)}px">
      </div>
      <div class="slab" style="top:${catH - 8}px;width:${W}px;height:${H}px">
        ${o.skin === "glyph" ? glyphBand() : ""}
        ${keys.join("")}
      </div>
    </div>`;
}

/** 字景 walks between the keys; that picture comes from the Rust 字景 engine. Until then: a marked placeholder. */
function glyphBand(): string {
  return `<div class="glyph-slot hero-band"><span>${t("字景：地景在鍵與鍵之間走", "字景：地景在键与键之间走")}</span><small>${t("等 Rust 字景引擎（meowpuut-glyph）接上", "等 Rust 字景引擎（meowpuut-glyph）接上")}</small></div>`;
}

/** Wires a rendered hero to the window's key events. */
export class Hero {
  private leanTimer = 0;
  private board: GlyphBoard | null = null;
  constructor(private root: HTMLElement) {
    if (root.dataset.glyph) this.board = new GlyphBoard(root);
  }

  /** Previews: a mouse click on a key of the 字景 board. */
  mouse(code: string): Promise<void> | void {
    return this.board?.mouse(code);
  }

  /** Stops the glyph board's clock when the page goes. */
  dispose(): void {
    this.board?.dispose();
  }

  private keyEl(code: string): HTMLElement | null {
    return this.root.querySelector(`.key[data-code="${code}"]`);
  }

  press(code: string): void {
    if (this.board) return this.board.key(code, true);
    const el = this.keyEl(normal(code, this.root.dataset.os as Platform));
    if (!el) return;
    el.classList.add("down");
    // The clay cats lean toward the key, a turn of the one picture (as on the phone), and come back.
    const box = this.root.getBoundingClientRect();
    const k = el.getBoundingClientRect();
    const lean = k.left + k.width / 2 < box.left + box.width / 2 ? -4 : 4;
    this.root.style.setProperty("--lean", `${lean}deg`);
    clearTimeout(this.leanTimer);
    this.leanTimer = window.setTimeout(() => this.root.style.setProperty("--lean", "0deg"), 420);
  }

  release(code: string): void {
    if (this.board) return this.board.key(code, false);
    this.keyEl(normal(code, this.root.dataset.os as Platform))?.classList.remove("down");
  }

  clear(): void {
    if (this.board) return this.board.clear();
    for (const el of this.root.querySelectorAll(".key.down")) el.classList.remove("down");
    this.setMap(false);
  }

  setMap(on: boolean): void {
    if (this.board) return this.board.setHold(on);
    this.root.classList.toggle("map", on);
  }
}

/** A Mac has no right Control; a Windows keyboard's Win key may report as either side. */
function normal(code: string, os: Platform): string {
  if (os === "windows" && code === "MetaRight") return "MetaLeft";
  if (os === "mac" && code === "ControlRight") return "ControlLeft";
  return code;
}

/** The home keyboard of 字景: its keys go to the engine (`KeyboardEvent.code`), its frames come back as pixels. While
 *  the engine says it is busy the clock is ticked twelve times a second; at rest nothing is asked. */
class GlyphBoard {
  private events: [string, boolean][] = [];
  private hold = false;
  private release = false;
  private k = kOf();
  private cv: HTMLCanvasElement;
  private timer = 0;
  private busy = false;
  private running = false;
  private dirty = false;
  private ready = false;
  private gone = false;
  /** Milliseconds from the key event to the pixels on the canvas (for the measurements). */
  readonly latency: number[] = [];

  constructor(private root: HTMLElement) {
    this.cv = root.querySelector<HTMLCanvasElement>("canvas.board")!;
    this.wire();
    void this.open();
  }

  private async open(): Promise<void> {
    const here = await invoke<{ place: number }>("glyph_here").catch(() => ({ place: 0 }));
    const size = await invoke<{ w: number; h: number }>("board_open", {
      ask: {
        windows: this.root.dataset.os === "windows", zhuyin: this.root.dataset.layout === "zhuyin", pitch: Number(this.root.dataset.u) || 44,
        dark: isDark(), place: here.place, out: store.companion.catsOut, still: reducedMotion(),
      },
    });
    if (this.gone) return;
    const sp = pointCss(this.k);
    this.root.style.width = `${size.w * sp}px`;
    this.root.style.height = `${size.h * sp}px`;
    this.ready = true;
    this.dirty = true;
    this.sp = sp;
    this.keys = await invoke<[string, number, number, number, number][]>("board_keys").catch(() => []);
    this.go();
  }

  /** Previews: presses the key with a real mouse event at the middle of its rectangle (MEOWPUUT_START=home?mouse=KeyM). */
  async mouse(code: string): Promise<void> {
    while (!this.ready) await new Promise((r) => setTimeout(r, 50));
    const key = this.keys.find((k) => k[0] === code);
    if (!key) return;
    const r = this.cv.getBoundingClientRect();
    this.cv.dispatchEvent(new MouseEvent("mousedown", { bubbles: true, clientX: r.left + (key[1] + key[3] / 2) * this.sp, clientY: r.top + (key[2] + key[4] / 2) * this.sp }));
  }

  private keys: [string, number, number, number, number][] = [];
  private sp = 1;
  private down: string | null = null;

  /** A click on a key lights it, the same as typing it (the engine hands out the keys' rectangles, in points). */
  private wire(): void {
    const at = (e: MouseEvent): string | null => {
      const r = this.cv.getBoundingClientRect();
      const [x, y] = [(e.clientX - r.left) / this.sp, (e.clientY - r.top) / this.sp];
      return this.keys.find(([, kx, ky, kw, kh]) => x >= kx && x < kx + kw && y >= ky && y < ky + kh)?.[0] ?? null;
    };
    this.cv.addEventListener("mousedown", (e) => {
      const code = at(e);
      if (!code) return;
      e.preventDefault();
      this.down = code;
      this.key(code, true);
    });
    const up = () => {
      if (this.down) this.key(this.down, false);
      this.down = null;
    };
    addEventListener("mouseup", up);
    this.cv.addEventListener("mouseleave", up);
    this.cv.style.cursor = "pointer";
  }

  key(code: string, down: boolean): void {
    this.events.push([code, down]);
    this.go();
  }

  setHold(on: boolean): void {
    if (on === this.hold) return;
    this.hold = on;
    this.go();
  }

  clear(): void {
    this.release = true;
    this.hold = false;
    this.go();
  }

  dispose(): void {
    this.gone = true;
    clearTimeout(this.timer);
  }

  private go(): void {
    if (this.running || !this.ready || this.gone) return;
    this.running = true;
    void this.frame(false);
  }

  private async frame(step: boolean): Promise<void> {
    const events = this.events;
    this.events = [];
    const release = this.release;
    this.release = false;
    const force = this.dirty;
    this.dirty = false;
    const t0 = performance.now();
    try {
      const p = await picture("board_frame", { frame: { events, hold: this.hold, composing: false, step, release, force, k: this.k } });
      if (p.w) {
        paint(this.cv, p);
        if (events.length) this.latency.push(performance.now() - t0);
      }
      this.busy = word(p, 0) === 1;
    } catch {
      this.busy = false;
    }
    this.running = false;
    if (this.gone) return;
    if (this.events.length || this.release) return this.go();
    if (this.busy) {
      this.timer = window.setTimeout(() => {
        if (this.running) return;
        this.running = true;
        void this.frame(true);
      }, 1000 / 12);
    }
  }
}

export { canvasOf };

/** The page's Hero for a rendered hero element (made once). */
export function mountHero(el: HTMLElement): Hero {
  const holder = el as HTMLElement & { hero?: Hero };
  return (holder.hero ??= new Hero(el));
}
