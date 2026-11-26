// 生活照 (FINAL.md §2.5, 「照手機」): the public photos of the two cats. The Rust side keeps the cache and the network
// (src-tauri/src/photos.rs); the page only asks for today's list (cache only, instant), shows each photo as its bytes
// arrive, and is told when a new manifest was cached. Nothing here waits for the network.
//
// Paper skins: the photo itself on A's photo-paper white border, a little turned. 字景: first the photo as type dots, then
// the photo on a click (the engine's `page::photo_type`; see the marked hook in `figure`). Until that exists the 字景
// skin shows the photo itself in a plain ink frame, which is what it will turn into on the click.

import { invoke } from "@tauri-apps/api/core";
import { api, type PhotoInfo } from "./api";
import { esc, t } from "./i18n";
import { canvasOf, kOf, pointCss, unpack } from "./glyph";
import { SKINS, isDark } from "./skins";
import { store, today } from "./store";

const urls = new Map<string, Promise<string | null>>();

/** A `blob:` URL for the photo's bytes (cached for the session; null when the download did not work). */
export function photoUrl(file: string): Promise<string | null> {
  let url = urls.get(file);
  if (!url) {
    url = invoke<ArrayBuffer>("photo_image", { file })
      .then((buf) => URL.createObjectURL(new Blob([buf], { type: "image/jpeg" })))
      .catch(() => {
        urls.delete(file); // asked again next time
        return null;
      });
    urls.set(file, url);
  }
  return url;
}

export const titleOf = (p: PhotoInfo): string => {
  if (p.caption) return p.caption;
  const mimimo = p.cats.includes("mimimo");
  const momo = p.cats.includes("momo");
  if (mimimo && momo) return t("米米莫和莫莫", "米米莫和莫莫");
  if (mimimo) return t("米米莫", "米米莫");
  if (momo) return t("莫莫", "莫莫");
  return t("貓", "猫");
};

export const monthText = (p: PhotoInfo): string | null => {
  const m = /^(\d{4})-(\d{2})$/.exec(p.month ?? "");
  return m ? t(`${m[1]} 年 ${Number(m[2])} 月`, `${m[1]} 年 ${Number(m[2])} 月`) : null;
};

export async function todaysPhotos() {
  return api.photosToday(today());
}

/** A turn of a degree or two that is the same for the same photo every time. */
const turn = (file: string): number => {
  let h = 0;
  for (const c of file) h = (h * 31 + c.charCodeAt(0)) | 0;
  return [-1.6, 1.1, -0.7, 1.5, -1.2, 0.8][Math.abs(h) % 6];
};

/** One photo as HTML (the image is filled in by `fill`). `height`: the row's height in px (the image covers it), or 0 for
 *  the photo's own shape. */
export function figure(p: PhotoInfo, height = 0): string {
  const glyph = SKINS[store.companion.skin].world === "glyph";
  const shape = height ? `height:${height}px` : `aspect-ratio:${p.width}/${p.height}`;
  // Under 字景 the first picture is the photo as type (the engine's `page::photo_type`, see `setInType`); a click then shows
  // the photo itself.
  return `<figure class="ph${glyph ? " glyph" : ""}" data-file="${esc(p.file)}" style="${glyph ? "" : `--turn:${turn(p.file)}deg`}" tabindex="0" role="button" aria-label="${esc(titleOf(p))}">
    <div class="ph-img" style="${shape}"><img alt="" decoding="async" hidden></div>
    ${height ? "" : `<figcaption>${esc(titleOf(p))}${monthText(p) ? ` · ${esc(monthText(p)!)}` : ""}</figcaption>`}
  </figure>`;
}

/** Loads the images of the figures under `root` as they come into view. */
export function fill(root: HTMLElement, scrollRoot: Element | null = null): () => void {
  const load = async (fig: HTMLElement) => {
    const img = fig.querySelector<HTMLImageElement>("img")!;
    const url = await photoUrl(fig.dataset.file!);
    if (!fig.isConnected) return;
    if (url) {
      img.src = url;
      fig.classList.add("loaded");
      if (fig.classList.contains("glyph")) await setInType(fig, img);
      else img.hidden = false;
    } else fig.classList.add("failed");
  };
  const io = new IntersectionObserver(
    (entries) => {
      for (const e of entries)
        if (e.isIntersecting) {
          io.unobserve(e.target);
          void load(e.target as HTMLElement);
        }
    },
    { root: scrollRoot, rootMargin: "200px" },
  );
  for (const fig of root.querySelectorAll<HTMLElement>("figure.ph")) io.observe(fig);
  return () => io.disconnect();
}

/** Tells a page that a new manifest was cached (main.ts forwards the backend's event). */
export const PHOTOS_EVENT = "meowpuut-photos";
export function onPhotosUpdated(run: () => void): () => void {
  addEventListener(PHOTOS_EVENT, run);
  return () => removeEventListener(PHOTOS_EVENT, run);
}

/** The words for when there is nothing to show. */
export const waiting = (haveManifest: boolean): string =>
  haveManifest ? t("還沒有照片。", "还没有照片。") : t("照片要連上網路才會出現；載入過一次，之後沒有網路也看得到。", "照片要连上网络才会出现；载入过一次，之后没有网络也看得到。");

/** The photo, large, in a layer over the page (a click or Enter on a figure; Esc or a click closes it). */
export function lightbox(p: PhotoInfo): void {
  const layer = document.createElement("div");
  layer.className = "lightbox";
  layer.innerHTML = `<figure class="ph big${SKINS[store.companion.skin].world === "glyph" ? " glyph shown" : ""}" style="--turn:0deg"><div class="ph-img"><img alt="" hidden></div><figcaption>${esc(titleOf(p))}${monthText(p) ? ` · ${esc(monthText(p)!)}` : ""}</figcaption></figure>`;
  document.body.append(layer);
  const close = () => {
    layer.remove();
    removeEventListener("keydown", onKey, true);
  };
  const onKey = (e: KeyboardEvent) => {
    if (e.key === "Escape") {
      e.preventDefault();
      e.stopImmediatePropagation();
      close();
    }
  };
  addEventListener("keydown", onKey, true);
  layer.addEventListener("click", close);
  void photoUrl(p.file).then((url) => {
    const img = layer.querySelector<HTMLImageElement>("img");
    if (img && url) {
      img.src = url;
      img.hidden = false;
    }
  });
}

/** 字景: the photo set in type, in place of the photo until it is clicked (`.shown`). The page decodes the photo and sends
 *  it small (at most 256 px on its long side) as raw RGBA; the engine sets it in type for the box it stands in. Without
 *  the engine (a browser preview) or when it fails, the photo itself is shown. */
async function setInType(fig: HTMLElement, img: HTMLImageElement): Promise<void> {
  const box = fig.querySelector<HTMLElement>(".ph-img")!;
  const show = () => {
    img.hidden = false;
    fig.classList.add("shown");
  };
  try {
    await img.decode();
    const k = kOf();
    const sp = pointCss(k);
    const [ow, oh] = [Math.max(24, Math.round(box.clientWidth / sp)), Math.max(24, Math.round(box.clientHeight / sp))];
    const scale = Math.min(1, 256 / Math.max(img.naturalWidth, img.naturalHeight));
    const [w, h] = [Math.max(1, Math.round(img.naturalWidth * scale)), Math.max(1, Math.round(img.naturalHeight * scale))];
    const cv = document.createElement("canvas");
    cv.width = w;
    cv.height = h;
    const ctx = cv.getContext("2d", { willReadFrequently: true })!;
    ctx.drawImage(img, 0, 0, w, h);
    const rgba = ctx.getImageData(0, 0, w, h).data;
    const reply = await invoke<ArrayBuffer>("photo_type", new Uint8Array(rgba.buffer), {
      headers: { w: String(w), h: String(h), "out-w": String(ow), "out-h": String(oh), k: String(k), dark: isDark() ? "1" : "0" },
    });
    if (!fig.isConnected) return;
    const typed = canvasOf(unpack(reply), "ph-type");
    box.prepend(typed);
  } catch {
    show();
  }
}
