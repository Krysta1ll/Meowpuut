// The skin system (the phone's AppSkin: tokens + slots). The pages never ask which skin they wear: they read CSS
// variables (the tokens) and a few classes (the slots and the keycap material).
//
//   tokens   --paper --paper2 --ink --mute --accent --on --pa --pb (the chosen candidate) --tint --gold --r --line --face
//   world    w-paper: 日常玻璃, 經典雲玻璃, 像素遊戲機, 百爪典藏      w-glyph: 字景 (tokens only for now; its pictures
//            come from the Rust 字景 engine, not drawn here)
//   material mat-glass, mat-pixel, mat-enamel, mat-glyph: what the keycaps and the candidate window are made of
//
// 日常玻璃 follows the six colour families of the paper tokens (assets/brand/paper/paper-tokens.json, as generated
// into Sources/PaperTokens.swift); the other values are the c-porch prototype's (design-preview/pc/c-porch).

import type { SkinKey } from "./api";
import { t } from "./i18n";

interface Tokens {
  paper: string;
  paper2: string;
  ink: string;
  mute: string;
  accent: string;
  on: string;
  pa: string;
  pb: string;
  tint: string;
  gold: string;
}

export interface Skin {
  key: SkinKey;
  no: string;
  name: [string, string];
  note: [string, string];
  world: "paper" | "glyph";
  mat: "glass" | "pixel" | "enamel" | "glyph";
  face: "round" | "pixel";
  r: number;
  line: number;
  light: Tokens;
  dark: Tokens;
}

export const SKINS: Record<SkinKey, Skin> = {
  daily: {
    key: "daily", no: "No.01", name: ["日常玻璃", "日常玻璃"], note: ["預設・六個色系", "默认・六个色系"],
    world: "paper", mat: "glass", face: "round", r: 20, line: 1,
    light: { paper: "#FAF2E6", paper2: "#EFE0CE", ink: "#3A3334", mute: "#6B5F60", accent: "#B82E1F", on: "#FFFFFF", pa: "#AB8689", pb: "#946D71", tint: "#91453D", gold: "#B82E1F" },
    dark: { paper: "#2A2326", paper2: "#201A1D", ink: "#F5EFE9", mute: "#B9AEAA", accent: "#FF917A", on: "#FFFFFF", pa: "#A88D8C", pb: "#8C716F", tint: "#E69687", gold: "#FF917A" },
  },
  classic: {
    key: "classic", no: "No.02", name: ["經典雲玻璃", "经典云玻璃"], note: ["雲朵玻璃・海鹽藍", "云朵玻璃・海盐蓝"],
    world: "paper", mat: "glass", face: "round", r: 26, line: 1,
    light: { paper: "#EEF2F2", paper2: "#DCE5E8", ink: "#22313B", mute: "#586873", accent: "#47688A", on: "#FFFFFF", pa: "#7393B4", pb: "#47688A", tint: "#47688A", gold: "#47688A" },
    dark: { paper: "#1E2830", paper2: "#161E25", ink: "#EAF1F5", mute: "#A9B8C2", accent: "#7FA3C4", on: "#FFFFFF", pa: "#7FA3C4", pb: "#587894", tint: "#7FA3C4", gold: "#7FA3C4" },
  },
  pixel: {
    key: "pixel", no: "No.03", name: ["像素遊戲機", "像素游戏机"], note: ["方塊・像素字（12 px 整數倍）", "方块・像素字（12 px 整数倍）"],
    world: "paper", mat: "pixel", face: "pixel", r: 0, line: 3,
    light: { paper: "#E3DBF2", paper2: "#F7F2FF", ink: "#2B1F42", mute: "#614D82", accent: "#614D82", on: "#FAF5FF", pa: "#614D82", pb: "#614D82", tint: "#614D82", gold: "#614D82" },
    dark: { paper: "#211C33", paper2: "#332B4A", ink: "#FFF5E8", mute: "#A38AC2", accent: "#A38AC2", on: "#211C33", pa: "#A38AC2", pb: "#A38AC2", tint: "#A38AC2", gold: "#A38AC2" },
  },
  centenary: {
    key: "centenary", no: "No.100", name: ["百爪典藏", "百爪典藏"], note: ["瓷白／夜藍琺瑯、香檳金邊", "瓷白／夜蓝珐琅、香槟金边"],
    world: "paper", mat: "enamel", face: "round", r: 12, line: 1,
    light: { paper: "#F7EFE0", paper2: "#FBF6EC", ink: "#302A22", mute: "#6D5D48", accent: "#A67C3D", on: "#302A22", pa: "#E6C789", pb: "#BD924A", tint: "#A67C3D", gold: "#C9A45C" },
    dark: { paper: "#0F1724", paper2: "#182231", ink: "#F8EFDC", mute: "#D4BE96", accent: "#DFBD7A", on: "#0F1724", pa: "#EACF93", pb: "#C29A52", tint: "#DFBD7A", gold: "#DFBD7A" },
  },
  glyph: {
    key: "glyph", no: "No.04", name: ["字景", "字景"], note: ["字的風景，一鍵一步", "字的风景，一键一步"],
    world: "glyph", mat: "glyph", face: "pixel", r: 0, line: 2,
    light: { paper: "#EFE6DE", paper2: "#E7DDD2", ink: "#191919", mute: "#6E675D", accent: "#C54229", on: "#EFE6DE", pa: "#C54229", pb: "#C54229", tint: "#C54229", gold: "#C54229" },
    dark: { paper: "#101010", paper2: "#1B1A18", ink: "#E6E6D6", mute: "#8C877C", accent: "#DE5A3A", on: "#101010", pa: "#DE5A3A", pb: "#DE5A3A", tint: "#DE5A3A", gold: "#DE5A3A" },
  },
};

export const ORDER: SkinKey[] = ["daily", "classic", "pixel", "centenary", "glyph"];

/** 日常玻璃's colour families: paper, paper2, ink, quieter ink, pill (the chosen candidate), stamp (the accent). */
const PALETTES: Record<string, { name: [string, string]; light: string[]; dark: string[] }> = {
  rose: { name: ["玫瑰奶茶", "玫瑰奶茶"], light: ["#FAF2E6", "#EFE0CE", "#3A3334", "#6B5F60", "#8E6468", "#B82E1F"], dark: ["#2A2326", "#201A1D", "#F5EFE9", "#B9AEAA", "#8A6E6C", "#FF917A"] },
  ocean: { name: ["海鹽藍", "海盐蓝"], light: ["#EEF2F2", "#DCE5E8", "#22313B", "#586873", "#47688A", "#C0392B"], dark: ["#1E2830", "#161E25", "#EAF1F5", "#A9B8C2", "#587894", "#FF9A86"] },
  sage: { name: ["鼠尾草綠", "鼠尾草绿"], light: ["#F2F1E3", "#E2E3D0", "#2B382D", "#5A675B", "#4F6B57", "#B5432A"], dark: ["#222B25", "#192019", "#EEF2E8", "#AEBBAF", "#5F7A64", "#FF9F7F"] },
  lavender: { name: ["霧紫", "雾紫"], light: ["#F3EEF4", "#E4DCE8", "#2E2838", "#62586E", "#765C98", "#B5352E"], dark: ["#272230", "#1D1927", "#F1ECF6", "#B8AEC4", "#7F6C9C", "#FF917A"] },
  amber: { name: ["琥珀蜜", "琥珀蜜"], light: ["#F4E8D1", "#E7D5B5", "#3A2D1D", "#6A5A44", "#7E5F32", "#B23A22"], dark: ["#2C251C", "#211B14", "#F7EDDA", "#BDB09A", "#8C714D", "#FF9A74"] },
  graphite: { name: ["石墨", "石墨"], light: ["#EFEEEA", "#DEDDD8", "#2A2D31", "#586067", "#59636F", "#B8392B"], dark: ["#25272A", "#1B1D20", "#ECEEF0", "#AEB4BA", "#68717C", "#FF917A"] },
};
export const PALETTE_KEYS = Object.keys(PALETTES);
export const paletteName = (key: string): string => t(...(PALETTES[key]?.name ?? PALETTES.rose.name));
export const paletteSwatch = (key: string, dark: boolean): string => (PALETTES[key] ?? PALETTES.rose)[dark ? "dark" : "light"][4];
export const SHAPES: Record<string, [string, string]> = { capsule: ["膠囊", "胶囊"], wave: ["波浪", "波浪"], cloud: ["雲朵", "云朵"] };

export const skinName = (key: SkinKey): string => t(...SKINS[key].name);
export const isDark = (): boolean => matchMedia("(prefers-color-scheme: dark)").matches;

function mix(a: string, b: string, k: number): string {
  const p = (h: string) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
  const [x, y] = [p(a), p(b)];
  return "#" + x.map((v, i) => Math.round(v + (y[i] - v) * k).toString(16).padStart(2, "0")).join("");
}

export function tokensOf(key: SkinKey, palette: string | null, dark: boolean): Tokens {
  const base = SKINS[key][dark ? "dark" : "light"];
  if (key !== "daily" || !palette || palette === "rose" || !PALETTES[palette]) return base;
  const [paper, paper2, ink, mute, pill, stamp] = PALETTES[palette][dark ? "dark" : "light"];
  return { paper, paper2, ink, mute, accent: stamp, on: "#FFFFFF", pa: mix(pill, "#FFFFFF", 0.18), pb: pill, tint: pill, gold: stamp };
}

const ROUND = '"Meowpuut Paper Round", ui-rounded, "SF Pro Rounded", -apple-system, BlinkMacSystemFont, "PingFang TC", "Microsoft JhengHei UI", "Microsoft JhengHei", sans-serif';
const PIXEL = '"Meowpuut Pixel A", ui-monospace, monospace';

/** Dresses `target` (the page, or one preview card) in a skin. */
export function applySkin(target: HTMLElement, key: SkinKey, palette: string | null = null, dark = isDark()): void {
  const s = SKINS[key];
  const tk = tokensOf(key, palette, dark);
  const st = target.style;
  for (const [name, value] of Object.entries(tk)) st.setProperty(`--${name}`, value);
  st.setProperty("--r", `${s.r}px`);
  st.setProperty("--line", `${s.line}px`);
  st.setProperty("--face", s.face === "pixel" ? PIXEL : ROUND);
  target.classList.remove(...ORDER.map((k) => `sk-${k}`), "w-paper", "w-glyph", "f-pixel", "f-round", "mat-glass", "mat-pixel", "mat-enamel", "mat-glyph");
  target.classList.add(`sk-${key}`, `w-${s.world}`, `f-${s.face}`, `mat-${s.mat}`);
}

/** 字景's paper tokens from the engine (`page::Tokens::glyph`), so the page and the pictures are one paper. Outside the app
 *  the values above stand. */
export function adoptGlyphTokens(both: { paper: string; paper2: string; ink: string; mute: string; red: string }[]): void {
  (["light", "dark"] as const).forEach((mode, i) => {
    const g = both[i];
    if (!g) return;
    SKINS.glyph[mode] = { paper: g.paper, paper2: g.paper2, ink: g.ink, mute: g.mute, accent: g.red, on: g.paper, pa: g.red, pb: g.red, tint: g.red, gold: g.red };
  });
}
