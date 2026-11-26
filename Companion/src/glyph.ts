// 字景's pictures from the Rust engine (src-tauri/src/glyph.rs). Every picture comes back as one binary reply: a header
// of sixteen little-endian u32 words (width and height in device pixels first), then straight RGBA. Nothing is base64.

import { invoke } from "@tauri-apps/api/core";

/** The engine only exists inside the app; a plain browser (npm run dev) gets notes instead of pictures. */
export const hasEngine = "__TAURI_INTERNALS__" in window;

const HEADER = 64;

/** The display's scale. `&dpr=1` in the start route (screenshots) answers 1 on a 2× display, to look at the 1× pictures. */
export function dpr(): number {
  const forced = Number(new URLSearchParams(location.hash.split("?")[1] ?? "").get("dpr"));
  return forced > 0 ? forced : window.devicePixelRatio || 1;
}

export interface Picture {
  w: number;
  h: number;
  words: DataView;
  rgba: Uint8ClampedArray<ArrayBuffer>;
}

export function unpack(buf: ArrayBuffer): Picture {
  const words = new DataView(buf, 0, HEADER);
  const w = words.getUint32(0, true);
  const h = words.getUint32(4, true);
  return { w, h, words, rgba: new Uint8ClampedArray(buf, HEADER, w * h * 4) };
}

/** A header word after the size: index 0 is the command's first word. */
export const word = (p: Picture, i: number): number => p.words.getUint32(8 + i * 4, true);
export const float = (p: Picture, i: number): number => p.words.getFloat32(8 + i * 4, true);

export async function picture(command: string, args: Record<string, unknown>): Promise<Picture> {
  return unpack(await invoke<ArrayBuffer>(command, args));
}

/** The integer device scale of the pictures: k = argmin |12k − 14.5 × scale| (FINAL.md §3), so the pixel face
 *  (12 px at k = 1) stays about as big as the system's text. */
export function kOf(scale = dpr()): number {
  let best = 1;
  for (let k = 2; k <= 8; k++) if (Math.abs(12 * k - 14.5 * scale) < Math.abs(12 * best - 14.5 * scale)) best = k;
  return best;
}

/** CSS pixels one engine point takes at scale k: the picture's device pixels land one to one on the display's. */
export const pointCss = (k = kOf()): number => k / dpr();

/** Draws a picture into a canvas at its own device size; the CSS size makes it one to one on the display. */
export function paint(cv: HTMLCanvasElement, p: Picture): void {
  cv.width = p.w;
  cv.height = p.h;
  cv.style.width = `${p.w / dpr()}px`;
  cv.style.height = `${p.h / dpr()}px`;
  cv.getContext("2d")!.putImageData(new ImageData(p.rgba, p.w, p.h), 0, 0);
}

export function canvasOf(p: Picture, cls = ""): HTMLCanvasElement {
  const cv = document.createElement("canvas");
  if (cls) cv.className = cls;
  paint(cv, p);
  return cv;
}

/** `#RRGGBB` tokens of a paper skin in the order the engine wants. */
export const paperList = (t: { paper: string; paper2: string; ink: string; mute: string; accent: string }): string[] => [t.paper, t.paper2, t.ink, t.mute, t.accent];

export const reducedMotion = (): boolean => matchMedia("(prefers-reduced-motion: reduce)").matches;

/** Development only: measurements to the terminal (MEOWPUUT_START=journey?bench=1). */
export function report(text: string): void {
  if (hasEngine) void invoke("dev_report", { text });
}
